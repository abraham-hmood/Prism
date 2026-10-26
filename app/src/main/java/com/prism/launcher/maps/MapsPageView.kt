package com.prism.launcher.maps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import com.prism.launcher.trusted.PrismLocations
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedLocations
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prism Maps: a map, place search, favorites, directions and offline areas.
 *
 * The map is drawn by [OsmMapView] rather than a WebView or another app. Search and routing are
 * fetched by [MapServices], but the result is rendered here. Tiles are stored by [MapTiles], which
 * is what makes a downloaded area usable with no connection.
 */
class MapsPageView(context: Context) : LinearLayout(context) {

    private val map: OsmMapView
    private val search: EditText
    private val results: LinearLayout
    private val cardHost: LinearLayout
    private val mapHost: FrameLayout
    /** Map zoom buttons. Kept as a field so the crop overlay can lift them above itself. */
    private val zoomColumn: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-map-work").apply { isDaemon = true }
    }
    private val poiWork = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-map-pois").apply { isDaemon = true }
    }

    private var searchGeneration = 0
    private var pendingQuery = ""
    private var selected: MapPlace? = null
    private var selectedDevice: String? = null
    private var route: MapServices.Route? = null
    private var routeMode = MapServices.TravelMode.CAR
    private var directionsOpen = false
    private var current: LatLon? = null
    private var downloadCancel = AtomicBoolean(false)
    private var sheet: MapDownloadSheet? = null
    private var crop: MapCropOverlay? = null

    private val poiCache = object : LinkedHashMap<String, List<MapPoi>>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MapPoi>>?): Boolean =
            size > 16
    }
    private var poiKey = ""
    private var poiRequestKey = ""
    private var poiWantedKey = ""
    private var poiGeneration = 0
    private var poiFailedKey = ""
    private var poiRetryAt = 0L

    private val poiDelay = Runnable { loadPoisForViewport() }

    private val searchDelay = Runnable {
        val query = pendingQuery
        if (query.trim().length >= 2) runSearch(query)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))
        setPadding(0, d(8), 0, 0)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), 0, d(12), 0)
        }
        header.addView(TextView(context).apply {
            text = "Maps"
            textSize = 30f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_download_24, "Download offline maps") {
            showDownloadSheet()
        }, iconParams(d(4)))
        header.addView(iconButton(R.drawable.ic_map_location_24, "My location") {
            locateMe()
        }, iconParams(d(4)))
        addView(header)

        search = EditText(context).apply {
            hint = "Search places, buildings, cities, addresses"
            textSize = 15f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = IosUi.fieldBackground(context)
            setPadding(d(14), d(11), d(14), d(11))
            setCompoundDrawablePadding(d(8))
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_search_24)
            icon?.let {
                DrawableCompat.setTint(it, IosUi.secondaryLabel(context))
                setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
            }
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = d(16)
            rightMargin = d(16)
            topMargin = d(2)
            bottomMargin = d(8)
        })

        map = OsmMapView(context)
        map.dark = IosUi.isDark(context)
        map.onPlaceTap = { place -> selectPlace(place, recenter = false) }
        map.onPlacePreview = { clearPlaceCard() }
        map.onViewportChanged = { schedulePois() }
        map.onDeviceTap = { fingerprint ->
            if (fingerprint == null) {
                if (!directionsOpen) {
                    selectedDevice = null
                    renderCard()
                }
            } else {
                selected = null
                directionsOpen = false
                route = null
                map.routePoints = emptyList()
                map.selectedPlace = null
                selectedDevice = fingerprint
                renderCard()
            }
        }
        map.onMapTap = {
            if (!directionsOpen) {
                selected = null
                map.selectedPlace = null
                selectedDevice = null
                renderCard()
            }
        }

        mapHost = FrameLayout(context)
        mapHost.addView(map, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))
        addView(mapHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        results = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        mapHost.addView(results, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply {
            leftMargin = d(12)
            rightMargin = d(12)
            topMargin = d(8)
        })

        zoomColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBackground(12)
            setPadding(d(3), d(3), d(3), d(3))
        }
        zoomColumn.addView(iconButton(com.prism.launcher.R.drawable.ic_zoom_in_24, "Zoom in") {
            if (crop?.isDownloading() != true) {
                map.zoomIn()
                crop?.refreshEstimate()
            }
        }, iconParams(0))
        zoomColumn.addView(iconButton(com.prism.launcher.R.drawable.ic_zoom_out_24, "Zoom out") {
            if (crop?.isDownloading() != true) {
                map.zoomOut()
                crop?.refreshEstimate()
            }
        }, iconParams(0))
        mapHost.addView(zoomColumn, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            rightMargin = d(12)
            bottomMargin = d(30)
        })

        cardHost = LinearLayout(context).apply { orientation = VERTICAL }
        mapHost.addView(cardHost, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply {
            leftMargin = d(12)
            rightMargin = d(12)
            bottomMargin = d(12)
        })

        mapHost.addView(TextView(context).apply {
            text = "© OpenStreetMap contributors"
            textSize = 10f
            setTextColor(IosUi.secondaryLabel(context))
            background = GradientDrawable().apply {
                setColor(if (IosUi.isDark(context)) 0xB3000000.toInt() else 0xCCFFFFFF.toInt())
                cornerRadius = d(6).toFloat()
            }
            setPadding(d(6), d(2), d(6), d(2))
        }, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = d(8)
            bottomMargin = d(4)
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingQuery = s?.toString().orEmpty()
                handler.removeCallbacks(searchDelay)
                if (pendingQuery.trim().length < 2) {
                    results.removeAllViews()
                    results.visibility = View.GONE
                } else {
                    handler.postDelayed(searchDelay, 450)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchDelay)
                if (pendingQuery.trim().length >= 2) runSearch(pendingQuery)
                true
            } else false
        }

        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        handler.post(tick)
        schedulePois()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(poiDelay)
        sheet?.dismiss()
        sheet = null
        // Leaving the page is a cancel, not a reason to keep writing tiles with no visible
        // progress. The worker checks this before its next tile and then stops.
        downloadCancel.set(true)
        crop?.let { overlay ->
            crop = null
            mapHost.removeView(overlay)
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Coming back from the permission dialog or the system settings means the tile cache and
        // the current-location dot may both be stale.
        if (hasWindowFocus) refresh()
    }

    // ── Data ───────────────────────────────────────────────────────────────

    private fun refresh() {
        val fixes = runCatching { TrustedLocations.all() }.getOrDefault(emptyList())
        val trusted = runCatching { TrustedDevices.all().filter { it.confirmed } }
            .getOrDefault(emptyList())
        map.dark = IosUi.isDark(context)
        map.fixes = fixes
        map.trusted = trusted
        map.favorites = MapStore.favorites()
        map.selectedPlace = selected
        if (!map.hasCenter) {
            val own = runCatching { PrismLocations.latestFix(context) }.getOrNull()
            if (own != null) {
                map.centerOn(own.latitude, own.longitude, 15)
                current = LatLon(own.latitude, own.longitude)
                map.current = current
            } else if (fixes.isNotEmpty()) {
                map.centerOn(
                    fixes.map { it.latitude }.average(),
                    fixes.map { it.longitude }.average(),
                    12,
                )
            } else {
                map.centerOn(20.0, 0.0, 2)
            }
        }
        map.invalidate()
        if (selectedDevice != null && fixes.none { it.fingerprint == selectedDevice }) {
            selectedDevice = null
        }
        schedulePois()
    }

    // ── Places of interest ─────────────────────────────────────────────────

    /**
     * Fetches POIs for the viewport, but only after the map has stopped moving.
     *
     * Overpass is a shared volunteer service. A request on every `onScroll` frame would be both
     * abusive and useless, so the last movement wins and a small cache absorbs panning back over
     * the same block. At low zoom the box is too large to be meaningful and the dots would cover
     * the map, so they are simply left out until the user is close enough. The page's other map
     * work (search, routing, downloads) runs on [work]; POIs get their own executor so a slow
     * Overpass response cannot hold up a search. The response is capped in [MapServices].
     */
    private fun schedulePois() {
        if (crop != null) return
        handler.removeCallbacks(poiDelay)
        if (map.zoom < POI_MIN_ZOOM) {
            clearPois()
            return
        }
        val bounds = map.visibleBounds(d(24).toFloat())
        if (!bounds.valid) return
        val key = poiCacheKey(bounds, map.zoom)
        poiWantedKey = key
        if (key == poiKey || key == poiRequestKey) return
        // A failed Overpass request is not retried on the three-second refresh tick. The public
        // mirrors are shared; one polite retry a minute is enough while the user keeps browsing.
        if (key == poiFailedKey && System.currentTimeMillis() < poiRetryAt) return
        handler.postDelayed(poiDelay, POI_DEBOUNCE_MS)
    }

    private fun loadPoisForViewport() {
        if (crop != null) return
        if (map.zoom < POI_MIN_ZOOM) {
            clearPois()
            return
        }
        val bounds = map.visibleBounds(d(24).toFloat())
        if (!bounds.valid) return
        val key = poiCacheKey(bounds, map.zoom)
        if (key != poiWantedKey) return
        poiCache[key]?.let { found ->
            applyPois(found, key)
            return
        }
        if (key == poiRequestKey) return
        val generation = ++poiGeneration
        poiRequestKey = key
        poiWork.execute {
            val result = MapServices.placesOfInterest(bounds)
            handler.post {
                if (generation != poiGeneration) return@post
                poiRequestKey = ""
                if (poiWantedKey != key) return@post
                result.onSuccess { found ->
                    poiFailedKey = ""
                    poiCache[key] = found
                    applyPois(found, key)
                    PrismLogger.logInfo(TAG, "Loaded ${found.size} places of interest")
                }.onFailure {
                    poiFailedKey = key
                    poiRetryAt = System.currentTimeMillis() + POI_RETRY_MS
                    PrismLogger.logWarning(TAG, "Places of interest unavailable: ${it.message}")
                }
            }
        }
    }

    private fun applyPois(found: List<MapPoi>, key: String) {
        map.pois = found
        poiKey = key
        map.invalidate()
    }

    private fun clearPois() {
        poiGeneration++
        poiRequestKey = ""
        poiWantedKey = ""
        poiKey = ""
        poiFailedKey = ""
        if (map.pois.isNotEmpty()) {
            map.pois = emptyList()
            map.invalidate()
        }
    }

    private fun poiCacheKey(bounds: MapBounds, zoom: Int): String {
        val decimals = when {
            zoom <= 13 -> 2
            zoom <= 15 -> 3
            else -> 4
        }
        return String.format(
            Locale.US,
            "%.${decimals}f,%.${decimals}f,%.${decimals}f,%.${decimals}f@%d",
            bounds.south, bounds.west, bounds.north, bounds.east, zoom,
        )
    }

    private fun locateMe() {
        val activity = context as? Activity ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            fetchLocation()
            return
        }
        requestLocationPermission(activity) { granted ->
            if (granted) fetchLocation() else toast("Location permission is needed to show where you are.")
        }
    }

    private fun fetchLocation() {
        executorToast("Finding your location…")
        work.execute {
            val known = runCatching { PrismLocations.latestFix(context) }.getOrNull()
            val fresh = if (known == null) {
                runCatching { PrismLocations.freshFix(context) }.getOrNull()
            } else null
            val lat = known?.latitude ?: fresh?.latitude
            val lon = known?.longitude ?: fresh?.longitude
            handler.post {
                if (lat == null || lon == null) {
                    toast("No location fix is available. Turn on location and try again.")
                } else {
                    current = LatLon(lat, lon)
                    map.current = current
                    map.centerOn(lat, lon, 16)
                    map.invalidate()
                }
            }
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────

    private fun runSearch(query: String) {
        val generation = ++searchGeneration
        executorToast("Searching…")
        work.execute {
            val found = MapServices.search(query)
            handler.post {
                if (generation != searchGeneration) return@post
                showResults(found)
            }
        }
    }

    private fun showResults(found: List<MapPlace>) {
        results.removeAllViews()
        val card = IosUi.card(context)
        if (found.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "No places found"
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
            })
        } else {
            found.forEachIndexed { index, place ->
                if (index > 0) {
                    card.addView(View(context).apply {
                        setBackgroundColor(IosUi.separator(context))
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, d(1)).apply {
                            topMargin = d(6)
                            bottomMargin = d(6)
                        }
                    })
                }
                card.addView(TextView(context).apply {
                    text = place.name
                    textSize = 15f
                    paint.isFakeBoldText = true
                    setTextColor(IosUi.label(context))
                })
                card.addView(TextView(context).apply {
                    text = place.address
                    textSize = 12f
                    setTextColor(IosUi.secondaryLabel(context))
                    maxLines = 2
                    setPadding(0, d(2), 0, d(6))
                })
                card.getChildAt(card.childCount - 2).setOnClickListener { choose(place) }
                card.getChildAt(card.childCount - 1).setOnClickListener { choose(place) }
            }
        }
        results.addView(card)
        results.visibility = View.VISIBLE
    }

    private fun choose(place: MapPlace) {
        search.setText("")
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        results.removeAllViews()
        results.visibility = View.GONE
        selectPlace(place)
    }

    private fun selectPlace(place: MapPlace, recenter: Boolean = true) {
        selected = place
        selectedDevice = null
        route = null
        directionsOpen = false
        map.routePoints = emptyList()
        map.selectedPlace = place
        map.favorites = MapStore.favorites()
        if (recenter) map.centerOn(place.lat, place.lon, 16)
        map.invalidate()
        renderCard()
    }

    /** A POI circle was tapped: dismiss whatever card was open, but leave the bubble on the map. */
    private fun clearPlaceCard() {
        selected = null
        selectedDevice = null
        directionsOpen = false
        route = null
        map.routePoints = emptyList()
        map.selectedPlace = null
        renderCard()
    }

    // ── Place and device cards ─────────────────────────────────────────────

    private fun renderCard() {
        cardHost.removeAllViews()
        val place = selected
        val deviceFingerprint = selectedDevice
        if (place == null && deviceFingerprint == null) return

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = cardBackground(16)
            setPadding(d(16), d(14), d(16), d(14))
            elevation = d(6).toFloat()
        }
        if (place != null) renderPlace(card, place) else renderDevice(card, deviceFingerprint!!)
        cardHost.addView(card)
    }

    private fun renderPlace(card: LinearLayout, place: MapPlace) {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = place.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 24f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(d(12), 0, d(2), d(4))
            setOnClickListener {
                selected = null
                directionsOpen = false
                route = null
                map.routePoints = emptyList()
                map.selectedPlace = null
                map.invalidate()
                renderCard()
            }
        })
        card.addView(header)
        if (place.address.isNotBlank()) card.addView(TextView(context).apply {
            text = place.address
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, d(3), 0, d(10))
        })

        val favorite = MapStore.isFavorite(place)
        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(pill(if (favorite) "★ Saved" else "☆ Save") {
            MapStore.toggleFavorite(place)
            map.favorites = MapStore.favorites()
            map.invalidate()
            renderCard()
        })
        actions.addView(pill("Directions") {
            directionsOpen = true
            map.centerOn(place.lat, place.lon, 15)
            renderCard()
            loadRoute(routeMode)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = d(8)
        })
        card.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = d(2)
        })

        if (directionsOpen) {
            val modes = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, d(10), 0, 0)
            }
            MapServices.TravelMode.values().forEach { mode ->
                modes.addView(modeChip(mode) { loadRoute(mode) }, LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = d(6) })
            }
            card.addView(modes)
            val summary = route
            if (summary != null) {
                card.addView(TextView(context).apply {
                    text = summary.summary
                    textSize = 13f
                    setTextColor(IosUi.label(context))
                    setPadding(0, d(10), 0, 0)
                })
                card.addView(TextView(context).apply {
                    text = summary.attribution
                    textSize = 10f
                    setTextColor(IosUi.tertiaryLabel(context))
                })
            }
        }
    }

    private fun renderDevice(card: LinearLayout, fingerprint: String) {
        val fix = runCatching { TrustedLocations.all() }
            .getOrDefault(emptyList()).firstOrNull { it.fingerprint == fingerprint }
        val device = runCatching { TrustedDevices.byFingerprint(fingerprint) }.getOrNull()
        if (fix == null) {
            selectedDevice = null
            return
        }
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = fix.name.ifBlank { device?.name ?: "Trusted device" }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (device?.online == true) Color.rgb(48, 209, 88) else Color.rgb(255, 111, 181))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 24f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(d(12), 0, d(2), d(4))
            setOnClickListener {
                selectedDevice = null
                renderCard()
            }
        })
        card.addView(header)
        card.addView(TextView(context).apply {
            text = "%.5f, %.5f".format(fix.latitude, fix.longitude)
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, d(2), 0, 0)
        })
        card.addView(TextView(context).apply {
            text = "Last position " + DateUtils.getRelativeTimeSpanString(fix.at)
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        if (device?.online == true) {
            card.addView(pill("Play a sound") {
                val sent = runCatching { TrustedLocations.requestFind(fix.fingerprint) }.getOrDefault(false)
                toast(if (sent) "Asked the device to ring." else "The device is not reachable on the mesh.")
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = d(10)
            })
        }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(IosUi.accent(context))
        background = GradientDrawable().apply {
            cornerRadius = d(16).toFloat()
            setColor(IosUi.fill(context))
        }
        setPadding(d(12), d(7), d(12), d(7))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun modeChip(mode: MapServices.TravelMode, onClick: () -> Unit): TextView = TextView(context).apply {
        text = mode.label
        textSize = 12f
        setTextColor(if (mode == routeMode) Color.WHITE else IosUi.accent(context))
        background = GradientDrawable().apply {
            cornerRadius = d(14).toFloat()
            setColor(if (mode == routeMode) IosUi.accent(context) else IosUi.fill(context))
        }
        setPadding(d(12), d(7), d(12), d(7))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ── Directions ─────────────────────────────────────────────────────────

    private fun loadRoute(mode: MapServices.TravelMode) {
        val place = selected ?: return
        routeMode = mode
        directionsOpen = true
        route = null
        executorToast("Finding a ${mode.label.lowercase()} route…")
        ensureLocation { origin ->
            if (origin == null) {
                toast("A current location is needed for directions.")
                return@ensureLocation
            }
            current = origin
            map.current = origin
            work.execute {
                val result = MapServices.route(origin, place.point(), mode)
                handler.post {
                    if (selected?.id != place.id || routeMode != mode) return@post
                    result.onSuccess { found ->
                        route = found
                        map.routePoints = found.points
                        map.current = origin
                        fitRoute(origin, place.point(), found.points)
                        map.invalidate()
                        renderCard()
                    }.onFailure {
                        toast(it.message ?: "No route was found")
                        renderCard()
                    }
                }
            }
        }
    }

    private fun ensureLocation(then: (LatLon?) -> Unit) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!permission) {
            val activity = context as? Activity
            if (activity == null) {
                then(current)
                return
            }
            requestLocationPermission(activity) { granted -> if (granted) ensureLocation(then) else then(null) }
            return
        }
        val known = current ?: runCatching { PrismLocations.latestFix(context) }.getOrNull()?.let {
            LatLon(it.latitude, it.longitude)
        }
        if (known != null) {
            then(known)
            return
        }
        work.execute {
            val fix = runCatching { PrismLocations.freshFix(context) }.getOrNull()
            val point = fix?.let { LatLon(it.latitude, it.longitude) }
            handler.post { then(point) }
        }
    }

    private fun fitRoute(origin: LatLon, destination: LatLon, points: List<LatLon>) {
        val all = points.ifEmpty { listOf(origin, destination) }
        val minLat = all.minOf { it.lat }
        val maxLat = all.maxOf { it.lat }
        val minLon = all.minOf { it.lon }
        val maxLon = all.maxOf { it.lon }
        val spanMetres = MapStore.distanceLabel(LatLon(minLat, minLon), LatLon(maxLat, maxLon))
            .let { text ->
                if (text.endsWith("km")) text.removeSuffix(" km").toDoubleOrNull()?.times(1000) ?: 0.0
                else text.removeSuffix(" m").toDoubleOrNull() ?: 0.0
            }
        val zoom = when {
            spanMetres > 100_000 -> 7
            spanMetres > 40_000 -> 9
            spanMetres > 15_000 -> 11
            spanMetres > 5_000 -> 13
            else -> 15
        }
        map.centerOn((minLat + maxLat) / 2, (minLon + maxLon) / 2, zoom)
    }

    // ── Offline maps ───────────────────────────────────────────────────────

    private fun showDownloadSheet() {
        if (crop != null) return
        sheet?.dismiss()
        val created = MapDownloadSheet(
            context,
            onAdd = { startCrop() },
            onDelete = { area -> deleteOfflineArea(area) },
        )
        sheet = created
        created.show()
    }

    private fun startCrop() {
        sheet?.dismiss()
        sheet = null
        if (crop != null) return
        // The crop is drawn over the map, so a place card left open underneath would show
        // through the dimmed selection as a stale, half-visible card.
        clearPlaceCard()
        handler.removeCallbacks(poiDelay)
        val overlay = MapCropOverlay(
            context,
            map,
            onCancel = { endCrop() },
            onDownload = { bounds, minZoom, maxZoom -> downloadCrop(bounds, minZoom, maxZoom) },
        )
        overlay.onStopDownload = { downloadCancel.set(true) }
        crop = overlay
        mapHost.addView(overlay, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))
        // The crop overlay is full-screen, so it would otherwise sit on top of the zoom buttons
        // and swallow their touches. Lifting them above it keeps the existing controls usable
        // while the area is chosen, and the overlay's estimate is refreshed on every zoom.
        mapHost.bringChildToFront(zoomColumn)
    }

    private fun endCrop() {
        val overlay = crop ?: return
        crop = null
        mapHost.removeView(overlay)
        map.invalidate()
        schedulePois()
        if (isAttachedToWindow) handler.post { showDownloadSheet() }
    }

    private fun downloadCrop(bounds: MapBounds, minZoom: Int, maxZoom: Int) {
        val overlay = crop ?: return
        val tiles = MapStore.tilesForBounds(bounds, minZoom, maxZoom)
        if (tiles.isEmpty()) {
            overlay.finishProgress()
            toast("There are no tiles to save in that selection.")
            endCrop()
            return
        }
        if (tiles.size > MAX_DOWNLOAD_TILES) {
            overlay.finishProgress()
            toast("That area is too large for one download. Pinch in and try again.")
            return
        }

        val centre = bounds.centre
        val radiusKm = (MapGeo.distanceMetres(centre, LatLon(bounds.south, bounds.west)) / 1000.0)
            .toInt().coerceAtLeast(1)
        val area = MapStore.OfflineArea(
            id = "area-${System.currentTimeMillis()}",
            name = "Map ${MapStore.offlineAreas().size + 1}",
            lat = centre.lat,
            lon = centre.lon,
            radiusKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileCount = 0,
            downloaded = 0,
            at = System.currentTimeMillis(),
            bounds = bounds,
        )
        downloadCancel.set(false)
        work.execute {
            var done = 0
            var bytes = 0L
            for ((index, tile) in tiles.withIndex()) {
                if (downloadCancel.get()) break
                if (MapTiles.ensure(tile.first, tile.second, tile.third)) done++
                bytes += MapTiles.bytesOf(tile.first, tile.second, tile.third)
                if (index % 8 == 0) {
                    val current = done
                    handler.post {
                        if (crop === overlay) overlay.updateProgress(current, tiles.size)
                    }
                    // The tile server is a shared volunteer service; do not burst it.
                    runCatching { Thread.sleep(50) }
                }
            }
            val finalDone = done
            val finalBytes = bytes
            val stopped = downloadCancel.get()
            handler.post {
                if (crop !== overlay) return@post
                overlay.finishProgress()
                if (stopped && finalDone == 0) {
                    endCrop()
                    toast("Download stopped")
                    return@post
                }
                MapStore.addOfflineArea(area.copy(
                    tileCount = tiles.size,
                    downloaded = finalDone,
                    bytes = finalBytes,
                ))
                endCrop()
                map.invalidate()
                toast(
                    if (stopped) "Offline map saved partially ($finalDone tiles)"
                    else "Offline map saved ($finalDone tiles)"
                )
            }
        }
    }

    private fun deleteOfflineArea(area: MapStore.OfflineArea) {
        // The record goes first, on the UI thread: reopening the popup immediately must not show a
        // map that is already being deleted. The files are thousands of small deletes and belong
        // on the worker.
        MapStore.removeOfflineArea(area)
        map.invalidate()
        work.execute {
            runCatching {
                MapStore.tilesFor(area).forEach { tile ->
                    MapTiles.delete(tile.first, tile.second, tile.third)
                }
            }
            handler.post { toast("Offline map removed") }
        }
    }

    // ── Small helpers ──────────────────────────────────────────────────────

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = description
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.fill(context))
            }
            imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            setPadding(d(11), d(11), d(11), d(11))
            setOnClickListener { onClick() }
        }

    private fun iconParams(marginStart: Int): LayoutParams =
        LayoutParams(d(42), d(42)).apply {
            leftMargin = marginStart
            rightMargin = d(2)
        }

    private fun cardBackground(radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(IosUi.cardBackground(context))
        cornerRadius = d(radius).toFloat()
    }

    private fun executorToast(message: String) {
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun d(value: Int): Int = IosUi.dp(context, value.toFloat())

    companion object {
        private const val REFRESH_MS = 3_000L
        private const val MAX_DOWNLOAD_TILES = 4_000
        private const val TAG = "PrismMaps"
        private const val POI_MIN_ZOOM = 13
        private const val POI_DEBOUNCE_MS = 650L
        private const val POI_RETRY_MS = 60_000L
        const val REQUEST_LOCATION = 7710

        private var pendingLocation: ((Boolean) -> Unit)? = null

        fun requestLocationPermission(activity: Activity, onResult: (Boolean) -> Unit) {
            pendingLocation = onResult
            androidx.core.app.ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
        }

        /** Called by LauncherActivity after Android returns the permission result. */
        fun onPermissionResult(requestCode: Int, granted: Boolean) {
            if (requestCode != REQUEST_LOCATION) return
            val callback = pendingLocation
            pendingLocation = null
            callback?.invoke(granted)
        }
    }
}
