package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.prism.desktop.DesktopTour
import com.prism.launcher.PrismSettings
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

    // THE ACCOUNT COMES BEFORE THE TOUR AND CANNOT BE SKIPPED. The mail client signs in with this
    // username and password, so a tour that let somebody past it would leave them with a mailbox
    // they cannot open and no explanation. The phone's tour has the same gate.
    var hasAccount by remember {
        mutableStateOf(PrismSettings.getLykeProfileConfirmed() && PrismSettings.hasLykePassword())
    }
    if (!hasAccount) {
        LykeAccountGate { hasAccount = true }
        return
    }

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
                // A REAL GAP BETWEEN EVERY CONTROL, not just between Back and Next. The row used to
                // rely on one 8dp spacer in the middle of the group, so at a narrow window -- or
                // with the long "Start using Prism" label on the last step -- Next grew until it met
                // Back and the two read as a single broken control. spacedBy guarantees the gap,
                // the weight keeps Skip on the left and the actions on the right, and the final
                // label is short enough to not demand the room in the first place.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Skip is on EVERY step, not only the first. Somebody who has read enough should not
                    // have to finish to get out.
                    OutlinedButton(onClick = { finish() }) { Text("Skip", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    if (index > 0) {
                        OutlinedButton(onClick = { index-- }) { Text("Back", fontSize = 13.sp) }
                    }
                    Button(onClick = { if (last) finish() else index++ }) {
                        Text(if (last) "Finish" else "Next", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * The first-run account form, shown before the tour.
 *
 * A small screen rather than a step in the tour because the tour's Skip is on every page and this
 * cannot be skipped. The validation is deliberately the server's own: three characters, a dot or
 * dash or underscore allowed, and a password of eight, which is the floor EmailSecurity enforces.
 */
@Composable
private fun LykeAccountGate(onReady: () -> Unit) {
    val colors = LocalPrismColors.current
    var name by remember { mutableStateOf(PrismSettings.getLykeUserName()) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

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
                Text("YOUR PRISM ACCOUNT", fontSize = 10.sp, color = colors.accent)
                Spacer(Modifier.height(14.dp))
                Text("Create the account your mail lives on", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Prism can serve your mail itself. The address is this username at your mail " +
                        "domain, and the client signs in with this password. The same name is your " +
                        "Lyke name.\n\n" +
                        "The password is encrypted with your wallet phrase, so another device with " +
                        "the same wallet can read it back -- and so can anyone holding that phrase.",
                    fontSize = 13.sp,
                    color = colors.muted,
                    lineHeight = 20.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Username", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Confirm password", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 12.sp, color = Color(0xFFFF6B6B))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val clean = name.trim()
                        error = when {
                            clean.length < 3 -> "A username needs at least three characters."
                            !clean.matches(Regex("[A-Za-z0-9._-]+")) ->
                                "Usernames can use letters, numbers, dots, dashes and underscores."
                            password.length < 8 -> "The password needs at least eight characters."
                            password != confirm -> "The two passwords do not match."
                            else -> ""
                        }
                        if (error.isBlank()) {
                            PrismSettings.setLykeUserName(clean)
                            PrismSettings.setLykePassword(password)
                            PrismSettings.setLykeProfileConfirmed(true)
                            onReady()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create my account", fontSize = 13.sp) }
            }
        }
    }
}
