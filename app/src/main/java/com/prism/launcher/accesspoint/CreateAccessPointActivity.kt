package com.prism.launcher.accesspoint

import com.prism.launcher.accesspoint.AccessPointStore
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.launch
import com.prism.core.json.JSONObject

/**
 * Activity for creating and editing Prism Access Points.
 */
class CreateAccessPointActivity : PrismBaseActivity() {

    private var editingApId: Long = 0
    private lateinit var ssidInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var maxConnectionsInput: EditText
    private lateinit var networkTypeSpinner: Spinner
    private lateinit var authTypeSpinner: Spinner
    private lateinit var ipRangeInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var saveBtn: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        editingApId = intent.getLongExtra("accessPointId", 0)
        setupUI()
        
        if (editingApId > 0) {
            loadAccessPointData(editingApId)
        }
    }

    private fun setupUI() {
        val scrollView = ScrollView(this)
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val titleText = TextView(this).apply {
            text = if (editingApId > 0) "Edit Access Point" else "Create Access Point"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // SSID Input
        mainContainer.addView(TextView(this).apply { text = "SSID Name (broadcasts as DIRECT-<name>)"; textSize = 14f })
        ssidInput = EditText(this).apply {
            hint = "Enter network name (max 24 chars)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(ssidInput)

        // Network Type
        mainContainer.addView(TextView(this).apply { text = "Network Type"; textSize = 14f })
        networkTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CreateAccessPointActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("WiFi", "Bluetooth", "Hotspot")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(networkTypeSpinner)

        // Auth Type
        mainContainer.addView(TextView(this).apply { text = "Authentication"; textSize = 14f })
        authTypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CreateAccessPointActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("Open", "WPA2", "WPA3")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setSelection(2) // Default to WPA3
        }
        mainContainer.addView(authTypeSpinner)

        // Password Input
        mainContainer.addView(TextView(this).apply { text = "Password (8+ characters)"; textSize = 14f })
        passwordInput = EditText(this).apply {
            hint = "Enter password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(passwordInput)

        // Max Connections
        mainContainer.addView(TextView(this).apply { text = "Max Connections"; textSize = 14f })
        maxConnectionsInput = EditText(this).apply {
            setText("10")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(maxConnectionsInput)

        // IP Range
        mainContainer.addView(TextView(this).apply { text = "IP Range"; textSize = 14f })
        ipRangeInput = EditText(this).apply {
            setText("10.8.0.0/24")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(ipRangeInput)

        // Description
        mainContainer.addView(TextView(this).apply { text = "Description"; textSize = 14f })
        descriptionInput = EditText(this).apply {
            hint = "Enter description (optional)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(descriptionInput)

        statusText = TextView(this).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        mainContainer.addView(statusText)

        // Save Button
        saveBtn = Button(this).apply {
            text = if (editingApId > 0) "Update" else "Create"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { saveAccessPoint() }
        }
        mainContainer.addView(saveBtn)

        mainContainer.addView(titleText, 0)
        scrollView.addView(mainContainer)
        setContentView(scrollView)
    }

    private fun loadAccessPointData(apId: Long) {
        lifecycleScope.launch {
            try {
                val aps = AccessPointStore.getAccessPoints()
                val ap = aps.find { it.id == apId }
                
                if (ap != null) {
                    ssidInput.setText(ap.ssidName)
                    passwordInput.setText(ap.password)
                    maxConnectionsInput.setText(ap.maxConnections.toString())
                    networkTypeSpinner.setSelection(ap.networkType.ordinal)
                    authTypeSpinner.setSelection(ap.authType.ordinal)
                    ipRangeInput.setText(ap.ipRange)
                    descriptionInput.setText(ap.description)
                }
            } catch (e: Exception) {
                statusText.text = "Error loading data: ${e.message}"
            }
        }
    }

    private fun saveAccessPoint() {
        try {
            val ssid = ssidInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val networkType = NetworkType.values()[networkTypeSpinner.selectedItemPosition]
            val authType = AuthType.values()[authTypeSpinner.selectedItemPosition]
            val maxConnections = maxConnectionsInput.text.toString().toIntOrNull() ?: 10
            val ipRange = ipRangeInput.text.toString()
            val description = descriptionInput.text.toString()

            // Validation
            if (ssid.isEmpty() || ssid.length > 24) {
                statusText.text = "✗ SSID must be 1-24 characters (broadcasts as DIRECT-<name>)"
                return
            }

            if (authType != AuthType.OPEN && password.length < 8) {
                statusText.text = "✗ Password must be at least 8 characters"
                return
            }

            val ap = AccessPointConfig(
                id = editingApId,
                ssidName = ssid,
                password = password,
                networkType = networkType,
                authType = authType,
                maxConnections = maxConnections,
                ipRange = ipRange,
                description = description
            )

            lifecycleScope.launch {
                try {
                    AccessPointStore.saveAccessPoint(ap)
                    statusText.text = "✓ Access Point saved successfully"
                    
                    // Return to portal
                    finish()
                } catch (e: Exception) {
                    statusText.text = "✗ Error saving: ${e.message}"
                }
            }
        } catch (e: Exception) {
            statusText.text = "✗ Validation error: ${e.message}"
        }
    }
}

/**
 * Activity to view access point statistics.
 */
class AccessPointStatsActivity : PrismBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val statsJson = intent.getStringExtra("stats") ?: return
        setupUI(statsJson)
    }

    private fun setupUI(statsJson: String) {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val titleText = TextView(this).apply {
            text = "Access Point Statistics"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        try {
            val stats = JSONObject(statsJson)
            val statsText = TextView(this).apply {
                text = """
                    ID: ${stats.optLong("apId")}
                    Total Connections: ${stats.optLong("totalConns")}
                    Current Devices: ${stats.optInt("currentDevices")}
                    Data Transferred: ${stats.optLong("dataTransferred")} bytes
                    Avg Latency: ${stats.optLong("latency")}ms
                    Uptime: ${stats.optLong("uptime")}s
                """.trimIndent()
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            container.addView(titleText)
            container.addView(statsText)
        } catch (e: Exception) {
            container.addView(TextView(this).apply { text = "Error: ${e.message}" })
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }
}
