package com.prism.launcher

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.browser.PrismBlocklist
import com.prism.launcher.databinding.ActivityBlocklistBinding
import com.prism.launcher.databinding.ItemBlocklistDomainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlocklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistBinding
    private lateinit var adapter: DomainAdapter
    private var allDomains = listOf<String>()
    private var customDomains = setOf<String>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val hosts = mutableSetOf<String>()
                contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        val clean = line.trim()
                        if (clean.isEmpty() || clean.startsWith("#")) continue
                        // Handle standard hosts format "0.0.0.0 domain.com" or just "domain.com"
                        val parts = clean.split(Regex("\\s+"))
                        val target = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                            parts[1]
                        } else {
                            parts[0]
                        }
                        if (target.isNotEmpty() && target != "localhost") {
                            hosts.add(target)
                        }
                    }
                }
                if (hosts.isNotEmpty()) {
                    PrismBlocklist.get().mergeCustomDomains(hosts)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BlocklistActivity, "Imported ${hosts.size} domains", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BlocklistActivity, "Failed to parse file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.blocklistToolbar.title = "Blocklist"
        setSupportActionBar(binding.blocklistToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.blocklistToolbar.setNavigationOnClickListener { finish() }

        adapter = DomainAdapter(this::onRemoveClicked)
        binding.blocklistRecycler.layoutManager = LinearLayoutManager(this)
        binding.blocklistRecycler.adapter = adapter
        binding.blocklistRecycler.setHasFixedSize(true)

        binding.addDomainBtn.setOnClickListener {
            val domain = binding.addDomainInput.text.toString().trim()
            if (domain.isNotEmpty()) {
                PrismBlocklist.get().addCustomDomain(domain)
                binding.addDomainInput.text.clear()
                refreshList()
                Toast.makeText(this, "Added $domain", Toast.LENGTH_SHORT).show()
            }
        }

        binding.importFileBtn.setOnClickListener {
            importLauncher.launch("text/*")
        }

        binding.searchDomainInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshList()
    }

    private fun refreshList() {
        val store = PrismBlocklist.get()
        allDomains = store.snapshotAllDomains()
        customDomains = store.snapshotCustomDomains()
        binding.blocklistStats.text = "Total blocked: ${allDomains.size} (${customDomains.size} custom)"
        filterList(binding.searchDomainInput.text.toString())
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allDomains
        } else {
            allDomains.filter { it.contains(q) }
        }
        // Custom domains get an active remove button. Built-in domains get hidden remove buttons.
        adapter.submit(filtered, customDomains)
    }

    private fun onRemoveClicked(domain: String) {
        val clean = domain.removePrefix("*.")
        PrismBlocklist.get().removeCustomDomain(clean)
        refreshList()
    }
}

class DomainAdapter(
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<DomainAdapter.VH>() {

    private var items = emptyList<String>()
    private var customSet = emptySet<String>()

    fun submit(newItems: List<String>, custom: Set<String>) {
        items = newItems
        customSet = custom
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBlocklistDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val domain = items[position]
        holder.binding.domainText.text = domain
        val isCustom = customSet.contains(domain.removePrefix("*."))
        
        holder.binding.removeDomainBtn.visibility = android.view.View.VISIBLE
        holder.binding.removeDomainBtn.setOnClickListener { onRemove(domain) }
        
        if (isCustom) {
            holder.binding.domainText.setTextColor(androidx.core.content.ContextCompat.getColor(holder.binding.domainText.context, R.color.prism_text_primary))
        } else {
            holder.binding.domainText.setTextColor(androidx.core.content.ContextCompat.getColor(holder.binding.domainText.context, R.color.prism_text_muted))
        }
    }

    class VH(val binding: ItemBlocklistDomainBinding) : RecyclerView.ViewHolder(binding.root)
}
