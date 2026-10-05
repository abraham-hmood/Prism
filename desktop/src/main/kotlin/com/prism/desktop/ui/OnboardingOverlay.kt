package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.DesktopTour
import com.prism.launcher.onboarding.OnboardingTour

/**
 * The first-run tour. PHASE 109.
 *
 * ## Why it is a dismissible overlay and not a wizard
 *
 * A wizard has to be completed; an overlay can be walked away from. Somebody opening Prism for the first
 * time may well want to look around rather than read, and a tour that blocked the product until it had been
 * read to the end would be the wrong first impression. "Skip" is offered on every step and the flag is set
 * either way -- finishing and giving up are both answers.
 *
 * ## Why it is mounted window-wide
 *
 * So it covers whatever page Prism opened on, and so it appears exactly once regardless of navigation. A
 * per-page overlay would either show up repeatedly or depend on which page happened to be first.
 *
 * ## The flag is shared with the phone
 *
 * Deliberately: see `OnboardingTour`. Somebody shown round on their phone should not be shown round again
 * here, because the fact they already know what Prism is is a fact about them, not about the device.
 */
@Composable
fun OnboardingOverlay(onDone: () -> Unit) {
    val colors = LocalPrismColors.current
    val steps = remember { OnboardingTour.walk(DesktopTour.SECTIONS) }
    var index by remember { mutableStateOf(0) }

    if (steps.isEmpty()) {
        onDone()
        return
    }

    val (sectionName, step) = steps[index.coerceIn(0, steps.lastIndex)]
    val last = index >= steps.lastIndex

    fun finish() {
        OnboardingTour.markSeen()
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xE60B0B10)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 560.dp).padding(24.dp),
        ) {
            Column(Modifier.padding(26.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sectionName.uppercase(), fontSize = 10.sp, color = colors.accent)
                    Spacer(Modifier.weight(1f))
                    Text(
                        (index + 1).toString() + " of " + steps.size,
                        fontSize = 10.sp,
                        color = colors.faint,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(step.glyph, fontSize = 40.sp, color = colors.accent)
                Spacer(Modifier.height(12.dp))
                Text(step.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                Spacer(Modifier.height(10.dp))

                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(step.body, fontSize = 13.sp, color = colors.muted, lineHeight = 20.sp)
                }

                if (step.whereToFind.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Color(0xFF1B1B22), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Where: " + step.whereToFind,
                            fontSize = 11.sp,
                            color = colors.faint,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Progress as dots rather than a bar: with a dozen steps the dots also say how far in you
                // are within the section, which a bar flattens away.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    steps.indices.forEach { dot ->
                        Surface(
                            color = if (dot == index) colors.accent else Color(0xFF2E2E38),
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 4.dp).size(if (dot == index) 7.dp else 5.dp),
                        ) {}
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Skip is on EVERY step, not only the first. Somebody who has read enough should not
                    // have to finish to get out.
                    OutlinedButton(onClick = { finish() }) { Text("Skip", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    if (index > 0) {
                        OutlinedButton(onClick = { index-- }) { Text("Back", fontSize = 13.sp) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = { if (last) finish() else index++ }) {
                        Text(if (last) "Start using Prism" else "Next")
                    }
                }
            }
        }
    }
}
