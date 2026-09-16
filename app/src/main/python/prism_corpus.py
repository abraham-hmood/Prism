"""Turns whatever corpus a user has into the JSON format CakeChat actually reads.

THE TARGET FORMAT, verified two ways
-----------------------------------
Against upstream `data/corpora_processed/train_processed_dialogs.txt`, and against
`cakechat/utils/dataset_loader.py`, which parses it with `load_processed_dialogs_from_json()` using
`text_field_name='text'` and `condition_field_name='condition'`. One JSON ARRAY per line, each
element an utterance object:

    [{"text": "Hello", "condition": "neutral"}, {"text": "Oh, hi!", "condition": "joy"}]

Nothing else is accepted. There is no raw-corpus directory in the repository and no ingestion step
that will convert for you -- `tools/train.py` reads this file directly and raises if it is absent or
malformed, which is why a DailyDialog download cannot simply be handed over.

WHAT THIS ACCEPTS
-----------------
  * a DailyDialog zip                 -- pairs the text file with its parallel emotion file, and
                                         looks inside the nested train/validation/test archives
  * dialogues_text.txt on its own     -- ` __eou__ `-separated, conditions default to neutral
  * JSONL of string arrays            -- ["hi", "hello"]           -> conditions added
  * a single JSON array of arrays     -- [["hi", "hello"], ...]     -> flattened to lines
  * JSONL already in the target shape -- rewritten, not blindly copied
  * plain text, blank-line separated  -- one utterance per line

Every conversion reports the dialog count, the files it used and the condition spread, so a corpus
that silently lost its labels is visible immediately rather than as "1 conditions" once a long
training run has already started.
"""
import io
import json
import os
import zipfile

#: CakeChat's five conditions, taken from its own config (EMOTIONS_TYPES).
CONDITIONS = ("neutral", "joy", "sadness", "anger", "fear")

#: DailyDialog's emotion indices, mapped onto CakeChat's smaller set.
#:
#: LOSSY, AND DELIBERATELY SO. DailyDialog has seven labels and CakeChat has five, so two have to go
#: somewhere: `disgust` folds into anger as the nearest negative-arousal label, and `surprise` folds
#: into neutral rather than joy -- surprise is as often bad news as good, and mapping it to joy would
#: teach the model to sound delighted about shocks.
DAILYDIALOG_EMOTIONS = {
    0: "neutral",    # no emotion
    1: "anger",
    2: "anger",      # disgust
    3: "fear",
    4: "joy",        # happiness
    5: "sadness",
    6: "neutral",    # surprise
}

#: DailyDialog ships text and emotion labels as PARALLEL files, named differently per split. The
#: full archive has dialogues_text.txt/dialogues_emotion.txt at the top level; the per-split
#: archives nested inside it use dialogues_train.txt/dialogues_emotion_train.txt and the same shape
#: for validation and test.
#:
#: PAIRING BY SPLIT IS THE POINT. Matching "any text file" to "any emotion file" would cheerfully
#: pair the train text with the test labels, which aligns nothing and mislabels every utterance --
#: worse than having no labels at all, because the model would learn the wrong associations.
DAILYDIALOG_PAIRS = (
    ("dialogues_text.txt", "dialogues_emotion.txt"),
    ("dialogues_train.txt", "dialogues_emotion_train.txt"),
    ("dialogues_validation.txt", "dialogues_emotion_validation.txt"),
    ("dialogues_test.txt", "dialogues_emotion_test.txt"),
)

_EOU = "__eou__"


def _utterance(text, condition="neutral"):
    text = " ".join(str(text).split())
    return {"text": text, "condition": condition if condition in CONDITIONS else "neutral"}


def _write(dialogs, out_path):
    """Writes dialogs out, dropping anything too short to be a dialog.

    CakeChat needs at least a context and a reply, so a one-utterance line is not a shorter dialog --
    it is an unusable one, and leaving it in produces training pairs with no target.
    """
    written = 0
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        for dialog in dialogs:
            usable = [u for u in dialog if u["text"]]
            if len(usable) < 2:
                continue
            handle.write(json.dumps(usable, ensure_ascii=False))
            handle.write("\n")
            written += 1
    return written


# ── Detection ──────────────────────────────────────────────────────────────

#: What the conversion has said so far, newest last. Bounded, because a dataset of six shards is
#: chatty and nothing reads more than the tail.
_LOGS = []

#: The single line describing what is happening RIGHT NOW, for a status field that has room for one.
_STATUS = ""


def _log(message):
    """Records a line of progress, for a UI that is polling rather than waiting.

    NEEDED BECAUSE CONVERSION IS NO LONGER QUICK. A DailyDialog zip converts in a second and a
    single return value was enough for it; a six-shard Parquet dataset takes minutes per file, and
    a UI that says nothing until the end is indistinguishable from one that has hung.
    """
    global _STATUS
    _STATUS = message
    _LOGS.append(message)
    if len(_LOGS) > 400:
        del _LOGS[:200]


def recent_logs(limit=200):
    """The tail of the log, for Kotlin to poll while a conversion runs."""
    return _LOGS[-int(limit):]


def current_status():
    """The one-line description of the current step."""
    return _STATUS


def _reset_log():
    global _STATUS
    del _LOGS[:]
    _STATUS = ""


#: Leaf names that hold what was said, and who said it, in decreasing order of preference. These
#: are the LAST component of a column path, not the whole path -- see [_find_chat_columns].
_CONTENT_LEAVES = ("content", "value", "text", "message", "utterance")
_ROLE_LEAVES = ("role", "from", "speaker", "sender")

#: Parents worth preferring when a file has more than one repeated group of strings.
_DIALOG_PARENTS = ("conversation", "conversations", "messages", "dialog", "dialogue", "turns")


def _find_chat_columns(names):
    """Works out which columns hold the dialog, from the file's own schema.

    MATCHED BY LEAF NAME, NEVER BY FULL PATH. Parquet spells a list's inner element differently
    depending on what wrote the file: pyarrow emits `conversation.list.element.content`, while
    LMSYS-Chat-1M -- written by an older toolchain -- emits `conversation.list.item.content`. Both
    describe exactly the same data. A reader that matches whole paths therefore works on files it
    was tested against and silently finds nothing in the ones it was not, which is precisely what
    happened: the dataset was recognised as Parquet, every column was read, and not one dialog came
    out of it.

    Returns (content_path, role_path); role may be None, which is fine -- roles are not used as
    conditions anyway.
    """
    def leaf(name):
        return name.rsplit(".", 1)[-1].lower()

    def parent(name):
        return name.split(".", 1)[0].lower()

    def rank(name):
        # Prefer a column under a parent that sounds like a conversation, so a dataset that also
        # carries, say, moderation categories keyed by "text" does not win.
        return 0 if parent(name) in _DIALOG_PARENTS else 1

    content = None
    for wanted in _CONTENT_LEAVES:
        candidates = sorted((n for n in names if leaf(n) == wanted), key=rank)
        if candidates:
            content = candidates[0]
            break
    if content is None:
        return None, None

    # The role sits beside the content under the same parent path, whatever that path is called.
    prefix = content.rsplit(".", 1)[0]
    role = None
    for wanted in _ROLE_LEAVES:
        for name in names:
            if name.rsplit(".", 1)[0] == prefix and leaf(name) == wanted:
                role = name
                break
        if role:
            break
    return content, role


def _is_parquet(path):
    """Recognised by its trailing magic rather than its name.

    Parquet files carry "PAR1" at both ends. Checking the tail rather than the extension means a
    file downloaded as `train-00000-of-00006` with no suffix is still recognised, and a `.parquet`
    that is actually an HTML error page is not.
    """
    try:
        with open(path, "rb") as handle:
            handle.seek(0, os.SEEK_END)
            if handle.tell() < 12:
                return False
            handle.seek(-4, os.SEEK_END)
            return handle.read(4) == b"PAR1"
    except Exception:
        return False


def _parquet_files(directory):
    """Every parquet file in a directory, in name order.

    SORTED, BECAUSE ORDER IS PART OF THE DATA. Hugging Face publishes shards named
    `train-00000-of-00006.parquet`, and reading them in filesystem order would interleave a corpus
    differently on every machine -- which makes a training run unreproducible for no reason.

    Not recursive: a dataset directory holds its shards flat, and descending would sweep up whatever
    else happens to be nearby.
    """
    try:
        names = sorted(os.listdir(directory))
    except Exception:
        return []
    found = []
    for name in names:
        candidate = os.path.join(directory, name)
        if os.path.isfile(candidate) and _is_parquet(candidate):
            found.append(candidate)
    return found


def _read_parquet_columns(path, wanted):
    """Reads columns through pyarrow when it is installed, and the built-in reader otherwise.

    Desktop has pyarrow -- it is faster and handles every corner of the format. Android cannot:
    Chaquopy ships no pyarrow, no fastparquet and no python-snappy, so [prism_parquet] decodes the
    file itself. Both paths are checked against each other by the test fixture, because a parquet
    reader that is subtly wrong returns plausible strings in the wrong order rather than failing.
    """
    try:
        import pyarrow.parquet as pq
    except ImportError:
        import prism_parquet
        return prism_parquet.read_columns(path, wanted)

    table = pq.read_table(path)
    names = set(table.schema.names)
    out = {}
    for target in wanted:
        # pyarrow addresses the list itself; the dotted leaf path is Parquet's own spelling.
        top = target.split(".")[0]
        if top not in names:
            out[target] = []
            continue
        leaf = target.split(".")[-1]
        rows = []
        for value in table.column(top).to_pylist():
            if value is None:
                rows.append([])
                continue
            rows.append([item.get(leaf) for item in value if isinstance(item, dict)])
        out[target] = rows
    return out


def _from_parquet(paths):
    """Turns chat-shaped parquet into dialogs.

    Each row is one conversation and each element of its list is one utterance, which is already
    CakeChat's shape. Roles are read but DROPPED: they say who spoke, not how, and CakeChat's
    condition is an emotion. Turning "user"/"assistant" into conditions would train a model whose
    only conditioning axis is speaker identity, which is not what the feature means and would make
    emotion selection silently meaningless.

    Returns (dialogs, note) where the note describes what was found, so a file whose shape is not
    recognised can say what it DID contain rather than only that it produced nothing.
    """
    dialogs = []
    note = ""
    total = len(paths)

    for index, path in enumerate(paths, 1):
        # NAMED AND NUMBERED. With six shards of a quarter-gigabyte each, "converting" on its own
        # leaves no way to tell progress from a hang, and the numbers are what say which.
        _log("Reading %s (%d of %d)…" % (os.path.basename(path), index, total))
        try:
            names = _parquet_column_names(path)
        except Exception as exc:
            note = "could not read %s: %s" % (os.path.basename(path), exc)
            continue

        content_path, role_path = _find_chat_columns(names)
        if content_path is None:
            note = "no message column in %s; it has: %s" % (
                os.path.basename(path), ", ".join(names[:12]) or "nothing",
            )
            continue

        wanted = [content_path] + ([role_path] if role_path else [])
        columns = _read_parquet_columns(path, wanted)
        rows = columns.get(content_path) or []
        if not note:
            note = "read %s" % content_path

        before = len(dialogs)
        for row in rows:
            utterances = []
            for value in row:
                if isinstance(value, bytes):
                    value = value.decode("utf-8", "replace")
                text = (value or "").strip()
                if text:
                    utterances.append(_utterance(text))
            # A dialog needs a prompt and a reply; a single stranded turn teaches nothing about
            # responding and would pad the corpus with noise.
            if len(utterances) >= 2:
                dialogs.append(utterances)

        _log("%s: %d dialogs (%d so far)"
             % (os.path.basename(path), len(dialogs) - before, len(dialogs)))

    return dialogs, note


def _parquet_column_names(path):
    """Leaf column paths, through pyarrow when available and the built-in reader otherwise."""
    try:
        import pyarrow.parquet as pq
    except ImportError:
        import prism_parquet
        return prism_parquet.column_names(path)

    def walk(field, prefix):
        import pyarrow as pa
        name = prefix + field.name
        if pa.types.is_list(field.type) or pa.types.is_large_list(field.type):
            # pyarrow reports the logical type; Parquet's own path inserts the repeated group and
            # its child, and the child's name is exactly the thing that differs between writers.
            child = field.type.value_field
            return walk(child, name + ".list.")
        if pa.types.is_struct(field.type):
            out = []
            for i in range(field.type.num_fields):
                out.extend(walk(field.type.field(i), name + "."))
            return out
        return [name]

    schema = pq.ParquetFile(path).schema_arrow
    names = []
    for i in range(len(schema)):
        names.extend(walk(schema.field(i), ""))
    return names


def detect(path):
    """Names the input format, so the caller can say what it is doing.

    Sniffed from the first non-empty line rather than the extension: a DailyDialog text file and a
    JSONL corpus are both `.txt`, and users rename downloads. Parquet is the exception -- it is
    binary and carries its own magic, so it is recognised by that instead.
    """
    if os.path.isdir(path):
        return "parquet-dir" if _parquet_files(path) else "unknown"
    if _is_parquet(path):
        return "parquet"
    if zipfile.is_zipfile(path):
        return "zip"
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
            head = ""
            for line in handle:
                if line.strip():
                    head = line.strip()
                    break
        if not head:
            return "empty"
        if head.startswith("["):
            try:
                parsed = json.loads(head)
            except ValueError:
                # A single JSON array spanning the whole file starts with "[" but does not parse as
                # one line, which is how the array-of-arrays form shows up.
                return "json-array-file"
            if isinstance(parsed, list) and parsed:
                if isinstance(parsed[0], dict):
                    return "cakechat"
                if isinstance(parsed[0], str):
                    return "jsonl-strings"
                if isinstance(parsed[0], list):
                    return "json-array-file"
            return "unknown"
        if _EOU in head:
            return "dailydialog-text"
        return "plain-text"
    except Exception:
        return "unknown"


# ── Converters ─────────────────────────────────────────────────────────────

def _from_dailydialog_lines(text_lines, emotion_lines=None):
    """Pairs ` __eou__ `-separated utterances with their parallel emotion labels.

    Alignment is checked per dialog rather than assumed. The two files are separate downloads and
    can disagree -- a mismatched pair silently shifts every label by one utterance, which is worse
    than no labels at all because it teaches exactly the wrong associations.
    """
    emotion_iter = iter(emotion_lines) if emotion_lines is not None else None
    mismatches = 0

    for raw in text_lines:
        raw = raw.strip()
        if not raw:
            continue
        parts = [p.strip() for p in raw.split(_EOU)]
        parts = [p for p in parts if p]
        if len(parts) < 2:
            continue

        labels = None
        if emotion_iter is not None:
            try:
                label_line = next(emotion_iter)
            except StopIteration:
                emotion_iter = None
            else:
                tokens = label_line.split()
                if len(tokens) == len(parts):
                    labels = [
                        DAILYDIALOG_EMOTIONS.get(int(t), "neutral") if t.isdigit() else "neutral"
                        for t in tokens
                    ]
                else:
                    mismatches += 1

        if labels is None:
            yield [_utterance(p) for p in parts]
        else:
            yield [_utterance(p, c) for p, c in zip(parts, labels)]

    _from_dailydialog_lines.last_mismatches = mismatches


_from_dailydialog_lines.last_mismatches = 0


def _zip_members(archive, prefix=""):
    """Every file in the archive, recursing into nested zips.

    DailyDialog nests train.zip / validation.zip / test.zip INSIDE the outer archive, so the split
    files -- the ones carrying the 11,118 train dialogs -- are invisible to a flat listing. Yields
    (display_name, reader) where reader() returns the bytes.
    """
    for name in archive.namelist():
        if name.startswith("__MACOSX") or name.endswith("/"):
            continue
        if name.lower().endswith(".zip"):
            try:
                nested_bytes = archive.read(name)
                with zipfile.ZipFile(io.BytesIO(nested_bytes)) as nested:
                    for inner_name, reader in _zip_members(nested, prefix + name + "!/"):
                        yield inner_name, reader
            except Exception:
                continue
        else:
            yield prefix + name, (lambda n=name, a=archive: a.read(n))


def _from_zip(path):
    """Finds a matched DailyDialog text/emotion pair, looking inside nested archives.

    Returns (dialogs, had_emotions, description). The description names the files actually used, so
    a run that fell back to unlabelled text is visible in the log rather than only as "1 conditions"
    much later.
    """
    with zipfile.ZipFile(path) as archive:
        members = {}
        for name, reader in _zip_members(archive):
            members[name] = reader()          # read eagerly; the archive closes with this block

    def find(suffix):
        matches = [n for n in members if n.replace("!/", "/").split("/")[-1] == suffix]
        # Shortest path first, so a top-level file wins over a nested one of the same name.
        return sorted(matches, key=len)[0] if matches else None

    # Logged for the same reason the parquet shards are: an archive can hold several candidate
    # pairs nested inside one another, and which one was chosen decides whether emotions survive.
    for text_name, emotion_name in DAILYDIALOG_PAIRS:
        text_path = find(text_name)
        if text_path is None:
            continue

        emotion_path = find(emotion_name)
        text_lines = members[text_path].decode("utf-8", "replace").splitlines()
        emotion_lines = (
            members[emotion_path].decode("utf-8", "replace").splitlines()
            if emotion_path else None
        )

        dialogs = list(_from_dailydialog_lines(text_lines, emotion_lines))
        described = ("%s + %s" % (text_path, emotion_path)) if emotion_path \
            else ("%s (no matching emotion file)" % text_path)
        _log("Using %s from the archive" % described)
        return dialogs, bool(emotion_path), described

    raise ValueError(
        "No DailyDialog text file in the archive. Looked for %s; found %s"
        % (", ".join(t for t, _ in DAILYDIALOG_PAIRS),
           ", ".join(sorted(members)[:12]) or "nothing")
    )


def _from_jsonl_strings(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if isinstance(entry, list):
                yield [
                    _utterance(u) if isinstance(u, str)
                    else _utterance(u.get("text", ""), u.get("condition", "neutral"))
                    for u in entry
                ]


def _from_json_array_file(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        parsed = json.load(handle)
    if not isinstance(parsed, list):
        raise ValueError("The file is JSON, but not an array of dialogs.")
    for entry in parsed:
        if not isinstance(entry, list):
            continue
        yield [
            _utterance(u) if isinstance(u, str)
            else _utterance(u.get("text", ""), u.get("condition", "neutral"))
            for u in entry
        ]


def _from_plain_text(path):
    """Blank-line separated blocks, one utterance per line."""
    block = []
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                block.append(_utterance(stripped))
            elif block:
                yield block
                block = []
    if block:
        yield block


# ── Entry point ────────────────────────────────────────────────────────────

def _condition_counts(path):
    """How many utterances carry each condition, most common first."""
    counts = {}
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            for raw in handle:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    for utterance in json.loads(raw):
                        name = utterance.get("condition", "?")
                        counts[name] = counts.get(name, 0) + 1
                except ValueError:
                    continue
    except Exception:
        return []
    return sorted(counts.items(), key=lambda kv: -kv[1])


#: Readable names for the format ids [detect] returns. The ids are internal slugs and appear in
#: messages a person reads after picking a file, where "jsonl-strings" says nothing useful.
FORMAT_NAMES = {
    "zip": "a DailyDialog archive",
    "dailydialog-text": "DailyDialog text",
    "cakechat": "an existing CakeChat corpus",
    "jsonl-strings": "JSON lines",
    "json-array-file": "a JSON array",
    "plain-text": "plain text",
    "parquet": "a Parquet file",
    "parquet-dir": "a Parquet dataset",
}


def describe_format(kind):
    return FORMAT_NAMES.get(kind, kind)


def convert(path, out_path):
    """Converts [path] into CakeChat's JSON corpus at [out_path].

    Returns (ok, message, dialog_count). Never raises -- the caller is a UI, and the message is
    written to be read by whoever picked the file.

    An input already in the target shape is REWRITTEN rather than copied, which normalises
    whitespace, drops single-utterance lines and guarantees the trailing newline
    `FileTextLinesIterator` expects.
    """
    if not os.path.isfile(path) and not os.path.isdir(path):
        return False, "Nothing at %s" % path, 0

    _reset_log()
    kind = detect(path)
    _log("Reading %s…" % describe_format(kind))
    _from_dailydialog_lines.last_mismatches = 0

    try:
        had_emotions = True
        described = None

        if kind in ("parquet", "parquet-dir"):
            # A DIRECTORY IS THE NORMAL CASE, not the exception: datasets of this size are
            # published as numbered shards, and a corpus assembled from one of six files would be
            # a sixth of the data with nothing to indicate it.
            files = [path] if kind == "parquet" else _parquet_files(path)
            dialogs, note = _from_parquet(files)
            had_emotions = False
            described = "%d parquet file(s)%s" % (len(files), ", %s" % note if note else "")
        elif kind == "zip":
            dialogs, had_emotions, described = _from_zip(path)
        elif kind == "dailydialog-text":
            with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
                dialogs = list(_from_dailydialog_lines(handle.read().splitlines(), None))
            had_emotions = False
        elif kind in ("cakechat", "jsonl-strings"):
            dialogs = list(_from_jsonl_strings(path))
        elif kind == "json-array-file":
            dialogs = list(_from_json_array_file(path))
        elif kind == "plain-text":
            dialogs = list(_from_plain_text(path))
            had_emotions = False
        elif kind == "empty":
            return False, "That file is empty.", 0
        else:
            return False, (
                "Could not tell what format that file is. Expected a DailyDialog zip, a "
                "__eou__-separated text file, or JSON lines of dialogs."
            ), 0

        written = _write(dialogs, out_path)
        if written == 0:
            return False, (
                "Recognised the file as %s but found no dialogs with at least two utterances."
                % describe_format(kind)
            ), 0

        message = "Converted %d dialogs from %s." % (written, describe_format(kind))
        _log(message)
        if described:
            message += " Used %s." % described

        # Reported up front. "1 conditions" only becomes visible during training otherwise, by which
        # point a long run has already started on a corpus that cannot teach conditioning anything.
        spread = _condition_counts(out_path)
        if spread:
            message += " Conditions: %s." % ", ".join(
                "%s=%d" % (name, count) for name, count in spread
            )

        if not had_emotions:
            message += (
                " No emotion labels were present, so every utterance is neutral — conditioning "
                "will have nothing to learn from."
            )
        if _from_dailydialog_lines.last_mismatches:
            message += (
                " %d dialogs had emotion labels that did not line up with their utterances and "
                "were left neutral." % _from_dailydialog_lines.last_mismatches
            )
        return True, message, written
    except Exception as exc:
        return False, "Conversion failed: %s" % exc, 0
