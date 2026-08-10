package com.prism.launcher.browser

import com.prism.core.MeshUtils
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.databinding.ActivityP2pHostingBinding
import com.prism.launcher.databinding.ItemP2pHostedSiteBinding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.prism.launcher.R

class P2pHostingActivity : PrismBaseActivity() {

    private lateinit var binding: ActivityP2pHostingBinding
    private lateinit var adapter: P2pHostingAdapter

    private var pendingDomain: String = ""

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            finalizeAddSite(pendingDomain, uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityP2pHostingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hostingToolbar.setNavigationOnClickListener { finish() }
        
        adapter = P2pHostingAdapter(
            onToggle = { site, active ->
                val sites = PrismSettings.getP2pHostedSites().toMutableList()
                sites.find { it.id == site.id }?.isActive = active
                PrismSettings.setP2pHostedSites(sites)
                
                if (!active) {
                    P2pDnsManager.deleteRecord(this, site.domain)
                } else {
                    val myIp = MeshUtils.getLocalMeshIp()
                    P2pDnsManager.updateRecord(this, site.domain, myIp)
                }
            },
            onDelete = { site ->
                PrismDialogFactory.show(this, "Stop Hosting?", "Remove ${site.domain} from your local hosting list?", onPositive = {
                    val sites = PrismSettings.getP2pHostedSites().toMutableList()
                    sites.removeAll { it.id == site.id }
                    PrismSettings.setP2pHostedSites(sites)
                    P2pDnsManager.deleteRecord(this, site.domain)
                    refresh()
                })
            },
            onItemClick = { site, holderBinding ->
                performHealthCheck(site, holderBinding)
            }
        )

        binding.hostingList.layoutManager = LinearLayoutManager(this)
        binding.hostingList.adapter = adapter
        binding.addSiteBtn.setOnClickListener { showAddSiteDialog() }

        intent.getStringExtra("PREFILL_PATH")?.let { path ->
            showAddSiteDialog(path)
        }

        refresh()
    }

    private fun performHealthCheck(site: PrismSettings.P2pHostedSite, itemBinding: ItemP2pHostedSiteBinding) {
        lifecycleScope.launch(Dispatchers.Main) {
            val isRtl = itemBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            
            // 1. Check DNS Presence
            val dnsResolved = withContext(Dispatchers.IO) {
                P2pDnsManager.resolve(site.domain)
            }

            if (dnsResolved != null) {
                // DNS Match: Animate Light Blue
                animateSweep(itemBinding.sweepLayer, getColor(R.color.check_dns_blue), isRtl, true)
            } else {
                // No DNS: Animate Pink R->L and Show Snackbar
                animateSweep(itemBinding.sweepLayer, getColor(R.color.check_no_dns_pink), isRtl, false)
                showPinkSnackbar("${site.domain} is not on Prism P2P DNS.")
                return@launch // Stop here
            }

            // 2. Check Server Liveness
            try {
                withContext(Dispatchers.IO) {
                    val socket = com.prism.launcher.vpn.PrismSocket()
                    socket.connect(java.net.InetSocketAddress(site.domain, 8080), 3000)
                    socket.close()
                }
                
                // Success: Animate Green Left -> Right
                animateSweep(itemBinding.sweepLayer, android.graphics.Color.GREEN, isRtl, true)
            } catch (e: Exception) {
                // Failure: Animate Red Right -> Left
                animateSweep(itemBinding.sweepLayer, android.graphics.Color.RED, isRtl, false)
                
                // Show Red Snackbar with White Text
                val snackbar = com.google.android.material.snackbar.Snackbar.make(
                    binding.root, 
                    "Check Failed: ${e.message ?: "Unknown Error"}", 
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                snackbar.view.setBackgroundColor(android.graphics.Color.RED)
                val textView = snackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                textView.setTextColor(android.graphics.Color.WHITE)
                snackbar.show()
            }
        }
    }

    private fun showPinkSnackbar(message: String) {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )
        snackbar.view.setBackgroundColor(getColor(R.color.check_no_dns_pink))
        val textView = snackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(android.graphics.Color.WHITE)
        snackbar.show()
    }

    private fun animateSweep(view: View, color: Int, isRtl: Boolean, isSuccess: Boolean) {
        view.visibility = View.VISIBLE
        view.setBackgroundColor(color)
        view.alpha = 0.6f
        
        val width = view.width.toFloat()
        
        // Determination of Start/End based on User Request + RTL Logic
        // Success (Green/Blue): L->R. Failure (Red/Pink): R->L (Inverted in RTL)
        // If RTL is enabled: L->R becomes R->L visually.
        
        val moveLTR = (isSuccess && !isRtl) || (!isSuccess && isRtl)
        
        val startX = if (moveLTR) -width else width
        val endX = 0f
        
        view.translationX = startX
        
        view.animate()
            .translationX(endX)
            .setDuration(400)
            .withEndAction {
                view.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { view.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private fun refresh() {
        adapter.submitList(PrismSettings.getP2pHostedSites())
    }

    private fun showAddSiteDialog(prefilledPath: String? = null) {
        // ... (Dialog logic remains same)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val domainInput = EditText(this).apply { 
            hint = "Domain Name (e.g. blog.p2p or site.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(domainInput)

        PrismDialogFactory.show(this, "Host New Website", "Enter the domain name you want to claim on the mesh.", onPositive = {
            val domain = domainInput.text.toString().trim().lowercase()
            if (domain.isNotEmpty()) {
                pendingDomain = domain
                if (prefilledPath != null) finalizeAddSite(domain, prefilledPath)
                else folderPicker.launch(null)
            }
        }, customView = layout)
    }

    private fun finalizeAddSite(domain: String, path: String) {
        val sites = PrismSettings.getP2pHostedSites().toMutableList()
        if (sites.any { it.domain == domain }) {
            Toast.makeText(this, "Domain already hosted locally", Toast.LENGTH_SHORT).show()
            return
        }
        val existingIp = P2pDnsManager.resolve(domain)
        val myIp = MeshUtils.getLocalMeshIp()
        if (existingIp != null && existingIp != myIp) {
            PrismDialogFactory.show(this, "Domain Collision", "This domain is already registered to another peer ($existingIp).")
            return
        }
        sites.add(PrismSettings.P2pHostedSite(domain = domain, localPath = path))
        PrismSettings.setP2pHostedSites(sites)
        refresh()
        P2pDnsManager.updateRecord(this, domain, myIp)
        PrismDialogFactory.show(this, "Website Live!", "Your site $domain is now active.")
    }
}

class P2pHostingAdapter(
    private val onToggle: (PrismSettings.P2pHostedSite, Boolean) -> Unit,
    private val onDelete: (PrismSettings.P2pHostedSite) -> Unit,
    private val onItemClick: (PrismSettings.P2pHostedSite, ItemP2pHostedSiteBinding) -> Unit
) : RecyclerView.Adapter<P2pHostingAdapter.VH>() {

    private var items: List<PrismSettings.P2pHostedSite> = emptyList()

    fun submitList(newItems: List<PrismSettings.P2pHostedSite>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemP2pHostedSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.siteDomain.text = item.domain
        val uri = android.net.Uri.parse(item.localPath)
        val readable = uri.path?.substringAfterLast("/") ?: item.localPath
        holder.binding.sitePath.text = readable
        holder.binding.siteToggle.isChecked = item.isActive
        
        holder.binding.siteToggle.setOnCheckedChangeListener { _, isChecked -> onToggle(item, isChecked) }
        holder.binding.deleteSiteBtn.setOnClickListener { onDelete(item) }
        
        // Health Check Trigger
        holder.binding.root.setOnClickListener {
            onItemClick(item, holder.binding)
        }
    }

    class VH(val binding: ItemP2pHostedSiteBinding) : RecyclerView.ViewHolder(binding.root)
}
