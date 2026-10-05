package com.prism.launcher.minigames

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Playing against the people on your mesh.
 *
 * ## What actually goes over the wire
 *
 * Almost nothing. Both devices run the same [Pong] and [Chess] code, so a game is a seed plus the
 * moves: a chess move is five characters, and a pong paddle is one number a few times a second.
 * There is no server, no authoritative state, and no snapshot of the board — which is what makes
 * this possible at all on a UDP gossip mesh with no reliability layer.
 *
 * ## Matchmaking is an announcement, not a lobby
 *
 * A device looking for a game says so, periodically, to everyone. A device that hears an
 * announcement for the game IT is waiting on answers directly. First answer wins; the loser of the
 * race gets a decline and keeps looking. That is the whole protocol, and it fits the mesh's
 * existing broadcast-and-reply shape rather than requiring anything new from it.
 *
 * "It only finds players trying to find players for the same game" — so the game id is in the
 * announcement, and a pong seeker ignores chess seekers entirely.
 *
 * ## The world map
 *
 * Peers also announce their country, which is what turns the local map into a global one. A peer
 * that has not announced is not on the map: the design says a mesh with nobody on it shows the AI
 * world, and an empty peer list is exactly that case.
 */
object MinigameMesh {

    /** Opcodes 0x50 and up were free; see PrismMeshService's dispatch for the rest. */
    const val OPCODE_SEEK: Byte = 0x50
    const val OPCODE_ACCEPT: Byte = 0x51
    const val OPCODE_MOVE: Byte = 0x52
    const val OPCODE_COUNTRY: Byte = 0x53
    const val OPCODE_ALLIANCE: Byte = 0x54
    const val OPCODE_ALLIANCE_REPLY: Byte = 0x55
    const val OPCODE_BATTLE: Byte = 0x56
    const val OPCODE_STRIKE: Byte = 0x57

    const val GAME_PONG = "pong"
    const val GAME_CHESS = "chess"

    /** How long a peer's announcement is believed. Three gossip rounds. */
    private const val PEER_TTL_MS = 45_000L

    // THROUGH MeshTransport, which is the seam that let this file move to :core at all. The Android
    // transport is PrismMeshService and the desktop's is MeshCore; both speak the same wire protocol,
    // and this file never learns which one it got. See MeshTransport for why the Android side was
    // deliberately never refactored to delegate.
    fun isAvailable(): Boolean = MeshTransport.isOnMesh()

    /** On the mesh but nobody else is there: a real state, and a different message to the user. */
    fun isAlone(): Boolean = isAvailable() && MeshTransport.peerCount() == 0

    // -- Peer countries -------------------------------------------------------

    private data class PeerCountry(val country: WorldMap.Country, val heardAt: Long)

    private val peers = ConcurrentHashMap<String, PeerCountry>()

    /** Every peer country heard from recently. Empty is the honest answer, not a reason to invent. */
    fun peerCountries(): List<WorldMap.Country> {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        peers.entries.removeAll { it.value.heardAt < cutoff }
        return peers.values.map { it.country }.sortedBy { it.id }
    }

    fun peerCountry(id: String): WorldMap.Country? = peers[id]?.country

    /**
     * Tells the mesh about this device's country.
     *
     * The outline is sent along with the stats, because a peer's blob has to be in a consistent
     * place on everybody's map — deriving it from the peer's IP would give each viewer a different
     * shape for the same country.
     */
    fun announceCountry(base: PaperBase, worldSeed: Long) {
        if (!isAvailable()) return
        val mine = WorldMap.playerCountry(base, worldSeed)
        val payload = JSONObject().apply {
            put("name", mine.name)
            put("level", mine.level)
            put("soldiers", mine.soldiers)
            put("buildings", mine.buildingCount)
            put("population", mine.population)
            put("xp", mine.xp)
            put("won", mine.battlesWon)
            put("lost", mine.battlesLost)
            put("cx", mine.centreX)
            put("cy", mine.centreY)
            put("outline", mine.outline.joinToString(";") { "${it.first},${it.second}" })
        }
        broadcast(OPCODE_COUNTRY, payload.toString())
    }

    fun onCountry(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            // Generated locally around the place this device puts the peer, rather than trusted
            // from the packet: an outline drawn somewhere else would not sit on its own country.
            val (px, py) = WorldChunks.peerCentre(peerIp)
            val shape = Rng(Rng.seedOf("peer-shape", peerIp))
            // Sized the same way an AI country's blob is: a peer who has expanded their own
            // boundaries looks bigger here too, not just on their own device.
            val peerLevel = json.optInt("level", 1)
            val baseR = WorldChunks.blobRadius(peerLevel, shape)
            val outline = (0 until 12).map { i ->
                val angle = 2.0 * Math.PI * i / 12
                val r = baseR * (0.72 + shape.nextDouble() * 0.56)
                ((px + r * Math.cos(angle)).toInt()) to ((py + r * 0.72 * Math.sin(angle)).toInt())
            }
            val country = WorldMap.Country(
                id = peerIp,
                name = json.optString("name", peerIp),
                owner = WorldMap.Owner.PEER,
                level = peerLevel,
                outline = outline,
                // Derived from the peer's id rather than taken from the packet, so every device
                // puts the same peer in the same place -- and so a peer cannot announce itself on
                // top of somebody else's country.
                centreX = WorldChunks.peerCentre(peerIp).first,
                centreY = WorldChunks.peerCentre(peerIp).second,
                soldiers = json.optInt("soldiers"),
                buildingCount = json.optInt("buildings"),
                population = json.optInt("population"),
                xp = json.optLong("xp"),
                battlesWon = json.optInt("won"),
                battlesLost = json.optInt("lost"),
            )
            if (country.outline.size >= 3) {
                peers[peerIp] = PeerCountry(country, System.currentTimeMillis())
            }
        }.onFailure { PrismPlatform.log.warn(TAG, "Bad country announcement from $peerIp") }
    }

    // -- Matchmaking ----------------------------------------------------------

    /** A match somebody is offering or has agreed to. */
    data class Match(
        val gameId: String,
        val peerIp: String,
        val seed: Long,
        /** The side this device plays. Decided by the accepter and obeyed by both. */
        val youAreFirst: Boolean,
    )

    @Volatile
    private var seeking: String? = null

    @Volatile
    private var onMatched: ((Match) -> Unit)? = null

    /** Starts looking. The callback fires on whatever thread the mesh delivers on. */
    fun seek(gameId: String, onMatch: (Match) -> Unit) {
        seeking = gameId
        onMatched = onMatch
        broadcast(OPCODE_SEEK, gameId)
    }

    /** Sends the announcement again. The page calls this on a timer while the dialog is open. */
    fun reannounce() {
        seeking?.let { broadcast(OPCODE_SEEK, it) }
    }

    fun stopSeeking() {
        seeking = null
        onMatched = null
    }

    /**
     * Somebody is looking for a game.
     *
     * If this device is looking for the SAME game, it accepts — and it is the accepter that picks
     * the seed and decides who moves first, so both ends agree without a negotiation. The accepter
     * also stops seeking immediately, so a third peer arriving a moment later gets nothing rather
     * than a second match.
     */
    fun onSeek(peerIp: String, payload: String) {
        val want = seeking ?: return
        if (payload != want) return

        val seed = System.currentTimeMillis() xor peerIp.hashCode().toLong()
        // The accepter takes second move, which is the small courtesy of letting whoever asked go
        // first, and — more usefully — makes the assignment unambiguous on both sides.
        val match = Match(want, peerIp, seed, youAreFirst = false)
        seeking = null

        MeshTransport.sendToPeer(peerIp, OPCODE_ACCEPT, "$want|$seed")
        onMatched?.invoke(match)
    }

    fun onAccept(peerIp: String, payload: String) {
        val want = seeking ?: return
        val parts = payload.split("|")
        if (parts.getOrNull(0) != want) return
        val seed = parts.getOrNull(1)?.toLongOrNull() ?: return
        seeking = null
        onMatched?.invoke(Match(want, peerIp, seed, youAreFirst = true))
    }

    // -- In-game traffic ------------------------------------------------------

    @Volatile
    private var moveListener: ((String, String) -> Unit)? = null

    fun listenForMoves(listener: (peerIp: String, payload: String) -> Unit) {
        moveListener = listener
    }

    fun stopListening() {
        moveListener = null
    }

    /**
     * Sends one move, or one paddle position.
     *
     * Fire and forget, over UDP, with no acknowledgement — which is right for pong (the next
     * position supersedes the last one, so a dropped packet costs one frame of smoothness) and
     * needs care in chess, where the sender repeats its last move until it sees a reply. That
     * repetition lives in the chess view, because only it knows what "a reply" means.
     */
    fun sendMove(peerIp: String, payload: String) {
        if (!isAvailable()) return
        MeshTransport.sendToPeer(peerIp, OPCODE_MOVE, payload)
    }

    fun onMove(peerIp: String, payload: String) {
        moveListener?.invoke(peerIp, payload)
    }

    // -- Alliances between players --------------------------------------------

    fun sendAllianceRequest(peerIp: String, fromName: String, fromLevel: Int) {
        if (!isAvailable()) return
        MeshTransport.sendToPeer(peerIp, OPCODE_ALLIANCE, "$fromName|$fromLevel")
    }

    fun onAllianceRequest(peerIp: String, payload: String) {
        val parts = payload.split("|")
        MinigameStore.addRequest(
            WorldMap.AllianceRequest(
                id = "$peerIp-${System.currentTimeMillis()}",
                fromCountryId = peerIp,
                fromName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: peerIp,
                fromLevel = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                sentAt = System.currentTimeMillis(),
            )
        )
        PrismPlatform.log.info(TAG, "Alliance request from $peerIp")
    }

    fun replyToAlliance(peerIp: String, accepted: Boolean) {
        if (!isAvailable()) return
        MeshTransport.sendToPeer(peerIp, OPCODE_ALLIANCE_REPLY, if (accepted) "yes" else "no")
    }

    fun onAllianceReply(peerIp: String, payload: String) {
        MinigameStore.setRelation(
            peerIp,
            if (payload == "yes") WorldMap.Relation.ALLIED else WorldMap.Relation.REFUSED,
        )
    }

    // -- Battles others can watch ---------------------------------------------

    private val liveBattles = ConcurrentHashMap<String, WorldMap.BattleTicket>()

    /**
     * Announces a battle so peers can watch it.
     *
     * The ticket is the whole battle: a seed, who is fighting, and what they brought. Every
     * spectator runs [Battle.simulate] from it and sees precisely the fight the attacker is seeing,
     * without a single frame crossing the network.
     */
    fun announceBattle(ticket: WorldMap.BattleTicket) {
        liveBattles[ticket.id] = ticket
        if (!isAvailable()) return
        val payload = JSONObject().apply {
            put("id", ticket.id)
            put("attacker", ticket.attackerId)
            put("defender", ticket.defenderId)
            put("seed", ticket.seed)
            put("at", ticket.startedAt)
            put("level", ticket.attackerLevel)
            put("soldiers", ticket.attackerSoldiers)
            put("weapon", ticket.attackerWeaponId)
            // Without this a spectator simulates the same seed against an all-infantry army and
            // watches a different battle from the one being fought.
            put("support", JSONArray(ticket.attackerSupportIds))
            put("military", ticket.attackerMilitaryBonus)
        }
        broadcast(OPCODE_BATTLE, payload.toString())
    }

    /**
     * Tells the mesh a warhead is on its way.
     *
     * Carried so a peer sees the launch crossing their map too. Advisory, like everything else on
     * the mesh: a dropped packet costs a spectator an animation, not a game state.
     */
    fun announceStrike(targetId: String, kind: WeaponCatalog.Wmd, fromId: String) {
        val payload = JSONObject().apply {
            put("target", targetId)
            put("kind", kind.name)
            put("from", fromId)
            put("at", System.currentTimeMillis())
        }
        broadcast(OPCODE_STRIKE, payload.toString())
    }

    /** A launch somebody else made. Recorded so it flies across this device's map as well. */
    fun onStrike(peerIp: String, payload: String, record: (String, WorldMap.Strike) -> Unit) {
        runCatching {
            val json = JSONObject(payload)
            val kind = WeaponCatalog.Wmd.valueOf(json.optString("kind"))
            val target = json.optString("target").ifBlank { return }
            record(
                target,
                WorldMap.Strike(
                    kind = kind,
                    at = json.optLong("at", System.currentTimeMillis()),
                    fromId = json.optString("from").ifBlank { peerIp },
                ),
            )
        }
    }

    fun onBattle(peerIp: String, payload: String) {
        runCatching {
            val json = JSONObject(payload)
            val ticket = WorldMap.BattleTicket(
                id = json.optString("id"),
                attackerId = json.optString("attacker").ifBlank { peerIp },
                defenderId = json.optString("defender"),
                seed = json.optLong("seed"),
                startedAt = json.optLong("at"),
                attackerLevel = json.optInt("level", 1),
                attackerSoldiers = json.optInt("soldiers"),
                attackerWeaponId = json.optString("weapon", WeaponCatalog.STARTER.id),
                attackerSupportIds = json.optJSONArray("support")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                attackerMilitaryBonus = json.optDouble("military", 1.0),
            )
            if (ticket.id.isNotBlank()) liveBattles[ticket.id] = ticket
        }
    }

    /** Battles started in the last few minutes. Anything older has finished. */
    fun watchableBattles(): List<WorldMap.BattleTicket> {
        val cutoff = System.currentTimeMillis() - 5 * 60_000L
        liveBattles.entries.removeAll { it.value.startedAt < cutoff }
        return liveBattles.values.sortedByDescending { it.startedAt }
    }

    /**
     * The battle happening AT a country.
     *
     * Defender first, and only then attacker. Matching either way round meant that tapping your own
     * country while it was being raided found the ticket for the invasion YOU had launched
     * somewhere else — so "watch the attack on my country" replayed your own attack on somebody
     * else's. A battle belongs to the place it is being fought.
     */
    fun battleFor(countryId: String): WorldMap.BattleTicket? =
        watchableBattles().firstOrNull { it.defenderId == countryId }
            ?: watchableBattles().firstOrNull { it.attackerId == countryId }

    // -- Plumbing -------------------------------------------------------------

    private fun broadcast(opcode: Byte, payload: String) {
        if (!isAvailable()) return
        runCatching { MeshTransport.broadcast(opcode, payload, null) }
            .onFailure { PrismPlatform.log.warn(TAG, "Could not broadcast: ${it.message}") }
    }

    private const val TAG = "PrismMinigames"
}
