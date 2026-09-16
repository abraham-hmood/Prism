package com.prism.launcher

import com.prism.core.PrismPlatform
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Third-party pages, loaded from JARs.
 *
 * ANDROID FINDS THESE BY QUERYING PackageManager for activities declaring
 * `PrismContracts.ACTION_DESKTOP_PAGE`, then reads a metadata key naming the view class. Desktop
 * has neither an intent system nor installed packages to query, so the discovery mechanism is
 * necessarily different: a plugins directory is scanned for `.jar` files, and each is asked for
 * the same two facts through its manifest.
 *
 * THE CONTRACT IS THE SAME TWO FACTS, which is what makes this a port rather than a new feature:
 * a label to show, and a class to instantiate. Android carries them in an activity's meta-data;
 * a JAR carries them in `META-INF/MANIFEST.MF` under [MANIFEST_LABEL] and [MANIFEST_CLASS]. A
 * plugin author writes the same class either way.
 *
 * LOADING CODE FROM A DIRECTORY IS A REAL TRUST DECISION and is treated as one. A JAR here runs
 * with Prism's full privileges -- there is no sandbox, exactly as an Android plugin page runs
 * with its own app's. The difference is that Android's plugin had to be installed as a package
 * the user consented to, and a file dropped in a directory had no such moment. So loading is OFF
 * by default and the user turns it on knowingly; see [enabled].
 */
object PluginPages {

    const val MANIFEST_CLASS = "Prism-Page-Class"
    const val MANIFEST_LABEL = "Prism-Page-Label"

    private const val TAG = "Prism/plugins"

    /** Where JARs are looked for. */
    fun directory(): File = File(PrismPlatform.host.dataDir(), "plugins")

    /**
     * Whether plugin loading is permitted.
     *
     * Defaults to false. A plugin runs unsandboxed, and the Android equivalent at least required
     * installing a package; a JAR appearing in a directory does not carry that consent, so it has
     * to be granted explicitly rather than assumed.
     */
    fun enabled(): Boolean = PrismSettings.getPluginPagesEnabled()

    data class PluginPage(
        val label: String,
        val className: String,
        val jar: File,
    )

    /**
     * Lists the plugins present, without loading any code.
     *
     * Reading a manifest opens the JAR as a zip; it does not define a class or run anything. That
     * separation is deliberate -- the settings UI can show what is installed while loading stays
     * off, so a user can see what they would be enabling before they enable it.
     */
    fun discover(): List<PluginPage> {
        val dir = directory()
        if (!dir.isDirectory) return emptyList()
        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar", true) }
            ?: return emptyList()

        return jars.mapNotNull { jar ->
            try {
                JarFile(jar).use { jf ->
                    val attrs = jf.manifest?.mainAttributes ?: return@use null
                    val cls = attrs.getValue(MANIFEST_CLASS) ?: return@use null
                    val label = attrs.getValue(MANIFEST_LABEL) ?: jar.nameWithoutExtension
                    PluginPage(label, cls, jar)
                }
            } catch (e: Exception) {
                PrismPlatform.log.warn(TAG, "Could not read ${jar.name}: ${e.message}")
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Loads a plugin's class.
     *
     * Returns null when loading is disabled, rather than throwing -- a disabled plugin is a
     * normal state, not an error.
     *
     * The parent classloader is Prism's own, so a plugin can see the interfaces it is written
     * against. That is also why this is not a security boundary: a plugin with access to Prism's
     * classes has access to everything Prism can do.
     */
    fun load(page: PluginPage): Class<*>? {
        if (!enabled()) {
            PrismPlatform.log.info(TAG, "Plugin loading is off; ${page.label} not loaded")
            return null
        }
        return try {
            val loader = URLClassLoader(arrayOf(page.jar.toURI().toURL()), javaClass.classLoader)
            loader.loadClass(page.className)
        } catch (e: Throwable) {
            // Throwable: a JAR compiled against a different Prism throws NoClassDefFoundError,
            // which is the single likeliest failure here and is not an Exception.
            PrismPlatform.log.error(TAG, "Could not load ${page.label}", e)
            null
        }
    }
}
