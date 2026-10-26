package com.prism.desktop

import com.prism.core.MeshTransport
import com.prism.launcher.pdb.JvmPdbHost
import com.prism.launcher.pdb.Pdb
import com.prism.launcher.pdb.PdbDevices
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `adb` on this machine, talking to a Prism bridge.
 *
 * The transport is the part that cannot be checked by unit-testing the PDB client: adb has its own
 * CNXN/OPEN/WRTE framing, its own shell-v2 payloads and its own notion of a serial. This starts a
 * real PDB listener, registers it with the real adb server, and runs a command through `adb shell`,
 * which is the path a user takes in Windows Terminal or a Linux shell.
 */
class AdbBridgeTest {

    @Test
    fun `adb devices lists a pdb bridge and adb shell returns its output`() {
        if (!AdbBridge.available()) return
        val root = java.nio.file.Files.createTempDirectory("prism-adb-test").toFile()
        val previousShell = Pdb.shell
        val previousHost = Pdb.host
        val previousWorkDir = Pdb.workDir
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        var running: AdbBridge.Running? = null
        try {
            val trustDir = File(root, "trust").apply { mkdirs() }
            TrustedDevices.install(trustDir)
            TrustedDevices.localName = { "ADB test controller" }
            TrustedDevices.localPlatform = "windows"
            val fingerprint = TrustedDevices.localFingerprint()
            File(trustDir, "trusted-devices.json").writeText(
                """[{"fp":"$fingerprint","name":"ADB test target","platform":"windows","ip":"127.0.0.1","seen":1,"out":[],"in":[],"confirmed":true}]""",
            )
            TrustedDevices.peerCheck = { true }
            TrustedDevices.sender = { _, _, _ -> }
            MeshTransport.install(
                isOnMesh = { true },
                peerCount = { 1 },
                peers = { listOf("127.0.0.1") },
                sendToPeer = { _, _, _ -> },
                broadcast = { _, _, _ -> },
            )
            PdbDevices.install(File(root, "pdb"))
            val trust = TrustedDevices.all().first { it.fingerprint == fingerprint }
            PdbDevices.add(trust, "127.0.0.1", secret)

            Pdb.shell = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                listOf("cmd.exe", "/c")
            } else {
                listOf("/bin/sh", "-c")
            }
            Pdb.workDir = { root }
            Pdb.host = JvmPdbHost()
            Pdb.start(0)
            val device = Pdb.devices().first()
            running = AdbBridge.start(device, manual = true, port = Pdb.boundPort)

            val waited = waitForDevice(running.serial)
            assertTrue(waited, "adb should attach the bridge: " + running.message)

            val output = adb("-s", running.serial, "shell", "echo", "prism-adb")
            assertTrue(output.contains("prism-adb"), "adb shell output was: [" + output + "]")

            val state = adb("-s", running.serial, "get-state")
            assertEquals("device", state.trim())

            val upload = File(root, "push-me.txt").apply { writeText("prism sync payload") }
            val pushed = adb("-s", running.serial, "push", upload.absolutePath, "/data/local/tmp/push-me.txt")
            assertTrue(pushed.contains("1 file") || pushed.contains("pushed"), "adb push said: " + pushed)
            val pulled = File(root, "pulled.txt")
            val pullOutput = adb("-s", running.serial, "pull", "/data/local/tmp/push-me.txt", pulled.absolutePath)
            assertTrue(pulled.isFile, "adb pull should create the local file; said: " + pullOutput)
            assertEquals("prism sync payload", pulled.readText())

            val packageFile = File(root, "test-package.apk").apply { writeText("not a real apk") }
            val install = adb("-s", running.serial, "install", packageFile.absolutePath)
            assertTrue(
                install.contains("Stored") || install.contains("Installing"),
                "adb install should reach the target installer; said: " + install,
            )
        } finally {
            running?.let { runCatching { AdbBridge.stop(it) } }
            Pdb.shutdown()
            Pdb.shell = previousShell
            Pdb.host = previousHost
            Pdb.workDir = previousWorkDir
            TrustedDevices.peerCheck = { com.prism.core.MeshCore.isPeer(it) }
            root.deleteRecursively()
        }
    }

    private fun waitForDevice(serial: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val text = adb("devices")
            if (text.lineSequence().any { it.startsWith(serial) && it.contains("device") }) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun adb(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf(AdbBridge.adb()!!) + args)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            ""
        } else {
            process.inputStream.bufferedReader().readText()
        }
    }.getOrDefault("")
}
