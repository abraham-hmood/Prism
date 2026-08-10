package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService
import com.prism.launcher.messaging.OllamaDiscoveryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cloud and LAN AI: model management, LAN discovery, and a streaming chat against either.
 *
 * PHASES 29 AND 30 ON ONE PAGE, because they are one feature from the user's side. A cloud model
 * and an Ollama server on the desk next to you are both "a model that is not on this machine";
 * splitting them across two screens would only mirror how the code is organised, which is not a
 * reason.
 *
 * ALL OF THE ACTUAL WORK IS IN :core. `CloudAiService` and `OllamaDiscoveryService` moved there
 * essentially unchanged -- both were plain HttpURLConnection and JSON, so the only Android in
 * them was logging, base64 and a Bitmap return type. This file is a form and a transcript.
 *
 * THE PLAN SAID PHASE 30 NEEDED A NETWORK CAPABILITY (WifiManager, for the local subnet). It did
 * not. `OllamaDiscoveryService.scan` already asked `MeshUtils.getLocalMeshIp()`, which is pure
 * java.net and had already moved to :core; the `Context` parameter was carried along unused, the
 * same way MeshUtils carried one. That is the third time in this port a recorded blocker turned
 * out to be a parameter nobody read.
 */
@Composable
fun CloudAiPage() {
    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current

    var models by remember { mutableStateOf(PrismSettings.getCloudModels()) }
    var activeId by remember { mutableStateOf(PrismSettings.getActiveCloudModelId()) }
    var servers by remember { mutableStateOf<List<OllamaDiscoveryService.OllamaServer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    var prompt by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Target?>(null) }

    PageScaffold("Cloud AI", "Cloud endpoints and Ollama servers on your network") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            // ── Cloud models ──────────────────────────────────────────────────────────────
            SectionHeader("CLOUD MODELS")
            Card {
                if (models.isEmpty()) {
                    Text(
                        "No cloud models configured.",
                        fontSize = 12.sp, color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                models.forEachIndexed { i, model ->
                    if (i > 0) Hairline()
                    ModelRow(
                        name = model.id,
                        detail = "${model.baseUrl}  ·  ${model.modelId}",
                        active = model.id == activeId,
                        onSelect = {
                            PrismSettings.setActiveCloudModelId(model.id)
                            activeId = model.id
                            target = Target.Cloud(model.id)
                        },
                        onRemove = {
                            PrismSettings.removeCloudModel(model.id)
                            models = PrismSettings.getCloudModels()
                            activeId = PrismSettings.getActiveCloudModelId()
                        },
                    )
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickableRow { adding = true }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Add a cloud model", fontSize = 13.sp, color = colors.accent)
                }
            }
            SectionFooter(
                "Any OpenAI-compatible endpoint. The base URL should end in a slash -- Prism " +
                    "appends chat/completions and images/generations to it."
            )

            Spacer(Modifier.height(16.dp))

            // ── Ollama on the LAN ─────────────────────────────────────────────────────────
            SectionHeader("OLLAMA ON YOUR NETWORK")
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Scan the local subnet", fontSize = 14.sp)
                        Text(
                            "Probes every address on your /24 for an Ollama server on port 11434.",
                            fontSize = 11.sp, color = colors.faint,
                        )
                    }
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            scanning = true
                            scope.launch {
                                servers = try {
                                    withContext(Dispatchers.IO) { OllamaDiscoveryService.scan() }
                                } catch (e: Exception) {
                                    PrismPlatform.log.error("Prism/ollama", "Scan failed", e)
                                    emptyList()
                                }
                                scanning = false
                            }
                        }) { Text("Scan") }
                    }
                }

                servers.forEach { server ->
                    Hairline()
                    ModelRow(
                        name = "${server.host}:${server.port}",
                        detail = server.models.joinToString(", ").ifBlank { "no models loaded" },
                        active = (target as? Target.Ollama)?.ip == server.host,
                        onSelect = {
                            target = Target.Ollama(server.host, server.port, server.models.firstOrNull().orEmpty())
                        },
                        onRemove = null,
                    )
                }
            }
            SectionFooter(
                if (servers.isEmpty() && !scanning) {
                    "Nothing found yet. The sweep only covers private subnets -- it will not " +
                        "probe a public address range."
                } else {
                    "Select a server to chat against it. Prism uses the first model it reports."
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Chat ──────────────────────────────────────────────────────────────────────
            SectionHeader("TRY IT")
            Card {
                Text(
                    when (val t = target) {
                        null -> "Select a cloud model or an Ollama server above."
                        is Target.Cloud -> "Cloud: ${t.id}"
                        is Target.Ollama -> "Ollama: ${t.ip}:${t.port} (${t.model})"
                    },
                    fontSize = 12.sp,
                    color = if (target == null) colors.faint else colors.accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                CommittingField(
                    prompt,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) { prompt = it }

                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Button(
                        enabled = target != null && prompt.isNotBlank() && !busy,
                        onClick = {
                            val t = target ?: return@Button
                            busy = true
                            reply = ""
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        // Streaming, so tokens land as they arrive rather than
                                        // the page sitting blank for the whole generation.
                                        when (t) {
                                            is Target.Cloud -> {
                                                val m = PrismSettings.getCloudModels()
                                                    .firstOrNull { it.id == t.id }
                                                if (m == null) reply = "That model is gone."
                                                else CloudAiService.fetchResponseStreaming(
                                                    m.baseUrl, m.apiKey, m.modelId, prompt,
                                                    maxTokens = PrismSettings.getMaxTokens(),
                                                ) { token -> reply += token }
                                            }
                                            is Target.Ollama ->
                                                CloudAiService.fetchOllamaChatStreaming(
                                                    t.ip, t.port, t.model, prompt,
                                                ) { token -> reply += token }
                                        }
                                    } catch (e: Exception) {
                                        reply = "Failed: ${e.message}"
                                        PrismPlatform.log.error("Prism/cloudai", "Request failed", e)
                                    }
                                }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Generating…" else "Send") }
                }

                if (reply.isNotEmpty()) {
                    Hairline()
                    Text(
                        reply,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (adding) {
        AddCloudModelDialog(
            onDismiss = { adding = false },
            onAdd = { id, baseUrl, apiKey, modelId ->
                PrismSettings.addCloudModel(
                    PrismSettings.CloudModelProfile(
                        id = id, apiKey = apiKey, baseUrl = baseUrl, modelId = modelId,
                    )
                )
                models = PrismSettings.getCloudModels()
                activeId = PrismSettings.getActiveCloudModelId()
                adding = false
            },
        )
    }
}

private sealed interface Target {
    data class Cloud(val id: String) : Target
    data class Ollama(val ip: String, val port: Int, val model: String) : Target
}

@Composable
private fun ModelRow(
    name: String,
    detail: String,
    active: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
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
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                detail, fontSize = 11.sp, color = colors.faint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        onRemove?.let {
            Icon(
                Icons.Filled.Close, "Remove",
                tint = colors.faint,
                modifier = Modifier.size(15.dp).clickableRow { it() },
            )
        }
    }
}

@Composable
private fun AddCloudModelDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, baseUrl: String, apiKey: String, modelId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1/") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("gpt-4o-mini") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = { onAdd(name.trim(), baseUrl.trim(), apiKey.trim(), model.trim()) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a cloud model") },
        text = {
            Column(Modifier.width(420.dp)) {
                LabelledField("Label", name) { name = it }
                LabelledField("Base URL", baseUrl) { baseUrl = it }
                LabelledField("API key", apiKey) { apiKey = it }
                LabelledField("Model", model) { model = it }
                Text(
                    "The key is stored in the same settings file as everything else, in plain " +
                        "text. That matches the Android build; treat the file accordingly.",
                    fontSize = 10.sp,
                    color = Color(0xFF83838F),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
    )
}

@Composable
private fun LabelledField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 10.sp, letterSpacing = 1.0.sp, color = Color(0xFF6C6C78))
        CommittingField(value, modifier = Modifier.fillMaxWidth(), onCommit = onChange)
    }
}
