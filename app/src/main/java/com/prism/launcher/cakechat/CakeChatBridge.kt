package com.prism.launcher.cakechat

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * The single point where Kotlin reaches into Python.
 *
 * Chaquopy's interpreter is a process-wide singleton that must be started exactly once and never
 * restarted -- CPython cannot be re-initialised in the same process. Routing every call through here
 * means that rule is enforced in one place rather than depended on at each call site.
 */
object CakeChatBridge {

    private const val MODULE = "prism_cakechat"
    private const val CORPUS_MODULE = "prism_corpus"

    @Volatile
    private var started = false

    private fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
        }
    }

    /**
     * The port module.
     *
     * Deliberately not cached across calls: Chaquopy already caches modules, and holding a PyObject
     * in a Kotlin field keeps a reference into the interpreter alive for the life of the process
     * for no benefit.
     */
    fun module(context: Context): PyObject {
        ensureStarted(context)
        return Python.getInstance().getModule(MODULE)
    }

    /**
     * The corpus converter.
     *
     * Separate from [module] because it depends on nothing but the standard library -- no
     * TensorFlow, no CakeChat import -- so a dataset can be converted and checked on a device where
     * the training stack would fail to load at all.
     */
    fun corpusModule(context: Context): PyObject {
        ensureStarted(context)
        return Python.getInstance().getModule(CORPUS_MODULE)
    }

    /** Whether Python can run here at all. False on an ABI with no interpreter in the APK. */
    fun isAvailable(context: Context): Boolean = runCatching {
        module(context)
        true
    }.getOrDefault(false)

    /** The traceback from the last failed call, for a UI that should show the real reason. */
    fun lastError(context: Context): String? = runCatching {
        module(context).callAttr("last_error")?.toString()
    }.getOrNull()
}
