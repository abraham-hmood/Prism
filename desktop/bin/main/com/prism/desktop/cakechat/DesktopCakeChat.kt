package com.prism.desktop.cakechat

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * CakeChat on the desktop, driven through a real Python interpreter.
 *
 * ## Why this is a different implementation, not shared code
 *
 * Android embeds Python via Chaquopy and calls `prism_cakechat` in-process. A desktop has a real
 * interpreter, a real package index and real virtual memory, so it runs the same scripts as a
 * SUBPROCESS instead. The Python is genuinely shared -- desktop resources point at the Android
 * module's python directory -- but how it is invoked cannot be.
 *
 * The desktop side is the easier of the two by some margin, and it is worth saying why: with a real
 * pip, CakeChat can be installed with the TensorFlow 1.12 and Keras 2.2.4 it was written for. None
 * of the compatibility bridging that the Android build needs applies, because none of the version
 * drift it papers over exists here.
 *
 * ## Memory
 *
 * There is no swap machinery here and none is needed. Sam's [com.prism.launcher.messaging.PrismSwap]
 * exists because Android gives a process a hard memory ceiling and no paging; a desktop OS pages to
 * disk on its own, which is the same trick applied by something better placed to do it. The batch
 * size still halves on an out-of-memory, because that is cheaper than swapping either way.
 */
object DesktopCakeChat {

    private const val ARCHIVE_URL =
        "https://github.com/lukalabs/cakechat/archive/refs/heads/master.zip"

    /** Progress, in the same shape the Android service publishes. */
    data class Progress(
        val running: Boolean = false,
        val phase: String = "idle",
        val step: Int = 0,
        val total: Int = 0,
        val epoch: Int = 0,
        val epochs: Int = 0,
        val loss: Double = 0.0,
        val elapsedSeconds: Long = 0,
        val etaSeconds: Long = 0,
        val logs: List<String> = emptyList(),
        val error: String? = null,
        val finished: Boolean = false,
    ) {
        val percent: Int get() = if (total <= 0) 0 else ((step * 100) / total).coerceIn(0, 100)
        val stepsLeft: Int get() = (total - step).coerceAtLeast(0)
    }

    enum class State { NO_PYTHON, ABSENT, INSTALLED, TRAINED }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    @Volatile private var process: Process? = null

    // ── Locations ──────────────────────────────────────────────────────────

    private fun home(): File =
        File(PrismPlatform.host.dataDir(), "cakechat").apply { mkdirs() }

    /** The cloned repository. */
    fun repoDir(): File = File(home(), "repo")

    /** Weights and index files. Outside [repoDir] so re-downloading source keeps a trained model. */
    fun weightsDir(): File = File(home(), "weights").apply { mkdirs() }

    /** The virtual environment CakeChat's dependencies are installed into. */
    fun venvDir(): File = File(home(), "venv")

    /** Where the shared scripts are unpacked from resources. */
    private fun scriptsDir(): File = File(home(), "scripts").apply { mkdirs() }

    fun datasetFile(): File = File(weightsDir(), "train_dialogs.txt")

    // ── Python discovery ───────────────────────────────────────────────────

    /** An interpreter that exists, with the version that decides which stack it can run. */
    data class Python(val exe: File, val major: Int, val minor: Int) {
        val version: String get() = "$major.$minor"

        /**
         * Whether this interpreter can have CakeChat's original stack, TensorFlow 1.12 with
         * multi-backend Keras. Those wheels stop at CPython 3.6, so this is almost never true on a
         * machine of today -- but when it is, it is the closest thing to what CakeChat was tested
         * against and worth preferring.
         */
        val runsUpstreamStack: Boolean get() = major == 3 && minor in 5..6

        /**
         * Whether TensorFlow 2.15 installs. It is bounded at both ends: 2.14 dropped Python 3.8, and
         * 3.11 is the newest CPython it publishes wheels for. The version itself is the ceiling for
         * a different reason -- TensorFlow 2.16 switched to Keras 3, whose module layout the shims
         * in prism_cakechat cannot bridge, so upgrading past it is not an option regardless.
         */
        val runsShimmedStack: Boolean get() = major == 3 && minor in 9..11

        val isSupported: Boolean get() = runsUpstreamStack || runsShimmedStack
    }

    /**
     * The interpreter to build the environment with, preferring one that can actually run CakeChat.
     *
     * ORDERED BY WHAT WORKS, NOT BY WHAT IS FOUND FIRST. The default `python` on a current machine
     * is likely to be 3.12 or newer, which no TensorFlow this bridge supports will install into; a
     * plain "first Python 3 wins" search picks it and then fails at pip, minutes later, with an
     * error about wheels. Probing versions up front costs milliseconds and lets an installed 3.11
     * be used even when it is not the default.
     *
     * An explicit PRISM_PYTHON always wins, supported or not: it is a deliberate instruction, and
     * overriding it would make the variable useless for trying something new.
     */
    fun findPythonInfo(): Python? {
        val explicit = System.getenv("PRISM_PYTHON")?.takeIf { it.isNotBlank() }
        if (explicit != null) probe(listOf(explicit))?.let { return it }

        val candidates = buildList {
            // The py launcher is how a specific version is reached on Windows, where interpreters
            // are not named python3.11 on PATH.
            for (minor in listOf(11, 10, 9)) {
                add(listOf("py", "-3.$minor"))
                add(listOf("python3.$minor"))
            }
            add(listOf("python3.7")); add(listOf("python3.6"))
            add(listOf("python3")); add(listOf("python"))
        }

        val found = candidates.mapNotNull { probe(it) }
        return found.firstOrNull { it.runsUpstreamStack }
            ?: found.firstOrNull { it.runsShimmedStack }
            ?: found.firstOrNull()
    }

    /** Backwards-compatible accessor: the executable alone, for callers that only show a path. */
    fun findPython(): File? = findPythonInfo()?.exe

    /**
     * Asks an interpreter what it is.
     *
     * Reports sys.executable rather than the command used to reach it, because "py -3.11" is a
     * launcher invocation and not a path -- everything downstream needs the real executable to
     * build a virtual environment from.
     */
    private fun probe(command: List<String>): Python? = runCatching {
        val script = "import sys;print(sys.version_info[0],sys.version_info[1],sys.executable)"
        val process = ProcessBuilder(command + listOf("-c", script))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) return@runCatching null

        val parts = output.lines().last().trim().split(" ", limit = 3)
        if (parts.size < 3) return@runCatching null
        Python(File(parts[2]), parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    /**
     * Roughly what a full install occupies: interpreter, wheels, and the temporary copies pip makes
     * while unpacking them. Deliberately generous -- the cost of overestimating is a warning the
     * user can ignore by freeing a little space, and the cost of underestimating is a half-written
     * environment that fails somewhere inside pip.
     */
    private const val INSTALL_BYTES = 4L * 1024 * 1024 * 1024

    /**
     * Checks there is room before committing to a download measured in hundreds of megabytes.
     *
     * CHECKED UP FRONT BECAUSE THE FAILURE IS OTHERWISE UNREADABLE. Running out of space surfaces as
     * `OSError: [Errno 28] No space left on device` in the middle of pip's output, after several
     * minutes of downloading, and it leaves a partially populated environment behind that looks
     * installed. Saying so before starting turns that into one clear sentence.
     */
    private fun hasRoomToInstall(onLine: (String) -> Unit): Boolean {
        val free = runCatching { home().usableSpace }.getOrDefault(0L)
        // A zero here means the query failed rather than that the disk is full; not a reason to
        // block an install that might well succeed.
        if (free <= 0L) return true
        if (free >= INSTALL_BYTES) return true

        val freeGb = free / (1024.0 * 1024 * 1024)
        onLine(
            "Not enough disk space. %s has %.1f GB free, and installing TensorFlow needs about 4 GB, including what pip unpacks while it works. Free some space and try again."
                .format(home().path, freeGb)
        )
        return false
    }

    /**
     * A scratch directory for the tools Prism runs, pointed at by TMPDIR and friends.
     *
     * The system temp directory is on the boot volume, which is routinely the fullest one on a
     * machine; venv creation and pip both stage large files there and fail outright when it is
     * full, even when the drive Prism installs to has room to spare. Keeping the scratch space
     * beside the install means both succeed or both fail, for the same visible reason.
     */
    private fun toolTemp(): File = File(home(), "tmp").apply { mkdirs() }

    /**
     * An interpreter CakeChat can run on: the machine's, or one Prism fetches.
     *
     * DOWNLOADING RATHER THAN REFUSING. TensorFlow 2.15 is the last release exposing Keras 2, and
     * it publishes no wheels past CPython 3.11 -- so on a machine with only 3.12 or newer there is
     * nothing to install and no action the user could take inside Prism. Telling them to go and
     * install an older Python by hand makes the feature unreachable for the common case rather
     * than the rare one, so Prism provisions its own instead.
     *
     * The system interpreter is still preferred when it qualifies: it is already there, it is
     * already patched, and 50 MB not downloaded is 50 MB not downloaded.
     */
    private fun usablePython(onLine: (String) -> Unit): Python? {
        val found = findPythonInfo()
        if (found != null && found.isSupported) {
            onLine("Using Python ${found.version} at ${found.exe.path}")
            return found
        }
        if (found != null) {
            onLine(
                "Python ${found.version} cannot run CakeChat -- TensorFlow 2.15 is the newest " +
                    "release with the Keras 2 layout CakeChat needs, and it stops at Python 3.11."
            )
        }
        return provisionPython(onLine)
    }

    // -- A Python of our own -------------------------------------------------

    /**
     * The python-build-standalone release Prism installs when the machine has nothing usable.
     *
     * WHY NOT PYTHON.ORG. The official Windows installer is an interactive MSI that wants elevation
     * and edits PATH for the whole machine; the "embeddable" zip that does not is a cut-down build
     * with no pip, no venv and no ensurepip. python-build-standalone publishes complete,
     * relocatable CPython builds -- the same ones uv provisions -- which unpack into a directory
     * and run from there, touching nothing outside Prism's data directory.
     *
     * Pinned rather than resolved from the "latest" release: a build that changes underneath users
     * turns one person's working install into another's broken one, with nothing in Prism to
     * explain the difference. 3.11 is the newest CPython TensorFlow 2.15 publishes wheels for.
     */
    private const val PYTHON_RELEASE = "20260814"
    private const val PYTHON_VERSION = "3.11.16"

    /** Where a downloaded interpreter lives. Beside the venv, not inside it -- the venv is built from it. */
    private fun runtimeDir(): File = File(home(), "python-$PYTHON_VERSION")

    /** The interpreter inside [runtimeDir], once unpacked. The tarball roots everything at `python/`. */
    private fun managedPythonExe(): File =
        if (isWindows()) File(runtimeDir(), "python/python.exe")
        else File(runtimeDir(), "python/bin/python3")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", true)

    /**
     * The asset for this machine, or null if python-build-standalone does not publish one.
     *
     * Returning null rather than guessing: a wrong triple downloads 50 MB that cannot execute, and
     * the resulting error ("not a valid application") says nothing about the actual cause.
     */
    private fun standaloneAsset(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()

        val cpu = when {
            arch in listOf("aarch64", "arm64") -> "aarch64"
            arch in listOf("amd64", "x86_64") -> "x86_64"
            else -> return null
        }
        val triple = when {
            os.startsWith("windows") -> "$cpu-pc-windows-msvc"
            os.startsWith("mac") || os.startsWith("darwin") -> "$cpu-apple-darwin"
            os.startsWith("linux") -> "$cpu-unknown-linux-gnu"
            else -> return null
        }
        return "cpython-$PYTHON_VERSION+$PYTHON_RELEASE-$triple-install_only.tar.gz"
    }

    /**
     * Downloads and unpacks a supported interpreter.
     *
     * Idempotent: an already-unpacked runtime is verified by running it, not by checking that the
     * file exists. A download interrupted halfway leaves an executable behind that cannot start,
     * and trusting the path alone would turn that into a confusing failure at pip instead of a
     * second, working download here.
     */
    private fun provisionPython(onLine: (String) -> Unit): Python? {
        probe(listOf(managedPythonExe().path))?.let {
            onLine("Using the Python ${it.version} Prism installed earlier.")
            return it
        }

        val asset = standaloneAsset() ?: run {
            onLine(
                "No prebuilt Python is available for this platform " +
                    "(${System.getProperty("os.name")} ${System.getProperty("os.arch")}). " +
                    "Install Python 3.11 yourself and set PRISM_PYTHON to it."
            )
            return null
        }

        val url = "https://github.com/astral-sh/python-build-standalone/releases/download/" +
            "$PYTHON_RELEASE/$asset"
        val archive = File(home(), asset)

        onLine("No supported Python found. Downloading CPython $PYTHON_VERSION (about 50 MB)...")
        val downloaded = runCatching {
            java.net.URL(url).openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        }
        if (downloaded.isFailure) {
            onLine("Could not download Python: ${downloaded.exceptionOrNull()?.message}")
            archive.delete()
            return null
        }

        onLine("Unpacking...")
        // Unpacked fresh every time. A previous attempt may have stopped partway through, and
        // extracting over the remains would leave a mixture of two runtimes.
        if (runtimeDir().exists()) runtimeDir().deleteRecursively()
        val unpacked = runCatching { untarGz(archive, runtimeDir()) }
        archive.delete()
        if (unpacked.isFailure) {
            onLine("Could not unpack Python: ${unpacked.exceptionOrNull()?.message}")
            return null
        }

        val python = probe(listOf(managedPythonExe().path))
        if (python == null) {
            onLine("The downloaded Python did not run. Install Python 3.11 and set PRISM_PYTHON.")
            return null
        }
        onLine("Installed CPython ${python.version} for Prism's own use.")
        return python
    }

    /**
     * Extracts a gzipped tar into [dest].
     *
     * A HAND-WRITTEN USTAR READER, to avoid adding Apache Commons Compress for one archive. It is
     * safe to keep this small because the input is not arbitrary: these releases contain regular
     * files only -- no symlinks, no hard links, no directory entries, no GNU long-name records --
     * which is why directories are created from each file's own path rather than from entries.
     *
     * The prefix field is not optional here despite being rare: paths in this archive reach 117
     * characters, and ustar splits anything over 100 across prefix and name. Ignoring it would
     * silently write files to the wrong place.
     */
    private fun untarGz(archive: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator

        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (!gz.readFully(header)) break
                // Two consecutive zero blocks end the archive; one is enough to stop reading.
                if (header.all { it == 0.toByte() }) break

                val name = header.text(0, 100)
                val prefix = header.text(345, 155)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val size = header.text(124, 12).trim().takeIf { it.isNotEmpty() }
                    ?.toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()

                val padded = ((size + 511) / 512) * 512
                if (path.isEmpty() || (type != '0' && type != '\u0000')) {
                    gz.skipFully(padded)
                    continue
                }

                val out = File(dest, path)
                // Refuses anything that resolves outside dest, which is the one thing a malicious
                // or malformed archive could do that matters here.
                if (!out.canonicalPath.startsWith(root)) {
                    gz.skipFully(padded)
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { sink -> gz.copyExactly(sink, size) }
                gz.skipFully(padded - size)

                // Restores the executable bit from the tar mode. Without it the unpacked python on
                // macOS and Linux is a file that cannot be run; Windows ignores it.
                val mode = header.text(100, 8).trim().takeIf { it.isNotEmpty() }?.toIntOrNull(8)
                if (mode != null && mode and 0b001_000_000 != 0) out.setExecutable(true, false)
            }
        }
    }

    /** Reads a NUL-padded ASCII field. */
    private fun ByteArray.text(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.US_ASCII).trim()
    }

    /** Fills [buffer] completely, or reports that the stream ended. Streams may return short reads. */
    private fun java.io.InputStream.readFully(buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun java.io.InputStream.copyExactly(sink: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = bytes
        while (left > 0) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("Archive ended mid-file")
            sink.write(buffer, 0, n)
            left -= n
        }
    }

    private fun java.io.InputStream.skipFully(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = skip(left)
            // skip can legally return 0 without being at the end, so a read has to make progress.
            if (skipped <= 0) {
                if (read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun venvPython(): File {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", true)
        return if (windows) File(venvDir(), "Scripts/python.exe")
        else File(venvDir(), "bin/python")
    }

    /**
     * Written only after pip succeeds, and the thing [state] trusts.
     *
     * The repository is downloaded BEFORE the dependencies are installed, so its presence says
     * nothing about whether the install finished -- a run that failed at pip leaves a full source
     * tree behind and used to be reported as INSTALLED. That enabled Train against an environment
     * with no TensorFlow in it, and the resulting failure surfaced far from its cause.
     */
    private fun installStamp(): File = File(home(), ".installed")

    /**
     * NO_PYTHON now means "and none can be fetched either", since [provisionPython] can supply one.
     * It is reported only when this platform has no prebuilt runtime available, which is the only
     * case the user still has to resolve themselves.
     */
    fun state(): State = when {
        findPythonInfo()?.isSupported != true &&
            !managedPythonExe().isFile && standaloneAsset() == null -> State.NO_PYTHON
        !File(repoDir(), "cakechat").isDirectory -> State.ABSENT
        !installStamp().isFile || !venvPython().isFile -> State.ABSENT
        weightsDir().listFiles()?.any { it.name.endsWith(".h5") || isHdf5(it) } == true ->
            State.TRAINED
        else -> State.INSTALLED
    }

    private fun isHdf5(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val magic = ByteArray(8)
            stream.read(magic) == 8 && magic.contentEquals(
                byteArrayOf(0x89.toByte(), 0x48, 0x44, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        }
    }.getOrDefault(false)

    // ── Setup ──────────────────────────────────────────────────────────────

    /**
     * Unpacks the shared Python next to the repository.
     *
     * Written out on every setup rather than only when missing: the scripts ship inside the jar, so
     * an app update carries new ones, and a stale copy on disk would silently keep running the
     * previous release's bridge against the new UI.
     */
    private fun stageScripts(): File {
        val dir = scriptsDir()
        // EVERY MODULE, not just the entry points. These are unpacked as plain files next to each
        // other and imported by name at run time, so a module missing here fails as
        // "No module named ..." from inside a subprocess -- naming the file that was never staged
        // rather than the list that forgot it.
        for (name in listOf(
            "prism_cakechat.py", "prism_corpus.py", "prism_cakechat_cli.py", "prism_parquet.py",
        )) {
            // NOT SKIPPED WHEN ABSENT. A missing script means the build did not package
            // ../app/src/main/python, and carrying on would surface that as a Python ImportError
            // from inside a subprocess -- far from the cause. Fail here, where the reason is plain.
            val stream = DesktopCakeChat::class.java.classLoader.getResourceAsStream(name)
                ?: error("$name is missing from this build; the shared Python was not packaged.")
            stream.use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dir
    }

    /**
     * Downloads CakeChat and builds the virtual environment.
     *
     * Blocking; call it off the UI thread. [onLine] receives human-readable progress.
     */
    fun install(onLine: (String) -> Unit): Boolean {
        if (!hasRoomToInstall(onLine)) return false

        val python = usablePython(onLine) ?: return false

        // Cleared first: from here until pip succeeds the install is incomplete, and a stamp left
        // over from a previous good install would describe an environment being replaced.
        installStamp().delete()

        onLine("Downloading CakeChat…")
        if (!downloadRepo(onLine)) return false

        onLine("Staging Prism's CakeChat scripts…")
        stageScripts()

        onLine("Creating a virtual environment…")
        if (!run(listOf(python.exe.path, "-m", "venv", venvDir().path), home(), onLine)) {
            onLine("Could not create the virtual environment.")
            return false
        }

        onLine("Installing dependencies (this takes a few minutes)…")
        val pip = listOf(venvPython().path, "-m", "pip", "install", "--upgrade", "pip")
        run(pip, home(), onLine)

        val requirements = requirementsFor(python)
        onLine(
            if (python.runsUpstreamStack) "Installing CakeChat's original TensorFlow 1.12 stack..."
            else "Installing TensorFlow 2.15 with Prism's compatibility layer..."
        )
        if (!run(listOf(venvPython().path, "-m", "pip", "install") + requirements, home(), onLine)) {
            onLine("Dependency install failed. The pip output above says which package refused.")
            return false
        }

        installStamp().writeText(
            "cakechat installed with python ${python.version} on ${java.time.Instant.now()}\n"
        )
        onLine("CakeChat is installed.")
        return true
    }

    /**
     * The dependency set this interpreter can actually resolve.
     *
     * TWO STACKS, BECAUSE TENSORFLOW 1.12 IS UNINSTALLABLE ON A MODERN PYTHON. Its last wheels are
     * CPython 3.6, so pinning it unconditionally fails at pip on essentially every machine in use
     * today -- which is what makes the TensorFlow 2 path the normal one rather than the exception.
     *
     * That path is not a compromise: it is the same combination Android runs, driven through the
     * same shims in prism_cakechat, so a fix on either platform is a fix on both. TensorFlow 2.15
     * is the ceiling -- 2.16 replaced Keras 2 with Keras 3, which reorganises the modules CakeChat
     * imports beyond what aliasing can reconcile.
     */
    private fun requirementsFor(python: Python): List<String> =
        if (python.runsUpstreamStack) {
            // What CakeChat was written and tested against. protobuf is pinned for the reason the
            // Android build pins it: TensorFlow declares no upper bound, and a protobuf years newer
            // than the generated code cannot read it.
            listOf(
                "tensorflow==1.12.0", "keras==2.2.4", "protobuf==3.6.1",
                "numpy==1.16.0", "scipy==1.2.0", "scikit-learn==0.20.2",
                "pandas==0.23.4", "nltk==3.4.5", "gensim==1.0.1", "h5py==2.10.0",
                "tqdm==4.30.0", "cachetools==3.0.0", "unicodecsv==0.14.1",
            )
        } else {
            // Ranges rather than exact pins: unlike the 2018 stack these are versions pip can still
            // resolve against each other across several Python versions, and over-pinning here
            // creates conflicts instead of preventing them. numpy is the exception -- numpy 2
            // changed the C ABI and TensorFlow 2.15 was built against 1.x.
            listOf(
                "tensorflow==2.15.1", "numpy<2", "protobuf>=3.20.3,<5",
                "scipy", "scikit-learn", "pandas", "nltk", "gensim", "h5py",
                "tqdm", "cachetools", "unicodecsv",
            )
        }

    private fun downloadRepo(onLine: (String) -> Unit): Boolean = runCatching {
        val zip = File(home(), "cakechat-master.zip")
        java.net.URL(ARCHIVE_URL).openStream().use { input ->
            zip.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        val target = repoDir()
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) { zis.closeEntry(); continue }
                val out = File(target, relative)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    zis.closeEntry(); continue
                }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it, 64 * 1024) }
                }
                zis.closeEntry()
            }
        }
        zip.delete()
        onLine("CakeChat downloaded to ${target.path}")
        true
    }.onFailure { onLine("Download failed: ${it.message}") }.getOrDefault(false)

    // ── Corpus ─────────────────────────────────────────────────────────────

    /** Converts any supported corpus into CakeChat's format, using the shared converter. */
    /**
     * Converts a corpus, reporting each file as it is read.
     *
     * [onLine] receives the converter's own progress -- which shard of how many, and how many
     * dialogs it yielded. A multi-file dataset takes minutes per file, and a caller that shows only
     * the final message cannot distinguish work from a hang for the whole of it.
     */
    fun importCorpus(source: File, onLine: (String) -> Unit = {}): String {
        val scripts = stageScripts()
        val result = StringBuilder("Conversion produced no result.")
        val logs = ArrayList<String>()

        _progress.value = _progress.value.copy(
            running = true, finished = false, phase = "converting corpus", error = null,
        )
        runCli(
            listOf("convert", "--corpus", source.path, "--out", datasetFile().path),
            scripts,
        ) { payload ->
            when (payload.optString("kind")) {
                "log" -> {
                    val line = payload.optString("line")
                    onLine(line)
                    logs.add(line)
                    if (logs.size > 400) logs.subList(0, 200).clear()
                    // Into the same panel training uses, so one place shows everything that has
                    // happened to this model rather than progress being split across two views.
                    _progress.value = _progress.value.copy(logs = logs.toList())
                }
                "result" -> {
                    result.setLength(0)
                    result.append(payload.optString("message"))
                }
            }
        }
        _progress.value = _progress.value.copy(running = false, phase = "idle")
        return result.toString()
    }

    // ── Training ───────────────────────────────────────────────────────────

    /**
     * @param subsetSize dialogs to train on, or 0 for the whole corpus. Steps per epoch is
     *   `samples / batchSize`, so this is the setting that decides how long a run takes.
     */
    fun startTraining(epochs: Int, batchSize: Int, subsetSize: Int, hiddenDim: Int) {
        if (process != null) return

        // Checked here, where each one can say what is wrong, rather than left to fail inside
        // Python as an ImportError or a missing-file traceback several layers from the cause.
        val problem = when {
            !File(repoDir(), "cakechat").isDirectory ->
                "CakeChat is not installed yet — use \"Download and install\" above."
            !datasetFile().isFile ->
                "No corpus yet — choose one under Corpus above."
            !venvPython().isFile ->
                "The Python environment is missing. Reinstall CakeChat."
            else -> null
        }
        if (problem != null) {
            _progress.value = Progress(running = false, finished = true, error = problem,
                logs = listOf(problem))
            return
        }

        val scripts = runCatching { stageScripts() }.getOrElse {
            val message = "Could not unpack Prism's CakeChat scripts: ${it.message}"
            _progress.value = Progress(running = false, finished = true, error = message,
                logs = listOf(message))
            return
        }
        _progress.value = Progress(running = true, phase = "starting", epochs = epochs)

        Thread({
            val logs = ArrayList<String>()
            runCli(
                listOf(
                    "train",
                    "--repo", repoDir().path,
                    "--corpus", datasetFile().path,
                    "--out", weightsDir().path,
                    "--epochs", epochs.toString(),
                    "--batch-size", batchSize.toString(),
                    "--subset", subsetSize.toString(),
                    "--hidden", hiddenDim.toString(),
                ),
                scripts,
            ) { payload ->
                when (payload.optString("kind")) {
                    "progress" -> _progress.value = _progress.value.copy(
                        running = payload.optBoolean("running", true),
                        phase = payload.optString("phase", "training"),
                        step = payload.optInt("step"),
                        total = payload.optInt("total"),
                        epoch = payload.optInt("epoch"),
                        epochs = payload.optInt("epochs"),
                        loss = payload.optDouble("loss", 0.0),
                        elapsedSeconds = payload.optDouble("elapsed", 0.0).toLong(),
                        etaSeconds = payload.optDouble("eta", 0.0).toLong(),
                    )
                    "log" -> {
                        logs.add(payload.optString("line"))
                        if (logs.size > 400) logs.subList(0, 200).clear()
                        _progress.value = _progress.value.copy(logs = logs.toList())
                    }
                    "result" -> {
                        val ok = payload.optBoolean("ok", false)
                        _progress.value = _progress.value.copy(
                            running = false,
                            finished = true,
                            error = if (ok) null else payload.optString("message"),
                            logs = logs.toList(),
                        )
                    }
                }
            }
            process = null

            // AFTER TRAINING, AUTOMATICALLY. A model that has to be converted by hand is a model
            // that reaches the phone as the 500 MB TensorFlow build, which is the thing this whole
            // path exists to avoid. Only on success -- there is nothing to convert otherwise.
            if (_progress.value.error == null && weightsDir().listFiles()?.any { isHdf5(it) } == true) {
                _progress.value = _progress.value.copy(phase = "converting")
                val converted = convertToTflite { line ->
                    logs.add(line)
                    _progress.value = _progress.value.copy(logs = logs.toList())
                }
                if (!converted) {
                    logs.add("The model trained, but converting it for mobile did not work.")
                    _progress.value = _progress.value.copy(logs = logs.toList())
                }
            }

            if (_progress.value.running) {
                // The process ended without a result line, which means it died rather than
                // finished. Said plainly instead of leaving a bar frozen mid-run.
                _progress.value = _progress.value.copy(
                    running = false, finished = true,
                    error = "The training process exited unexpectedly.",
                )
            }
        }, "cakechat-desktop-train").apply { isDaemon = true; start() }
    }

    /**
     * Converts the trained model to TFLite, so it can run on a phone without TensorFlow.
     *
     * A SEPARATE PROCESS, not a call inside the training one. Conversion builds a second copy of
     * the network to trace it, and doing that while the training graph is still resident doubles
     * the peak memory of the very machine that just spent hours on the first copy. A fresh process
     * starts from nothing and gives it all back on exit.
     *
     * Failure is reported, never fatal: the trained weights are already saved and usable through
     * the Python path, so a conversion that does not work costs the mobile route, not the model.
     */
    fun convertToTflite(onLine: (String) -> Unit): Boolean {
        val scripts = runCatching { stageScripts() }.getOrElse {
            onLine("Could not unpack Prism's CakeChat scripts: ${it.message}")
            return false
        }
        var ok = false
        runCli(
            listOf("export-tflite", "--repo", repoDir().path, "--out", weightsDir().path),
            scripts,
        ) { payload ->
            when (payload.optString("kind")) {
                "log" -> onLine(payload.optString("line"))
                "result" -> {
                    ok = payload.optBoolean("ok", false)
                    onLine(payload.optString("message"))
                }
            }
        }
        return ok
    }

    /**
     * Converts an already-trained model, reporting through the same progress the trainer uses.
     *
     * FOR MODELS THAT PREDATE AUTOMATIC CONVERSION. Training converts as it finishes, so this is
     * only reached by a model trained before that existed -- and re-running a night of training
     * purely to produce a file that takes minutes would be an absurd way to get it.
     */
    fun startConversion() {
        if (process != null || _progress.value.running) return

        val problem = when {
            !File(repoDir(), "cakechat").isDirectory -> "CakeChat is not installed."
            weightsDir().listFiles()?.any { isHdf5(it) } != true -> "There are no trained weights to convert."
            else -> null
        }
        if (problem != null) {
            _progress.value = Progress(finished = true, error = problem, logs = listOf(problem))
            return
        }

        _progress.value = Progress(running = true, phase = "converting")

        Thread({
            val logs = ArrayList<String>()
            val ok = convertToTflite { line ->
                logs.add(line)
                if (logs.size > 400) logs.subList(0, 200).clear()
                _progress.value = _progress.value.copy(logs = logs.toList())
            }
            _progress.value = _progress.value.copy(
                running = false,
                finished = true,
                phase = "idle",
                error = if (ok) null else "Converting the model for mobile did not work.",
                logs = logs.toList(),
            )
        }, "cakechat-desktop-convert").apply { isDaemon = true; start() }
    }

    fun stopTraining() {
        process?.destroy()
        process = null
        _progress.value = _progress.value.copy(running = false, finished = true)
    }

    /**
     * Runs the CLI and feeds every line to [onPayload], as protocol or as log.
     *
     * TensorFlow writes freely to stdout and stderr, so the reader has to tolerate arbitrary text
     * interleaved with the JSON protocol. It FORWARDS that text as log lines rather than dropping
     * it. Dropping was the original design and it was wrong in the one case that matters: when
     * Python dies during import, the traceback is the entire explanation and it arrives as plain
     * text, before any JSON has been written. Discarding it left a run that ended with no output at
     * all, which is indistinguishable from a button that does nothing.
     */
    private fun runCli(args: List<String>, scripts: File, onPayload: (JSONObject) -> Unit) {
        // REPORTED, NOT SWALLOWED. Everything below can fail before the subprocess produces a single
        // line -- a missing venv, an interpreter that will not execute, a staging error. Discarding
        // that exception is what made a failed run look like a button that does nothing: no logs, no
        // error, no progress, because the only channel any of those travel on is this stream.
        fun report(message: String) {
            onPayload(JSONObject().put("kind", "log").put("line", message))
            onPayload(JSONObject().put("kind", "result").put("ok", false).put("message", message))
        }

        val python = venvPython()
        if (!python.isFile) {
            report(
                "CakeChat's Python is missing (expected ${python.path}). " +
                    "Run \"Download and install\" first."
            )
            return
        }

        runCatching {
            // Re-staged on every run, not just at install: an app update ships new scripts inside
            // the jar, and the copy on disk was written by whichever version installed CakeChat.
            // Without this, an updated Prism would keep driving the previous release's bridge.
            stageScripts()

            val command = listOf(venvPython().path, File(scripts, "prism_cakechat_cli.py").path) + args
            val builder = ProcessBuilder(command)
                .directory(home())
                .redirectErrorStream(true)
            builder.environment()["PYTHONPATH"] = scripts.path
            builder.environment()["PYTHONUNBUFFERED"] = "1"

            val started = builder.start()
            process = started
            started.inputStream.bufferedReader().forEachLine { line ->
                val trimmed = line.trim()
                val parsed =
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        runCatching { JSONObject(trimmed) }.getOrNull()
                    } else null

                if (parsed != null) onPayload(parsed)
                else if (trimmed.isNotEmpty()) {
                    onPayload(JSONObject().put("kind", "log").put("line", trimmed))
                }
            }
            started.waitFor()
        }.onFailure { report("Could not run CakeChat: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun run(command: List<String>, dir: File, onLine: (String) -> Unit): Boolean =
        runCatching {
            val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
            // All three, because which one is consulted depends on the platform and the tool:
            // Windows reads TEMP and TMP, POSIX reads TMPDIR, and Python checks all of them.
            for (name in listOf("TMPDIR", "TEMP", "TMP")) {
                builder.environment()[name] = toolTemp().path
            }
            val started = builder.start()
            started.inputStream.bufferedReader().forEachLine { onLine(it) }
            started.waitFor() == 0
        }.getOrElse { onLine("Failed: ${it.message}"); false }

    // ── Bundles ────────────────────────────────────────────────────────────

    /** Same format the mobile app writes, so a model moves either direction. */
    /**
     * Copies the vocabulary files next to the weights if they are not already there.
     *
     * WEIGHTS ARE SIZED BY THEIR VOCABULARY. The embedding and the output projection both have a
     * dimension equal to the token count, so weights without their index files load into the wrong
     * shape -- and weights paired with a DIFFERENT corpus's indices load fine and answer nonsense,
     * which is worse. A bundle carrying one without the other is not a model.
     *
     * Needed because harvesting used to copy only the weights, leaving the indices in the
     * repository where an export could not see them. A model trained before that was fixed still
     * has that layout, and retraining to repair a bundle is hours of work to undo a copy that takes
     * milliseconds -- so they are fetched from the repository on the way out.
     *
     * Returns how many were put in place.
     */
    private fun backfillIndexFiles(): Int {
        val dir = weightsDir()
        val already = dir.listFiles()?.any {
            it.name.startsWith("t_idx_") || it.name.startsWith("c_idx_")
        } == true
        if (already) return 0

        var copied = 0
        for (source in listOf(
            File(repoDir(), "data/tokens_index"),
            File(repoDir(), "data/conditions_index"),
        )) {
            for (file in source.listFiles().orEmpty()) {
                if (!file.isFile) continue
                if (!file.name.startsWith("t_idx_") && !file.name.startsWith("c_idx_")) continue
                runCatching { file.copyTo(File(dir, file.name), overwrite = true) }
                    .onSuccess { copied++ }
            }
        }
        return copied
    }

    /** Written by the Python trainer beside the weights; see `_record_sizing`. */
    private const val SIZING_NAME = "prism_model_sizing.json"

    /** The converted model: two graphs plus the metadata the Kotlin sampler is driven by. */
    private val TFLITE_NAMES = setOf(
        "cakechat_decoder.tflite", "cakechat_encoder.tflite", "cakechat_tflite.json",
    )

    fun exportBundle(target: File): String {
        backfillIndexFiles()

        val files = weightsDir().listFiles()?.filter { file ->
            file.isFile && (
                file.name.startsWith("t_idx_") || file.name.startsWith("c_idx_") ||
                    // The architecture record: weights only fit the shape that produced them, so a
                    // model trained at a non-default width is unloadable without it.
                    file.name == SIZING_NAME ||
                    // Carried so an imported model can run without TensorFlow on the far side too;
                    // re-converting requires the Python stack the import may exist to avoid.
                    file.name in TFLITE_NAMES ||
                    file.name.endsWith(".h5") || isHdf5(file)
                )
        }.orEmpty()
        val weights = files.count { it.name.endsWith(".h5") || isHdf5(it) }
        val indexes = files.count { it.name.startsWith("t_idx_") || it.name.startsWith("c_idx_") }
        if (weights == 0) return "Nothing to export — no trained weights yet."
        // Refused rather than exported: a bundle without indices imports and then answers nonsense,
        // and discovering that on the other device is far worse than failing here.
        if (indexes == 0) {
            return "Nothing to export — the vocabulary files are missing, so the weights cannot " +
                "be loaded anywhere. Train again to rebuild them."
        }

        return runCatching {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("prism_cakechat.json"))
                zip.write(
                    JSONObject().apply {
                        put("format", "prism-cakechat-model")
                        put("version", 1)
                        put("weights", weights)
                        put("indexes", indexes)
                    }.toString().toByteArray()
                )
                zip.closeEntry()
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            "Exported ${files.size} file(s) to ${target.name}."
        }.getOrElse { "Export failed: ${it.message}" }
    }

    fun importBundle(source: File): String {
        var weights = 0
        var tokenIndex = 0
        var conditionIndex = 0
        return runCatching {
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = entry.name.substringAfterLast('/')
                    if (name.isEmpty() || name == "prism_cakechat.json") { zip.closeEntry(); continue }

                    val out = File(weightsDir(), name)
                    if (!out.canonicalPath.startsWith(weightsDir().canonicalPath + File.separator)) {
                        zip.closeEntry(); continue
                    }
                    out.outputStream().use { zip.copyTo(it, 64 * 1024) }
                    zip.closeEntry()
                    when {
                        name.startsWith("t_idx_") -> tokenIndex++
                        name.startsWith("c_idx_") -> conditionIndex++
                        name.endsWith(".h5") || isHdf5(out) -> weights++
                        else -> out.delete()
                    }
                }
            }
            when {
                weights == 0 -> "No weights in that bundle."
                tokenIndex == 0 || conditionIndex == 0 ->
                    "Imported $weights weight file(s), but an index file is missing. Weights are " +
                        "sized by their vocabulary and will not load without it."
                else -> "Imported $weights weight file(s) with both index files."
            }
        }.getOrElse { "Could not read that bundle: ${it.message}" }
    }
}
