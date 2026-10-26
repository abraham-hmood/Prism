package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.AdbBridge
import com.prism.launcher.pdb.Pdb
import com.prism.launcher.pdb.PdbAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop Prism Debug Bridge.
 *
 * ## One dialog, four states
 *
 * List, add, verify and terminal are modes of one modal rather than four windows. A shell is a
 * thing you open FROM the device list and come back to; making each step its own window would
 * leave a trail of dialogs to close when a verification fails.
 *
 * ## The terminal is real
 *
 * [PdbTerminalPanel] owns a live [Pdb.Client] and reads its output on a coroutine. It sends one
 * command at a time so a `cat` and an `ls` cannot interleave, and it disposes the connection when
 * the dialog closes. That is the difference between a terminal and a text box that says it is one.
 */
@Composable
fun PdbDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf(Pdb.devices()) }
    var online by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var meshOn by remember { mutableStateOf(Pdb.isAvailable()) }
    var adding by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Pdb.Target>?>(null) }
    var verifyTarget by remember { mutableStateOf<Pdb.Target?>(null) }
    var verifyClient by remember { mutableStateOf<Pdb.Client?>(null) }
    var verifySalt by remember { mutableStateOf<ByteArray?>(null) }
    var phrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf<Pdb.Device?>(null) }

    DisposableEffect(Unit) {
        onDispose { runCatching { verifyClient?.close() } }
    }

    LaunchedEffect(Unit) {
        while (true) {
            meshOn = Pdb.isAvailable()
            val listed = runCatching { Pdb.devices() }.getOrDefault(emptyList())
            devices = listed
            online = withContext(Dispatchers.IO) {
                listed.associate { it.fingerprint to runCatching { it.target().online }.getOrDefault(false) }
            }
            delay(1_500L)
        }
    }

    LaunchedEffect(adding) {
        if (!adding) return@LaunchedEffect
        candidates = null
        candidates = withContext(Dispatchers.IO) {
            runCatching { Pdb.candidates().filter { it.online } }.getOrDefault(emptyList())
        }
    }

    fun startVerify(target: Pdb.Target) {
        message = ""
        phrase = ""
        busy = true
        verifyTarget = target
        scope.launch {
            val client = Pdb.Client(target)
            try {
                val hello = withContext(Dispatchers.IO) {
                    client.connect()
                    client.hello()
                }
                when (hello) {
                    is Pdb.Hello.Trusted -> {
                        // client.target, not target: the target may have stated a current identity
                        // that differs from the record this dialog started with, and the record
                        // has already been moved to it. Remembering the old one would add a
                        // second, unusable entry for the same device.
                        Pdb.remember(client.target, client.pairedSecret)
                        client.close()
                        devices = Pdb.devices()
                        verifyTarget = null
                        adding = false
                        busy = false
                        message = target.name + " was already verified."
                    }
                    is Pdb.Hello.Challenge -> {
                        verifyClient = client
                        verifySalt = hello.salt
                        busy = false
                    }
                    is Pdb.Hello.Denied -> {
                        client.close()
                        verifyTarget = null
                        busy = false
                        message = hello.reason
                    }
                }
            } catch (failure: Exception) {
                client.close()
                verifyTarget = null
                busy = false
                message = "Could not reach " + target.name + ": " +
                    (failure.message ?: "connection failed") + "."
            }
        }
    }

    fun prove() {
        val target = verifyTarget ?: return
        val client = verifyClient ?: return
        val salt = verifySalt ?: return
        if (phrase.isBlank() || busy) return
        busy = true
        message = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.prove(phrase, salt).getOrThrow() }
            }
            if (result.isSuccess) {
                Pdb.remember(client.target, client.pairedSecret)
                client.close()
                verifyClient = null
                verifyTarget = null
                adding = false
                devices = Pdb.devices()
                message = "Verified " + target.name + "."
            } else {
                client.close()
                verifyClient = null
                verifySalt = null
                phrase = ""
                message = (result.exceptionOrNull()?.message ?: "The phrase was refused.") +
                    " Start again to see a new phrase on " + target.name + "."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = {
            runCatching { verifyClient?.close() }
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { verifyClient?.close() }
                onDismiss()
            }) { Text("Done") }
        },
        title = { Text("Prism Debug Bridge") },
        text = {
            Column(Modifier.widthIn(min = 620.dp, max = 740.dp).heightIn(max = 610.dp)) {
                if (terminal != null) {
                    PdbTerminalPanel(terminal!!, colors, onBack = { terminal = null })
                } else if (verifyTarget != null) {
                    val target = verifyTarget!!
                    Text(
                        "Type the code shown on " + target.name + ".",
                        fontSize = 15.sp,
                        color = colors.onSurface,
                    )
                    Text(
                        "It is displayed there and is never sent over the network. The code is " +
                            "used once. Case matters; the spaces are only there to group it.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text("Code") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                        ),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = { prove() }, enabled = !busy && phrase.isNotBlank()) {
                            if (busy) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.onSurface,
                                )
                            } else {
                                Text("Verify")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching { verifyClient?.close() }
                                verifyClient = null
                                verifySalt = null
                                verifyTarget = null
                                adding = true
                            },
                            enabled = !busy,
                        ) { Text("Back") }
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            fontSize = 12.sp,
                            color = if (busy) colors.faint else Color(0xFFFF8A8A),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else if (adding) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            adding = false
                            verifyTarget = null
                            message = ""
                        }) { Text("‹ Back") }
                        Spacer(Modifier.weight(1f))
                        Text("Add a device", fontSize = 16.sp, color = colors.onSurface)
                    }
                    Text(
                        if (meshOn) Pdb.meshState() else "The mesh is off. Turn it on first.",
                        fontSize = 12.sp,
                        color = if (meshOn) colors.faint else Color(0xFFFF8A8A),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val list = candidates
                    when {
                        !meshOn -> Text(
                            "Prism Debug Bridge only works while this device is serving or connected " +
                                "to a meshnet.",
                            fontSize = 13.sp,
                            color = colors.faint,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        list == null -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Looking for trusted devices on the mesh…", fontSize = 13.sp, color = colors.faint)
                        }
                        list.isEmpty() -> Text(
                            "No online trusted devices. Pair one under Trusted devices, or bring " +
                                "the other device back onto the mesh.",
                            fontSize = 13.sp,
                            color = colors.faint,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(list, key = { it.fingerprint }) { target ->
                                Surface(
                                    color = colors.surfaceRaised,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clickable { startVerify(target) },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(target.name.ifBlank { "Prism device" }, fontSize = 15.sp, color = colors.onSurface)
                                            Text(
                                                target.platform.ifBlank { "device" } + " · " + target.ip,
                                                fontSize = 11.sp,
                                                color = colors.faint,
                                            )
                                        }
                                        Text("Verify", fontSize = 13.sp, color = colors.accent)
                                    }
                                }
                            }
                        }
                    }
                    if (message.isNotBlank()) {
                        Text(message, fontSize = 12.sp, color = Color(0xFFFF8A8A), modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    Text(
                        "A shell on a device you have trusted, carried over the mesh. It needs no " +
                            "USB cable and no wireless debugging — the target runs the command in its " +
                            "own Prism process. Commands that need Android's `shell` user or root will " +
                            "report their own permission error.",
                        fontSize = 12.sp,
                        color = colors.faint,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (meshOn) Pdb.meshState() else "The mesh is off.",
                            fontSize = 12.sp,
                            color = if (meshOn) colors.accent else Color(0xFFFF8A8A),
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            color = colors.accent,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(34.dp)
                                .clickable(enabled = meshOn) { adding = true },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (devices.isEmpty()) {
                        Text(
                            "No devices bridged yet. Tap + to verify one.",
                            fontSize = 13.sp,
                            color = colors.faint,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp)) {
                            items(devices, key = { it.fingerprint }) { device ->
                                val isOnline = online[device.fingerprint] == true
                                Surface(
                                    color = colors.surfaceRaised,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clickable(enabled = isOnline) { terminal = device },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(device.name.ifBlank { "Prism device" }, fontSize = 15.sp, color = colors.onSurface)
                                            Text(
                                                device.platform.ifBlank { "device" } + " · " +
                                                    device.lastIp.ifBlank { "no address" } + " · " +
                                                    if (isOnline) "online" else "offline",
                                                fontSize = 11.sp,
                                                color = if (isOnline) colors.accent else colors.faint,
                                            )
                                        }
                                        Text(
                                            if (isOnline) "Open" else "Offline",
                                            fontSize = 13.sp,
                                            color = if (isOnline) colors.accent else colors.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Verification is mutual and remembered per device. The code is shown " +
                            "only on the device being opened and can be used once.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (message.isNotBlank()) {
                        Text(message, fontSize = 12.sp, color = colors.faint, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
    )
}

/**
 * The terminal for one bridged device.
 *
 * It keeps one connection for the whole panel. Closing the dialog disposes it, which sends `bye`
 * and closes the socket; a shell that outlived its window would be a command still running that
 * nobody can see or stop.
 */
@Composable
private fun PdbTerminalPanel(
    device: Pdb.Device,
    colors: PrismColors,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<Pdb.Client?>(null) }
    var status by remember { mutableStateOf("Connecting…") }
    var failed by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var adbRunner by remember { mutableStateOf<PdbAdb?>(null) }
    // Set while this device is waiting for the code shown on the target. The CONNECTION STAYS
    // OPEN in this state: the target is holding its side of the handshake while it shows the
    // phrase, and closing here is what made that screen flash and vanish before anybody could
    // read it.
    var needsPhrase by remember { mutableStateOf(false) }
    var challengeSalt by remember { mutableStateOf<ByteArray?>(null) }
    var phrase by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<AdbBridge.Running?>(null) }
    var bridging by remember { mutableStateOf(false) }
    var adbVersion by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val liveClient = rememberUpdatedState(client)
    val liveBridge = rememberUpdatedState(bridge)

    DisposableEffect(device.fingerprint) {
        onDispose {
            liveBridge.value?.let { old ->
                Thread {
                    runCatching { AdbBridge.stop(old) }
                }.apply { isDaemon = true }.start()
            }
            runCatching { liveClient.value?.bye() }
            runCatching { liveClient.value?.close() }
        }
    }

    LaunchedEffect(Unit) {
        adbVersion = withContext(Dispatchers.IO) {
            if (AdbBridge.available()) AdbBridge.version() else null
        }
    }

    // Opens the shell on a connection whose pair is already proved. Shared by the ordinary path
    // and by the one that follows a phrase, so a verified-now session and a remembered one behave
    // identically from here on.
    val openShell: suspend (Pdb.Client) -> Unit = { connection ->
        val opened = withContext(Dispatchers.IO) { connection.open() }
        if (opened.isFailure) {
            throw IllegalStateException(
                opened.exceptionOrNull()?.message ?: "The target refused the shell.",
            )
        }
        connected = true
        needsPhrase = false
        challengeSalt = null
        phrase = ""
        status = "Connected over the mesh"
        output += "Prism Debug Bridge · " + device.name + "\n" +
            "Commands run as the Prism app on that device. Type `help`.\n\n"
        adbRunner = PdbAdb(connection, device) { text, _ ->
            javax.swing.SwingUtilities.invokeLater { output += text }
        }
    }

    LaunchedEffect(device.fingerprint) {
        val connection = Pdb.Client(device.target())
        client = connection
        try {
            val hello = withContext(Dispatchers.IO) {
                connection.connect()
                connection.hello()
            }
            when (hello) {
                is Pdb.Hello.Trusted -> openShell(connection)
                is Pdb.Hello.Challenge -> {
                    // THE TARGET IS SHOWING A CODE RIGHT NOW. Ask for it here and keep the
                    // socket up; the target is blocked waiting for the proof, and a close would
                    // dismiss its screen before the user could read anything off it.
                    challengeSalt = hello.salt
                    needsPhrase = true
                    status = "Needs verification"
                    failed = false
                    output = "Prism Debug Bridge · " + device.name + "\n" +
                        "This device is trusted, but a shell needs the code shown on its screen.\n\n"
                }
                is Pdb.Hello.Denied -> {
                    connection.close()
                    status = "Refused"
                    failed = true
                    output = hello.reason + "\n"
                }
            }
        } catch (failure: Exception) {
            connection.close()
            if (client === connection) {
                client = null
                connected = false
                status = "Disconnected"
                failed = true
                output = "Could not open the bridge: " +
                    (failure.message ?: "connection failed") + "\n"
            }
        }
    }

    /** Sends the code typed here as a proof, then opens the shell on the same connection. */
    fun verify() {
        val connection = client ?: return
        val salt = challengeSalt ?: return
        if (phrase.isBlank() || busy) return
        busy = true
        status = "Verifying…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { connection.prove(phrase, salt).getOrThrow() }
            }
            if (result.isSuccess) {
                // Remembered before the shell opens: the pair secret is what every later session
                // authenticates with, and losing it would mean another code.
                runCatching { Pdb.remember(connection.target, connection.pairedSecret) }
                try {
                    openShell(connection)
                } catch (failure: Exception) {
                    runCatching { connection.close() }
                    client = null
                    needsPhrase = false
                    challengeSalt = null
                    phrase = ""
                    status = "Disconnected"
                    failed = true
                    output += "The code was accepted, but the shell did not open: " +
                        (failure.message ?: "closed") + "\n"
                }
            } else {
                // The target refuses a wrong code and drops the session, so there is nothing to
                // retry on this socket. Say so, then let the user open the device again for a new
                // code rather than leaving a dead connection on screen.
                runCatching { connection.close() }
                client = null
                needsPhrase = false
                challengeSalt = null
                phrase = ""
                status = "Not verified"
                failed = true
                output += (result.exceptionOrNull()?.message ?: "The code was refused.") + "\n" +
                    "Open the device again to see a new code on " + device.name + ".\n"
            }
            busy = false
        }
    }

    LaunchedEffect(output) {
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }

    fun run() {
        if (busy) return
        val raw = input.trim()
        if (raw.isEmpty()) return
        if (raw == "clear") {
            output = ""
            input = ""
            return
        }
        if (raw == "exit" || raw == "quit") {
            onBack()
            return
        }
        val runner = adbRunner ?: return
        input = ""
        busy = true
        output += "\n$ " + raw + "\n"
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { runner.run(raw) }
                    .getOrElse {
                        output += "Could not send the command: " + (it.message ?: "closed") + "\n"
                        1
                    }
            }
            busy = false
            if (code != 0) output += "[exit " + code + "]\n"
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                runCatching { client?.bye() }
                runCatching { client?.close() }
                client = null
                onBack()
            }) { Text("‹ Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(device.name.ifBlank { "Prism device" }, fontSize = 15.sp, color = colors.onSurface)
                Text(
                    status,
                    fontSize = 11.sp,
                    color = if (failed) Color(0xFFFF8A8A) else colors.accent,
                )
            }
            Text("PDB", fontSize = 10.sp, letterSpacing = 1.2.sp, color = colors.faint)
        }

        Surface(
            color = Color(0xFF08080C),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(360.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                SelectionContainer {
                    Text(
                        output.ifEmpty { " " },
                        color = Color(0xFFD7D7E0),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(12.dp),
                    )
                }
            }
        }

        if (needsPhrase) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Code shown on " + device.name.ifBlank { "the other device" }, fontSize = 12.sp) },
                    placeholder = { Text("12 characters, case-sensitive", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { verify() }, enabled = !busy && phrase.isNotBlank()) {
                    Text(if (busy) "Verifying…" else "Verify")
                }
            }
            Text(
                "The code stays on that screen and is never sent. Typing it here proves you can " +
                    "see it, which is what a shell on somebody's device requires.",
                fontSize = 10.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("adb shell ls /sdcard", fontSize = 12.sp) },
                singleLine = true,
                enabled = connected && !busy,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { run() }, enabled = connected && !busy && input.isNotBlank()) {
                Text("Run")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (adbVersion != null) {
                OutlinedButton(
                    onClick = {
                        if (bridging) return@OutlinedButton
                        val running = bridge
                        if (running != null) {
                            bridge = null
                            output += "\nADB bridge stopped. The device is no longer listed by adb.\n"
                            scope.launch {
                                withContext(Dispatchers.IO) { AdbBridge.stop(running) }
                            }
                        } else {
                            bridging = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        AdbBridge.start(device, manual = true) { line ->
                                            javax.swing.SwingUtilities.invokeLater {
                                                output += line + "\n"
                                            }
                                        }
                                    }
                                }
                                result.onSuccess { started ->
                                    bridge = started
                                    output += if (started.connected) {
                                        "\nADB bridge ready on " + started.serial + ".\n" +
                                            "  adb -s " + started.serial + " shell id\n" +
                                            "  adb -s " + started.serial + " logcat\n" +
                                            "  adb -s " + started.serial + " shell pm list packages\n\n"
                                    } else {
                                        "\nADB bridge is listening on " + started.serial +
                                            ", but adb did not attach.\n" +
                                            "  " + started.message.replace("\n", "\n  ") + "\n" +
                                            "  Is the adb server running? Try `adb start-server`.\n\n"
                                    }
                                }.onFailure { failure ->
                                    output += "\nCould not start the ADB bridge: " +
                                        (failure.message ?: "adb refused") + "\n"
                                }
                                bridging = false
                            }
                        }
                    },
                    enabled = connected && !bridging,
                ) {
                    Text(
                        when {
                            bridging -> "Bridging…"
                            bridge != null -> "Stop adb bridge"
                            else -> "Bridge to adb"
                        },
                    )
                }
                if (bridge != null) {
                    Text(
                        bridge!!.serial,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.accent,
                    )
                }
            }
        }
        Text(
            "Commands execute as the Prism app. Nothing here needs USB or wireless debugging.",
            fontSize = 10.sp,
            color = colors.faint,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (adbVersion != null) {
                "adb found: " + adbVersion + ". Bridging registers this device with the adb " +
                    "already on this machine; the terminal above works without it."
            } else {
                "No adb on this machine. The terminal works anyway; install Android platform-tools " +
                    "to expose the device to adb, Android Studio and scripts."
            },
            fontSize = 10.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
