package com.prism.core

import com.prism.launcher.pdb.JvmPdbHost
import com.prism.launcher.pdb.Pdb
import com.prism.launcher.pdb.PdbAdb
import com.prism.launcher.pdb.PdbDevices
import com.prism.launcher.pdb.PdbRemote
import com.prism.launcher.pdb.Phrase
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.WalletCipher
import com.prism.launcher.wallet.WalletVault
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bridge's two ends, spoken to each other.
 *
 * A handshake that compiles but cannot open a shell is the failure this test exists for: the
 * phrase proof, the verified-device write and the command transport all have to meet in the same
 * session before the terminal can work.
 */
class PdbTest {

    @Test
    fun `verification codes are mixed characters and normalise only spacing`() {
        val code = Phrase.generate()
        // Twelve characters shown in three groups of four; the spaces exist only to make it
        // readable, so the form the key is derived from has none of them.
        assertEquals(3, code.split(" ").size)
        assertEquals(Phrase.LENGTH, Phrase.normalise(code).length)
        assertTrue(Phrase.normalise(code).all { it in '!'..'~' }, "printable characters only: $code")

        // Case is part of the code, not something leniency may throw away.
        assertTrue(Phrase.normalise("aB3!") != Phrase.normalise("AB3!"))
        assertEquals("aB3!xY9", Phrase.normalise("  aB3! xY9\n"))
    }

    @Test
    fun `desktop tools keep their state without a shell`() {
        val root = java.nio.file.Files.createTempDirectory("pdb-tools").toFile()
        val previousHost = PrismPlatform.host
        val previousPdbHost = Pdb.host
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            Pdb.host = JvmPdbHost()

            val put = assertNotNull(PdbRemote.reply("settings put global wifi_on 1"))
            assertEquals(0, put.code, "settings put should succeed: " + put.err)
            val get = assertNotNull(PdbRemote.reply("settings get global wifi_on"))
            assertEquals("1", get.out.trim(), "settings get should read back what was put")
            val listed = assertNotNull(PdbRemote.reply("settings list global"))
            assertTrue(listed.out.contains("wifi_on=1"), "settings list should show the key: " + listed.out)

            assertNotNull(PdbRemote.reply("setprop ro.prism.test yes"))
            val property = assertNotNull(PdbRemote.reply("getprop ro.prism.test"))
            assertEquals("[yes]", property.out.trim(), "getprop should read what setprop stored")
            val all = assertNotNull(PdbRemote.reply("getprop"))
            assertTrue(all.out.contains("sys.platform"), "getprop should include the platform: " + all.out)

            val empty = assertNotNull(PdbRemote.reply("settings get secure missing"))
            assertEquals("null", empty.out.trim(), "an absent setting prints null, as Android does")

            val unknown = assertNotNull(PdbRemote.reply("settings put nowhere key 1"))
            assertEquals(2, unknown.code, "an unknown namespace should be refused")
            assertTrue(unknown.err.contains("namespace"), "the refusal should name the problem: " + unknown.err)
        } finally {
            Pdb.host = previousPdbHost
            PrismPlatform.host = previousHost
            root.deleteRecursively()
        }
    }

    @Test
    fun `a trusted device is verified with words and can run a command`() {
        val root = java.nio.file.Files.createTempDirectory("pdb-test").toFile()
        val trustDir = File(root, "trust").apply { mkdirs() }
        TrustedDevices.install(trustDir)
        TrustedDevices.localName = { "Test controller" }
        TrustedDevices.localPlatform = "test"
        val fingerprint = TrustedDevices.localFingerprint()
        File(trustDir, "trusted-devices.json").writeText(
            """[{"fp":"$fingerprint","name":"Test target","platform":"test","ip":"","seen":1,"out":[],"in":[],"confirmed":true}]""",
        )
        TrustedDevices.sender = { _, _, _ -> }
        TrustedDevices.peerCheck = { true }
        MeshTransport.install(
            isOnMesh = { true },
            peerCount = { 1 },
            peers = { listOf("127.0.0.1") },
            sendToPeer = { _, _, _ -> },
            broadcast = { _, _, _ -> },
        )

        PdbDevices.install(File(root, "pdb"))
        val previousHost = PrismPlatform.host
        val previousPdbHost = Pdb.host
        PrismPlatform.host = JvmHost(rootOverride = root)
        Pdb.host = JvmPdbHost()
        Pdb.shell = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            listOf("cmd.exe", "/c")
        } else {
            listOf("/bin/sh", "-c")
        }
        Pdb.workDir = { root }
        val promptRef = AtomicReference<Pdb.Prompt>()
        val promptReady = CountDownLatch(1)
        Pdb.onPrompt = { prompt ->
            promptRef.set(prompt)
            promptReady.countDown()
        }

        var client: Pdb.Client? = null
        try {
            val port = Pdb.start(0)
            assertTrue(port > 0, "the bridge should bind a port")

            val target = Pdb.Target(fingerprint, "Test target", "test", "127.0.0.1")
            client = Pdb.Client(target, port)
            client.connect()
            val hello = client.hello()
            assertIs<Pdb.Hello.Challenge>(hello, "an unverified pair should be challenged")

            assertTrue(promptReady.await(5, TimeUnit.SECONDS), "the target should show a code")
            val prompt = assertNotNull(promptRef.get())
            assertEquals(
                Phrase.LENGTH,
                Phrase.normalise(prompt.phrase).length,
                "the code should carry every generated character",
            )

            val proved = client.prove(prompt.phrase, hello.salt)
            assertTrue(proved.isSuccess, "the phrase shown on the target should be accepted")
            assertTrue(PdbDevices.isVerified(fingerprint), "verification should be remembered")

            assertTrue(client.open().isSuccess, "a verified pair should open a shell")
            client.exec(1, "echo prism-pdb")

            val output = StringBuilder()
            var exited = false
            var code = -1
            var events = 0
            while (!exited && events < 20) {
                events++
                val event = client.readEvent(4_000) ?: break
                if (event.type == "out" || event.type == "err") output.append(event.data)
                if (event.type == "exit") {
                    exited = true
                    code = event.code
                }
            }
            assertTrue(exited, "the command should report an exit")
            assertEquals(0, code, "a successful command should carry its exit status")
            assertTrue(output.contains("prism-pdb"), "output should arrive: " + output)

            client.exec(2, "prism-no-such-command-6774")
            var failedCode = 0
            var failedExit = false
            events = 0
            while (!failedExit && events < 20) {
                events++
                val event = client.readEvent(4_000) ?: break
                if (event.type == "exit") {
                    failedExit = true
                    failedCode = event.code
                }
            }
            assertTrue(failedExit, "the failed command should still report an exit")
            assertTrue(failedCode != 0, "a missing command should not look successful")

            // The adb-shaped client is what both terminals actually run. Exercise the file
            // channel through it, because a push that only works at the PDB layer is not a push
            // a user can perform.
            val adbOut = StringBuilder()
            val adbErr = StringBuilder()
            val adb = PdbAdb(
                client,
                Pdb.Device(fingerprint, "Test target", "test", "127.0.0.1", System.currentTimeMillis()),
            ) { text, error -> if (error) adbErr.append(text) else adbOut.append(text) }

            val upload = File(root, "push-payload.txt").apply { writeText("pdb file channel") }
            assertEquals(
                0,
                adb.run("adb push \"" + upload.absolutePath + "\" pdb-upload.txt"),
                "adb push failed: " + adbErr,
            )
            val landed = File(root, "pdb-upload.txt")
            assertTrue(landed.isFile, "adb push should create the target file: " + adbErr)
            assertEquals("pdb file channel", landed.readText())

            val pulled = File(root, "pull-copy.txt")
            assertEquals(
                0,
                adb.run("adb pull pdb-upload.txt \"" + pulled.absolutePath + "\""),
                "adb pull failed: " + adbErr,
            )
            assertTrue(pulled.isFile, "adb pull should create the local file: " + adbErr)
            assertEquals("pdb file channel", pulled.readText())

            val packageFile = File(root, "sample.bin").apply { writeText("stored package") }
            assertEquals(
                0,
                adb.run("adb install \"" + packageFile.absolutePath + "\""),
                "adb install failed: " + adbErr,
            )
            assertTrue(
                adbOut.contains("Stored"),
                "a package this host cannot run should be stored and named: " + adbOut,
            )
        } finally {
            runCatching { client?.close() }
            Pdb.onPrompt = null
            Pdb.shutdown()
            Pdb.host = previousPdbHost
            PrismPlatform.host = previousHost
            TrustedDevices.install(File(root, "trust-after").apply { mkdirs() })
            root.deleteRecursively()
        }
    }

    /**
     * The controller holding the target under an older identity than the one it now proves.
     *
     * This is the "the far side did not prove the new pair's key" failure. The target signs a
     * proof bound to the fingerprint it uses NOW, while the controller's record -- written before
     * a wallet import changed it -- names the value from before. Verifying against the record
     * failed even though both sides held the right secret, and there is nothing on screen that
     * could tell the user that. The announced identity is what the proof is checked against, and
     * one success moves both local records to it.
     */
    @Test
    fun `a stale target identity is repaired by the proof the target sends`() {
        val root = java.nio.file.Files.createTempDirectory("pdb-identity").toFile()
        val trustDir = File(root, "trust").apply { mkdirs() }
        val previousHost = PrismPlatform.host
        val previousPdbHost = Pdb.host
        var client: Pdb.Client? = null
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            WalletVault.installCipher(object : WalletCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(ciphertext: String): String? = ciphertext
            })
            WalletVault.import(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))

            TrustedDevices.install(trustDir)
            TrustedDevices.localName = { "Test target" }
            TrustedDevices.localPlatform = "test"
            TrustedDevices.sender = { _, _, _ -> }
            TrustedDevices.peerCheck = { true }
            MeshTransport.install(
                isOnMesh = { true },
                peerCount = { 1 },
                peers = { listOf("127.0.0.1") },
                sendToPeer = { _, _, _ -> },
                broadcast = { _, _, _ -> },
            )
            val current = TrustedDevices.localFingerprint()
            val stale = TrustedDevices.legacyFingerprintOf("Test target")
            assertTrue(stale != current, "the fixture needs an older identity to hold")

            // The target knows this controller; the controller's record for the target is stale.
            File(trustDir, "trusted-devices.json").writeText(
                """[{"fp":"$current","name":"Test target","platform":"test","ip":"127.0.0.1","seen":1,"out":["messages"],"in":["messages"],"confirmed":true}]""",
            )

            PdbDevices.install(File(root, "pdb"))
            Pdb.host = JvmPdbHost()
            Pdb.workDir = { root }
            val promptRef = AtomicReference<Pdb.Prompt>()
            val promptReady = CountDownLatch(1)
            Pdb.onPrompt = { prompt ->
                promptRef.set(prompt)
                promptReady.countDown()
            }

            val port = Pdb.start(0)
            assertTrue(port > 0, "the bridge should bind a port")
            client = Pdb.Client(Pdb.Target(stale, "Test target", "test", "127.0.0.1"), port)
            client.connect()
            val hello = client.hello()
            assertIs<Pdb.Hello.Challenge>(
                hello,
                "the pair should still be found from the old record: " +
                    (hello as? Pdb.Hello.Denied)?.reason,
            )

            assertTrue(promptReady.await(5, TimeUnit.SECONDS), "the target should show a code")
            val proved = client.prove(assertNotNull(promptRef.get()).phrase, hello.salt)
            assertTrue(
                proved.isSuccess,
                "the proof must be checked against the identity the target states: " +
                    proved.exceptionOrNull()?.message,
            )
            assertEquals(current, client.target.fingerprint, "the controller should adopt the new identity")
            assertTrue(
                PdbDevices.isVerified(current),
                "the pair secret should live under the identity now announced",
            )
            assertTrue(PdbDevices.all().none { it.fingerprint == stale }, "the stale record should be gone")
        } finally {
            runCatching { client?.close() }
            Pdb.onPrompt = null
            Pdb.shutdown()
            Pdb.host = previousPdbHost
            PrismPlatform.host = previousHost
            TrustedDevices.install(File(root, "trust-after").apply { mkdirs() })
            root.deleteRecursively()
        }
    }

    /**
     * The exact shape of a pair that "is added but not trusted".
     *
     * A device that paired before it had a wallet was stored under the fingerprint it had then.
     * Importing a wallet used to change its identity, so the record could never match again and the
     * bridge refused every session with "This device is not trusted" on a device that both lists
     * showed as trusted. The identity is pinned now, and a record from before that still has to be
     * reachable -- the device presents the value it used to have, one successful verification moves
     * the record to the value it uses now.
     */
    @Test
    fun `a device paired before its wallet can still open the bridge`() {
        val root = java.nio.file.Files.createTempDirectory("pdb-legacy").toFile()
        val trustDir = File(root, "trust").apply { mkdirs() }
        val previousHost = PrismPlatform.host
        val previousPdbHost = Pdb.host
        var client: Pdb.Client? = null
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            WalletVault.installCipher(object : WalletCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(ciphertext: String): String? = ciphertext
            })
            WalletVault.import(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))

            TrustedDevices.install(trustDir)
            TrustedDevices.localName = { "Test controller" }
            TrustedDevices.localPlatform = "test"
            TrustedDevices.sender = { _, _, _ -> }
            TrustedDevices.peerCheck = { true }
            val announced = TrustedDevices.localFingerprint()
            val beforeWallet = TrustedDevices.legacyFingerprintOf("Test controller")
            assertTrue(
                announced != beforeWallet,
                "the fixture needs a device whose wallet changed the value it used to announce",
            )

            // What the other device stored when this one paired with it before its wallet existed.
            File(trustDir, "trusted-devices.json").writeText(
                """[{"fp":"$beforeWallet","name":"Test controller","platform":"test","ip":"127.0.0.1","seen":1,"out":["messages"],"in":["messages"],"confirmed":true}]""",
            )

            PdbDevices.install(File(root, "pdb"))
            Pdb.host = JvmPdbHost()
            Pdb.workDir = { root }
            val promptRef = AtomicReference<Pdb.Prompt>()
            val promptReady = CountDownLatch(1)
            Pdb.onPrompt = { prompt ->
                promptRef.set(prompt)
                promptReady.countDown()
            }

            val port = Pdb.start(0)
            assertTrue(port > 0, "the bridge should bind a port")
            // The target's own identity is the one this side always had for it; only the controller
            // changed, so the pair secret and the phrase are still tied to this same machine.
            val target = Pdb.Target(announced, "Test controller", "test", "127.0.0.1")
            client = Pdb.Client(target, port)
            client.connect()
            val hello = client.hello()
            assertIs<Pdb.Hello.Challenge>(
                hello,
                "an old but genuine record has to be found, not refused as untrusted",
            )

            assertTrue(promptReady.await(5, TimeUnit.SECONDS), "the target should show a phrase")
            val proved = client.prove(assertNotNull(promptRef.get()).phrase, hello.salt)
            assertTrue(proved.isSuccess, "the phrase should be accepted: " + proved.exceptionOrNull()?.message)
            assertTrue(
                PdbDevices.isVerified(announced),
                "the secret belongs to the identity the device announces now",
            )
            assertEquals(
                announced,
                TrustedDevices.all().single().fingerprint,
                "one verification should move the record off the old identity",
            )
            assertTrue(client.open().isSuccess, "the bridge should open after the repair")
        } finally {
            runCatching { client?.close() }
            Pdb.onPrompt = null
            Pdb.shutdown()
            Pdb.host = previousPdbHost
            PrismPlatform.host = previousHost
            TrustedDevices.install(File(root, "trust-after").apply { mkdirs() })
            root.deleteRecursively()
        }
    }

    /**
     * The live failure this was built from: "This device is not trusted" on a device that is.
     *
     * The saved log showed a bridge hello from a phone whose announced identity matched none of the
     * target's confirmed records, with exactly one record present -- a pair that both lists showed
     * and neither could act on. The identity is derived from a wallet namespace and a device name,
     * and a record written by an older build can hold a value no current derivation reproduces, so
     * there is nothing on either side that could be "fixed" by matching harder.
     *
     * What must happen instead is the ceremony a first pairing uses: the target recognises the
     * record by the name and address it was paired from, shows a phrase, and re-adopts the record
     * under the identity the device announces once the phrase proves it is the same physical device.
     */
    @Test
    fun `a record whose identity can no longer be derived is re-adopted by name`() {
        val root = java.nio.file.Files.createTempDirectory("pdb-unmatchable").toFile()
        val trustDir = File(root, "trust").apply { mkdirs() }
        val previousHost = PrismPlatform.host
        val previousPdbHost = Pdb.host
        var client: Pdb.Client? = null
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            WalletVault.installCipher(object : WalletCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(ciphertext: String): String? = ciphertext
            })
            WalletVault.import(Bip39.generate(Bip39.MIN_NEW_WALLET_WORDS).joinToString(" "))

            TrustedDevices.install(trustDir)
            TrustedDevices.localName = { "Test controller" }
            TrustedDevices.localPlatform = "test"
            TrustedDevices.sender = { _, _, _ -> }
            TrustedDevices.peerCheck = { true }
            MeshTransport.install(
                isOnMesh = { true },
                peerCount = { 1 },
                peers = { listOf("127.0.0.1") },
                sendToPeer = { _, _, _ -> },
                broadcast = { _, _, _ -> },
            )
            val announced = TrustedDevices.localFingerprint()
            val unreachable = "0123456789abcdef"
            assertTrue(
                announced != unreachable && TrustedDevices.legacyFingerprintOf("Test controller") != unreachable,
                "the fixture needs a value NEITHER derivation produces",
            )

            // The record the target holds: a confirmed pair whose name, address AND identity this
            // build can no longer reproduce -- the worst case, and the one the live log showed
            // (one confirmed trust, and a hello matching none of it).
            File(trustDir, "trusted-devices.json").writeText(
                """[{"fp":"$unreachable","name":"An Older Name","platform":"test","ip":"10.8.0.44","seen":1,"out":["messages"],"in":["messages"],"confirmed":true}]""",
            )

            PdbDevices.install(File(root, "pdb"))
            Pdb.host = JvmPdbHost()
            Pdb.workDir = { root }
            val promptRef = AtomicReference<Pdb.Prompt>()
            val promptReady = CountDownLatch(1)
            Pdb.onPrompt = { prompt ->
                promptRef.set(prompt)
                promptReady.countDown()
            }

            val port = Pdb.start(0)
            assertTrue(port > 0, "the bridge should bind a port")
            client = Pdb.Client(Pdb.Target(unreachable, "Test controller", "test", "127.0.0.1"), port)
            client.connect()
            val hello = client.hello()
            assertIs<Pdb.Hello.Challenge>(
                hello,
                "a confirmed pair must be offered a phrase, not refused as untrusted: " +
                    (hello as? Pdb.Hello.Denied)?.reason,
            )

            assertTrue(promptReady.await(5, TimeUnit.SECONDS), "the target should show a phrase")
            val proved = client.prove(assertNotNull(promptRef.get()).phrase, hello.salt)
            assertTrue(proved.isSuccess, "the phrase should be accepted: " + proved.exceptionOrNull()?.message)
            assertEquals(announced, client.target.fingerprint, "the controller adopts the stated identity")
            assertEquals(
                announced,
                TrustedDevices.all().single().fingerprint,
                "the record moves to the identity the device announces",
            )
            assertNotNull(
                TrustedDevices.byFingerprint(unreachable),
                "the value it moved from has to keep finding it",
            )
            assertTrue(PdbDevices.isVerified(announced), "the bridge secret is recorded")
            assertTrue(client.open().isSuccess, "the bridge should open after the repair")
        } finally {
            runCatching { client?.close() }
            Pdb.onPrompt = null
            Pdb.shutdown()
            Pdb.host = previousPdbHost
            PrismPlatform.host = previousHost
            TrustedDevices.install(File(root, "trust-after").apply { mkdirs() })
            root.deleteRecursively()
        }
    }
}
