package com.prism.launcher.virtualapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import com.prism.launcher.virtualization.VirtualizedAppVault
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The Context a virtualized app sees.
 *
 * Every path it asks for lands in its own directory inside Prism's vault rather than in
 * `/data/data/<its package>`, and every resource lookup goes to its own APK rather than Prism's.
 *
 * ## What this does and does not redirect
 *
 * It redirects everything that goes through Context: `getFilesDir`, `getCacheDir`, `getDataDir`,
 * databases, shared preferences, `openFileOutput`. That is how the overwhelming majority of Android
 * code touches storage, so in practice most apps land entirely inside the vault.
 *
 * It does NOT redirect an absolute path built by hand -- `File("/data/data/com.example/x")` -- or
 * anything a native library opens through libc directly. Catching those means hooking `open`,
 * `stat` and friends in libc with an inline hooker, which is what VirtualApp does in native code and
 * is not done here. An app that does it will read and write nothing, because it has no permission
 * to that path under Prism's uid; it will not silently escape into the real app's data.
 *
 * Saying which of those two it is matters: "mostly redirected, and the rest fails closed" is a
 * different claim from "redirected", and only the first one is true.
 */
class VirtualContext(
    base: Context,
    private val virtualPackage: VirtualPackage,
) : ContextWrapper(base) {

    /**
     * The unsealed working directory, not the vault root.
     *
     * This process never holds the vault key -- VaultService, in the main process, unsealed the
     * archive into here before the app was loaded and seals it again when the app exits. The app
     * therefore reads and writes ordinary files, and the key it would need to read any OTHER app's
     * data lives in an address space it cannot reach.
     */
    private val home: File = VaultService.liveDir(base, virtualPackage.packageName)

    private fun dir(name: String): File = File(home, name).apply { mkdirs() }

    // ── Identity ───────────────────────────────────────────────────────────

    override fun getPackageName(): String = virtualPackage.packageName

    override fun getApplicationInfo(): ApplicationInfo = virtualPackage.applicationInfo

    override fun getClassLoader(): ClassLoader = virtualPackage.classLoader

    override fun getResources(): Resources = virtualPackage.resources

    override fun getAssets(): AssetManager = virtualPackage.resources.assets

    // -- Theme ---------------------------------------------------------------

    /**
     * The app's theme, built from the app's own resources.
     *
     * Without this the base context's theme is Prism's, and a hosted activity resolving it against
     * the app's resource table is asking for attribute ids that mean something different there. The
     * usual symptom is AppCompat refusing to start ("You need to use a Theme.AppCompat theme"),
     * which reads like the app's own fault and is not.
     *
     * Rebuilt rather than mutated on [setTheme], because applying a second style over the first
     * leaves the attributes of both, and an activity that switches themes at runtime expects the
     * one it just asked for.
     */
    private var themeResId: Int = 0

    private var themeInstance: Resources.Theme? = null

    override fun setTheme(resid: Int) {
        if (themeResId == resid && themeInstance != null) return
        themeResId = resid
        themeInstance = null
    }

    override fun getTheme(): Resources.Theme {
        themeInstance?.let { return it }
        val resolved = if (themeResId != 0) themeResId else virtualPackage.applicationInfo.theme
        return virtualPackage.resources.newTheme().also { theme ->
            if (resolved != 0) runCatching { theme.applyStyle(resolved, true) }
            themeInstance = theme
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    override fun getDataDir(): File = home

    override fun getFilesDir(): File = dir("files")

    override fun getCacheDir(): File = dir("cache")

    override fun getCodeCacheDir(): File = dir("code_cache")

    override fun getNoBackupFilesDir(): File = dir("no_backup")

    override fun getDir(name: String, mode: Int): File = dir("app_$name")

    override fun getDatabasePath(name: String): File = File(dir("databases"), name)

    override fun openFileInput(name: String): FileInputStream =
        FileInputStream(File(getFilesDir(), name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream =
        FileOutputStream(File(getFilesDir(), name), mode and Context.MODE_APPEND != 0)

    override fun fileList(): Array<String> =
        getFilesDir().list()?.let { Array(it.size) { index -> it[index] } } ?: emptyArray()

    override fun deleteFile(name: String): Boolean = File(getFilesDir(), name).delete()

    /**
     * Preferences, kept inside the vault so they are encrypted along with everything else.
     *
     * This used to delegate to the base context under a prefixed name, which put a virtualized
     * app's settings in PRISM's `shared_prefs` directory as plaintext XML -- outside the vault, and
     * so outside "encrypted when not in use". The prefix stopped two apps from colliding and did
     * nothing at all about that.
     *
     * Anything the old location holds is moved across on first use, so the fix does not read to the
     * user as every virtualized app forgetting its settings, and the plaintext copy is then deleted.
     */
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val store = File(dir("shared_prefs"), "$name.json")

        if (!store.isFile) {
            val legacyName = "virtual_${virtualPackage.packageName}_$name"
            val legacy = runCatching { super.getSharedPreferences(legacyName, mode) }.getOrNull()
            val held = legacy?.all.orEmpty()
            if (held.isNotEmpty()) {
                VaultPreferences.migrate(store, held)
                runCatching { deleteSharedPreferences(legacyName) }
            }
        }

        return VaultPreferences.open(store)
    }

    // -- Inflation -------------------------------------------------------------

    /**
     * An inflater bound to THIS context, so layouts resolve against the app's resources.
     *
     * `LayoutInflater.from(context)` is `context.getSystemService(LAYOUT_INFLATER_SERVICE)`, and
     * ContextWrapper forwards that to its base -- the host activity -- which hands back its own
     * cached inflater, permanently bound to Prism. Every layout the app inflated then resolved its
     * ids in PRISM's resource table, which fails in the most confusing way available:
     *
     * ```
     * NullPointerException: Missing required view with ID: com.prism.launcher:id/mini
     * ```
     *
     * A view binding asking for one of the app's own ids, and being told about a Prism id, because
     * the numbers happened to collide. `cloneInContext` rebinds a copy to this context, after which
     * the same lookup resolves in the app's table and finds its own view.
     */
    private val inflater: android.view.LayoutInflater by lazy {
        android.view.LayoutInflater.from(baseContext).cloneInContext(this)
    }

    override fun getSystemService(name: String): Any? =
        if (Context.LAYOUT_INFLATER_SERVICE == name) inflater else super.getSystemService(name)

    // -- Components -----------------------------------------------------------

    /**
     * Binds a service, or reports that it could not be bound.
     *
     * A virtualized app's services are declared in ITS manifest, which the system never parsed, so
     * the system refuses the bind: "Not allowed to bind to service Intent { cmp=... }". That much is
     * expected until services are hosted the way activities are.
     *
     * What is NOT acceptable is the shape of the refusal. `bindService` is documented to return
     * false when it cannot connect, and callers handle that; a SecurityException thrown out of it
     * unwinds whatever the app was doing. Firefox binds a crash-reporting helper while building its
     * component graph, and the throw took the whole launch down over a service it can live without.
     *
     * So: unsupported, reported as unsupported, in the way the API says to report it.
     */
    override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean =
        runCatching { super.bindService(service, conn, flags) }.getOrElse {
            com.prism.launcher.PrismLogger.logWarning(
                TAG, "Refused bind to ${service.component?.className ?: service.action}: ${it.message}",
            )
            false
        }

    override fun startService(service: android.content.Intent): android.content.ComponentName? =
        runCatching { super.startService(service) }.getOrElse {
            com.prism.launcher.PrismLogger.logWarning(
                TAG, "Refused start of ${service.component?.className ?: service.action}: ${it.message}",
            )
            null
        }

    /**
     * The app's own Application where there is one, and only this context before it exists.
     *
     * `applicationContext` is what app code casts to its own Application subclass, so returning
     * `this` guarantees a ClassCastException in any app that has one. The fallback still matters:
     * the Application's OWN base context is a VirtualContext, and it asks this question while being
     * constructed, before there is anything to hand back.
     */
    override fun getApplicationContext(): Context = virtualPackage.application ?: this

    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): Context =
        VirtualContext(super.createConfigurationContext(overrideConfiguration), virtualPackage)

    private companion object {
        const val TAG = "PrismVirtualApp"
    }
}
