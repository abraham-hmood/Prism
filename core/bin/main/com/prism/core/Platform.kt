package com.prism.core

import java.io.File

/**
 * Everything Prism's core needs from the machine it is running on.
 *
 * DEFINED BY WHAT PRISM NEEDS, NOT BY WHAT ANDROID OFFERS. This is the decision that determines
 * whether a port succeeds, and it is easy to get wrong in a way that only becomes obvious much
 * later. The tempting shape is an interface per Android class -- `IContext`, `IPackageManager`,
 * `IBitmap` -- which looks like abstraction but is really a reimplementation of the Android SDK
 * under new names. Every other platform then has to pretend to be Android, badly, forever.
 *
 * So these are capabilities. [PlatformHost] is not a `Context`; it is the four questions the core
 * actually asks a machine: where do I put files, where do I put throwaway files, where are the
 * user's Prism documents, and how much memory may I use. `Context` answers about forty questions,
 * and the other thirty-six are how a god object gets built.
 *
 * THE DEFAULTS ARE REAL IMPLEMENTATIONS, NOT STUBS. [JvmHost] works: it resolves the platform's
 * conventional application-data directory on Windows, macOS and Linux, stores preferences as
 * property files, and reports memory honestly. That is deliberate. A core that only functions
 * once a host is installed is a core that cannot be unit-tested, and "install a fake first" is
 * exactly the friction that stops tests being written. Prism-on-desktop is, to a first
 * approximation, this file's defaults plus a user interface.
 */
interface PlatformHost {

    /** Private, persistent, app-owned. Connectomes, swap files, spill files. */
    fun dataDir(): File

    /** Private, disposable. Anything reproducible; may vanish between runs. */
    fun cacheDir(): File

    /**
     * The user-visible Prism folder: datasets, generated output, backups.
     *
     * Separate from [dataDir] because the two have different contracts. Data is Prism's business
     * and nobody should have to look at it; this is the user's, and they should be able to drop
     * a folder of training images into it from a file manager.
     */
    fun documentsDir(): File

    /** A named key-value store. Backed by SharedPreferences on Android, a file elsewhere. */
    fun prefs(name: String): KeyValueStore

    /**
     * Whether Prism may read and write arbitrary user files.
     *
     * Android gates this behind MANAGE_EXTERNAL_STORAGE, which the user grants in system
     * settings. Nothing equivalent exists on desktop -- a process there can already read what its
     * user can -- so the default is true and only Android overrides it. Exposed because the
     * agentic file tools have to REFUSE with an explanation rather than throw when it is absent.
     */
    fun hasFileAccess(): Boolean = true

    /**
     * Whether this device currently has an active cellular line -- a physical SIM or an eSIM
     * profile actually provisioned, not just telephony hardware present. Nothing equivalent
     * exists on desktop (no telephony stack at all), so the default is false and only Android
     * overrides it. Exposed because the agentic messaging tools (read_text/send_text/make_call)
     * must REFUSE -- and never even be offered to a model -- when it is absent, the same
     * "capability the core asks about" shape [hasFileAccess] already established.
     */
    fun hasActiveCellularLine(): Boolean = false

    /**
     * The hard ceiling on managed-heap allocation.
     *
     * A real limit on Android, where ART caps a process at a few hundred megabytes regardless of
     * physical RAM, and effectively `-Xmx` on a desktop JVM. Nora sizes her connectome against
     * this, so a host that reports it wrongly produces either an OutOfMemoryError or a brain far
     * smaller than the machine could hold.
     */
    fun heapCeilingBytes(): Long

    /** Total physical RAM, or 0 if it cannot be determined. Context for the user, not a limit. */
    fun deviceRamBytes(): Long

    /**
     * Physical RAM free right now, or [deviceRamBytes] as a conservative fallback if the host
     * cannot report a live figure.
     *
     * Unlike [heapCeilingBytes], this is not a hard wall on any platform -- it exists for
     * [com.prism.launcher.aether.AetherGeometry]'s "use maximum available RAM" button, whose
     * Python-side equivalent (`brain/geometry.py`'s `available_ram_bytes()`) is the ceiling Aether
     * is designed against precisely because its tensors are meant to live off the managed heap
     * (see the off-heap/[com.prism.launcher.platform.SwapRegion] work), where physical memory
     * pressure -- not a GC cap -- is the thing that actually limits size. Default implementation
     * returns [deviceRamBytes], which is honest (over-reports what's free) rather than wrong
     * (under-reports); hosts that can query live free memory should override it.
     */
    fun availableRamBytes(): Long = deviceRamBytes()

    /** Free space on the volume holding [dir], or 0 if unknown. */
    fun freeStorageBytes(dir: File): Long

    /**
     * A short name identifying this machine to other people on the mesh.
     *
     * Becomes the default peer identity, so it wants to be recognisable rather than unique --
     * "SM-S901U" or a hostname, not a UUID. The user can override it; this is only the seed.
     */
    fun deviceName(): String
}

/**
 * A named bag of primitives.
 *
 * Deliberately smaller than `SharedPreferences`: no listeners, no observers, no type coercion.
 * It carries exactly the shapes Prism stores. String sets earned their place -- the VPN app
 * whitelist is a set and encoding it as a delimited string would invent a delimiter that can
 * appear in a package name. Widening this later is easy; narrowing it once several platforms
 * implement it is not.
 */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String?): String?
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putFloat(key: String, value: Float)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String?)
    fun putStringSet(key: String, value: Set<String>)
    fun remove(key: String)
    fun clear()
    /** Persists pending writes. A no-op where writes are already durable. */
    fun flush()

    /**
     * Begins a batch of writes committed together by [Editor.apply].
     *
     * This is NOT here to imitate `SharedPreferences`, despite the shape. It exists because the
     * file-backed store genuinely benefits from writing once instead of once per key -- several
     * settings update three or four values as a unit, and a properties file rewritten per key is
     * measurably worse than one rewritten per operation. Android's implementation gets the same
     * batching from `SharedPreferences.Editor` for free.
     */
    fun edit(): Editor = DefaultEditor(this)

    interface Editor {
        fun putFloat(key: String, value: Float): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, value: Set<String>): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** Commits the batch and persists it. */
        fun apply()
    }
}

/**
 * Buffers writes and applies them in one go.
 *
 * Deliberately does NOT make the values visible to readers before [apply] -- a half-applied
 * batch that other code can observe is a source of bugs nobody enjoys tracing.
 */
private class DefaultEditor(private val store: KeyValueStore) : KeyValueStore.Editor {

    private val pending = ArrayList<() -> Unit>()
    private var clearFirst = false

    override fun putFloat(key: String, value: Float) = apply { pending.add { store.putFloat(key, value) } }
    override fun putInt(key: String, value: Int) = apply { pending.add { store.putInt(key, value) } }
    override fun putLong(key: String, value: Long) = apply { pending.add { store.putLong(key, value) } }
    override fun putBoolean(key: String, value: Boolean) = apply { pending.add { store.putBoolean(key, value) } }
    override fun putString(key: String, value: String?) = apply { pending.add { store.putString(key, value) } }
    override fun putStringSet(key: String, value: Set<String>) = apply { pending.add { store.putStringSet(key, value) } }
    override fun remove(key: String) = apply { pending.add { store.remove(key) } }

    override fun clear() = apply { clearFirst = true; pending.clear() }

    override fun apply() {
        if (clearFirst) store.clear()
        pending.forEach { it() }
        pending.clear()
        clearFirst = false
        store.flush()
    }

    private inline fun apply(block: () -> Unit): KeyValueStore.Editor {
        block(); return this
    }
}

/**
 * Diagnostics.
 *
 * Tagged rather than hierarchical, matching what the code already does: every Nora line is
 * `Nora/<area>`, and filtering by tag is what makes one subsystem's story readable in a log
 * shared with everything else.
 */
interface PrismLog {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
    fun success(tag: String, message: String)
    /** Forces buffered output to storage. Called from crash handlers, where lazy is fatal. */
    fun flushBlocking()
}

/**
 * The installed platform.
 *
 * A service locator rather than constructor injection, and that is a considered trade rather
 * than laziness. Nora's numerics are objects and deeply-nested value classes reached from tight
 * loops; threading a host through `Tensor3` and `PredictiveLink` would put a field on structures
 * whose entire design is about not carrying anything they do not compute with. The locator is
 * settable, so a test can install a fake, which is the property that actually matters.
 *
 * Both fields start as working implementations, so nothing has to be initialized before use.
 */
object PrismPlatform {

    @Volatile
    var host: PlatformHost = JvmHost()

    @Volatile
    var log: PrismLog = ConsoleLog

    /**
     * Turns files into pixels. Defaults to write-only -- see [RasterCodec] for why that
     * asymmetry is deliberate rather than unfinished.
     */
    @Volatile
    var images: ImageCodec = RasterCodec

    /**
     * Curve25519 keypairs. Android overrides this with the WireGuard library it already ships,
     * so an existing install keeps generating keys the same way it always has.
     */
    @Volatile
    var wireGuardKeys: WireGuardKeys = JdkWireGuardKeys

    /** Deferred and repeating work. See [TaskScheduler] for what desktop does not guarantee. */
    @Volatile
    var scheduler: TaskScheduler = JvmTaskScheduler()

    /** User-visible notifications. Defaults to logging them rather than dropping them silently. */
    @Volatile
    var notifier: Notifier = NoOpNotifier

    /**
     * The installed applications.
     *
     * Promoted to a platform slot because :core needs it now -- the agentic `launch_app` and
     * `list_installed_apps` tools are the two builtins that touch the machine, and they were the
     * only thing keeping AgenticBuiltinTools tied to Android. Defaults to the host's real catalog
     * rather than an empty one, so desktop works with no wiring.
     */
    @Volatile
    var apps: AppCatalog = defaultAppCatalog()

    /**
     * Large-file downloads. Android replaces this with the system DownloadManager, which is the
     * only implementation that survives the app being killed -- see [Downloader].
     */
    @Volatile
    var downloader: Downloader = JvmDownloader()

    /**
     * Installs a platform. Called once, early -- from `Application.onCreate` on Android, from
     * `main` on desktop.
     */
    fun install(
        host: PlatformHost,
        log: PrismLog,
        images: ImageCodec = RasterCodec,
        wireGuardKeys: WireGuardKeys = JdkWireGuardKeys,
        scheduler: TaskScheduler = JvmTaskScheduler(),
        notifier: Notifier = NoOpNotifier,
        apps: AppCatalog = defaultAppCatalog(),
        downloader: Downloader = JvmDownloader()
    ) {
        this.host = host
        this.log = log
        this.images = images
        this.wireGuardKeys = wireGuardKeys
        this.scheduler = scheduler
        this.notifier = notifier
        this.apps = apps
        this.downloader = downloader
    }
}

/** Writes to stderr. The default, so a core with no platform installed still says things. */
object ConsoleLog : PrismLog {
    private fun emit(level: String, tag: String, message: String) {
        System.err.println("[$level] $tag: $message")
    }

    override fun debug(tag: String, message: String) = emit("DEBUG", tag, message)
    override fun info(tag: String, message: String) = emit("INFO", tag, message)
    override fun warn(tag: String, message: String) = emit("WARN", tag, message)
    override fun success(tag: String, message: String) = emit("OK", tag, message)

    override fun error(tag: String, message: String, throwable: Throwable?) {
        emit("ERROR", tag, message)
        throwable?.printStackTrace(System.err)
    }

    override fun flushBlocking() = System.err.flush()
}

/**
 * A working host for any desktop JVM.
 *
 * Follows each platform's own convention rather than inventing one:
 *
 *   Windows  %LOCALAPPDATA%\Prism        -- not Program Files, which is read-only to a normal
 *                                           user and would need elevation on every write.
 *   macOS    ~/Library/Application Support/Prism
 *   Linux    $XDG_DATA_HOME/prism, defaulting to ~/.local/share/prism per the XDG basedir spec.
 *
 * Documents go to the home directory as `Prism`, mirroring the `Prism/Nora` folder the Android
 * build already puts on external storage -- so a dataset folder means the same thing on both.
 */
class JvmHost(
    private val appName: String = "Prism",
    /**
     * Overrides every directory this host resolves.
     *
     * Exists for tests, which must not read or write the real user profile -- a settings test
     * that clobbers your actual configuration is worse than no test. Production always leaves
     * this null and gets the platform-conventional locations.
     */
    private val rootOverride: File? = null
) : PlatformHost {

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val home = File(System.getProperty("user.home").orEmpty())

    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac") || os.contains("darwin")

    private val dataRoot: File by lazy {
        val dir = rootOverride ?: when {
            isWindows -> File(
                System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path,
                appName
            )
            isMac -> File(home, "Library/Application Support/$appName")
            else -> File(
                System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path,
                appName.lowercase()
            )
        }
        dir.mkdirs()
        dir
    }

    private val cacheRoot: File by lazy {
        val dir = if (rootOverride != null) File(rootOverride, "cache") else when {
            isWindows -> File(dataRoot, "cache")
            isMac -> File(home, "Library/Caches/$appName")
            else -> File(
                System.getenv("XDG_CACHE_HOME") ?: File(home, ".cache").path,
                appName.lowercase()
            )
        }
        dir.mkdirs()
        dir
    }

    private val documentsRoot: File by lazy {
        (rootOverride?.let { File(it, "documents") } ?: File(home, appName)).also { it.mkdirs() }
    }

    private val stores = HashMap<String, KeyValueStore>()

    override fun dataDir(): File = dataRoot
    override fun cacheDir(): File = cacheRoot
    override fun documentsDir(): File = documentsRoot

    @Synchronized
    override fun prefs(name: String): KeyValueStore =
        stores.getOrPut(name) { PropertiesStore(File(dataRoot, "$name.properties")) }

    /**
     * On a desktop JVM this is `-Xmx`, which is the real allocation ceiling for the same reason
     * ART's cap is on Android: exceeding it throws rather than swapping.
     */
    override fun heapCeilingBytes(): Long = Runtime.getRuntime().maxMemory()

    /**
     * Physical RAM via the `com.sun.management` extension, reflectively.
     *
     * Reflection rather than a direct call because the interface is a HotSpot extension, not
     * part of the JMX contract, and a JVM without it should report "unknown" instead of failing
     * to start.
     */
    override fun deviceRamBytes(): Long = try {
        // Resolved through the PUBLIC interface rather than the bean's own class. The runtime
        // type is `com.sun.management.internal.OperatingSystemImpl`, which lives in a module
        // that is not opened for reflection -- looking the method up there finds it and then
        // fails to invoke it, which is how this silently returned 0. The interface is exported,
        // so a method handle taken from it invokes cleanly.
        //
        // Reflection at all, rather than a direct call, because `com.sun.management` does not
        // exist on Android and this file is compiled into the core that both platforms share.
        val iface = Class.forName("com.sun.management.OperatingSystemMXBean")
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        if (!iface.isInstance(bean)) 0L else {
            // Renamed in JDK 14; the old name is kept for older runtimes.
            val method = iface.methods.firstOrNull { it.name == "getTotalMemorySize" }
                ?: iface.methods.firstOrNull { it.name == "getTotalPhysicalMemorySize" }
            method?.invoke(bean) as? Long ?: 0L
        }
    } catch (t: Throwable) {
        0L
    }

    /** Same reflective `com.sun.management` path as [deviceRamBytes], the "free" figure instead. */
    override fun availableRamBytes(): Long = try {
        val iface = Class.forName("com.sun.management.OperatingSystemMXBean")
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        if (!iface.isInstance(bean)) deviceRamBytes() else {
            // Renamed in JDK 14; the old name is kept for older runtimes.
            val method = iface.methods.firstOrNull { it.name == "getFreeMemorySize" }
                ?: iface.methods.firstOrNull { it.name == "getFreePhysicalMemorySize" }
            (method?.invoke(bean) as? Long) ?: deviceRamBytes()
        }
    } catch (t: Throwable) {
        deviceRamBytes()
    }

    override fun freeStorageBytes(dir: File): Long = try {
        dir.usableSpace
    } catch (t: Throwable) {
        0L
    }

    /**
     * The machine's hostname, falling back to the OS name.
     *
     * Sanitized to the character set a mesh peer id is allowed to use, because this value is
     * gossiped and ends up in identifiers other peers parse.
     */
    override fun deviceName(): String {
        val raw = System.getenv("COMPUTERNAME")
            ?: System.getenv("HOSTNAME")
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?: System.getProperty("os.name")
            ?: "prism-desktop"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "-").take(48).ifBlank { "prism-desktop" }
    }
}

/**
 * A [KeyValueStore] backed by a Java properties file.
 *
 * Written on [flush] rather than on every put: the settings screens commit a value per edit, and
 * a synchronous file write per keystroke-completed field is the sort of thing that is invisible
 * on an SSD and miserable on anything else. Values are held in memory and are authoritative
 * whether or not the file has caught up.
 */
private class PropertiesStore(private val file: File) : KeyValueStore {

    private companion object {
        /**
         * Always "\n", never the platform line separator.
         *
         * The separator is part of the STORED FORMAT, so it has to be the same byte on every
         * machine. Using System.lineSeparator() would make a settings file written on Windows
         * unreadable on Linux, which is exactly the kind of bug a cross-platform port exists to
         * avoid rather than introduce.
         */
        const val SET_SEPARATOR = "\n"
    }


    private val props = java.util.Properties()
    private var dirty = false

    init {
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
                .onFailure {
                    PrismPlatform.log.warn("Prism/prefs", "Could not read ${file.name}: ${it.message}")
                }
        }
    }

    @Synchronized
    override fun contains(key: String): Boolean = props.containsKey(key)

    @Synchronized
    override fun getFloat(key: String, default: Float): Float =
        props.getProperty(key)?.toFloatOrNull() ?: default

    @Synchronized
    override fun getInt(key: String, default: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun getLong(key: String, default: Long): Long =
        props.getProperty(key)?.toLongOrNull() ?: default

    @Synchronized
    override fun getBoolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    @Synchronized
    override fun getString(key: String, default: String?): String? =
        props.getProperty(key) ?: default

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) remove(key) else put(key, value)
    }

    /**
     * Sets are stored newline-separated.
     *
     * Newline is the one separator that cannot appear in the values these sets actually hold --
     * package names and file paths -- and `Properties.store` escapes it on write, so the file
     * stays a single valid line per key. A comma or a colon would eventually collide with a real
     * value and silently split one entry into two.
     */
    @Synchronized
    override fun getStringSet(key: String, default: Set<String>): Set<String> {
        val raw = props.getProperty(key) ?: return default
        if (raw.isEmpty()) return emptySet()
        return raw.split(SET_SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    @Synchronized
    override fun putStringSet(key: String, value: Set<String>) {
        put(key, value.joinToString(SET_SEPARATOR))
    }

    @Synchronized
    override fun putFloat(key: String, value: Float) = put(key, value.toString())

    @Synchronized
    override fun putInt(key: String, value: Int) = put(key, value.toString())

    @Synchronized
    override fun putLong(key: String, value: Long) = put(key, value.toString())

    @Synchronized
    override fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        dirty = true
    }

    @Synchronized
    override fun remove(key: String) {
        props.remove(key)
        dirty = true
    }

    @Synchronized
    override fun clear() {
        props.clear()
        dirty = true
        flush()
    }

    @Synchronized
    override fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Prism settings") }
            dirty = false
        }.onFailure {
            PrismPlatform.log.error("Prism/prefs", "Could not write ${file.name}", it)
        }
    }
}
