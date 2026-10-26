package com.prism.launcher

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.lang.reflect.Constructor

/**
 * Loads a desktop page out of another APK.
 *
 * ## A blank page is not an answer
 *
 * This used to return `View?` with every failure collapsed to null, so a plugin whose manifest
 * named a class that had been renamed, or one whose package could not be loaded at all, produced
 * an empty slot with nothing anywhere saying why. The loader now reports the reason it failed and
 * the pager draws that reason in the slot. A plugin that cannot load should look broken and say
 * so, not look like a feature that was never configured.
 */
object DynamicPageLoader {
    private const val TAG = "DynamicPageLoader"

    /** A page, or the reason there is not one. Never both. */
    data class Loaded(val view: View?, val error: String?)

    fun load(host: Context, packageName: String, className: String): Loaded {
        if (packageName.isBlank() || className.isBlank()) {
            return Loaded(null, "This page has no package or view class recorded.")
        }

        // The foreign context is what gives the view its own resources and class loader. Two
        // flags, both needed: INCLUDE_CODE makes the APK's code loadable, and IGNORE_SECURITY lets
        // a view built in another package resolve its own theme attributes. Without the second,
        // inflation of anything using a `<style>` from the plugin throws.
        val foreign = try {
            host.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create a context for $packageName", t)
            return Loaded(
                null,
                "Could not open the plugin \"$packageName\". It may have been uninstalled, or " +
                    "disabled in Android settings. (${t.javaClass.simpleName}: ${t.message})",
            )
        }

        val clazz = try {
            Class.forName(className, false, foreign.classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not find $className in $packageName", t)
            return Loaded(
                null,
                "\"$packageName\" is installed, but its page class \"$className\" could not be " +
                    "found. The plugin's manifest and its code disagree.",
            )
        }

        if (!View::class.java.isAssignableFrom(clazz)) {
            return Loaded(
                null,
                "\"$className\" is not an Android View, so it cannot be shown as a page.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val viewType = clazz as Class<out View>
        instantiateView(viewType, foreign)
            ?.let { return Loaded(it, null) }
        instantiateView(viewType, host)
            ?.let { return Loaded(it, null) }

        return Loaded(
            null,
            "\"$className\" could not be constructed. A plugin page needs a public constructor " +
                "taking Context, or Context and AttributeSet.",
        )
    }

    /** Kept for callers that only want the view; [load] carries the reason. */
    fun inflateView(host: Context, packageName: String, className: String): View? =
        load(host, packageName, className).view

    private fun instantiateView(clazz: Class<out View>, context: Context): View? =
        tryConstructor(clazz, arrayOf(Context::class.java), arrayOf(context))
            ?: tryConstructor(
                clazz,
                arrayOf(Context::class.java, AttributeSet::class.java),
                arrayOf(context, null),
            )
            ?: tryConstructor(
                clazz,
                arrayOf(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(context, null, 0),
            )

    private fun tryConstructor(
        clazz: Class<out View>,
        types: Array<Class<*>>,
        args: Array<Any?>,
    ): View? {
        return try {
            val ctor: Constructor<out View> = clazz.getConstructor(*types)
            ctor.newInstance(*args)
        } catch (t: Throwable) {
            Log.d(TAG, "Constructor ${types.joinToString()} failed for ${clazz.name}: ${t.message}")
            null
        }
    }
}
