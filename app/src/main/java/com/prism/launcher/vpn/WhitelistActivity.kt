package com.prism.launcher.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismSettings
import com.prism.launcher.databinding.ActivityBlocklistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistBinding
    private lateinit var adapter: WhitelistAdapter
    private var allApps = listOf<ApplicationInfo>()
    private var whitelisted = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.blocklistToolbar.title = "App Whitelist"
        setSupportActionBar(binding.blocklistToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.blocklistToolbar.setNavigationOnClickListener { finish() }

        // Hide domain-specific UI
        binding.addDomainContainer.visibility = android.view.View.GONE
        binding.importFileBtn.visibility = android.view.View.GONE
        binding.searchDomainInput.hint = "Search apps..."

        adapter = WhitelistAdapter(packageManager) { pkg, isChecked ->
            if (isChecked) whitelisted.add(pkg) else whitelisted.remove(pkg)
            PrismSettings.setAppWhitelist(whitelisted)
        }
        binding.blocklistRecycler.layoutManager = LinearLayoutManager(this)
        binding.blocklistRecycler.adapter = adapter

        whitelisted = PrismSettings.getAppWhitelist().toMutableSet()

        GlobalScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            
            withContext(Dispatchers.Main) {
                allApps = apps
                adapter.submit(allApps, whitelisted)
                binding.blocklistStats.text = "Total apps: ${allApps.size} (${whitelisted.size} bypassing VPN)"
            }
        }
    }
}

class WhitelistAdapter(
    private val pm: PackageManager,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<WhitelistAdapter.VH>() {

    private var items = emptyList<ApplicationInfo>()
    private var allowed = emptySet<String>()

    fun submit(newItems: List<ApplicationInfo>, allowedSet: Set<String>) {
        items = newItems
        allowed = allowedSet
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // Use a simple layout manually or inflate a checkbox layout.
        // For simplicity, we'll create a basic layout programmatically
        val container = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val icon = android.widget.ImageView(parent.context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(96, 96)
        }
        val text = android.widget.TextView(parent.context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(parent.context, com.prism.launcher.R.color.prism_text_primary))
            setPadding(32, 0, 32, 0)
        }
        val check = CheckBox(parent.context)
        
        container.addView(icon)
        container.addView(text)
        container.addView(check)
        return VH(container, icon, text, check)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.text.text = app.loadLabel(pm)
        holder.icon.setImageDrawable(app.loadIcon(pm))
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = allowed.contains(app.packageName)
        holder.check.setOnCheckedChangeListener { _, isChecked -> 
            onToggle(app.packageName, isChecked)
        }
    }

    class VH(
        view: android.view.View,
        val icon: android.widget.ImageView,
        val text: android.widget.TextView,
        val check: CheckBox
    ) : RecyclerView.ViewHolder(view)
}
