package com.prism.launcher.storage.drive

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * NTFS, read-only.
 *
 * Everything on NTFS is a record in the Master File Table, and everything about a record is an
 * attribute: its names ($FILE_NAME), its contents ($DATA), and for a directory its index -- a B-tree
 * of file names held in $INDEX_ROOT and, once it outgrows the record, $INDEX_ALLOCATION. Large
 * attributes live outside the record as "data runs", a compact list of (length, cluster delta)
 * pairs. Records and index blocks carry an update sequence that has to be put back before they are
 * read, which is the first thing an NTFS reader gets wrong.
 *
 * Not supported: compressed and encrypted files (they are listed, and opening one says so), and
 * replaying $LogFile -- a drive removed without ejecting may be missing its last changes here.
 */
class NtfsFileSystem(private val device: BlockDevice) : DriveFileSystem {

    private val boot = device.read(0, 512)
    private val bytesPerSector = Bytes.u16(boot, 11)
    private val clusterSize: Int = Bytes.u8(boot, 13).let { raw ->
        if (raw > 0x80) 1 shl (256 - raw) else raw
    } * bytesPerSector
    private val mftCluster = Bytes.u64(boot, 48)
    private val recordSize: Int = boot[64].toInt().let { raw ->
        if (raw < 0) 1 shl -raw else raw * clusterSize
    }

    init {
        if (Bytes.ascii(boot, 3, 8) != "NTFS    ") throw IOException("Not NTFS")
    }

    override val typeId = "ntfs"
    override val typeName = "NTFS"
    override val label: String by lazy {
        runCatching {
            val volume = record(3)
            attributes(volume, 3).firstOrNull { it.type == 0x60 }?.let { attr ->
                String(attr.residentValue(), Charsets.UTF_16LE)
            }
        }.getOrNull().orEmpty()
    }

    // ── Records ───────────────────────────────────────────────────────────

    /** $MFT's own data runs, read from record 0 at the cluster the boot sector names. */
    private val mftRuns: List<DataRun> by lazy {
        val zero = fixup(device.read(mftCluster * clusterSize, recordSize), "FILE")
        val data = attributes(zero, 0).filter { it.type == 0x80 && it.name.isEmpty() }
        data.flatMap { it.runs() }
    }

    private fun record(number: Long): ByteArray {
        val offset = number * recordSize
        val bytes = readRuns(mftRuns, offset, recordSize)
        return fixup(bytes, "FILE")
    }

    /** Puts back the last two bytes of each sector, which the update sequence array saved. */
    private fun fixup(raw: ByteArray, magic: String): ByteArray {
        if (Bytes.ascii(raw, 0, 4) != magic) throw IOException("Bad $magic record")
        val usaOffset = Bytes.u16(raw, 4)
        val usaCount = Bytes.u16(raw, 6)
        val sector = bytesPerSector
        for (i in 1 until usaCount) {
            val end = i * sector - 2
            if (end + 1 >= raw.size) break
            raw[end] = raw[usaOffset + i * 2]
            raw[end + 1] = raw[usaOffset + i * 2 + 1]
        }
        return raw
    }

    // ── Attributes ────────────────────────────────────────────────────────

    private data class DataRun(val vcn: Long, val lcn: Long?, val length: Long)

    private inner class Attribute(val record: ByteArray, val at: Int) {
        val type = Bytes.u32(record, at).toInt()
        val length = Bytes.u32(record, at + 4).toInt()
        val nonResident = Bytes.u8(record, at + 8) != 0
        val flags = Bytes.u16(record, at + 12)
        val name: String = Bytes.u8(record, at + 9).let { chars ->
            if (chars == 0) "" else Bytes.utf16(record, at + Bytes.u16(record, at + 10), chars)
        }

        fun residentValue(): ByteArray {
            val size = Bytes.u32(record, at + 16).toInt()
            val offset = Bytes.u16(record, at + 20)
            return record.copyOfRange(at + offset, at + offset + size)
        }

        val startVcn get() = Bytes.u64(record, at + 16)
        val dataSize get() = if (nonResident) Bytes.u64(record, at + 48) else Bytes.u32(record, at + 16)
        val compressed get() = flags and 0x0001 != 0
        val encrypted get() = flags and 0x4000 != 0

        fun runs(): List<DataRun> {
            val out = mutableListOf<DataRun>()
            var p = at + Bytes.u16(record, at + 32)
            var vcn = startVcn
            var lcn = 0L
            while (p < at + length) {
                val header = Bytes.u8(record, p)
                if (header == 0) break
                val lengthBytes = header and 0x0F
                val offsetBytes = header shr 4
                var runLength = 0L
                for (i in 0 until lengthBytes) runLength = runLength or (Bytes.u8(record, p + 1 + i).toLong() shl (8 * i))
                var delta = 0L
                for (i in 0 until offsetBytes) delta = delta or (Bytes.u8(record, p + 1 + lengthBytes + i).toLong() shl (8 * i))
                // The offset is signed: sign-extend from its top byte.
                if (offsetBytes > 0 && Bytes.u8(record, p + lengthBytes + offsetBytes) and 0x80 != 0) {
                    delta = delta or (-1L shl (8 * offsetBytes))
                }
                if (offsetBytes == 0) {
                    out += DataRun(vcn, null, runLength) // Sparse.
                } else {
                    lcn += delta
                    out += DataRun(vcn, lcn, runLength)
                }
                vcn += runLength
                p += 1 + lengthBytes + offsetBytes
            }
            return out
        }
    }

    /**
     * A record's attributes, following $ATTRIBUTE_LIST into the extension records a heavily
     * fragmented file or a large directory spills into.
     */
    private fun attributes(record: ByteArray, number: Long): List<Attribute> {
        val own = mutableListOf<Attribute>()
        var at = Bytes.u16(record, 20)
        while (at + 8 < record.size) {
            val type = Bytes.u32(record, at)
            if (type == 0xFFFFFFFFL) break
            val length = Bytes.u32(record, at + 4).toInt()
            if (length <= 0) break
            own += Attribute(record, at)
            at += length
        }
        val list = own.firstOrNull { it.type == 0x20 } ?: return own
        val listBytes = if (list.nonResident) readAttribute(list) else list.residentValue()
        val extensions = mutableSetOf<Long>()
        var p = 0
        while (p + 26 <= listBytes.size) {
            val entryLength = Bytes.u16(listBytes, p + 4)
            if (entryLength == 0) break
            val reference = Bytes.u64(listBytes, p + 16) and 0xFFFFFFFFFFFFL
            if (reference != number) extensions += reference
            p += entryLength
        }
        return own + extensions.flatMap { reference ->
            runCatching {
                val extension = record(reference)
                var a = Bytes.u16(extension, 20)
                val found = mutableListOf<Attribute>()
                while (a + 8 < extension.size && Bytes.u32(extension, a) != 0xFFFFFFFFL) {
                    val length = Bytes.u32(extension, a + 4).toInt()
                    if (length <= 0) break
                    found += Attribute(extension, a)
                    a += length
                }
                found
            }.getOrDefault(emptyList())
        }
    }

    private fun readRuns(runs: List<DataRun>, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val position = offset + done
            val vcn = position / clusterSize
            val within = (position % clusterSize).toInt()
            val run = runs.firstOrNull { vcn >= it.vcn && vcn < it.vcn + it.length } ?: break
            val count = minOf((run.vcn + run.length - vcn) * clusterSize - within, (length - done).toLong()).toInt()
            if (run.lcn != null) {
                System.arraycopy(device.read((run.lcn + (vcn - run.vcn)) * clusterSize + within, count), 0, out, done, count)
            }
            done += count
        }
        return out
    }

    private fun readAttribute(attribute: Attribute): ByteArray =
        if (!attribute.nonResident) attribute.residentValue()
        else readRuns(attribute.runs(), 0, attribute.dataSize.coerceAtMost(64L * 1024 * 1024).toInt())

    // ── Directories ───────────────────────────────────────────────────────

    private data class Raw(val entry: DriveEntry, val reference: Long)

    private fun fileTime(value: Long): Long = if (value == 0L) 0 else value / 10_000 - 11_644_473_600_000L

    private fun indexEntries(block: ByteArray, headerAt: Int, path: String, out: MutableMap<Long, Raw>, subnodes: MutableList<Long>) {
        var p = headerAt + Bytes.u32(block, headerAt).toInt()
        val end = headerAt + Bytes.u32(block, headerAt + 4).toInt()
        while (p + 16 <= end && p + 16 <= block.size) {
            val length = Bytes.u16(block, p + 8)
            val keyLength = Bytes.u16(block, p + 10)
            val flags = Bytes.u32(block, p + 12)
            if (length == 0) break
            if (flags and 0x1L != 0L) subnodes += Bytes.u64(block, p + length - 8)
            if (flags and 0x2L != 0L) break // The last entry carries no key.
            if (keyLength >= 66) {
                val reference = Bytes.u64(block, p) and 0xFFFFFFFFFFFFL
                val key = p + 16
                val nameLength = Bytes.u8(block, key + 64)
                val namespace = Bytes.u8(block, key + 65)
                // DOS-only short names duplicate the real one.
                if (namespace != 2) {
                    val name = Bytes.utf16(block, key + 66, nameLength)
                    val isDir = Bytes.u32(block, key + 56) and 0x10000000L != 0L
                    val hidden = reference < 24 && name.startsWith("$")
                    if (!hidden && name != ".") {
                        out[reference] = Raw(
                            DriveEntry(
                                name = name,
                                path = DriveFileSystem.child(path, name),
                                isDirectory = isDir,
                                size = if (isDir) 0 else Bytes.u64(block, key + 48),
                                modified = fileTime(Bytes.u64(block, key + 16)),
                            ),
                            reference,
                        )
                    }
                }
            }
            p += length
        }
    }

    private fun entries(reference: Long, path: String): List<Raw> {
        val rec = record(reference)
        val attrs = attributes(rec, reference)
        val root = attrs.firstOrNull { it.type == 0x90 && it.name == "\$I30" }
            ?: throw FileNotFoundException("$path is not a directory")
        val value = root.residentValue()
        val indexBlockSize = Bytes.u32(value, 8).toInt()
        val found = LinkedHashMap<Long, Raw>()
        val pending = mutableListOf<Long>()
        indexEntries(value, 16, path, found, pending)
        val allocation = attrs.filter { it.type == 0xA0 && it.name == "\$I30" }.flatMap { it.runs() }
        val visited = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val vcn = pending.removeAt(0)
            if (!visited.add(vcn) || allocation.isEmpty()) continue
            val block = runCatching {
                fixup(readRuns(allocation, vcn * vcnUnit(indexBlockSize), indexBlockSize), "INDX")
            }.getOrNull() ?: continue
            indexEntries(block, 24, path, found, pending)
        }
        return found.values.toList()
    }

    /** Index VCNs count clusters, or 512-byte units when a cluster is bigger than an index block. */
    private fun vcnUnit(indexBlockSize: Int): Long =
        if (clusterSize <= indexBlockSize) clusterSize.toLong() else 512L

    private fun resolve(path: String): Raw {
        var reference = ROOT_RECORD
        var current = Raw(DriveEntry("", "/", true, 0, 0), ROOT_RECORD)
        val clean = DriveFileSystem.normalise(path)
        if (clean == "/") return current
        var currentPath = "/"
        for (part in clean.removePrefix("/").split('/')) {
            val list = entries(reference, currentPath)
            current = list.firstOrNull { it.entry.name == part }
                ?: list.firstOrNull { it.entry.name.equals(part, ignoreCase = true) }
                ?: throw FileNotFoundException(clean)
            reference = current.reference
            currentPath = current.entry.path
        }
        return current
    }

    override fun list(path: String): List<DriveEntry> {
        val target = resolve(path)
        if (!target.entry.isDirectory) throw FileNotFoundException("$path is a file")
        return entries(target.reference, DriveFileSystem.normalise(path)).map { raw ->
            // The size in an index entry can lag behind the file's; the record's $DATA is the truth.
            if (raw.entry.isDirectory) raw.entry
            else raw.entry.copy(size = runCatching { dataAttribute(raw.reference).second }.getOrDefault(raw.entry.size))
        }
    }

    /** The unnamed $DATA attribute's pieces and the file's size. */
    private fun dataAttribute(reference: Long): Pair<List<Attribute>, Long> {
        val rec = record(reference)
        val data = attributes(rec, reference).filter { it.type == 0x80 && it.name.isEmpty() }
        if (data.isEmpty()) throw FileNotFoundException("No data")
        val size = data.firstOrNull { !it.nonResident || it.startVcn == 0L }?.dataSize ?: 0L
        return data to size
    }

    override fun open(path: String): InputStream {
        val target = resolve(path)
        if (target.entry.isDirectory) throw FileNotFoundException("$path is a directory")
        val (pieces, size) = dataAttribute(target.reference)
        val first = pieces.first()
        if (first.compressed) throw IOException("${target.entry.name} is NTFS-compressed, which Prism cannot read yet")
        if (first.encrypted) throw IOException("${target.entry.name} is encrypted by Windows (EFS)")
        if (!first.nonResident) return first.residentValue().inputStream()
        val runs = pieces.flatMap { it.runs() }.sortedBy { it.vcn }
        return ChunkedInputStream(size) { offset ->
            readRuns(runs, offset, minOf(1L shl 20, size - offset).toInt())
        }
    }

    private companion object {
        const val ROOT_RECORD = 5L
    }
}
