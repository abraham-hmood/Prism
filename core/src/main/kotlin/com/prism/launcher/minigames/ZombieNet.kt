package com.prism.launcher.minigames

import com.prism.core.MeshTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Four-player Zombies on the mesh, without a new transport.
 *
 * ## Why this is not [MinigameMesh.seek]
 *
 * Pong and Chess are two-player games: one asker, one accepter, one opponent. A co-op wave needs a
 * host and up to three joiners, and it needs the host to keep simulating after the lobby. The
 * existing handshake stops looking after the first accept, which is correct for a duel and wrong
 * here. This object therefore reads the same seek/accept opcodes before the two-player path and
 * claims only payloads with its own `zombies|` prefix.
 *
 * ## Host authority
 *
 * The host owns the zombies, the waves and every player's health. A joiner sends input and draws
 * the snapshot it gets back. That is the only arrangement that stays consistent on a UDP mesh where
 * packets are dropped: two devices simulating bites independently would disagree about who died.
 *
 * ## The snapshot is compact
 *
 * One state packet is a pipe-separated line, not JSON. At four players and twenty-four enemies it
 * is roughly a kilobyte, which fits a LAN datagram; at 8 Hz that is a handful of kilobytes a
 * second, and a dropped packet only costs one interpolation step.
 */
object ZombieNet {

    const val GAME = "zombies"
    const val MAX_PLAYERS = 4
    private const val PREFIX = "zombies|"
    private const val STATE_PREFIX = "z|s|"
    private const val INPUT_PREFIX = "z|i|"
    private const val WELCOME_PREFIX = "z|w|"
    private const val BYE_PREFIX = "z|b|"

    enum class Mode { NONE, HOST, CLIENT }

    data class Welcome(val id: Int, val seed: Long, val hostName: String)

    @Volatile var mode: Mode = Mode.NONE
        private set
    @Volatile var hostIp: String = ""
        private set
    @Volatile var localId: Int = 0
        private set
    @Volatile var seed: Long = 0L
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile var lastError: String = ""
        private set

    private val inputs = ConcurrentHashMap<Int, FpsInput>()
    private val peerIds = ConcurrentHashMap<String, Int>()
    private val peerNames = ConcurrentHashMap<Int, String>()
    private val peerSeen = ConcurrentHashMap<Int, Long>()
    private val joins = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val leaves = ConcurrentLinkedQueue<Int>()
    private val welcomes = ConcurrentLinkedQueue<Welcome>()
    private val snapshot = AtomicReference<ZombieSnapshot?>(null)
    private val joinName = AtomicReference("")
    private val lastPublish = AtomicReference(0L)
    private val lastInput = AtomicReference(0L)
    private var announceThread: Thread? = null
    @Volatile private var running = false

    fun available(): Boolean = runCatching { MinigameMesh.isAvailable() }.getOrDefault(false)
    fun peerCount(): Int = peerIds.size
    fun isHost(): Boolean = mode == Mode.HOST
    fun isClient(): Boolean = mode == Mode.CLIENT

    fun host(name: String): Boolean {
        if (!available()) {
            lastError = "Not on a Prism mesh"
            return false
        }
        stop()
        seed = System.currentTimeMillis() xor (name.hashCode().toLong() shl 17)
        hostName = name.ifBlank { "HOST" }
        localId = 0
        mode = Mode.HOST
        running = true
        install()
        announceThread = Thread({ announceLoop() }, "prism-zombies-host").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun join(name: String): Boolean {
        if (!available()) {
            lastError = "Not on a Prism mesh"
            return false
        }
        stop()
        joinName.set(name.ifBlank { "SURVIVOR" })
        mode = Mode.CLIENT
        running = true
        install()
        announceThread = Thread({ joinLoop() }, "prism-zombies-join").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (running && mode == Mode.CLIENT && hostIp.isNotBlank()) {
            runCatching { MeshTransport.sendToPeer(hostIp, MinigameMesh.OPCODE_MOVE, "$BYE_PREFIX|${joinName.get()}") }
        }
        running = false
        announceThread?.interrupt()
        announceThread = null
        MinigameMesh.setRawListeners()
        inputs.clear()
        peerIds.clear()
        peerNames.clear()
        peerSeen.clear()
        joins.clear()
        leaves.clear()
        welcomes.clear()
        snapshot.set(null)
        mode = Mode.NONE
        hostIp = ""
        seed = 0L
        localId = 0
        lastPublish.set(0L)
    }

    private fun install() {
        MinigameMesh.setRawListeners(
            seek = { ip, payload -> onSeek(ip, payload) },
            accept = { ip, payload -> onJoinRequest(ip, payload) },
            move = { ip, payload -> onMove(ip, payload) },
        )
    }

    private fun announceLoop() {
        while (running && mode == Mode.HOST) {
            runCatching {
                MeshTransport.broadcast(
                    MinigameMesh.OPCODE_SEEK,
                    "${PREFIX}host|$seed|$hostName|${MAX_PLAYERS - 1}",
                    null,
                )
            }
            sleep(1200)
        }
    }

    private fun joinLoop() {
        while (running && mode == Mode.CLIENT) {
            val ip = hostIp
            if (ip.isNotBlank() && welcomes.isEmpty()) {
                runCatching {
                    MeshTransport.sendToPeer(ip, MinigameMesh.OPCODE_ACCEPT, "${PREFIX}join|${joinName.get()}")
                }
            }
            sleep(1100)
        }
    }

    private fun onSeek(ip: String, payload: String) {
        if (mode != Mode.CLIENT || !payload.startsWith("${PREFIX}host|")) return
        if (hostIp.isNotBlank() && welcomes.isNotEmpty()) return
        val parts = payload.removePrefix("${PREFIX}host|").split("|")
        val hostSeed = parts.getOrNull(0)?.toLongOrNull() ?: return
        val name = parts.getOrNull(1).orEmpty().ifBlank { "HOST" }
        hostIp = ip
        seed = hostSeed
        hostName = name
        runCatching {
            MeshTransport.sendToPeer(ip, MinigameMesh.OPCODE_ACCEPT, "${PREFIX}join|${joinName.get()}")
        }
    }

    private fun onJoinRequest(ip: String, payload: String) {
        if (mode != Mode.HOST || !payload.startsWith("${PREFIX}join|")) return
        val name = payload.removePrefix("${PREFIX}join|").trim().ifBlank { "SURVIVOR" }
        val existing = peerIds[ip]
        if (existing != null) {
            peerNames[existing] = name
            peerSeen[existing] = System.currentTimeMillis()
            return
        }
        if (peerIds.size >= MAX_PLAYERS - 1) return
        val used = peerIds.values.toSet()
        val id = (1..4).firstOrNull { it !in used } ?: return
        peerIds[ip] = id
        peerNames[id] = name
        peerSeen[id] = System.currentTimeMillis()
        joins.add(id to name)
        runCatching {
            MeshTransport.sendToPeer(ip, MinigameMesh.OPCODE_MOVE, "${WELCOME_PREFIX}$id|$seed")
        }
    }

    private fun onMove(ip: String, payload: String) {
        when {
            payload.startsWith(WELCOME_PREFIX) -> {
                if (mode != Mode.CLIENT) return
                val parts = payload.removePrefix(WELCOME_PREFIX).split("|")
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return
                val hostSeed = parts.getOrNull(1)?.toLongOrNull() ?: seed
                localId = id
                seed = hostSeed
                welcomes.add(Welcome(id, hostSeed, hostName))
            }
            payload.startsWith(STATE_PREFIX) -> {
                if (mode != Mode.CLIENT) return
                runCatching { ZombieSnapshotCodec.decode(payload) }.onSuccess { snapshot.set(it) }
            }
            payload.startsWith(INPUT_PREFIX) -> {
                if (mode != Mode.HOST) return
                val id = peerIds[ip] ?: return
                val input = parseInput(payload.removePrefix(INPUT_PREFIX)) ?: return
                inputs[id] = input
                peerSeen[id] = System.currentTimeMillis()
            }
            payload.startsWith(BYE_PREFIX) -> {
                if (mode != Mode.HOST) return
                removePeer(ip)
            }
        }
    }

    private fun removePeer(ip: String) {
        val id = peerIds.remove(ip) ?: return
        peerNames.remove(id)
        peerSeen.remove(id)
        inputs.remove(id)
        leaves.add(id)
    }

    fun drainJoins(): List<Pair<Int, String>> {
        if (joins.isEmpty()) return emptyList()
        val list = ArrayList<Pair<Int, String>>()
        while (joins.isNotEmpty()) joins.poll()?.let { list.add(it) }
        return list
    }

    fun drainLeaves(): List<Int> {
        if (leaves.isEmpty()) return emptyList()
        val list = ArrayList<Int>()
        while (leaves.isNotEmpty()) leaves.poll()?.let { list.add(it) }
        return list
    }

    fun takeWelcome(): Welcome? = welcomes.poll()
    fun latestSnapshot(): ZombieSnapshot? = snapshot.get()
    fun inputFor(id: Int): FpsInput = inputs[id] ?: FpsInput()

    /** Sends the host's world to every joiner. Throttled, because 60 packets a second is noise. */
    fun publish(world: ZombieSnapshot) {
        if (mode != Mode.HOST || peerIds.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPublish.get() < 110L) return
        lastPublish.set(now)
        val encoded = ZombieSnapshotCodec.encode(world)
        val iterator = peerIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val id = entry.value
            val seen = peerSeen[id] ?: now
            if (now - seen > 25_000L) {
                iterator.remove()
                inputs.remove(id)
                peerNames.remove(id)
                leaves.add(id)
                continue
            }
            runCatching { MeshTransport.sendToPeer(entry.key, MinigameMesh.OPCODE_MOVE, encoded) }
        }
    }

    /** Sends this client's controls to the host. */
    fun sendInput(input: FpsInput) {
        if (mode != Mode.CLIENT || hostIp.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastInput.get() < 50L) return
        lastInput.set(now)
        runCatching {
            MeshTransport.sendToPeer(hostIp, MinigameMesh.OPCODE_MOVE, INPUT_PREFIX + encodeInput(input))
        }
    }

    private fun encodeInput(input: FpsInput): String = listOf(
        input.forward, input.strafe, input.turn, input.look,
    ).joinToString(",") { r3(it.toDouble()) } + "," +
        (if (input.fire) 1 else 0) + "," +
        (if (input.reload) 1 else 0) + "," +
        (if (input.use) 1 else 0) + "," +
        (if (input.sprint) 1 else 0) + "," +
        input.switchWeapon

    private fun parseInput(text: String): FpsInput? {
        val parts = text.split(",")
        if (parts.size < 9) return null
        return FpsInput(
            forward = parts[0].toFloatOrNull() ?: 0f,
            strafe = parts[1].toFloatOrNull() ?: 0f,
            turn = parts[2].toFloatOrNull() ?: 0f,
            look = parts[3].toFloatOrNull() ?: 0f,
            fire = parts[4] == "1",
            reload = parts[5] == "1",
            use = parts[6] == "1",
            sprint = parts[7] == "1",
            switchWeapon = parts[8].toIntOrNull() ?: -1,
        )
    }

    private fun r3(value: Double): String {
        val rounded = (value * 1000.0).roundToInt() / 1000.0
        return rounded.toString()
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * The wire format for a host snapshot.
 *
 * One line, pipe separated, with `~` between subtitles. Names are scrubbed of the separators rather
 * than escaped: these are player names chosen on a phone, and a name containing a pipe should not
 * be able to corrupt the world.
 */
object ZombieSnapshotCodec {

    fun encode(snapshot: ZombieSnapshot): String {
        val players = snapshot.players.joinToString(";") { player ->
            listOf(
                player.id, safe(player.name), r2(player.x), r2(player.y), r3(player.angle),
                r2(player.health.toDouble()), r2(player.maxHealth.toDouble()),
                if (player.downed) 1 else 0, r2(player.bleedout), r2(player.revive),
                player.score, player.kills, player.weapon, player.magazine, player.reserve,
                if (player.isBot) 1 else 0,
            ).joinToString(",")
        }
        val enemies = snapshot.enemies.joinToString(";") { enemy ->
            listOf(
                r2(enemy.x), r2(enemy.y), r3(enemy.angle), r2(enemy.health.toDouble()),
                r2(enemy.maxHealth.toDouble()), enemy.kind, r2(enemy.dying),
            ).joinToString(",")
        }
        val pickups = snapshot.pickups.joinToString(";") { pickup ->
            listOf(r2(pickup.x), r2(pickup.y), pickup.kind, pickup.weapon, safe(pickup.label)).joinToString(",")
        }
        return "z|s|" + listOf(
            snapshot.seed, snapshot.wave, snapshot.phase, r2(snapshot.waveTimer),
            snapshot.points, snapshot.kills, r2(snapshot.time), snapshot.status,
            safe(snapshot.message.orEmpty()), safe(snapshot.objective.orEmpty()),
            snapshot.subtitles.joinToString("~") { safe(it) },
            players, enemies, pickups.nilIfBlank(),
        ).joinToString("|")
    }

    fun decode(payload: String): ZombieSnapshot? {
        if (!payload.startsWith("z|s|")) return null
        val parts = payload.removePrefix("z|s|").split("|")
        if (parts.size < 14) return null
        val seed = parts[0].toLongOrNull() ?: return null
        val players = ArrayList<ZombiePlayerState>()
        if (parts[11].isNotBlank()) {
            for (entry in parts[11].split(";")) {
                val p = entry.split(",")
                if (p.size < 16) continue
                players.add(
                    ZombiePlayerState(
                        id = p[0].toIntOrNull() ?: continue,
                        name = p[1].ifBlank { "SURVIVOR" },
                        x = p[2].toDoubleOrNull() ?: continue,
                        y = p[3].toDoubleOrNull() ?: continue,
                        angle = p[4].toDoubleOrNull() ?: 0.0,
                        health = p[5].toFloatOrNull() ?: 0f,
                        maxHealth = p[6].toFloatOrNull() ?: 100f,
                        downed = p[7] == "1",
                        bleedout = p[8].toDoubleOrNull() ?: 0.0,
                        revive = p[9].toDoubleOrNull() ?: 0.0,
                        score = p[10].toIntOrNull() ?: 0,
                        kills = p[11].toIntOrNull() ?: 0,
                        weapon = p[12].toIntOrNull() ?: 0,
                        magazine = p[13].toIntOrNull() ?: 0,
                        reserve = p[14].toIntOrNull() ?: 0,
                        isBot = p[15] == "1",
                    )
                )
            }
        }
        val enemies = ArrayList<ZombieEnemyState>()
        if (parts[12].isNotBlank()) {
            for (entry in parts[12].split(";")) {
                val e = entry.split(",")
                if (e.size < 7) continue
                enemies.add(
                    ZombieEnemyState(
                        x = e[0].toDoubleOrNull() ?: continue,
                        y = e[1].toDoubleOrNull() ?: continue,
                        angle = e[2].toDoubleOrNull() ?: 0.0,
                        health = e[3].toFloatOrNull() ?: 0f,
                        maxHealth = e[4].toFloatOrNull() ?: 100f,
                        kind = e[5].toIntOrNull() ?: FpsSprites.ZOMBIE,
                        dying = e[6].toDoubleOrNull() ?: 0.0,
                    )
                )
            }
        }
        val pickups = ArrayList<ZombiePickupState>()
        if (parts[13].isNotBlank()) {
            for (entry in parts[13].split(";")) {
                val p = entry.split(",")
                if (p.size < 5) continue
                pickups.add(
                    ZombiePickupState(
                        x = p[0].toDoubleOrNull() ?: continue,
                        y = p[1].toDoubleOrNull() ?: continue,
                        kind = p[2].toIntOrNull() ?: 0,
                        weapon = p[3].toIntOrNull() ?: -1,
                        label = p[4],
                    )
                )
            }
        }
        return ZombieSnapshot(
            seed = seed,
            wave = parts[1].toIntOrNull() ?: 1,
            phase = parts[2].toIntOrNull() ?: 0,
            waveTimer = parts[3].toDoubleOrNull() ?: 0.0,
            points = parts[4].toIntOrNull() ?: 0,
            kills = parts[5].toIntOrNull() ?: 0,
            time = parts[6].toDoubleOrNull() ?: 0.0,
            status = parts[7].toIntOrNull() ?: 0,
            message = parts[8].ifBlank { null },
            objective = parts[9].ifBlank { null },
            subtitles = parts[10].split("~").filter { it.isNotBlank() },
            players = players,
            enemies = enemies,
            pickups = pickups,
        )
    }

    private fun safe(value: String): String = value
        .replace("|", "/")
        .replace(";", ",")
        .replace("~", "-")
        .replace("\n", " ")

    private fun r2(value: Double): String = (kotlin.math.round(value * 100.0) / 100.0).toString()
    private fun r3(value: Double): String = (kotlin.math.round(value * 1000.0) / 1000.0).toString()
    private fun String.nilIfBlank(): String = if (isBlank()) "" else this
}
