/*
 * android.app.Fragment is deprecated in favour of the AndroidX one, and this file cannot use the
 * AndroidX one. It reaches into the framework's own activity plumbing -- the VirtualApp/VirtualXposed
 * approach -- and the objects on the other side of that boundary are framework Fragments. Passing an
 * AndroidX Fragment where the platform expects its own is not a migration, it is a ClassCastException.
 */
@file:Suppress("DEPRECATION")

package com.prism.launcher.virtualapp

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.prism.launcher.PrismLogger
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * Loads a virtualized app and brings one of its activities to life.
 *
 * ## Why this is not part of the host
 *
 * There are two hosts now. A virtualized app can be shown in [VirtualAppActivity] -- a full-screen
 * window of its own -- or embedded in the launcher's virtualization page through
 * [VirtualAppHostService]. The work either one has to do is identical, and the earlier version of it
 * was written into the activity and reached into that activity for the framework objects a hosted
 * activity needs.
 *
 * That is the one thing this had to stop doing: a Service has no host Activity to borrow
 * `mInstrumentation`, `mMainThread` or a window token from. They come from `ActivityThread`
 * directly instead, which is where the host activity had them from in the first place.
 */
class VirtualAppRunner(private val hostContext: Context) {

    /** The activity currently hosted, if any. */
    var hosted: Activity? = null
        private set

    /**
     * Decrypts the app's data and loads its code. **Not** for the main thread.
     *
     * The unseal is a round trip to [VaultService] in the main process, and the load is AES over a
     * whole data directory plus a dex load.
     */
    fun load(packageName: String): Result<VirtualPackage> {
        if (!VaultBridge.unsealBlocking(hostContext, packageName)) {
            return Result.failure(IllegalStateException("$packageName's data could not be unsealed"))
        }
        return runCatching {
            VirtualPackage.load(hostContext, packageName)
                ?: throw IllegalStateException("$packageName could not be loaded from its backup")
        }
    }

    /**
     * Builds the app's own `Application` object.
     *
     * Not optional for real apps, for two separate reasons. An app that subclasses Application casts
     * `getApplication()` to that subclass, and handing it Prism's gets a ClassCastException before
     * any of its own code runs. And an app that initialises anything in `Application.onCreate` --
     * a DI graph, a crash reporter, an image loader -- finds all of it missing later on, which
     * surfaces as a null somewhere unrelated rather than as "the Application never ran".
     *
     * Failure is survivable: an app whose Application throws is likely to fail anyway, but it fails
     * further in, where the message says more.
     */
    fun createApplication(virtual: VirtualPackage): Application? = runCatching {
        virtual.application?.let { return it }

        val name = virtual.applicationInfo.className?.takeIf { it.isNotBlank() }
            ?: "android.app.Application"
        val clazz = virtual.classLoader.loadClass(name)
        val app = clazz.getDeclaredConstructor().newInstance() as Application

        Application::class.java
            .getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, VirtualContext(hostContext, virtual))

        // Published BEFORE onCreate, because an app's Application.onCreate routinely reaches for
        // getApplicationContext() and casts it to itself.
        virtual.application = app

        runCatching { app.onCreate() }.onFailure {
            PrismLogger.logWarning(TAG, "${virtual.packageName}'s Application.onCreate threw: $it")
        }
        PrismLogger.logInfo(TAG, "Created Application $name for ${virtual.packageName}")
        app
    }.getOrElse {
        // Unwrapped: reflection reports everything the constructor threw as
        // InvocationTargetException, and that name on its own says nothing about which of the app's
        // own initialisers failed.
        val cause = (it as? InvocationTargetException)?.targetException ?: it
        PrismLogger.logError(TAG, "Could not build an Application for ${virtual.packageName}", cause)
        null
    }

    /**
     * Instantiates [activityName] and runs its `onCreate`, returning the view it produced.
     *
     * The activity is built directly rather than through the system's launch path, which would need
     * a manifest entry that does not exist. What it gets instead is a [VirtualContext], its own
     * resources, its own Window, and `onCreate` called by hand -- enough for an activity that lays
     * itself out, and visibly not a full lifecycle.
     */
    fun createActivity(
        virtual: VirtualPackage,
        activityName: String,
        application: Application?,
        windowManager: WindowManager,
        hostToken: IBinder?,
    ): android.view.View {
        // An alias names the activity to START; the class to INSTANTIATE is its target.
        val className = virtual.implementationClass(activityName)
        if (className != activityName) {
            PrismLogger.logInfo(TAG, "$activityName is an alias for $className")
        }

        val instance = virtual.classLoader.loadClass(className)
            .getDeclaredConstructor()
            .newInstance() as Activity

        val themeRes = virtual.themeFor(activityName)
        val virtualContext = VirtualContext(hostContext, virtual).apply {
            if (themeRes != 0) setTheme(themeRes)
        }

        // attachBaseContext is protected, and an Activity with no base context throws from the first
        // getResources() call inside its own onCreate.
        ContextWrapperAccess.attachBaseContext(instance, virtualContext)
        hosted = instance

        // ITS OWN WINDOW.
        //
        // Reusing the host's looked simpler and is wrong in a way that only shows up once an app
        // draws anything: a Window hands out the LayoutInflater built from the context it was
        // created with, so setContentView(R.layout.something) would look the app's layout id up in
        // PRISM's resources and fail. A PhoneWindow over the VirtualContext inflates against the
        // app's own resources, which is the entire point of having built them.
        val window = ActivityAccess.newWindow(virtualContext, windowManager, hostToken, activityName)
            ?: throw IllegalStateException("No window could be created for $activityName")

        val component = ComponentName(virtual.packageName, activityName)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = component
        }

        ActivityAccess.attachMinimal(
            target = instance,
            context = virtualContext,
            window = window,
            application = application,
            activityInfo = virtual.activityInfo(activityName),
            themeRes = themeRes,
            component = component,
            intent = intent,
            windowManager = windowManager,
        )

        // The activity IS the window's callback -- that is how key events, menus and lifecycle
        // callbacks from the window reach it. Without this its UI draws and then ignores input.
        runCatching { window.callback = instance }

        ActivityAccess.callOnCreate(instance, null)

        PrismLogger.logInfo(TAG, "Hosting ${virtual.packageName}/$activityName")
        return window.decorView
    }

    /** Runs the hosted activity's `onDestroy`, best effort. */
    fun destroy() {
        hosted?.let { runCatching { ActivityAccess.callOnDestroy(it) } }
        hosted = null
    }

    companion object {
        private const val TAG = "PrismVirtualApp"
    }
}

/** `attachBaseContext` is protected on ContextWrapper, and a hosted activity has to be given one. */
internal object ContextWrapperAccess {
    fun attachBaseContext(target: Activity, base: Context) {
        val method = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        method.isAccessible = true
        method.invoke(target, base)
    }
}

/**
 * The parts of `Activity.attach` that a hosted activity cannot do without.
 *
 * The real `attach` takes a dozen framework objects that only `ActivityThread` has. What is set here
 * is the minimum an activity touches before its own `onCreate` returns -- its window, its
 * application, its Instrumentation, its Intent and its fragment host -- which is why an activity
 * that lays itself out works and one that reaches deeper does not.
 */
internal object ActivityAccess {

    private const val TAG = "PrismVirtualApp"

    fun attachMinimal(
        target: Activity,
        context: Context,
        window: Window,
        application: Application?,
        activityInfo: android.content.pm.ActivityInfo?,
        themeRes: Int,
        component: ComponentName,
        intent: Intent,
        windowManager: WindowManager,
    ) {
        val thread = activityThread()

        setField(target, "mWindow", window)
        setField(target, "mWindowManager", window.windowManager ?: windowManager)
        // The app's own Application where one could be built, so that a cast to its subclass
        // succeeds. Null leaves getApplication() null, which is better than a confident wrong answer.
        setField(target, "mApplication", application)
        setField(target, "mComponent", component)
        setField(target, "mUiThread", Thread.currentThread())
        // From ActivityThread rather than from a host activity: there may not be one.
        setField(target, "mMainThread", thread)
        setField(target, "mInstrumentation", thread?.let { readField(it, "mInstrumentation") })
        // The app's own ActivityInfo, not the host's: it carries the theme the activity declared,
        // and ContextThemeWrapper applies mThemeResource against the resources it was given.
        setField(target, "mActivityInfo", activityInfo)
        if (themeRes != 0) setField(target, "mThemeResource", themeRes)
        setField(target, "mBase", context)

        // getIntent() must not be null. Activities read it in onCreate as a matter of course, and a
        // null there is an NPE inside the app's own first few lines.
        setField(target, "mIntent", intent)

        attachFragmentHost(target)
        installPrivateFactory(target, window)
    }

    /**
     * `Activity.attach()` calls `mFragments.attachHost(null)`, and skipping attach() skipped it.
     *
     * The framework's FragmentManager is constructed with the Activity but has no HOST until
     * attachHost runs, and `Activity.onCreate` dispatches fragment creation unconditionally. The
     * result was `IllegalStateException("No activity")` thrown from inside the app's own
     * `super.onCreate()` -- an error about the activity, raised by the activity, while it was
     * plainly there.
     */
    private fun attachFragmentHost(target: Activity) {
        runCatching {
            // Looked up on android.app.Activity SPECIFICALLY, not by walking the hierarchy.
            // androidx's FragmentActivity declares its own `mFragments`, which shadows the
            // framework's and takes an androidx Fragment -- so a hierarchy walk finds the wrong one
            // and leaves the framework's host unattached, which is the one Activity.onCreate uses.
            // androidx attaches its own host from its own onCreate and needs no help here.
            val controller = Activity::class.java.getDeclaredField("mFragments")
                .apply { isAccessible = true }
                .get(target)
            if (controller != null) {
                // `null as Fragment?`, NOT a bare null: Method.invoke takes a vararg array, and a
                // bare null there is an EMPTY argument list rather than one null argument, so the
                // call fails with "wrong number of arguments" -- silently, if it is wrapped.
                controller.javaClass
                    .getMethod("attachHost", android.app.Fragment::class.java)
                    .invoke(controller, null as android.app.Fragment?)
            }
        }.onFailure {
            // Logged, because a swallowed failure here does not look like a failure here: it looks
            // like IllegalStateException("No activity") thrown out of the app's own super.onCreate().
            PrismLogger.logWarning(TAG, "Could not attach the fragment host: $it")
        }
    }

    /**
     * Also from attach(): the private factory is how an Activity intercepts inflation.
     *
     * `<fragment>` tags in a layout are resolved through it, and AppCompat relies on it to swap in
     * its own widget implementations.
     */
    private fun installPrivateFactory(target: Activity, window: Window) {
        runCatching {
            val inflater = window.layoutInflater
            LayoutInflater::class.java
                .getMethod("setPrivateFactory", LayoutInflater.Factory2::class.java)
                .invoke(inflater, target as LayoutInflater.Factory2)
        }
    }

    /**
     * A private `PhoneWindow` over the virtualized app's context.
     *
     * Reflective because `PhoneWindow` is internal and `setWindowManager(wm, token, name, hwAccel)`
     * is hidden -- both reachable only once [HiddenApi] has lifted enforcement, which is why that
     * runs first.
     */
    fun newWindow(
        context: Context,
        windowManager: WindowManager,
        hostToken: IBinder?,
        name: String,
    ): Window? = runCatching {
        val window = Class.forName("com.android.internal.policy.PhoneWindow")
            .getConstructor(Context::class.java)
            .newInstance(context) as Window

        Window::class.java.getMethod(
            "setWindowManager",
            WindowManager::class.java,
            IBinder::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(window, windowManager, hostToken, name, false)

        window
    }.getOrElse {
        PrismLogger.logWarning(TAG, "Could not create a window for $name: ${it.message}")
        null
    }

    fun callOnCreate(target: Activity, state: Bundle?) {
        val method = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        method.isAccessible = true
        method.invoke(target, state)
    }

    fun callOnDestroy(target: Activity) {
        val method = Activity::class.java.getDeclaredMethod("onDestroy")
        method.isAccessible = true
        method.invoke(target)
    }

    /** The process's ActivityThread, which is where a host activity's framework objects came from. */
    private fun activityThread(): Any? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
    }.getOrNull()

    private fun readField(target: Any, name: String): Any? = runCatching {
        findField(target.javaClass, name)?.apply { isAccessible = true }?.get(target)
    }.getOrNull()

    private fun setField(target: Any, name: String, value: Any?) {
        runCatching {
            val field = findField(target.javaClass, name) ?: return
            field.isAccessible = true
            field.set(target, value)
        }
    }

    private fun findField(start: Class<*>, name: String): Field? {
        var clazz: Class<*>? = start
        while (clazz != null) {
            runCatching { return clazz!!.getDeclaredField(name) }
            clazz = clazz.superclass
        }
        return null
    }
}
