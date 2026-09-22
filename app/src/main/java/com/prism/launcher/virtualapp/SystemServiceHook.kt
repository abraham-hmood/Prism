package com.prism.launcher.virtualapp

import com.prism.launcher.PrismLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Makes the system server accept calls a virtualized app makes in its own name.
 *
 * ## The problem this exists to solve
 *
 * A hosted app believes it is itself, and says so: it asks for a `PendingIntent` as
 * `org.mozilla.firefox`, registers receivers as itself, starts its own activities. The system server
 * checks those claims against the CALLER'S UID, and the caller is Prism:
 *
 * ```
 * SecurityException: Permission Denial: getIntentSender() from pid=7082, uid=10612,
 *   (need uid=10583) is not allowed to send as package org.mozilla.firefox
 * ```
 *
 * Nothing inside this process can satisfy that check, because it is not being made here. The refusal
 * arrives from another process that is right about what it sees.
 *
 * ## What is done instead
 *
 * Every binder call to the activity managers goes through a `Singleton` holding one `IActivityManager`
 * (and, since API 29, one `IActivityTaskManager`). Replacing the instance in that singleton with a
 * proxy puts a seam between the app and the system, and the proxy rewrites the app's package name to
 * Prism's on the way out. The system then sees a claim that matches the uid making it, and allows it.
 *
 * This is the hook every app-virtualization framework installs, and it is what separates "the app's
 * code runs" from "the app works".
 *
 * ## Why rewriting every matching string is safe HERE and would not be in the main process
 *
 * The rule is blunt on purpose: any argument that is exactly a virtualized package's name becomes
 * Prism's. That is reasonable only because this process hosts virtualized apps and nothing else --
 * no launcher UI, no Prism feature, nothing with a legitimate reason to name those packages. In the
 * main process the same rule would rewrite arguments belonging to Prism's own code, which is one of
 * several reasons virtualized apps live in `:virtualapp`.
 *
 * ## What this does not fix
 *
 * The app still runs under Prism's uid with Prism's permissions. Rewriting the name it presents makes
 * the system stop refusing; it does not make the app be itself. Play Integrity still fails, and a
 * permission Prism does not hold is still one the app does not have.
 */
object SystemServiceHook {

    private const val TAG = "PrismVirtualApp"

    @Volatile
    private var installed = false

    /**
     * Every package this process is rewriting, not just the latest one.
     *
     * The process outlives a single launch -- open one app, close it, open another, and the second
     * arrives in the process the first left behind. Binding the rewrite to one package at install
     * time meant the second app was hosted by a proxy still rewriting the FIRST app's name, so it
     * hit exactly the `getIntentSender` denial the hook exists to prevent, with the hook visible in
     * the stack trace not doing its job.
     */
    private val virtualPackages = java.util.concurrent.CopyOnWriteArraySet<String>()

    @Volatile
    private var hostPackage: String = ""

    /**
     * Handles a virtualized app starting one of its OWN activities.
     *
     * Returning true means the start was dealt with inside this process and must not reach the
     * system. That matters twice over: the system has never heard of the target -- it is declared in
     * the app's manifest, not Prism's -- so the call fails, and the failure arrives inside the app's
     * own `onClick`, where it is an uncaught exception that takes the whole process down. Tapping a
     * menu row that opens a second screen was enough to kill a running app.
     *
     * Set by [VirtualAppHostService], which hosts the target in place of the current one.
     */
    @Volatile
    var onStartVirtualActivity: ((android.content.Intent) -> Boolean)? = null

    /**
     * Installs the proxies, and registers [virtualPackage] for rewriting.
     *
     * Safe to call repeatedly and with different packages: the proxies are installed once, the set
     * of names they rewrite grows.
     *
     * Failure is reported and survivable: an unhooked app is one that fails later, on its first
     * call that names itself, rather than one that cannot start at all -- and some apps never make
     * such a call.
     */
    @Synchronized
    fun install(virtualPackage: String, hostPackage: String) {
        virtualPackages.add(virtualPackage)
        this.hostPackage = hostPackage

        if (installed) return
        installed = true

        hook(
            owner = "android.app.ActivityManager",
            field = "IActivityManagerSingleton",
            iface = "android.app.IActivityManager",
        )

        // Activity starts moved to their own service in API 29; on those releases the ActivityManager
        // proxy alone would not see them.
        hook(
            owner = "android.app.ActivityTaskManager",
            field = "IActivityTaskManagerSingleton",
            iface = "android.app.IActivityTaskManager",
        )
    }

    private fun hook(owner: String, field: String, iface: String) {
        runCatching {
            val singleton = Class.forName(owner)
                .getDeclaredField(field)
                .apply { isAccessible = true }
                .get(null) ?: return

            val singletonClass = Class.forName("android.util.Singleton")

            // get() first: mInstance is null until something has asked for the binder, and replacing
            // a null would simply be overwritten the first time the app made a call.
            singletonClass.getDeclaredMethod("get").apply { isAccessible = true }.invoke(singleton)

            val instanceField = singletonClass
                .getDeclaredField("mInstance")
                .apply { isAccessible = true }

            val real = instanceField.get(singleton) ?: return
            val contract = Class.forName(iface)

            val proxy = Proxy.newProxyInstance(
                contract.classLoader,
                arrayOf(contract),
                InvocationHandler { _, method, args ->
                    // Intercepted BEFORE the name rewrite: an activity belonging to a virtualized
                    // app never goes to the system at all.
                    if (method.name.startsWith("startActivity")) {
                        val intent = args?.filterIsInstance<android.content.Intent>()?.firstOrNull()
                        val component = intent?.component
                        if (component != null && component.packageName in virtualPackages &&
                            onStartVirtualActivity?.invoke(intent) == true
                        ) {
                            PrismLogger.logInfo(TAG, "Hosted ${component.className} in place")
                            // START_SUCCESS. The caller believes the system started its activity,
                            // which from the app's point of view is exactly what happened.
                            return@InvocationHandler if (method.returnType == Int::class.javaPrimitiveType) 0 else null
                        }
                    }

                    val rewritten = args?.map { argument ->
                        if (argument is String && argument in virtualPackages) hostPackage
                        else argument
                    }?.toTypedArray()

                    try {
                        if (rewritten == null) method.invoke(real) else method.invoke(real, *rewritten)
                    } catch (e: InvocationTargetException) {
                        // Unwrapped, so the app sees the SecurityException or RemoteException the
                        // system actually threw rather than a reflection wrapper it cannot catch.
                        throw e.targetException
                    }
                },
            )

            instanceField.set(singleton, proxy)
            PrismLogger.logInfo(TAG, "Hooked $owner.$field")
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not hook $owner.$field: ${it.message}")
        }
    }
}
