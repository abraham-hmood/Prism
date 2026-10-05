/*
 * AssetManager.newInstance() is deprecated and hidden, and is precisely what this file needs: a
 * SEPARATE asset manager for a virtualized package, so its resources resolve against its own APK
 * rather than against Prism's. The public API offers no way to construct one, which is why the
 * VirtualApp approach has always gone through this call.
 */
@file:Suppress("DEPRECATION")

package com.prism.launcher.virtualapp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import com.prism.launcher.PrismLogger
import com.prism.launcher.virtualization.VirtualizedAppVault
import dalvik.system.DexClassLoader
import java.io.File

/**
 * One virtualized app, loaded into this process: its code, its resources and its identity.
 *
 * ## What "loaded" means here
 *
 * Three separate things have to be made, and an app breaks in a different way if any of them is
 * missing:
 *
 * - A **ClassLoader** over the app's APKs, so its classes exist. Its parent is the BOOT loader, not
 *   Prism's -- see [load] for why that distinction is load-bearing rather than tidiness.
 * - **Resources** built from an `AssetManager` with the APKs added. Without this the app finds its
 *   classes and then fails on the first `R.layout` lookup, because Prism's resources do not contain
 *   its layouts.
 * - Its **ApplicationInfo**, read out of the APK rather than from the installed package, so the
 *   identity comes from the file in the vault. That matters once an app has been uninstalled from
 *   the phone and only the backup remains.
 *
 * ## Native libraries
 *
 * `nativeLibraryDir` points into the vault, and the libraries are extracted there on first load.
 * An app with native code whose libraries are still inside the APK loads its Java classes and then
 * dies on `System.loadLibrary`, which reads as "the app crashed" rather than "a directory was
 * empty".
 */
class VirtualPackage private constructor(
    val packageName: String,
    val applicationInfo: ApplicationInfo,
    val apks: List<File>,
    val classLoader: ClassLoader,
    val resources: Resources,
    val manifest: ApkManifest?,
) {

    /**
     * The app's entry point, taken from the APK's own manifest.
     *
     * The manifest is the only source that is right for every app. Asking the INSTALLED package
     * (`getLaunchIntentForPackage`) fails for an app that has been uninstalled since it was
     * virtualized -- which the vault exists to support -- and falling back to "the first activity
     * declared" picks a splash screen, a settings page or a share target about as often as it picks
     * the real one. [ApkManifest] reads the intent filters that decide it.
     */
    val launchActivity: String? get() = manifest?.launchActivity ?: fallbackLaunchActivity

    /**
     * The app's own Application, once built.
     *
     * Held here rather than passed around because [VirtualContext] has to answer
     * `getApplicationContext()` with it. An enormous amount of app code does
     * `context.applicationContext as MyApplication` -- Firefox does it in a top-level extension
     * function that half its UI calls -- and answering with the Context instead produces a
     * ClassCastException naming a Prism class, which looks like the app is broken.
     *
     * Volatile: written on the main thread while the startup worker may still be running.
     */
    @Volatile
    var application: android.app.Application? = null

    /**
     * Only used when the binary manifest could not be parsed at all.
     *
     * Deliberately the old, worse behaviour rather than a refusal to start: a manifest this parser
     * cannot read is a bug to fix, not a reason to make the app unopenable.
     */
    private val fallbackLaunchActivity: String? by lazy {
        runCatching {
            hostContext?.packageManager?.getLaunchIntentForPackage(packageName)?.component?.className
        }.getOrNull() ?: archivedActivities.keys.firstOrNull()
    }

    /**
     * The app's own `ActivityInfo` for [className], or null if the manifest has no such activity.
     *
     * Still read through PackageManager, because an ActivityInfo is what the framework wants set on
     * a hosted activity. The manifest supplies what PackageManager drops -- filters, and therefore
     * the entry point -- rather than replacing it.
     */
    fun activityInfo(className: String): android.content.pm.ActivityInfo? =
        archivedActivities[className]

    private val archivedActivities: Map<String, android.content.pm.ActivityInfo> by lazy {
        runCatching {
            hostContext?.packageManager
                ?.getPackageArchiveInfo(apks.first().absolutePath, PackageManager.GET_ACTIVITIES)
                ?.activities.orEmpty()
                .onEach {
                    // The parsed entries carry an ApplicationInfo with no paths, the same way the
                    // package's own did before load() filled it in. Point them at the patched one
                    // rather than repeating the patch per activity.
                    it.applicationInfo = applicationInfo
                }
                .associateBy { it.name }
        }.getOrNull().orEmpty()
    }

    /**
     * The class to actually instantiate for [className].
     *
     * A launcher entry is often an `<activity-alias>` rather than an activity -- Firefox's
     * `org.mozilla.firefox.App` is one, pointing at `org.mozilla.fenix.HomeActivity` -- and an alias
     * is a manifest entry with no class behind it. Loading the alias name directly gets
     * `ClassNotFoundException` for a name that is plainly in the manifest, which is a confusing way
     * to be told to follow `targetActivity`.
     */
    fun implementationClass(className: String): String =
        manifest?.implementationClass(className)
            ?: activityInfo(className)?.targetActivity?.takeIf { it.isNotBlank() }
            ?: className

    /** The theme the app declares for [className], falling back to its application-wide theme. */
    fun themeFor(className: String): Int {
        manifest?.let { parsed ->
            val fromManifest = parsed.themeFor(className)
            if (fromManifest != 0) return fromManifest
        }
        val declared = activityInfo(className)?.theme ?: 0
        return if (declared != 0) declared else applicationInfo.theme
    }

    companion object {

        private const val TAG = "PrismVirtualApp"

        private var hostContext: Context? = null

        /**
         * Loads a virtualized app from its backup in the vault.
         *
         * Returns null when the app cannot be loaded at all -- no backup, or an APK the platform
         * refuses to parse. The caller reports that rather than starting something half-built.
         */
        fun load(context: Context, packageName: String): VirtualPackage? {
            hostContext = context.applicationContext

            val apks = VirtualizedAppVault.apkDir(context, packageName)
                .listFiles().orEmpty()
                .filter { it.extension.equals("apk", true) }
                // base.apk first: it carries the manifest, and the split loading order follows it.
                .sortedBy { if (it.name.startsWith("base")) "" else it.name }

            if (apks.isEmpty()) {
                PrismLogger.logWarning(TAG, "No backed-up APK for $packageName")
                return null
            }

            val info = runCatching {
                context.packageManager
                    .getPackageArchiveInfo(apks.first().absolutePath, 0)
                    ?.applicationInfo
            }.getOrNull() ?: run {
                PrismLogger.logWarning(TAG, "Could not parse ${apks.first().name}")
                return null
            }

            // The parsed ApplicationInfo has no paths -- getPackageArchiveInfo leaves them unset,
            // because the archive is not installed anywhere. Everything below points at the vault.
            val home = VirtualizedAppVault.appDir(context, packageName)
            val nativeDir = File(home, "lib").apply { mkdirs() }
            info.sourceDir = apks.first().absolutePath
            info.publicSourceDir = apks.first().absolutePath
            if (apks.size > 1) {
                val splits = apks.drop(1).map { it.absolutePath }.toTypedArray()
                info.splitSourceDirs = splits
                info.splitPublicSourceDirs = splits
            }
            info.dataDir = home.absolutePath
            info.nativeLibraryDir = nativeDir.absolutePath

            extractNativeLibraries(apks, nativeDir)

            // Android 14 refuses to load a dex file the app can write to, and a copied APK is
            // writable by whoever copied it. Re-applied on every load, not just after the backup:
            // a backup taken before this rule was handled is still writable on disk and would fail
            // with SecurityException every single time otherwise.
            VirtualizedAppVault.markReadOnly(apks)

            // PARENT IS THE BOOT LOADER, NOT PRISM'S.
            //
            // Class loading is parent-first, so anything both Prism and the app ship would resolve
            // to PRISM's copy -- and both are Kotlin apps, so that is the entire Kotlin stdlib.
            // ART then refuses package-private access across class loaders, which surfaces as
            // "Illegal class access ('org.mozilla.fenix.HomeActivity' attempting to access
            // 'kotlin.collections.ArraysKt___ArraysKt')" before the activity's constructor returns.
            //
            // A real app's PathClassLoader parents to the boot loader for exactly this reason: the
            // app resolves its own copy of everything it bundles, and only android.* / java.* come
            // from outside. It also means hosted code cannot see Prism's classes, which is welcome.
            val bootLoader = context.classLoader.parent ?: ClassLoader.getSystemClassLoader()

            val loader = DexClassLoader(
                apks.joinToString(File.pathSeparator) { it.absolutePath },
                File(home, "odex").apply { mkdirs() }.absolutePath,
                nativeDir.absolutePath,
                bootLoader,
            )

            val resources = buildResources(context, apks) ?: run {
                PrismLogger.logWarning(TAG, "Could not build resources for $packageName")
                return null
            }

            // Read from base.apk, which is where an app bundle keeps the real manifest; the
            // splits carry stubs that declare nothing a launch needs.
            val manifest = ApkManifest.read(apks.first())
            if (manifest == null) {
                PrismLogger.logWarning(TAG, "Falling back to PackageManager for $packageName's entry point")
            } else if (info.className.isNullOrBlank() && manifest.applicationClass != null) {
                // getPackageArchiveInfo occasionally leaves this unset on bundles even though the
                // manifest declares it, and an app whose Application never runs fails later and
                // less clearly.
                info.className = manifest.applicationClass
            }

            PrismLogger.logInfo(
                TAG,
                "Loaded $packageName (${apks.size} apk(s), entry=${manifest?.launchActivity ?: "unknown"})",
            )
            return VirtualPackage(packageName, info, apks, loader, resources, manifest)
        }

        /**
         * Resources that resolve the app's own R references.
         *
         * `addAssetPath` is hidden API, which is why [HiddenApi] has to have run first. Every split
         * is added: a per-density or per-language split holds resources the base does not, and an
         * app that asks for one of those gets a `NotFoundException` if only the base was added.
         */
        private fun buildResources(context: Context, apks: List<File>): Resources? = runCatching {
            val assets = AssetManager::class.java.newInstance()
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }

            for (apk in apks) {
                val cookie = addAssetPath.invoke(assets, apk.absolutePath) as? Int ?: 0
                if (cookie == 0) {
                    PrismLogger.logWarning(TAG, "addAssetPath refused ${apk.name}")
                }
            }

            @Suppress("DEPRECATION")
            Resources(assets, context.resources.displayMetrics, context.resources.configuration)
        }.getOrNull()

        /**
         * Unpacks the per-ABI native libraries out of the APKs.
         *
         * Only this device's ABI, and only when the file is not already there at the same size --
         * an app with a large native payload should not be unpacked again on every launch.
         */
        private fun extractNativeLibraries(apks: List<File>, target: File) {
            val abis = android.os.Build.SUPPORTED_ABIS
            for (apk in apks) {
                runCatching {
                    java.util.zip.ZipFile(apk).use { zip ->
                        for (abi in abis) {
                            val prefix = "lib/$abi/"
                            val entries = zip.entries().asSequence()
                                .filter { it.name.startsWith(prefix) && it.name.endsWith(".so") }
                                .toList()
                            if (entries.isEmpty()) continue

                            for (entry in entries) {
                                val out = File(target, entry.name.substringAfterLast('/'))
                                if (out.isFile && out.length() == entry.size) continue
                                zip.getInputStream(entry).use { input ->
                                    out.outputStream().use { output -> input.copyTo(output) }
                                }
                            }
                            // The first ABI that matched is this device's best; the rest are for
                            // other devices and would overwrite these with the wrong architecture.
                            break
                        }
                    }
                }
            }
        }
    }
}
