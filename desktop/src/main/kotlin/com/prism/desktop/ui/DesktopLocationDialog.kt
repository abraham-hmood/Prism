package com.prism.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.launcher.maps.MapPlace
import com.prism.launcher.maps.MapServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Picks the place this machine reports as its position.
 *
 * A desktop has no GPS, so the honest answer is the place the user names. Search is the same
 * Nominatim lookup the phone uses, which means a picked place can be a town, a street address or a
 * landmark; the coordinates travel, never the search text.
 */
@Composable
fun DesktopLocationDialog(
    title: String = "Set this machine's location",
    onDismiss: () -> Unit,
    onChosen: (MapPlace) -> Unit,
) {
    val colors = LocalPrismColors.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MapPlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        val cleaned = query.trim()
        if (cleaned.length < 2) {
            results = emptyList()
            busy = false
            error = ""
            return@LaunchedEffect
        }
        busy = true
        error = ""
        // Nominatim asks clients to be gentle; this is also long enough that typing a word does
        // not send one request per letter.
        delay(450)
        val found = withContext(Dispatchers.IO) {
            runCatching { MapServices.search(cleaned) }.getOrDefault(emptyList())
        }
        busy = false
        results = found
        if (found.isEmpty()) error = "No places matched. Try a town or a street address."
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A desktop has no GPS, so this is the position Prism shares for it. " +
                        "The place name stays on this machine; only coordinates are sent.",
                    fontSize = 12.sp,
                    color = colors.faint,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search a place") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Searching…", fontSize = 12.sp, color = colors.faint)
                    }
                } else if (error.isNotBlank()) {
                    Text(
                        error,
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }

                if (results.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp)
                    ) {
                        results.forEach { place ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onChosen(place) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text(place.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (place.address.isNotBlank() && place.address != place.name) {
                                    Text(
                                        place.address,
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
