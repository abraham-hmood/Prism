package com.prism.launcher.agentic

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.ItemAgenticToolBinding
import com.prism.launcher.databinding.ItemCloudModelBinding
import com.prism.launcher.databinding.ItemSettingHeaderBinding
import com.prism.launcher.databinding.PageAgenticToolsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.prism.core.json.JSONObject

/**
 * Desktop page for managing agentic tool-calling: built-in Prism tools, user-imported HTTP
 * tools, and imported "syntaxes" (custom prompt-injection + tool-call-extraction formats for
 * models without native structured tool-calling). See [AgenticEngine] for how these actually get
 * used during a chat turn.
 */
class AgenticToolsPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = PageAgenticToolsBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter = AgenticToolsAdapter(
        onImportedToolClick = { showTestToolDialog(it.name, it.description, it.parametersJson) { args -> AgenticToolExecutor.execute(it.toDefinition(), args) } },
        onImportedToolLongClick = { showImportToolDialog(it) },
        onImportedToolToggle = { entity, enabled -> toggleTool(entity, enabled) },
        onBuiltinToolClick = { showTestToolDialog(it.name, it.description, it.parametersSchema.toString()) { args -> AgenticToolExecutor.execute(it, args) } },
        onSyntaxClick = { showImportSyntaxDialog(it) }
    )

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        binding.agenticRecycler.layoutManager = LinearLayoutManager(context)
        binding.agenticRecycler.adapter = adapter
        // This page is itself a page inside the desktop ViewPager2, which is backed by its own
        // internal RecyclerView -- nesting another RecyclerView inside it is a known source of a
        // tap-with-tiny-jitter getting grabbed as scroll input by the outer pager before this
        // list's own click detection gets a clean shot at it. This list has no legitimate reason
        // to hand scroll/fling momentum up to an ancestor anyway (different axis entirely).
        binding.agenticRecycler.isNestedScrollingEnabled = false

        binding.agenticEnabledSwitch.isChecked = PrismSettings.getAgenticToolsEnabled()
        binding.agenticEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            PrismSettings.setAgenticToolsEnabled(checked)
        }

        binding.agenticAddBtn.setOnClickListener { showAddPopup() }

        refreshList()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) refreshList()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
    }

    private fun dao() = AppDatabase.get().agenticDao()

    private fun refreshList() {
        viewScope.launch {
            val tools = withContext(Dispatchers.IO) { dao().getAllTools() }
            val syntaxes = withContext(Dispatchers.IO) { dao().getAllSyntaxes() }
            val activeSyntaxId = PrismSettings.getActiveAgenticSyntaxId()

            val items = mutableListOf<AgenticListItem>()
            items.add(AgenticListItem.Header("Built-in Tools"))
            AgenticBuiltinTools.ALL.forEach { items.add(AgenticListItem.BuiltinTool(it)) }

            items.add(AgenticListItem.Header("Imported Tools"))
            tools.forEach { items.add(AgenticListItem.ImportedTool(it)) }

            items.add(AgenticListItem.Header("Imported Syntaxes"))
            syntaxes.forEach { items.add(AgenticListItem.SyntaxRow(it, it.id == activeSyntaxId)) }

            adapter.setItems(items)
            binding.agenticEmptyState.visibility = if (tools.isEmpty() && syntaxes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun toggleTool(entity: AgenticToolEntity, enabled: Boolean) {
        viewScope.launch {
            withContext(Dispatchers.IO) { dao().upsertTool(entity.copy(enabled = enabled)) }
        }
    }

    // ── "+" popup ────────────────────────────────────────────────────────────

    private fun showAddPopup() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun optionButton(text: String, onClick: () -> Unit) = com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            }
            setOnClickListener { onClick() }
        }

        var dialogRef: androidx.appcompat.app.AlertDialog? = null
        layout.addView(optionButton("Import a Tool") { dialogRef?.dismiss(); showImportToolDialog(null) })
        layout.addView(optionButton("Import Syntax") { dialogRef?.dismiss(); showImportSyntaxDialog(null) })

        dialogRef = PrismDialogFactory.show(
            context, "Add to Agentic Tools", "",
            positiveText = null, negativeText = "Cancel",
            customView = layout
        )
    }

    // ── Import / edit a tool (raw JSON) ─────────────────────────────────────

    private fun showImportToolDialog(existing: AgenticToolEntity?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val hint = existing?.let { toolToJson(it) } ?: EXAMPLE_TOOL_JSON
        val editor = jsonEditor(hint)
        layout.addView(sectionLabel(if (existing == null) "Tool definition JSON" else "Edit tool JSON"))
        layout.addView(editor)

        var deleteBtn: com.google.android.material.button.MaterialButton? = null
        if (existing != null) {
            deleteBtn = destructiveButton("Delete Tool")
            layout.addView(deleteBtn)
        }

        val dialog = PrismDialogFactory.show(
            context, if (existing == null) "Import a Tool" else "Edit Tool", "",
            positiveText = "Save",
            onPositive = {
                try {
                    val json = JSONObject(editor.text.toString())
                    val name = json.getString("name").trim()
                    val url = json.getString("url").trim()
                    if (name.isEmpty() || url.isEmpty()) throw IllegalArgumentException("name and url are required")
                    val entity = AgenticToolEntity(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        description = json.optString("description", ""),
                        parametersJson = (json.optJSONObject("parameters") ?: JSONObject("{\"type\":\"object\",\"properties\":{}}")).toString(),
                        httpMethod = json.optString("method", "GET").ifBlank { "GET" },
                        httpUrl = url,
                        httpHeadersJson = (json.optJSONObject("headers") ?: JSONObject()).toString(),
                        httpBodyTemplate = json.optString("body", ""),
                        enabled = existing?.enabled ?: true
                    )
                    viewScope.launch {
                        withContext(Dispatchers.IO) { dao().upsertTool(entity) }
                        refreshList()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid tool JSON: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            customView = layout
        )

        deleteBtn?.setOnClickListener {
            viewScope.launch {
                withContext(Dispatchers.IO) { dao().deleteTool(existing!!.id) }
                refreshList()
            }
            dialog.dismiss()
        }
    }

    private fun toolToJson(entity: AgenticToolEntity): String {
        return JSONObject()
            .put("name", entity.name)
            .put("description", entity.description)
            .put("parameters", JSONObject(entity.parametersJson))
            .put("method", entity.httpMethod)
            .put("url", entity.httpUrl)
            .put("headers", JSONObject(entity.httpHeadersJson))
            .put("body", entity.httpBodyTemplate)
            .toString(2)
    }

    // ── Import / edit a syntax (raw JSON) ───────────────────────────────────

    private fun showImportSyntaxDialog(existing: AgenticSyntaxEntity?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val hint = existing?.let { syntaxToJson(it) } ?: EXAMPLE_SYNTAX_JSON
        val editor = jsonEditor(hint)
        layout.addView(sectionLabel(if (existing == null) "Syntax definition JSON" else "Edit syntax JSON"))
        layout.addView(editor)

        val activeSwitch = android.widget.Switch(context).apply {
            text = "Use this syntax for tool-calling"
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
            isChecked = existing != null && PrismSettings.getActiveAgenticSyntaxId() == existing?.id
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        layout.addView(activeSwitch)

        var deleteBtn: com.google.android.material.button.MaterialButton? = null
        if (existing != null) {
            deleteBtn = destructiveButton("Delete Syntax")
            layout.addView(deleteBtn)
        }

        val dialog = PrismDialogFactory.show(
            context, if (existing == null) "Import Syntax" else "Edit Syntax", "",
            positiveText = "Save",
            onPositive = {
                try {
                    val json = JSONObject(editor.text.toString())
                    val name = json.getString("name").trim()
                    val systemPromptTemplate = json.getString("systemPromptTemplate")
                    val toolFormatTemplate = json.getString("toolFormatTemplate")
                    val callExtractionRegex = json.getString("callExtractionRegex")
                    if (name.isEmpty()) throw IllegalArgumentException("name is required")
                    Regex(callExtractionRegex) // validate it compiles before saving

                    val entity = AgenticSyntaxEntity(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        systemPromptTemplate = systemPromptTemplate,
                        toolFormatTemplate = toolFormatTemplate,
                        callExtractionRegex = callExtractionRegex
                    )
                    viewScope.launch {
                        withContext(Dispatchers.IO) { dao().upsertSyntax(entity) }
                        if (activeSwitch.isChecked) {
                            PrismSettings.setActiveAgenticSyntaxId(entity.id)
                        } else if (PrismSettings.getActiveAgenticSyntaxId() == entity.id) {
                            PrismSettings.setActiveAgenticSyntaxId(null)
                        }
                        refreshList()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid syntax JSON: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            customView = layout
        )

        deleteBtn?.setOnClickListener {
            val id = existing!!.id
            viewScope.launch {
                withContext(Dispatchers.IO) { dao().deleteSyntax(id) }
                if (PrismSettings.getActiveAgenticSyntaxId() == id) {
                    PrismSettings.setActiveAgenticSyntaxId(null)
                }
                refreshList()
            }
            dialog.dismiss()
        }
    }

    private fun syntaxToJson(entity: AgenticSyntaxEntity): String {
        return JSONObject()
            .put("name", entity.name)
            .put("systemPromptTemplate", entity.systemPromptTemplate)
            .put("toolFormatTemplate", entity.toolFormatTemplate)
            .put("callExtractionRegex", entity.callExtractionRegex)
            .toString(2)
    }

    // ── Test a tool ──────────────────────────────────────────────────────────

    /** Builds a small form from [parametersJson]'s schema properties. Run stays on this one
     * dialog and shows the result in place (truncated to [TEST_RESULT_PREVIEW_CHARS] chars) rather
     * than closing and popping a second dialog once the call completes -- the old two-dialog
     * handoff was the actual bug behind "Run just closes the dialog and nothing happens": the
     * positive button always auto-dismisses on click (that's built into AlertDialog), so the
     * follow-up "Result" dialog only ever appeared if the background call survived long enough
     * to still be running against a live view -- e.g. it silently never showed if the user
     * swiped to another desktop page mid-call, since that cancels [viewScope]. */
    private fun showTestToolDialog(name: String, description: String, parametersJson: String, execute: suspend (String) -> String) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (description.isNotBlank()) layout.addView(sectionLabel(description))

        val schema = try { JSONObject(parametersJson) } catch (e: Exception) { JSONObject() }
        val properties = schema.optJSONObject("properties")
        val inputs = mutableMapOf<String, EditText>()

        properties?.keys()?.forEach { key ->
            val propSchema = properties.optJSONObject(key)
            val propDesc = propSchema?.optString("description", "") ?: ""
            val input = EditText(context).apply {
                hint = if (propDesc.isNotBlank()) "$key — $propDesc" else key
                setTextColor(resolveAttr(R.attr.prismTextPrimary))
                setHintTextColor(resolveAttr(R.attr.prismTextSecondary))
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (8 * density).toInt()
                }
            }
            layout.addView(input)
            inputs[key] = input
        }

        if (properties == null || properties.length() == 0) {
            layout.addView(sectionLabel("This tool takes no parameters."))
        }

        val resultLabel = TextView(context).apply {
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
            textSize = 13f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        layout.addView(resultLabel)

        val dialog = PrismDialogFactory.show(
            context, "Test: $name", "",
            positiveText = "Run", negativeText = "Close",
            customView = layout
        )

        // Replace the button's click listener entirely instead of relying on onPositive -- a
        // manually-set View.OnClickListener does NOT auto-dismiss the dialog the way the listener
        // passed to setPositiveButton does, which is what lets the dialog stay open here.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val args = JSONObject()
            inputs.forEach { (key, input) -> args.put(key, input.text.toString()) }
            resultLabel.visibility = View.VISIBLE
            resultLabel.text = "Running..."
            viewScope.launch {
                val result = try {
                    withContext(Dispatchers.IO) { execute(args.toString()) }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                resultLabel.text = if (result.length > TEST_RESULT_PREVIEW_CHARS) {
                    result.take(TEST_RESULT_PREVIEW_CHARS) + "…"
                } else {
                    result
                }
            }
        }
    }

    // ── Shared UI helpers ────────────────────────────────────────────────────

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(resolveAttr(R.attr.prismTextSecondary))
        textSize = 12f
    }

    private fun jsonEditor(initial: String): EditText {
        val density = resources.displayMetrics.density
        return EditText(context).apply {
            setText(initial)
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            maxLines = 14
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
        }
    }

    private fun destructiveButton(text: String) = com.google.android.material.button.MaterialButton(
        context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text
        setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
        strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF6B6B"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private const val TEST_RESULT_PREVIEW_CHARS = 100

        private val EXAMPLE_TOOL_JSON = """
            {
              "name": "get_weather",
              "description": "Get current weather for a city",
              "parameters": {
                "type": "object",
                "properties": {
                  "city": { "type": "string", "description": "City name" }
                },
                "required": ["city"]
              },
              "method": "GET",
              "url": "https://api.example.com/weather?city={city}",
              "headers": {},
              "body": ""
            }
        """.trimIndent()

        private val EXAMPLE_SYNTAX_JSON = """
            {
              "name": "My Local Model Format",
              "systemPromptTemplate": "You can use these tools:\n{{tools}}\nTo call one, respond with <tool_call>{\"name\": \"...\", \"arguments\": {...}}</tool_call>",
              "toolFormatTemplate": "- {{name}}: {{description}} Parameters: {{parameters}}",
              "callExtractionRegex": "<tool_call>(.*?)</tool_call>"
            }
        """.trimIndent()
    }
}

// ── List model + adapter ─────────────────────────────────────────────────────

sealed class AgenticListItem {
    data class Header(val title: String) : AgenticListItem()
    data class ImportedTool(val entity: AgenticToolEntity) : AgenticListItem()
    data class BuiltinTool(val def: ToolDefinition) : AgenticListItem()
    data class SyntaxRow(val entity: AgenticSyntaxEntity, val isActive: Boolean) : AgenticListItem()
}

class AgenticToolsAdapter(
    private val onImportedToolClick: (AgenticToolEntity) -> Unit,
    private val onImportedToolLongClick: (AgenticToolEntity) -> Unit,
    private val onImportedToolToggle: (AgenticToolEntity, Boolean) -> Unit,
    private val onBuiltinToolClick: (ToolDefinition) -> Unit,
    private val onSyntaxClick: (AgenticSyntaxEntity) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<AgenticListItem> = emptyList()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TOOL = 1
        private const val TYPE_SYNTAX = 2
    }

    fun setItems(newItems: List<AgenticListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is AgenticListItem.Header -> TYPE_HEADER
        is AgenticListItem.ImportedTool, is AgenticListItem.BuiltinTool -> TYPE_TOOL
        is AgenticListItem.SyntaxRow -> TYPE_SYNTAX
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            TYPE_TOOL -> ToolVH(ItemAgenticToolBinding.inflate(inflater, parent, false))
            else -> SyntaxVH(ItemCloudModelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AgenticListItem.Header -> (holder as HeaderVH).binding.headerTitle.text = item.title
            is AgenticListItem.ImportedTool -> bindImportedTool(holder as ToolVH, item.entity)
            is AgenticListItem.BuiltinTool -> bindBuiltinTool(holder as ToolVH, item.def)
            is AgenticListItem.SyntaxRow -> bindSyntax(holder as SyntaxVH, item)
        }
    }

    private fun bindImportedTool(holder: ToolVH, entity: AgenticToolEntity) {
        val b = holder.binding
        b.toolName.text = entity.name
        b.toolSubtitle.text = "${entity.httpMethod} ${entity.httpUrl}"
        b.toolBuiltinBadge.visibility = View.GONE
        b.toolEnabledSwitch.visibility = View.VISIBLE
        b.toolEnabledSwitch.setOnCheckedChangeListener(null)
        b.toolEnabledSwitch.isChecked = entity.enabled
        b.toolEnabledSwitch.setOnCheckedChangeListener { _, checked -> onImportedToolToggle(entity, checked) }
        b.toolRoot.setOnClickListener { onImportedToolClick(entity) }
        b.toolRoot.setOnLongClickListener { onImportedToolLongClick(entity); true }
    }

    private fun bindBuiltinTool(holder: ToolVH, def: ToolDefinition) {
        val b = holder.binding
        b.toolName.text = def.name
        b.toolSubtitle.text = def.description
        b.toolBuiltinBadge.visibility = View.VISIBLE
        b.toolEnabledSwitch.visibility = View.GONE
        b.toolEnabledSwitch.setOnCheckedChangeListener(null)
        b.toolRoot.setOnClickListener { onBuiltinToolClick(def) }
        b.toolRoot.setOnLongClickListener { true }
    }

    private fun bindSyntax(holder: SyntaxVH, item: AgenticListItem.SyntaxRow) {
        val b = holder.binding
        b.cloudModelId.text = item.entity.name
        b.cloudModelBaseUrl.text = if (item.isActive) "Active for tool-calling" else "Custom syntax"
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onSyntaxClick(item.entity) }
    }

    class HeaderVH(val binding: ItemSettingHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ToolVH(val binding: ItemAgenticToolBinding) : RecyclerView.ViewHolder(binding.root)
    class SyntaxVH(val binding: ItemCloudModelBinding) : RecyclerView.ViewHolder(binding.root)
}
