"""A minimal Parquet reader in pure Python.

## Why this exists rather than `pandas.read_parquet`

pandas can read Parquet only through pyarrow or fastparquet, and NEITHER IS AVAILABLE ON ANDROID:
Chaquopy's package repository ships pandas and numpy but no pyarrow, no fastparquet and no
python-snappy, because all three are large C++/Cython builds with no Android wheels. So on a phone
there is no way to open a Parquet file at all unless the format is decoded here.

Desktop does have pyarrow, and [prism_corpus] prefers it when present -- it is faster, and it
handles every corner of the format. This module is what makes the same corpus importable on both.

## What it supports, and what it deliberately does not

Written against what Hugging Face actually publishes, which is a narrow slice of the specification:

  * compression: UNCOMPRESSED, SNAPPY (decompressed here), GZIP (zlib)
  * encodings: PLAIN, RLE_DICTIONARY / PLAIN_DICTIONARY, and RLE/bit-packed hybrid for levels
  * types: BYTE_ARRAY, BOOLEAN, INT32, INT64, DOUBLE
  * nesting: one repeated level, which is what `list<struct<...>>` needs -- the shape of every
    chat dataset, LMSYS-Chat-1M included

Anything else raises with the name of the thing it met. ZSTD and BROTLI in particular are common
enough to be worth a clear message rather than a wrong answer: neither has a stdlib decompressor,
so a file using them cannot be read here and says so.

## Why correctness here is not obvious

A Parquet reader that is subtly wrong does not raise -- it returns strings, just the wrong ones, or
the right strings grouped into the wrong rows. That is a corrupted training corpus that looks
entirely plausible. So this is checked against pyarrow on the desktop rather than trusted; see
`make_parquet_fixture.py` and the tests that read it.
"""

import io
import os
import struct
import zlib

# ── Thrift compact protocol ────────────────────────────────────────────────

#: Field types in Thrift's compact encoding. Only the ones Parquet's metadata uses.
_T_STOP, _T_TRUE, _T_FALSE, _T_BYTE, _T_I16, _T_I32, _T_I64 = 0, 1, 2, 3, 4, 5, 6
_T_DOUBLE, _T_BINARY, _T_LIST, _T_SET, _T_MAP, _T_STRUCT = 7, 8, 9, 10, 11, 12


class _Thrift(object):
    """Just enough of the compact protocol to walk Parquet's metadata structures.

    Written as a cursor over a bytes object rather than a stream: the footer is read whole, and
    page headers are parsed from a buffer whose end is not known until the header itself has been
    decoded -- so the position after parsing is part of the result.
    """

    def __init__(self, data, pos=0):
        self.data = data
        self.pos = pos

    def byte(self):
        value = self.data[self.pos]
        self.pos += 1
        return value

    def varint(self):
        result = 0
        shift = 0
        while True:
            byte = self.byte()
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result
            shift += 7

    def zigzag(self):
        value = self.varint()
        return (value >> 1) ^ -(value & 1)

    def binary(self):
        length = self.varint()
        out = self.data[self.pos:self.pos + length]
        self.pos += length
        return out

    def skip(self, kind):
        """Steps over a value of [kind] without interpreting it.

        Needed because Parquet's structures carry far more than this reader uses -- statistics,
        encodings, key/value metadata -- and a field cannot be ignored without being measured.
        """
        if kind in (_T_TRUE, _T_FALSE):
            return
        if kind == _T_BYTE:
            self.pos += 1
        elif kind in (_T_I16, _T_I32, _T_I64):
            self.zigzag()
        elif kind == _T_DOUBLE:
            self.pos += 8
        elif kind == _T_BINARY:
            self.binary()
        elif kind == _T_STRUCT:
            self.struct(lambda field, kind_: False)
        elif kind in (_T_LIST, _T_SET):
            size, element = self.list_header()
            for _ in range(size):
                self.skip(element)
        elif kind == _T_MAP:
            size = self.varint()
            if size:
                kinds = self.byte()
                key_kind, value_kind = kinds >> 4, kinds & 0x0F
                for _ in range(size):
                    self.skip(key_kind)
                    self.skip(value_kind)
        else:
            raise ValueError("unsupported thrift type %d" % kind)

    def list_header(self):
        header = self.byte()
        size = header >> 4
        element = header & 0x0F
        if size == 15:
            size = self.varint()
        return size, element

    def struct(self, handler):
        """Walks one struct, offering each field to [handler].

        [handler] returns True when it has consumed the field; anything it declines is skipped.
        Field ids are DELTA-ENCODED in this protocol, so they have to be accumulated rather than
        read -- getting that wrong silently misreads every field after the first.
        """
        field_id = 0
        while True:
            header = self.byte()
            if header == _T_STOP:
                return
            delta = header >> 4
            kind = header & 0x0F
            field_id = field_id + delta if delta else self.zigzag()
            if not handler(field_id, kind):
                self.skip(kind)


# ── Snappy ─────────────────────────────────────────────────────────────────

def _snappy_decompress(data):
    """Raw (non-framed) Snappy, which is what Parquet stores.

    Implemented here because python-snappy is a C extension with no Android build. The format is
    small: a varint of the uncompressed length, then a stream of literal and back-reference tags.
    """
    pos = 0
    length = 0
    shift = 0
    while True:
        byte = data[pos]
        pos += 1
        length |= (byte & 0x7F) << shift
        if not byte & 0x80:
            break
        shift += 7

    out = bytearray()
    end = len(data)
    while pos < end:
        tag = data[pos]
        pos += 1
        kind = tag & 0x03

        if kind == 0:                                   # literal
            size = tag >> 2
            if size >= 60:
                extra = size - 59
                size = int.from_bytes(data[pos:pos + extra], "little")
                pos += extra
            size += 1
            out += data[pos:pos + size]
            pos += size
            continue

        if kind == 1:                                   # copy, 1-byte offset
            size = 4 + ((tag >> 2) & 0x07)
            offset = ((tag >> 5) << 8) | data[pos]
            pos += 1
        elif kind == 2:                                 # copy, 2-byte offset
            size = (tag >> 2) + 1
            offset = int.from_bytes(data[pos:pos + 2], "little")
            pos += 2
        else:                                           # copy, 4-byte offset
            size = (tag >> 2) + 1
            offset = int.from_bytes(data[pos:pos + 4], "little")
            pos += 4

        # BYTE AT A TIME WHEN THE RUN OVERLAPS ITSELF. A copy may reference bytes it is in the
        # middle of producing -- that is how Snappy encodes runs -- so a slice-and-extend would
        # read the buffer as it was before the copy started and produce the wrong bytes.
        start = len(out) - offset
        if start < 0:
            raise ValueError("snappy offset before start of output")
        if offset >= size:
            out += out[start:start + size]
        else:
            for i in range(size):
                out.append(out[start + i])

    if len(out) != length:
        raise ValueError("snappy length mismatch: %d declared, %d produced" % (length, len(out)))
    return bytes(out)


_CODECS = {0: lambda b: b, 1: _snappy_decompress, 2: lambda b: zlib.decompress(b, 16 + zlib.MAX_WBITS)}
_CODEC_NAMES = {3: "LZO", 4: "BROTLI", 5: "LZ4", 6: "ZSTD", 7: "LZ4_RAW"}


# ── RLE / bit-packed hybrid ────────────────────────────────────────────────

def _read_rle_bitpacked(data, bit_width, count):
    """Decodes Parquet's hybrid encoding, used for levels and dictionary indices.

    Alternates between runs of one repeated value and blocks of bit-packed values, chosen per group
    by the low bit of a varint header.
    """
    if bit_width == 0:
        return [0] * count

    values = []
    pos = 0
    byte_width = (bit_width + 7) // 8
    mask = (1 << bit_width) - 1

    while len(values) < count and pos < len(data):
        header = 0
        shift = 0
        while True:
            byte = data[pos]
            pos += 1
            header |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7

        if header & 1:                                  # bit-packed
            groups = header >> 1
            total = groups * 8
            needed = (total * bit_width + 7) // 8
            chunk = data[pos:pos + needed]
            pos += needed
            buffer = int.from_bytes(chunk, "little")
            for i in range(total):
                if len(values) >= count:
                    break
                values.append((buffer >> (i * bit_width)) & mask)
        else:                                           # run-length
            run = header >> 1
            value = int.from_bytes(data[pos:pos + byte_width], "little")
            pos += byte_width
            values.extend([value] * min(run, count - len(values)))

    return values[:count]


# ── Plain values ───────────────────────────────────────────────────────────

_TYPE_BOOLEAN, _TYPE_INT32, _TYPE_INT64 = 0, 1, 2
_TYPE_INT96, _TYPE_FLOAT, _TYPE_DOUBLE, _TYPE_BYTE_ARRAY = 3, 4, 5, 6


def _read_plain(data, physical_type, count):
    values = []
    pos = 0
    if physical_type == _TYPE_BYTE_ARRAY:
        for _ in range(count):
            if pos + 4 > len(data):
                break
            size = int.from_bytes(data[pos:pos + 4], "little")
            pos += 4
            values.append(data[pos:pos + size])
            pos += size
    elif physical_type == _TYPE_INT32:
        for _ in range(count):
            values.append(int.from_bytes(data[pos:pos + 4], "little", signed=True))
            pos += 4
    elif physical_type == _TYPE_INT64:
        for _ in range(count):
            values.append(int.from_bytes(data[pos:pos + 8], "little", signed=True))
            pos += 8
    elif physical_type == _TYPE_DOUBLE:
        for _ in range(count):
            values.append(struct.unpack_from("<d", data, pos)[0])
            pos += 8
    elif physical_type == _TYPE_BOOLEAN:
        for i in range(count):
            values.append(bool(data[i // 8] >> (i % 8) & 1))
    else:
        raise ValueError("unsupported physical type %d" % physical_type)
    return values


# ── Schema ─────────────────────────────────────────────────────────────────

class _Column(object):
    """A leaf column, with the nesting depth needed to rebuild its values."""

    def __init__(self, path, physical_type, max_def, max_rep):
        self.path = path
        self.physical_type = physical_type
        self.max_def = max_def
        self.max_rep = max_rep


def _build_schema(elements):
    """Turns the flat schema list into leaf columns carrying max definition/repetition levels.

    The levels are what make nesting decodable: definition says how many optional ancestors were
    actually present, repetition says which ancestor a value continues. They cannot be recovered
    from the data, only from the schema, which is why this walk has to happen before any page is
    read.
    """
    columns = []

    def walk(index, parent, def_level, rep_level):
        element = elements[index]
        index += 1
        path = parent + [element["name"]]

        # LEVELS ACCUMULATE ON THE WAY DOWN, and both matter: definition counts ancestors that
        # could have been null, repetition counts ancestors that could repeat. A leaf's own
        # repetition type counts too -- it is the last link in that chain, not an exception to it.
        if element["repetition"] == 1:                  # OPTIONAL
            def_level += 1
        elif element["repetition"] == 2:                # REPEATED
            def_level += 1
            rep_level += 1

        if not element["num_children"]:
            columns.append(_Column(tuple(path), element["type"], def_level, rep_level))
            return index

        for _ in range(element["num_children"]):
            index = walk(index, path, def_level, rep_level)
        return index

    # The first element is the root group. It has a name ("schema") that is not part of any column
    # path and no repetition of its own, so its children are walked directly rather than it.
    index = 1
    for _ in range(elements[0]["num_children"]):
        index = walk(index, [], 0, 0)
    return columns


def _parse_metadata(footer):
    reader = _Thrift(footer)
    meta = {"schema": [], "row_groups": [], "num_rows": 0}

    def file_field(field_id, kind):
        if field_id == 2 and kind == _T_LIST:           # schema
            size, _ = reader.list_header()
            for _ in range(size):
                element = {"type": None, "repetition": 0, "name": "", "num_children": 0}

                def schema_field(fid, k, element=element):
                    if fid == 1 and k == _T_I32:
                        element["type"] = reader.zigzag()
                    elif fid == 3 and k == _T_I32:
                        element["repetition"] = reader.zigzag()
                    elif fid == 4 and k == _T_BINARY:
                        element["name"] = reader.binary().decode("utf-8", "replace")
                    elif fid == 5 and k == _T_I32:
                        element["num_children"] = reader.zigzag()
                    else:
                        return False
                    return True

                reader.struct(schema_field)
                meta["schema"].append(element)
            return True

        if field_id == 3 and kind == _T_I64:
            meta["num_rows"] = reader.zigzag()
            return True

        if field_id == 4 and kind == _T_LIST:           # row groups
            size, _ = reader.list_header()
            for _ in range(size):
                group = {"columns": [], "num_rows": 0}

                def group_field(fid, k, group=group):
                    if fid == 1 and k == _T_LIST:
                        count, _ = reader.list_header()
                        for _ in range(count):
                            chunk = {}

                            def chunk_field(cfid, ck, chunk=chunk):
                                if cfid == 3 and ck == _T_STRUCT:
                                    def column_meta(mfid, mk):
                                        if mfid == 1 and mk == _T_I32:
                                            chunk["type"] = reader.zigzag()
                                        elif mfid == 3 and mk == _T_LIST:
                                            n, _ = reader.list_header()
                                            chunk["path"] = tuple(
                                                reader.binary().decode("utf-8", "replace")
                                                for _ in range(n)
                                            )
                                        elif mfid == 4 and mk == _T_I32:
                                            chunk["codec"] = reader.zigzag()
                                        elif mfid == 5 and mk == _T_I64:
                                            chunk["num_values"] = reader.zigzag()
                                        elif mfid == 9 and mk == _T_I64:
                                            chunk["data_page_offset"] = reader.zigzag()
                                        elif mfid == 11 and mk == _T_I64:
                                            chunk["dictionary_page_offset"] = reader.zigzag()
                                        elif mfid == 7 and mk == _T_I64:
                                            chunk["total_compressed_size"] = reader.zigzag()
                                        else:
                                            return False
                                        return True

                                    reader.struct(column_meta)
                                    return True
                                return False

                            reader.struct(chunk_field)
                            group["columns"].append(chunk)
                        return True
                    if fid == 3 and k == _T_I64:
                        group["num_rows"] = reader.zigzag()
                        return True
                    return False

                reader.struct(group_field)
                meta["row_groups"].append(group)
            return True

        return False

    reader.struct(file_field)
    return meta


# ── Pages ──────────────────────────────────────────────────────────────────

def _read_column_chunk(handle, chunk, column):
    """Decodes one column chunk into (values, definition levels, repetition levels)."""
    codec = chunk.get("codec", 0)
    if codec not in _CODECS:
        raise ValueError(
            "this file uses %s compression, which needs a decompressor Python does not ship"
            % _CODEC_NAMES.get(codec, "codec %d" % codec)
        )
    decompress = _CODECS[codec]

    start = chunk.get("dictionary_page_offset") or chunk["data_page_offset"]
    if chunk.get("dictionary_page_offset") and chunk["dictionary_page_offset"] > chunk["data_page_offset"]:
        start = chunk["data_page_offset"]
    handle.seek(start)
    remaining = chunk["num_values"]

    dictionary = None
    values, definitions, repetitions = [], [], []

    while remaining > 0:
        header_bytes = handle.read(1 << 16)             # headers are small; this always covers one
        if not header_bytes:
            break
        reader = _Thrift(header_bytes)
        page = {"type": None, "uncompressed": 0, "compressed": 0, "num_values": 0,
                "encoding": 0, "def_encoding": 3, "rep_encoding": 3}

        def page_field(fid, kind):
            if fid == 1 and kind == _T_I32:
                page["type"] = reader.zigzag()
            elif fid == 2 and kind == _T_I32:
                page["uncompressed"] = reader.zigzag()
            elif fid == 3 and kind == _T_I32:
                page["compressed"] = reader.zigzag()
            elif fid == 5 and kind == _T_STRUCT:        # DataPageHeader
                def data_field(dfid, dk):
                    if dfid == 1 and dk == _T_I32:
                        page["num_values"] = reader.zigzag()
                    elif dfid == 2 and dk == _T_I32:
                        page["encoding"] = reader.zigzag()
                    elif dfid == 3 and dk == _T_I32:
                        page["def_encoding"] = reader.zigzag()
                    elif dfid == 4 and dk == _T_I32:
                        page["rep_encoding"] = reader.zigzag()
                    else:
                        return False
                    return True

                reader.struct(data_field)
            elif fid == 7 and kind == _T_STRUCT:        # DictionaryPageHeader
                def dict_field(dfid, dk):
                    if dfid == 1 and dk == _T_I32:
                        page["num_values"] = reader.zigzag()
                        return True
                    return False

                reader.struct(dict_field)
            else:
                return False
            return True

        reader.struct(page_field)
        header_size = reader.pos

        # The speculative read above almost certainly over-read; rewind to just past the header.
        handle.seek(start if False else handle.tell() - len(header_bytes) + header_size)
        body = decompress(handle.read(page["compressed"]))

        if page["type"] == 2:                           # dictionary page
            dictionary = _read_plain(body, column.physical_type, page["num_values"])
            continue

        pos = 0
        count = page["num_values"]

        if column.max_rep > 0:
            size = int.from_bytes(body[pos:pos + 4], "little")
            pos += 4
            width = max(1, (column.max_rep).bit_length())
            repetitions.extend(_read_rle_bitpacked(body[pos:pos + size], width, count))
            pos += size
        else:
            repetitions.extend([0] * count)

        if column.max_def > 0:
            size = int.from_bytes(body[pos:pos + 4], "little")
            pos += 4
            width = max(1, (column.max_def).bit_length())
            page_defs = _read_rle_bitpacked(body[pos:pos + size], width, count)
            pos += size
        else:
            page_defs = [0] * count
        definitions.extend(page_defs)

        present = sum(1 for d in page_defs if d == column.max_def)
        payload = body[pos:]

        if page["encoding"] in (2, 8):                  # PLAIN_DICTIONARY / RLE_DICTIONARY
            if dictionary is None:
                raise ValueError("dictionary-encoded page with no dictionary page")
            width = payload[0]
            indices = _read_rle_bitpacked(payload[1:], width, present)
            values.extend(dictionary[i] for i in indices)
        elif page["encoding"] == 0:                     # PLAIN
            values.extend(_read_plain(payload, column.physical_type, present))
        else:
            raise ValueError("unsupported page encoding %d" % page["encoding"])

        remaining -= count

    return values, definitions, repetitions


def _rows_from_levels(values, definitions, repetitions, column):
    """Regroups a flat column back into one list per row.

    A repetition level of 0 starts a new row; anything higher continues the current one. A value is
    present only at full definition level -- below that the entry is a null somewhere up the tree,
    and consumes a level slot without consuming a value.
    """
    rows = []
    current = None
    index = 0

    for def_level, rep_level in zip(definitions, repetitions):
        if rep_level == 0:
            if current is not None:
                rows.append(current)
            current = []
        if def_level == column.max_def:
            current.append(values[index])
            index += 1

    if current is not None:
        rows.append(current)
    return rows


def read_columns(path, wanted):
    """Reads the named leaf columns from one Parquet file.

    `wanted` is a sequence of dotted paths as Parquet stores them, e.g.
    `conversation.list.element.role`. Returns a dict of path -> list of per-row lists.
    """
    with open(path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        if size < 12:
            raise ValueError("not a parquet file: too short")
        handle.seek(size - 8)
        tail = handle.read(8)
        if tail[4:] != b"PAR1":
            raise ValueError("not a parquet file: missing trailing magic")
        footer_length = int.from_bytes(tail[:4], "little")
        handle.seek(size - 8 - footer_length)
        meta = _parse_metadata(handle.read(footer_length))

        columns = {}
        for column in _build_schema(meta["schema"]):
            columns[".".join(column.path)] = column

        results = dict((name, []) for name in wanted)
        for group in meta["row_groups"]:
            for chunk in group["columns"]:
                name = ".".join(chunk.get("path", ()))
                if name not in results:
                    continue
                column = columns.get(name)
                if column is None:
                    continue
                values, definitions, repetitions = _read_column_chunk(handle, chunk, column)
                results[name].extend(_rows_from_levels(values, definitions, repetitions, column))
        return results


def column_names(path):
    """Every leaf column in the file, as dotted paths. Used to recognise a dataset's shape."""
    with open(path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(size - 8)
        tail = handle.read(8)
        if tail[4:] != b"PAR1":
            raise ValueError("not a parquet file: missing trailing magic")
        footer_length = int.from_bytes(tail[:4], "little")
        handle.seek(size - 8 - footer_length)
        meta = _parse_metadata(handle.read(footer_length))
    return [".".join(c.path) for c in _build_schema(meta["schema"])]
