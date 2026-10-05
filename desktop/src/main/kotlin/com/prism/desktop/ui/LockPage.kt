package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.lock.EmergencyContacts
import com.prism.launcher.lock.LockStore
import com.prism.launcher.lock.MedicalRecord
import com.prism.launcher.lock.PrismLockScope

/**
 * The lock, the medical card and the emergency contacts. PHASE 101.
 *
 * ## The decision this page is built on, and where it is written down
 *
 * [PrismLockScope] holds it: on a desktop, locking locks PRISM and not the computer. Every gate it
 * closes carries a promise and a caveat, and this page shows BOTH -- because a security feature whose
 * limits live only in the source will be trusted further than it deserves. "This does not lock the
 * computer" is on the screen, not in a comment.
 *
 * ## Pattern unlock is here, and it is the same pattern
 *
 * Android draws a 3x3 grid and hashes the sequence of cell indices. So does this. The credential is
 * `LockStore`'s, which is in `:core` now, so a pattern set on the phone unlocks here after a profile
 * transfer -- and more importantly, a DURESS code set on the phone works here, which it would not if
 * the two platforms hashed differently.
 *
 * ## The duress path shows nothing
 *
 * Both credentials unlock and the screen does not react differently to either. That is the whole
 * point: a duress code that produced a visibly different outcome would tell the person watching the
 * screen that it was a duress code. What the duress code does instead is reported nowhere in the UI
 * and logged nowhere the lock screen can show.
 */
@Composable
fun LockPage() {
    val colors = LocalPrismColors.current
    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    val configured = remember(revision) { LockStore.isConfigured() }
    val enabled = remember(revision) { LockStore.isEnabled() }
    val mechanism = remember(revision) { LockStore.mechanism() }
    val hasDuress = remember(revision) { LockStore.hasDuress() }

    var setupMechanism by remember { mutableStateOf(LockStore.Mechanism.PIN) }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }

    var record by remember(revision) { mutableStateOf(MedicalRecord.get()) }
    var contacts by remember(revision) { mutableStateOf(EmergencyContacts.all()) }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }

    PageScaffold("Lock", "A credential for Prism, a medical card and emergency contacts") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── What the lock actually covers ──────────────────────────────
            SectionHeader("what locking Prism does")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "THIS IS NOT A LOCK ON THE COMPUTER. Windows and Linux own the login " +
                            "screen; an application that drew a panel and called the machine locked " +
                            "would be walked past by alt-tab, a second monitor, or the task manager. " +
                            "What this locks is Prism.",
                        fontSize = 12.sp,
                        color = Color(0xFFE0C060),
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrismLockScope.Gate.entries.forEachIndexed { index, gate ->
                        if (index > 0) Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(
                                color = colors.accent,
                                shape = CircleShape,
                                modifier = Modifier.padding(top = 5.dp).size(6.dp),
                            ) {}
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(gate.label, fontSize = 12.sp)
                                Text(
                                    gate.promise,
                                    fontSize = 10.sp,
                                    color = colors.muted,
                                    lineHeight = 15.sp,
                                )
                                Text(
                                    gate.caveat,
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── Credential ─────────────────────────────────────────────────
            SectionHeader("credential")
            Card {
                Column(Modifier.padding(14.dp)) {
                    if (configured) {
                        Text(
                            (mechanism?.label ?: "A credential") + " is set" +
                                (if (hasDuress) ", with a duress code." else ".") +
                                (if (enabled) "" else " The lock is currently disarmed."),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = enabled,
                                onClick = {
                                    PrismLockScope.lock()
                                    note = "Prism is locked."
                                },
                            ) { Text("Lock Prism now") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                LockStore.setEnabled(!enabled)
                                revision++
                                note = if (enabled) {
                                    "Disarmed. The credential is kept; nothing will lock."
                                } else {
                                    "Armed. Prism will come up locked."
                                }
                            }) { Text(if (enabled) "Disarm" else "Arm", fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                LockStore.clear()
                                revision++
                                note = "The credential and any duress code are gone."
                            }) { Text("Remove", fontSize = 12.sp) }
                        }

                        Spacer(Modifier.height(14.dp))
                        Hairline()
                        Spacer(Modifier.height(10.dp))
                        Text("duress code", fontSize = 11.sp, color = colors.accent)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A second credential that also unlocks, indistinguishably. What it " +
                                "triggers is deliberately invisible from this screen and from the " +
                                "lock screen — a duress code that behaved differently would " +
                                "announce itself to whoever was watching you type it.",
                            fontSize = 10.sp,
                            color = colors.faint,
                            lineHeight = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = duress,
                                onValueChange = { duress = it },
                                placeholder = {
                                    Text(
                                        "A different " + (mechanism?.label?.lowercase() ?: "code"),
                                        fontSize = 12.sp,
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.width(220.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = duress.isNotBlank(),
                                onClick = {
                                    val problem = LockStore.setDuress(duress)
                                    duress = ""
                                    revision++
                                    note = problem ?: "The duress code is set."
                                },
                            ) { Text("Set", fontSize = 12.sp) }
                            if (hasDuress) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = {
                                    LockStore.clearDuress()
                                    revision++
                                    note = "The duress code is gone."
                                }) { Text("Clear", fontSize = 12.sp) }
                            }
                        }
                    } else {
                        Text("No credential is set.", fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            LockStore.Mechanism.entries.forEach { candidate ->
                                OutlinedButton(
                                    onClick = {
                                        setupMechanism = candidate
                                        secret = ""
                                        confirm = ""
                                    },
                                    modifier = Modifier.padding(end = 6.dp),
                                ) {
                                    Text(
                                        candidate.label,
                                        fontSize = 12.sp,
                                        color = if (candidate == setupMechanism) colors.accent
                                        else colors.onSurface,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (setupMechanism == LockStore.Mechanism.PATTERN) {
                            Text(
                                "Draw it twice. The pattern is stored as the sequence of cells, " +
                                    "hashed the same way the phone hashes it.",
                                fontSize = 11.sp,
                                color = colors.faint,
                                lineHeight = 16.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row {
                                PatternGrid(
                                    label = if (secret.isBlank()) "Draw" else "Drawn",
                                    onDrawn = { secret = it },
                                )
                                Spacer(Modifier.width(16.dp))
                                PatternGrid(
                                    label = if (confirm.isBlank()) "Again" else "Drawn",
                                    onDrawn = { confirm = it },
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = secret,
                                onValueChange = {
                                    secret = if (setupMechanism == LockStore.Mechanism.PIN) {
                                        it.filter { c -> c.isDigit() }
                                    } else it
                                },
                                placeholder = { Text(setupMechanism.label, fontSize = 12.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.width(260.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = confirm,
                                onValueChange = {
                                    confirm = if (setupMechanism == LockStore.Mechanism.PIN) {
                                        it.filter { c -> c.isDigit() }
                                    } else it
                                },
                                placeholder = { Text("Again", fontSize = 12.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.width(260.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = secret.isNotBlank() && secret == confirm,
                            onClick = {
                                LockStore.configure(setupMechanism, secret)
                                secret = ""
                                confirm = ""
                                revision++
                                note = "Set. Prism will come up locked from now on."
                            },
                        ) {
                            Text(
                                when {
                                    secret.isBlank() -> "Enter it"
                                    secret != confirm -> "They do not match"
                                    else -> "Set the credential"
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Stored as salted SHA-256, iterated twenty thousand times. Worth being " +
                                "honest about: a four-digit PIN has ten thousand possibilities and " +
                                "no iteration count makes that expensive for anyone holding the " +
                                "file. The hash protects against a casual read, not against an " +
                                "offline attacker with the machine.",
                            fontSize = 10.sp,
                            color = colors.faint,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            // ── Medical card ───────────────────────────────────────────────
            SectionHeader("medical card")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Shown on the lock screen without unlocking, which is the only thing that " +
                            "makes it useful — a card nobody can reach is not an emergency card.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    MedicalField("Blood type", record.bloodType) {
                        record = record.copy(bloodType = it)
                    }
                    MedicalField("Allergies", record.allergies) {
                        record = record.copy(allergies = it)
                    }
                    MedicalField("Conditions", record.conditions) {
                        record = record.copy(conditions = it)
                    }
                    MedicalField("Medications", record.medications) {
                        record = record.copy(medications = it)
                    }
                    MedicalField("Notes", record.notes) { record = record.copy(notes = it) }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Toggle("Do not resuscitate", record.dnr) { record = record.copy(dnr = it) }
                        Spacer(Modifier.width(16.dp))
                        Toggle("Organ donor", record.organDonor) {
                            record = record.copy(organDonor = it)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        MedicalRecord.save(record)
                        revision++
                        note = "Medical card saved."
                    }) { Text("Save") }
                }
            }

            // ── Emergency contacts ─────────────────────────────────────────
            SectionHeader("emergency contacts")
            Card {
                Column {
                    contacts.forEachIndexed { index, contact ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name.ifBlank { contact.number }, fontSize = 13.sp)
                                Text(
                                    contact.number,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.faint,
                                )
                            }
                            Text(
                                "remove",
                                fontSize = 10.sp,
                                color = Color(0xFFE08080),
                                modifier = Modifier
                                    .clickableRow {
                                        EmergencyContacts.remove(contact.number)
                                        revision++
                                    }
                                    .padding(4.dp),
                            )
                        }
                    }
                    if (contacts.isEmpty()) {
                        Text(
                            "None. These are the numbers callable from the lock screen without " +
                                "unlocking, and the ones a duress code notifies.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Hairline()
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            placeholder = { Text("Name", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.width(150.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = contactNumber,
                            onValueChange = { contactNumber = it },
                            placeholder = { Text("Number", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.width(170.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            enabled = contactNumber.isNotBlank(),
                            onClick = {
                                EmergencyContacts.add(contactName.trim(), contactNumber.trim())
                                contactName = ""
                                contactNumber = ""
                                revision++
                            },
                        ) { Text("Add", fontSize = 12.sp) }
                    }
                }
            }

            SectionFooter(
                "The credential, the medical card and the contacts are all :core and all stored " +
                    "under the same keys the phone uses — so a duress code set on the phone works " +
                    "here, which it would not if the two platforms hashed differently."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun MedicalField(label: String, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = LocalPrismColors.current.faint, modifier = Modifier.width(96.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.clickableRow { onChange(!on) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = if (on) colors.accent else Color(0xFF2A2A31),
            shape = CircleShape,
            modifier = Modifier.size(9.dp),
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 11.sp)
    }
}

/**
 * The 3x3 pattern grid.
 *
 * ## The encoding is the cell sequence, same as Android's
 *
 * A pattern is stored as the digits of the cells it crossed, in order -- so "1-2-3-6-9" is the string
 * "12369". That is what `LockStore` hashes, and it has to be identical on both platforms or a pattern
 * set on the phone would not open the desktop.
 *
 * ## A cell is claimed on ENTRY, not on release
 *
 * Which is how a pattern is drawn: the pointer sweeps across cells while held down, and each new one
 * joins the sequence. Waiting for a release would make it a sequence of taps, which is a different
 * gesture and would not match what the phone recorded.
 */
@Composable
private fun PatternGrid(label: String, onDrawn: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var touched by remember { mutableStateOf<List<Int>>(emptyList()) }
    var drawing by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(150.dp)
                .background(Color(0xFF14141A), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (!change.pressed) {
                                if (drawing && touched.size >= 4) {
                                    onDrawn(touched.joinToString(""))
                                }
                                drawing = false
                                continue
                            }
                            if (!drawing) {
                                drawing = true
                                touched = emptyList()
                            }
                            val cell = size.width / 3f
                            val column = (change.position.x / cell).toInt().coerceIn(0, 2)
                            val rowIndex = (change.position.y / cell).toInt().coerceIn(0, 2)
                            // Claimed on entry, in order, once each. See the doc comment.
                            val index = rowIndex * 3 + column + 1
                            if (touched.lastOrNull() != index && index !in touched) {
                                touched = touched + index
                            }
                        }
                    }
                },
        ) {
            val cell = size.width / 3f
            fun centre(index: Int): Offset {
                val zero = index - 1
                return Offset(
                    (zero % 3) * cell + cell / 2f,
                    (zero / 3) * cell + cell / 2f,
                )
            }
            for (i in 1..9) {
                drawCircle(
                    color = if (i in touched) colors.accent else Color(0xFF32323C),
                    radius = cell * 0.13f,
                    center = centre(i),
                )
            }
            touched.zipWithNext().forEach { (a, b) ->
                drawLine(
                    color = colors.accent.copy(alpha = 0.7f),
                    start = centre(a),
                    end = centre(b),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label + (if (touched.size in 1..3) " — at least four points" else ""),
            fontSize = 10.sp,
            color = colors.faint,
        )
    }
}

/**
 * The lock screen itself, over the whole window.
 *
 * Mounted beside the rest of the window rather than as a page, because a lock that lived in the
 * navigation rail would be a page the user could navigate away from. This covers everything and
 * consumes input; what it does NOT do is cover the rest of the machine, and it says so.
 */
@Composable
fun LockOverlay() {
    val colors = LocalPrismColors.current
    var locked by remember { mutableStateOf(PrismLockScope.isLocked()) }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { locked = it }
        PrismLockScope.onChange(listener)
        onDispose { PrismLockScope.forget(listener) }
    }

    if (!locked) return

    val mechanism = remember(locked) { LockStore.mechanism() }
    var entered by remember(locked) { mutableStateOf("") }
    var wrong by remember(locked) { mutableStateOf(0) }
    var showCard by remember(locked) { mutableStateOf(false) }

    fun attempt(secret: String) {
        // ONE PATH FOR BOTH CREDENTIALS. unlock() reports which one it was and this does not look at
        // the answer beyond "not wrong": a branch here would be a visible difference, and a visible
        // difference is what a duress code exists to avoid.
        val outcome = PrismLockScope.unlock(secret)
        if (outcome == LockStore.Outcome.WRONG) {
            wrong++
            entered = ""
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07070A))) {
        Column(
            Modifier.fillMaxSize().padding(40.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(50.dp))
            Text("Prism is locked", fontSize = 22.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "This locks Prism, not the computer.",
                fontSize = 11.sp,
                color = colors.faint,
            )
            Spacer(Modifier.height(28.dp))

            if (mechanism == LockStore.Mechanism.PATTERN) {
                PatternGrid(label = "Draw your pattern", onDrawn = { attempt(it) })
            } else {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { typed ->
                        entered = if (mechanism == LockStore.Mechanism.PIN) {
                            typed.filter { it.isDigit() }
                        } else typed
                    },
                    placeholder = { Text(mechanism?.label ?: "Credential", fontSize = 13.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.width(260.dp),
                )
                Spacer(Modifier.height(10.dp))
                Button(enabled = entered.isNotBlank(), onClick = { attempt(entered) }) {
                    Text("Unlock")
                }
            }

            if (wrong > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "That is not right. " + wrong + " attempt(s).",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB4B4),
                )
            }

            Spacer(Modifier.height(30.dp))
            // Reachable WITHOUT unlocking, which is the only thing that makes an emergency card
            // worth having. The trade is deliberate and it is the same one every phone makes.
            val record = remember(showCard) { MedicalRecord.get() }
            val contacts = remember(showCard) { EmergencyContacts.all() }
            if (!record.isEmpty || contacts.isNotEmpty()) {
                Text(
                    if (showCard) "hide medical information" else "medical information",
                    fontSize = 12.sp,
                    color = colors.accent,
                    modifier = Modifier.clickableRow { showCard = !showCard }.padding(6.dp),
                )
            }
            if (showCard) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF16161A),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(420.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        listOf(
                            "Blood type" to record.bloodType,
                            "Allergies" to record.allergies,
                            "Conditions" to record.conditions,
                            "Medications" to record.medications,
                            "Notes" to record.notes,
                        ).forEach { (label, value) ->
                            if (value.isBlank()) return@forEach
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    color = colors.faint,
                                    modifier = Modifier.width(92.dp),
                                )
                                Text(value, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                        if (record.dnr) {
                            Text(
                                "DO NOT RESUSCITATE",
                                fontSize = 12.sp,
                                color = Color(0xFFFFB4B4),
                            )
                        }
                        if (record.organDonor) {
                            Text("Organ donor", fontSize = 12.sp, color = Color(0xFF9BE8B4))
                        }
                        if (contacts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("emergency contacts", fontSize = 10.sp, color = colors.accent)
                            contacts.forEach {
                                Text(
                                    it.name.ifBlank { "contact" } + " — " + it.number,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
