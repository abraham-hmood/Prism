package com.prism.launcher.accesspoint

import com.prism.launcher.accesspoint.AccessPointStore
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.launch
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.view.LayoutInflater
import android.view.ViewGroup

/**
 * Main Portal Activity for managing Prism Access Points.
 * Allows users to create, manage, and monitor WiFi/Bluetooth hotspots
 * that route traffic through P2P VPN with exclusive P2P DNS usage.
 */
class AccessPointPortalActivity : PrismBaseActivity() {

    private lateinit var accessPointManager: AccessPointManager
    private lateinit var dnsOverrideService: DnsOverrideService
    private lateinit var networkIsolationEngine: NetworkIsolationEngine
    
    private lateinit var accessPointsList: RecyclerView
    private lateinit var createAccessPointBtn: Button
    private lateinit var statsContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var loadingProgress: ProgressBar

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            initializeAccessPointSystem()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize components
        accessPointManager = AccessPointManager(this)
        dnsOverrideService = DnsOverrideService(this)
        networkIsolationEngine = NetworkIsolationEngine(this)

        setupUI()
        checkPermissions()
    }

    /**
     * Setup the main UI layout.
     */
    private fun setupUI() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "Prism Access Points Portal"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // Status
        statusText = TextView(this).apply {
            text = "Initializing..."
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // Loading progress
        loadingProgress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // Create Access Point Button
        createAccessPointBtn = Button(this).apply {
            text = "Create New Access Point"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener {
                startActivity(Intent(this@AccessPointPortalActivity, CreateAccessPointActivity::class.java))
            }
        }

        // Stats Container
        statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // Access Points List
        accessPointsList = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(this@AccessPointPortalActivity)
            adapter = AccessPointsAdapter(emptyList())
        }

        // Add all views to container
        mainContainer.addView(titleText)
        mainContainer.addView(statusText)
        mainContainer.addView(loadingProgress)
        mainContainer.addView(createAccessPointBtn)
        mainContainer.addView(statsContainer)
        mainContainer.addView(accessPointsList)

        scrollView.addView(mainContainer)
        setContentView(scrollView)
    }

    /**
     * Check and request necessary permissions.
     */
    private fun checkPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        // Add Bluetooth permissions if supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // NEARBY_WIFI_DEVICES replaces location for hotspot on API 33+; both needed on 29-32
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initializeAccessPointSystem()
        }
    }

    /**
     * Initialize the access point system after permissions are granted.
     */
    private fun initializeAccessPointSystem() {
        lifecycleScope.launch {
            try {
                statusText.text = "Initializing Access Point System..."
                
                // Load existing access points from storage
                val existingAps = AccessPointStore.getAccessPoints()
                
                // Update UI
                updateAccessPointsList(existingAps)
                updateStatsDisplay(existingAps)
                
                statusText.text = "Ready - ${existingAps.size} Access Points configured"
                loadingProgress.visibility = android.view.View.GONE
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    /**
     * Update the access points list UI.
     */
    private fun updateAccessPointsList(accessPoints: List<AccessPointConfig>) {
        accessPointsList.adapter = AccessPointsAdapter(accessPoints) { ap, action ->
            when (action) {
                "start" -> startAccessPoint(ap)
                "stop" -> stopAccessPoint(ap)
                "edit" -> editAccessPoint(ap)
                "delete" -> deleteAccessPoint(ap)
                "devices" -> viewConnectedDevices(ap)
                "stats" -> viewAccessPointStats(ap)
                else -> {}
            }
        }
    }

    /**
     * Update statistics display.
     */
    private fun updateStatsDisplay(accessPoints: List<AccessPointConfig>) {
        statsContainer.removeAllViews()
        
        val totalAps = accessPoints.size
        val activeAps = accessPoints.count { it.isActive }
        
        val statsText = TextView(this).apply {
            text = """
                Total Access Points: $totalAps
                Active: $activeAps
                Network Range: 10.8.0.0/24
            """.trimIndent()
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        
        statsContainer.addView(statsText)
    }

    /**
     * Start an access point.
     */
    private fun startAccessPoint(ap: AccessPointConfig) {
        lifecycleScope.launch {
            try {
                statusText.text = "Starting ${ap.ssidName}..."
                loadingProgress.visibility = android.view.View.VISIBLE
                val result = accessPointManager.startAccessPoint(ap)

                if (result.isSuccess) {
                    dnsOverrideService.startDnsServer(ap.gatewayIp)
                    networkIsolationEngine.applyIsolationRules(
                        ap.id, ap.gatewayIp, "10.8.0.1", ap.ipRange
                    )
                    val dnsPort = DnsOverrideService.boundPort
                    val dnsNote = if (dnsPort == 53) "" else
                        "\nDNS on port $dnsPort — set manually on connected devices"
                    statusText.text = "✓ ${result.getOrDefault("${ap.ssidName} started")}$dnsNote"
                } else {
                    statusText.text = "✗ ${result.exceptionOrNull()?.message ?: "Failed to start ${ap.ssidName}"}"
                }
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            } finally {
                loadingProgress.visibility = android.view.View.GONE
                initializeAccessPointSystem()
            }
        }
    }

    /**
     * Stop an access point.
     */
    private fun stopAccessPoint(ap: AccessPointConfig) {
        lifecycleScope.launch {
            try {
                loadingProgress.visibility = android.view.View.VISIBLE
                val result = accessPointManager.stopAccessPoint(ap.id)
                statusText.text = if (result.isSuccess) {
                    "✓ ${ap.ssidName} stopped"
                } else {
                    "✗ Failed to stop ${ap.ssidName}: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            } finally {
                loadingProgress.visibility = android.view.View.GONE
                initializeAccessPointSystem()
            }
        }
    }

    /**
     * Edit an access point configuration.
     */
    private fun editAccessPoint(ap: AccessPointConfig) {
        val intent = Intent(this, CreateAccessPointActivity::class.java).apply {
            putExtra("accessPointId", ap.id)
        }
        startActivity(intent)
    }

    /**
     * Delete an access point.
     */
    private fun deleteAccessPoint(ap: AccessPointConfig) {
        lifecycleScope.launch {
            try {
                accessPointManager.stopAccessPoint(ap.id)
                AccessPointStore.removeAccessPoint(ap.id)
                statusText.text = "✓ Access Point deleted"
                initializeAccessPointSystem()
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    /**
     * View connected devices for an access point.
     */
    private fun viewConnectedDevices(ap: AccessPointConfig) {
        val intent = Intent(this, ConnectedDevicesActivity::class.java).apply {
            putExtra("accessPointId", ap.id)
            putExtra("accessPointName", ap.ssidName)
        }
        startActivity(intent)
    }

    /**
     * View statistics for an access point.
     */
    private fun viewAccessPointStats(ap: AccessPointConfig) {
        val stats = accessPointManager.getAccessPointStats(ap.id)
        if (stats != null) {
            val intent = Intent(this, AccessPointStatsActivity::class.java).apply {
                putExtra("stats", stats.toJson().toString())
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        initializeAccessPointSystem()
    }
}

/**
 * Adapter for displaying access points in RecyclerView.
 */
class AccessPointsAdapter(
    private val accessPoints: List<AccessPointConfig>,
    private val onAction: ((AccessPointConfig, String) -> Unit)? = null
) : RecyclerView.Adapter<AccessPointViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccessPointViewHolder {
        val view = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }
        return AccessPointViewHolder(view, onAction)
    }

    override fun onBindViewHolder(holder: AccessPointViewHolder, position: Int) {
        holder.bind(accessPoints[position])
    }

    override fun getItemCount(): Int = accessPoints.size
}

class AccessPointViewHolder(
    private val itemView: LinearLayout,
    private val onAction: ((AccessPointConfig, String) -> Unit)?
) : RecyclerView.ViewHolder(itemView) {

    fun bind(ap: AccessPointConfig) {
        itemView.removeAllViews()

        val broadcastName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            "DIRECT-${ap.ssidName}" else ap.ssidName

        val titleText = TextView(itemView.context).apply {
            text = "${ap.ssidName}  ${if (ap.isActive) "● ACTIVE" else "○ INACTIVE"}"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        val detailsText = TextView(itemView.context).apply {
            text = buildString {
                append("Broadcasts as: $broadcastName")
                append("  |  Auth: ${ap.authType}")
                append("  |  Network: ${ap.ipRange}")
                if (ap.isActive) append("\nOther devices: scan WiFi and connect to \"$broadcastName\"")
            }
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }

        // Buttons Container
        val buttonsContainer = LinearLayout(itemView.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Start/Stop Button
        val toggleBtn = Button(itemView.context).apply {
            text = if (ap.isActive) "Stop" else "Start"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
            setOnClickListener { onAction?.invoke(ap, if (ap.isActive) "stop" else "start") }
        }

        // Edit Button
        val editBtn = Button(itemView.context).apply {
            text = "Edit"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
            setOnClickListener { onAction?.invoke(ap, "edit") }
        }

        // Devices Button
        val devicesBtn = Button(itemView.context).apply {
            text = "Devices"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
            setOnClickListener { onAction?.invoke(ap, "devices") }
        }

        // Stats Button
        val statsBtn = Button(itemView.context).apply {
            text = "Stats"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onAction?.invoke(ap, "stats") }
        }

        buttonsContainer.addView(toggleBtn)
        buttonsContainer.addView(editBtn)
        buttonsContainer.addView(devicesBtn)
        buttonsContainer.addView(statsBtn)

        itemView.addView(titleText)
        itemView.addView(detailsText)
        itemView.addView(buttonsContainer)
    }
}
