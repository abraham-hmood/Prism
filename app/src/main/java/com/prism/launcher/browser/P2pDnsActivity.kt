package com.prism.launcher.browser

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.R
import com.prism.launcher.PrismSettings
import com.prism.launcher.databinding.ActivityP2pDnsBinding
import com.prism.core.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.*

class P2pDnsActivity : PrismBaseActivity() {

    private lateinit var binding: ActivityP2pDnsBinding
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private val activeServer: PrismSettings.PrismServer?
        get() = PrismSettings.getActiveServer()

    private val isRemoteMode: Boolean
        get() = activeServer != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityP2pDnsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dnsToolbar.setNavigationIcon(R.drawable.ic_back_24)
        binding.dnsToolbar.setNavigationOnClickListener { finish() }

        binding.dnsList.layoutManager = LinearLayoutManager(this)
        
        // Observe DNS Records Flow
        activityScope.launch {
            P2pDnsManager.records.collect { records ->
                refreshList(records)
            }
        }

        // Periodic Mesh Status Update
        activityScope.launch {
            while (isActive) {
                updateMeshStatus()
                delay(5000)
            }
        }

        binding.addDnsBtn.setOnClickListener { showAddDialog() }
        
        // Use the old switch button as a manual Resync trigger
        binding.switchTargetBtn.text = "Resync Mesh"
        binding.switchTargetBtn.setOnClickListener { 
            val best = com.prism.launcher.mesh.PrismMeshService.getBestNode()
            if (best != null) {
                com.prism.launcher.mesh.PrismMeshService.requestSync(best)
                Toast.makeText(this, "Sync request sent to $best", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No active peers found for sync", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun updateMeshStatus() {
        val count = com.prism.launcher.mesh.PrismMeshService.getPeerCount()
        val bestNode = com.prism.launcher.mesh.PrismMeshService.getBestNode() ?: "None"
        binding.targetText.text = "Mesh: $count Peers Online (Best: $bestNode)"
    }

    private fun refreshList(records: Map<String, P2pDnsManager.DnsRecord> = P2pDnsManager.getRecords()) {
        binding.dnsList.adapter = DnsAdapter(records.toList()) { domain ->
            confirmDelete(domain)
        }
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val domainInput = EditText(this).apply { hint = "Domain (e.g. blog.p2p or google.com)" }
        val ipInput = EditText(this).apply { hint = "IP Address" }
        layout.addView(domainInput)
        layout.addView(ipInput)

        PrismDialogFactory.show(this, "Add P2P Mapping", "Enter the domain and target IP.", onPositive = {
            val domain = domainInput.text.toString().trim()
            val ip = ipInput.text.toString().trim()
            if (domain.isNotEmpty() && ip.isNotEmpty()) {
                if (isRemoteMode) sendRemoteCommand("ADD", domain, ip)
                else P2pDnsManager.updateRecord(this, domain, ip)
                refreshList()
            }
        }, customView = layout)
    }

    private fun confirmDelete(domain: String) {
        PrismDialogFactory.show(this, "Delete Mapping?", "Remove $domain from the P2P ledger?", onPositive = {
            if (isRemoteMode) sendRemoteCommand("DEL", domain, null)
            else P2pDnsManager.deleteRecord(this, domain)
            refreshList()
        })
    }

    private fun sendRemoteCommand(action: String, domain: String, ip: String?) {
        val server = activeServer ?: return
        if (action == "ADD" && ip != null) {
            // New Robust Sync: Update locally and broadcast specifically to the "Master" node
            P2pDnsManager.updateRecord(this, domain, ip)
            com.prism.launcher.mesh.PrismMeshService.sendPacket(server.address, server.port, 0x05.toByte(), JSONObject().apply {
                put("domain", domain)
                put("ip", ip)
            }.toString())
            Toast.makeText(this, "Propagated to node: ${server.name} (${server.address})", Toast.LENGTH_SHORT).show()
        } else {
            val payload = JSONObject().apply {
                put("action", action)
                put("domain", domain)
                if (ip != null) put("ip", ip)
            }.toString()
            com.prism.launcher.mesh.PrismMeshService.sendPacket(server.address, server.port, 0x05.toByte(), payload)
        }
    }


    private inner class DnsAdapter(val items: List<Pair<String, P2pDnsManager.DnsRecord>>, val onDelete: (String) -> Unit) : RecyclerView.Adapter<DnsVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsVH {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return DnsVH(view)
        }
        override fun onBindViewHolder(holder: DnsVH, position: Int) {
            val (domain, record) = items[position]
            holder.t1.text = domain
            holder.t1.setTextColor(if (record.isVerified) PrismSettings.getGlowColor() else androidx.core.content.ContextCompat.getColor(this@P2pDnsActivity, R.color.prism_text_primary))
            holder.t2.text = "${record.ip} • ${if (record.isVerified) "Verified" else "Auto-Seeded"}"
            holder.view.setOnLongClickListener {
                onDelete(domain)
                true
            }
        }
        override fun getItemCount() = items.size
    }

    private class DnsVH(val view: View) : RecyclerView.ViewHolder(view) {
        val t1 = view.findViewById<TextView>(android.R.id.text1)
        val t2 = view.findViewById<TextView>(android.R.id.text2)
    }
}
