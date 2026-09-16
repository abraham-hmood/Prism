package com.prism.launcher.accesspoint

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import kotlinx.coroutines.launch

/**
 * Activity to view and manage devices connected to an access point.
 */
class ConnectedDevicesActivity : PrismBaseActivity() {

    private var accessPointId: Long = 0
    private var accessPointName: String = ""
    
    private lateinit var devicesList: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var networkIsolationEngine: NetworkIsolationEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        accessPointId = intent.getLongExtra("accessPointId", 0)
        accessPointName = intent.getStringExtra("accessPointName") ?: "Unknown"
        networkIsolationEngine = NetworkIsolationEngine(this)

        setupUI()
        loadConnectedDevices()
    }

    private fun setupUI() {
        val scrollView = ScrollView(this)
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }

        val titleText = TextView(this).apply {
            text = "Connected Devices - $accessPointName"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        statusText = TextView(this).apply {
            text = "Loading devices..."
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        devicesList = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(this@ConnectedDevicesActivity)
            adapter = ConnectedDevicesAdapter(emptyList()) { device, action ->
                when (action) {
                    "block" -> blockDevice(device)
                    "unblock" -> unblockDevice(device)
                    "limit" -> limitBandwidth(device)
                    "disconnect" -> disconnectDevice(device)
                    else -> {}
                }
            }
        }

        mainContainer.addView(titleText)
        mainContainer.addView(statusText)
        mainContainer.addView(devicesList)
        scrollView.addView(mainContainer)
        setContentView(scrollView)
    }

    private fun loadConnectedDevices() {
        lifecycleScope.launch {
            try {
                // In a real implementation, fetch devices from AccessPointManager
                val devices = emptyList<ConnectedDevice>()
                statusText.text = "Connected Devices: ${devices.size}"
                devicesList.adapter = ConnectedDevicesAdapter(devices) { device, action ->
                    when (action) {
                        "block" -> blockDevice(device)
                        "unblock" -> unblockDevice(device)
                        "limit" -> limitBandwidth(device)
                        "disconnect" -> disconnectDevice(device)
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    private fun blockDevice(device: ConnectedDevice) {
        lifecycleScope.launch {
            val result = networkIsolationEngine.blockDevice(accessPointId, device.ipAddress)
            if (result.isSuccess) {
                statusText.text = "✓ Device ${device.deviceName} blocked"
            }
        }
    }

    private fun unblockDevice(device: ConnectedDevice) {
        lifecycleScope.launch {
            val result = networkIsolationEngine.unblockDevice(accessPointId, device.ipAddress)
            if (result.isSuccess) {
                statusText.text = "✓ Device ${device.deviceName} unblocked"
            }
        }
    }

    private fun limitBandwidth(device: ConnectedDevice) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(20, 20, 20, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Limit Bandwidth")
            .setMessage("Enter bandwidth limit in Kbps (0 = unlimited)")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val bandwidthKbps = input.text.toString().toIntOrNull() ?: 0
                lifecycleScope.launch {
                    if (bandwidthKbps > 0) {
                        val result = networkIsolationEngine.limitDeviceBandwidth(
                            accessPointId,
                            device.ipAddress,
                            bandwidthKbps
                        )
                        if (result.isSuccess) {
                            statusText.text = "✓ Bandwidth limit applied"
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disconnectDevice(device: ConnectedDevice) {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Device")
            .setMessage("Disconnect ${device.deviceName}?")
            .setPositiveButton("Yes") { _, _ -> blockDevice(device) }
            .setNegativeButton("No", null)
            .show()
    }
}

class ConnectedDevicesAdapter(
    private val devices: List<ConnectedDevice>,
    private val onAction: ((ConnectedDevice, String) -> Unit)? = null
) : RecyclerView.Adapter<ConnectedDeviceViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ConnectedDeviceViewHolder {
        val view = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }
        return ConnectedDeviceViewHolder(view, onAction)
    }

    override fun onBindViewHolder(holder: ConnectedDeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size
}

class ConnectedDeviceViewHolder(
    private val itemView: LinearLayout,
    private val onAction: ((ConnectedDevice, String) -> Unit)?
) : RecyclerView.ViewHolder(itemView) {

    fun bind(device: ConnectedDevice) {
        itemView.removeAllViews()

        val nameText = TextView(itemView.context).apply {
            text = device.deviceName
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }

        val detailsText = TextView(itemView.context).apply {
            text = """
                IP: ${device.ipAddress}
                MAC: ${device.macAddress}
                Connected: ${android.text.format.DateFormat.format("HH:mm:ss", device.connectedAt)}
                Data Used: ${device.dataUsed / 1024}KB
            """.trimIndent()
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }

        val buttonsContainer = LinearLayout(itemView.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val blockBtn = Button(itemView.context).apply {
            text = if (device.isBlocked) "Unblock" else "Block"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
            setOnClickListener { onAction?.invoke(device, if (device.isBlocked) "unblock" else "block") }
        }

        val limitBtn = Button(itemView.context).apply {
            text = "Limit"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 }
            setOnClickListener { onAction?.invoke(device, "limit") }
        }

        val disconnectBtn = Button(itemView.context).apply {
            text = "Disconnect"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onAction?.invoke(device, "disconnect") }
        }

        buttonsContainer.addView(blockBtn)
        buttonsContainer.addView(limitBtn)
        buttonsContainer.addView(disconnectBtn)

        itemView.addView(nameText)
        itemView.addView(detailsText)
        itemView.addView(buttonsContainer)
    }
}
