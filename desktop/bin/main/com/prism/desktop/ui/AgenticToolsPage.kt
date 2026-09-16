package com.prism.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismSettings
import com.prism.launcher.agentic.AgenticBuiltinTools
import com.prism.launcher.agentic.AgenticEngine
import com.prism.launcher.agentic.AgenticSyntaxEntity
import com.prism.launcher.agentic.AgenticToolEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agentic tools: the builtins, user-imported HTTP tools, syntax profiles, and a live loop.
 *
 * THE ENGINE IS ENTIRELY IN :core NOW. `AgenticEngine`, `AgenticToolExecutor` and
 * `AgenticBuiltinTools` all moved; the two tools that touched the machine -- `launch_app` and
 * `list_installed_apps` -- were rewritten onto `AppCatalog`, which is the capability that made
 * the rest of the file portable. This page is a list and a transcript.
 *
 * ONE BUILTIN IS PLATFORM-GATED and shown as such: `p2p_host_folder` needs the mesh DNS manager,
 * which is not ported. It appears in the list, disabled, with the reason -- the same
 * disabled-not-hidden rule the browser menu follows, so the capability is discoverable before the
 * platform can offer it.
 */
@Composable
fun AgenticToolsPage() {
    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current

    var tools by remember { mutableStateOf<List<AgenticToolEntity>>(emptyList()) }
    var syntaxes by remember { mutableStateOf<List<AgenticSyntaxEntity>>(emptyList()) }
    var activeSyntax by remember { mutableStateOf(PrismSettings.getActiveAgenticSyntaxId()) }
    var enabled by remember { mutableStateOf(PrismSettings.getAgenticToolsEnabled()) }
    var refresh by remember { mutableStateOf(0) }

    var prompt by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var addingTool by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        val dao = AppDatabase.get().agenticDao()
        val loaded = withContext(Dispatchers.IO) { dao.getAllTools() to dao.getAllSyntaxes() }
        tools = loaded.first
        syntaxes = loaded.second
    }

    PageScaffold("Agentic Tools", "Let a model call real tools and feed the results back") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("TOOL CALLING")
            Card {
                ToggleRow(
                    title = "Enable agentic tools",
                    detail = "When on, a chat turn with no attached image runs through the " +
                        "tool-calling loop instead of a single generation.",
                    checked = enabled,
                    onChange = {
                        enabled = it
                        PrismSettings.setAgenticToolsEnabled(it)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader("BUILT-IN TOOLS")
            Card {
                AgenticBuiltinTools.ALL.forEachIndexed { i, tool ->
                    if (i > 0) Hairline()
                    // ToolDefinition is keyed by `name`, which for a builtin IS its id.
                    val gated = tool.name == AgenticBuiltinTools.ID_P2P_HOST &&
                        AgenticBuiltinTools.p2pHostHandler == null
                    BuiltinRow(tool.name, tool.description, gated)
                }
            }
            SectionFooter(
                "list_installed_apps and launch_app run against this machine's application " +
                    "catalogue -- .desktop entries on Linux, the Start Menu on Windows. " +
                    "launch_app keeps its URI argument, so a model can open an app at a deep link."
            )

            Spacer(Modifier.height(16.dp))

            SectionHeader("YOUR TOOLS")
            Card {
                if (tools.isEmpty()) {
                    Text(
                        "No custom tools. A custom tool is an HTTP request with {placeholders} " +
                            "the model fills in.",
                        fontSize = 12.sp, color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                tools.forEachIndexed { i, tool ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, fontSize = 14.sp)
                            Text(
                                "${tool.httpMethod} ${tool.httpUrl}",
                                fontSize = 11.sp, color = colors.faint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = tool.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AppDatabase.get().agenticDao()
                                            .upsertTool(tool.copy(enabled = on))
                                    }
                                    refresh++
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Close, "Delete",
                            tint = colors.faint,
                            modifier = Modifier.size(15.dp).clickableRow {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AppDatabase.get().agenticDao().deleteTool(tool.id)
                                    }
                                    refresh++
                                }
                            },
                        )
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickableRow { addingTool = true }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Add an HTTP tool", fontSize = 13.sp, color = colors.accent)
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader("SYNTAX PROFILE")
            Card {
                SyntaxRow("None (native tool calling)", activeSyntax == null) {
                    PrismSettings.setActiveAgenticSyntaxId(null)
                    activeSyntax = null
                }
                syntaxes.forEach { syntax ->
                    Hairline()
                    SyntaxRow(syntax.name, activeSyntax == syntax.id) {
                        PrismSettings.setActiveAgenticSyntaxId(syntax.id)
                        activeSyntax = syntax.id
                    }
                }
            }
            SectionFooter(
                "Cloud and Ollama models have native tool calling and need no profile. Local " +
                    "GGUF models do not, so a profile tells Prism how to describe the tools in " +
                    "the prompt and how to recognise a call in the raw output."
            )

            Spacer(Modifier.height(16.dp))

            SectionHeader("RUN A TURN")
            Card {
                CommittingField(prompt, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    prompt = it
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Button(
                        enabled = prompt.isNotBlank() && !running,
                        onClick = {
                            running = true
                            transcript = ""
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val (answer, _) = AgenticEngine.run(
                                            userText = prompt,
                                            onToken = { transcript += it },
                                            onReasoning = null,
                                        )
                                        if (transcript.isBlank()) transcript = answer
                                    } catch (e: Exception) {
                                        transcript = "Failed: ${e.message}"
                                        PrismPlatform.log.error("Prism/agentic", "Run failed", e)
                                    }
                                }
                                running = false
                            }
                        },
                    ) { Text(if (running) "Running…" else "Run") }
                }
                if (transcript.isNotEmpty()) {
                    Hairline()
                    Text(
                        transcript,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            SectionFooter(
                "The loop runs at most five iterations. Tool results are fed back verbatim, so " +
                    "a failing tool tells the model what went wrong rather than disappearing."
            )

            Spacer(Modifier.height(20.dp))
        }
    }

    if (addingTool) {
        AddToolDialog(
            onDismiss = { addingTool = false },
            onAdd = { entity ->
                scope.launch {
                    withContext(Dispatchers.IO) { AppDatabase.get().agenticDao().upsertTool(entity) }
                    addingTool = false
                    refresh++
                }
            },
        )
    }
}

@Composable
private fun BuiltinRow(name: String, description: String, gated: Boolean) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp).alphaIf(!gated),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (gated) Icons.Filled.Block else Icons.Filled.CheckCircle,
            null,
            tint = if (gated) colors.faint else colors.accent,
            modifier = Modifier.size(15.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(
                if (gated) "Needs the mesh DNS manager, which is not ported to desktop yet."
                else description,
                fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun SyntaxRow(label: String, active: Boolean, onSelect: () -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (active) colors.accent else colors.faint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun AddToolDialog(onDismiss: () -> Unit, onAdd: (AgenticToolEntity) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf("https://") }
    var parameters by remember {
        mutableStateOf("""{"type":"object","properties":{}}""")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.length > 8,
                onClick = {
                    onAdd(
                        AgenticToolEntity(
                            name = name.trim(),
                            description = description.trim(),
                            parametersJson = parameters,
                            httpMethod = method.trim().uppercase(),
                            httpUrl = url.trim(),
                        )
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add an HTTP tool") },
        text = {
            Column(Modifier.width(460.dp)) {
                LabelledRow("Name", name) { name = it }
                LabelledRow("Description", description) { description = it }
                LabelledRow("Method", method) { method = it }
                LabelledRow("URL", url) { url = it }
                LabelledRow("Parameters (JSON Schema)", parameters) { parameters = it }
                Text(
                    "Use {paramName} in the URL or body; Prism substitutes the model's arguments " +
                        "at call time. The description is what the model reads to decide whether " +
                        "to call this at all, so it is worth writing properly.",
                    fontSize = 10.sp, color = Color(0xFF83838F),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

@Composable
private fun LabelledRow(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 10.sp, letterSpacing = 1.0.sp, color = Color(0xFF6C6C78))
        CommittingField(value, modifier = Modifier.fillMaxWidth(), onCommit = onChange)
    }
}
