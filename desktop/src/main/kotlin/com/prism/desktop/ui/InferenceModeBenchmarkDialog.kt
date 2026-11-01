package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.InferenceMode
import com.prism.launcher.messaging.InferenceModeBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop half of "Select the fastest inference mode".
 *
 * The row is disabled without a local model, so this only ever opens when there is one to test.
 * It sets the winner itself rather than asking the user to copy a number into another row: the
 * whole point of the action is that the choice is empirical, and a dialog that reports a result
 * without applying it would leave the setting exactly where it started.
 */
@Composable
fun InferenceModeBenchmarkDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf("Starting the smoke test…") }
    var results by remember { mutableStateOf<List<InferenceModeBench.Result>>(emptyList()) }
    var chosen by remember { mutableStateOf<InferenceMode?>(null) }

    LaunchedEffect(Unit) {
        val measured = withContext(Dispatchers.IO) {
            InferenceModeBench.run { index, total, mode ->
                // The benchmark runs on IO, and its progress callback fires there. Compose state
                // is main-thread-only, so hop back rather than writing `current` from the worker.
                scope.launch {
                    current = "Testing ${mode.label}… (${index + 1}/$total)"
                }
            }
        }
        val best = InferenceModeBench.best(measured)
        if (best != null) PrismSettings.setInferenceMode(best.id)
        results = measured
        chosen = best
        running = false
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text(if (running) "Testing…" else "Done") }
        },
        title = {
            Text(
                when {
                    running -> "Finding the fastest mode"
                    chosen != null -> "Selected ${chosen?.label}"
                    else -> "No mode completed"
                }
            )
        },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (running) {
                    Text(current, fontSize = 13.sp, color = colors.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The model is run once per mode on a short prompt. This can take a minute; " +
                            "closing the dialog will not interrupt it.",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                    return@Column
                }

                if (results.none { it.tokensPerSecond > 0.0 }) {
                    Text(
                        "No mode produced tokens. Check that the local model still loads, then try again.",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                    return@Column
                }

                results.filter { it.tokensPerSecond > 0.0 }.forEach { result ->
                    Row {
                        Text(
                            "%.1f tok/s".format(result.tokensPerSecond),
                            fontSize = 13.sp,
                            color = if (result.mode == chosen) colors.accent else colors.onSurface,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(
                            result.mode.label,
                            fontSize = 13.sp,
                            color = if (result.mode == chosen) colors.accent else colors.onSurface,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The winner is stored as the inference mode and is already in effect for the " +
                        "next reply. Heterogeneous execution reloads the model when it starts.",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
        },
    )
}
