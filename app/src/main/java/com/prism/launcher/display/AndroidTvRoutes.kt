package com.prism.launcher.display

import android.content.Context
import android.media.MediaRouter
import android.view.Display

/**
 * Televisions Android's own media router can reach, without opening the cast settings screen.
 *
 * ## The half SSDP cannot do
 *
 * SSDP tells us a TV exists and what it is called. Starting a Miracast (Wi-Fi Display) session is
 * not an SSDP operation: the platform owns the negotiation. That negotiation lives in
 * `android.media.MediaRouter`, whose route list and `selectRoute` are public methods on the device
 * but hidden from the compiled SDK stubs -- on compileSdk 35 the class exposes only the legacy
 * `selectRoute(int, RouteInfo)` and no `getInstance` at all.
 *
 * ## Why reflection rather than AndroidX
 *
 * `androidx.mediarouter` would be the clean answer, but it is a Google Maven artifact and this
 * build has to compile offline; adding it turned a one-minute build into a stalled download. The
 * platform class is present at runtime, so the bridge reflects the handful of hidden methods it
 * needs and degrades to an empty list on any device that refuses them.
 *
 * Once a route connects, the set appears in `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`, and
 * [SystemDisplayActivity] can put a mirror on it. The flow is therefore:
 *
 *   SSDP finds the set -> MediaRouter selects it -> the presentation display appears -> mirror.
 */
object AndroidTvRoutes {

    data class Route(
        val name: String,
        val description: String,
        val displayId: Int,
        val ready: Boolean,
        val connecting: Boolean,
        /** True when Android reports it as a television rather than a speaker or a phone. */
        val television: Boolean,
    )

    private const val ROUTER_CLASS = "android.media.MediaRouter"
    private const val ROUTE_CLASS = "android.media.MediaRouter\$RouteInfo"

    /** One route object and the class it came from, kept together for the reflective calls. */
    private class Ref(val value: Any, val type: Class<*>)

    private fun router(context: Context): Any? = runCatching {
        val type = Class.forName(ROUTER_CLASS)
        val getInstance = type.getMethod("getInstance", Context::class.java)
        getInstance.invoke(null, context.applicationContext)
    }.getOrNull()

    private fun call(target: Any, name: String, vararg args: Class<*>): Any? = runCatching {
        target.javaClass.getMethod(name, *args).invoke(target)
    }.getOrNull()

    private fun routeRefs(context: Context): List<Ref> {
        val router = router(context) ?: return emptyList()
        val routes = runCatching {
            val method = router.javaClass.getMethod("getRoutes")
            method.invoke(router) as? List<*>
        }.getOrNull().orEmpty()
        val type = runCatching { Class.forName(ROUTE_CLASS) }.getOrNull() ?: return emptyList()
        return routes.filterNotNull().map { Ref(it, type) }
    }

    /**
     * Routes that are plausibly screens.
     *
     * Speakers and Bluetooth headsets can be media routes too, and listing them under "televisions"
     * would be a lie the user has to debug. A route qualifies when it already has a presentation
     * display, or when Android itself classifies it as a TV.
     */
    fun routes(context: Context): List<Route> {
        val router = router(context) ?: return emptyList()
        // The router already knows the sinks Android's own cast sheet lists; there is no public
        // selector in the framework class to start an extra scan from here.
        return routeRefs(context).mapNotNull { ref ->
            val name = (call(ref.value, "getName") as? CharSequence)?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val display = call(ref.value, "getPresentationDisplay") as? Display
            val deviceType = call(ref.value, "getDeviceType") as? Int ?: -1
            val television = deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV
            if (display == null && !television) return@mapNotNull null
            Route(
                name = name,
                description = (call(ref.value, "getDescription") as? CharSequence)?.toString().orEmpty(),
                displayId = display?.displayId ?: -1,
                ready = display != null,
                connecting = call(ref.value, "isConnecting") as? Boolean ?: false,
                television = television,
            )
        }.distinctBy { it.name.lowercase() }
    }

    /**
     * The route that best matches an SSDP name.
     *
     * The two names are written by different parts of the TV and rarely match exactly:
     * SSDP says "Samsung UN40H5203", the router says "[TV] Samsung 5 Series (40)". Scoring on
     * shared distinctive tokens is more reliable than equality, and a name that shares no token
     * at all is not silently selected -- connecting the wrong screen is worse than asking again.
     */
    private fun findRouteRef(context: Context, ssdpName: String): Ref? {
        val wanted = tokensOf(ssdpName)
        var best: Ref? = null
        var bestScore = 0
        for (ref in routeRefs(context)) {
            val name = (call(ref.value, "getName") as? CharSequence)?.toString().orEmpty()
            if (name.isBlank()) continue
            val candidate = tokensOf(name)
            var score = 0
            for (token in wanted) {
                if (token.length < 3) continue
                if (candidate.any { it == token }) score += 3
                else if (candidate.any { it.contains(token) || token.contains(it) }) score += 1
            }
            if (call(ref.value, "getPresentationDisplay") != null) score += 1
            if (score > bestScore) {
                bestScore = score
                best = ref
            }
        }
        return if (bestScore >= 2) best else null
    }

    /** Asks Android to connect [ssdpName]. Returns the message to show, not a boolean. */
    fun connect(context: Context, ssdpName: String): String {
        val router = router(context) ?: return "This build has no media router."
        val route = findRouteRef(context, ssdpName)
            ?: return "No Miracast route for $ssdpName is being offered by Android yet. Make sure " +
                "the TV is on the same Wi-Fi and has Screen Mirroring enabled, then refresh."
        return runCatching {
            runCatching {
                router.javaClass.getMethod("selectRoute", route.type).invoke(router, route.value)
            }.recoverCatching {
                // Some builds expose only the int route-id overload.
                val id = router.javaClass.getMethod("getSelectedRoute", Int::class.javaPrimitiveType)
                    .invoke(router, 1)
                router.javaClass.getMethod("selectRoute", Int::class.javaPrimitiveType, route.type)
                    .invoke(router, 1, route.value)
                if (id == null) Unit
            }.getOrThrow()
            "Asking $ssdpName to accept the connection. It can take ten seconds; the set then " +
                "appears under System displays, where Mirror here opens it."
        }.getOrElse { "Could not connect to $ssdpName: ${it.message}" }
    }

    /** The presentation display id for a route, once Android has negotiated it. */
    fun displayId(context: Context, ssdpName: String): Int? {
        val ref = findRouteRef(context, ssdpName) ?: return null
        val display = call(ref.value, "getPresentationDisplay") as? Display ?: return null
        return display.displayId
    }

    private fun tokensOf(name: String): Set<String> = name
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }
        .toSet()
}
