package com.prism.launcher.minigames

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the Minigames page remembers.
 *
 * ## What is saved and what is not
 *
 * Saved: the player's base, their diplomacy, their inbox, their win record, and which game they had
 * open. Not saved: the AI world, which is a pure function of the world seed and regenerates
 * identically every time (see [WorldMap]); any battle, which is a seed and a list of orders; and
 * any chess or pong position, because a game abandoned mid-move is a game nobody wanted to return
 * to. Storing what can be recomputed is how a save file becomes a compatibility problem.
 *
 * ## Why JSON and not a database
 *
 * A base at level 500 is a few hundred buildings and a few hundred researched weapon ids: tens of
 * kilobytes. That parses in a couple of milliseconds and a schema migration is a `optString` with a
 * default. A table would buy nothing and cost a migration path for every field added.
 */
object MinigameStore {

    private const val PREFS = "prism_minigames"

    private const val KEY_BASE = "paper_base_v1"
    private const val KEY_WORLD_SEED = "world_seed_v1"
    private const val KEY_RELATIONS = "relations_v1"
    private const val KEY_INBOX = "inbox_v1"
    private const val KEY_ATTEMPTS = "alliance_attempts_v1"
    private const val KEY_LAST_GAME = "last_game_v1"
    private const val KEY_SEEN_OPENING = "seen_opening_raid_v1"
    private const val KEY_CHESS_RECORD = "chess_record_v1"
    private const val KEY_PONG_RECORD = "pong_record_v1"
    private const val KEY_CHESS_DIFFICULTY = "chess_difficulty_v1"
    private const val KEY_PONG_DIFFICULTY = "pong_difficulty_v1"
    private const val KEY_LAST_TICK = "last_tick_v1"
    private const val KEY_STRIKES = "strikes_v1"

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    // -- The world seed -------------------------------------------------------

    /**
     * The seed the player's whole world hangs off.
     *
     * Generated once, from the clock, and then never again. It is the only genuinely random number
     * in the game: everything else is derived from it, which is what makes a world both unique to a
     * player and reproducible on their device.
     */
    fun worldSeed(): Long {
        val stored = prefs().getLong(KEY_WORLD_SEED, 0L)
        if (stored != 0L) return stored
        val fresh = System.currentTimeMillis() * 2_654_435_761L + 0x9E3779B9L
        prefs().edit().putLong(KEY_WORLD_SEED, fresh).apply()
        return fresh
    }

    // -- The base -------------------------------------------------------------

    /**
     * Set once when [loadBase] has had to put a base back together. Read and cleared by the view,
     * which tells the player — a base that changes shape between sessions needs explaining.
     */
    var baseWasRecovered: Boolean = false
        private set

    fun clearRecoveryNotice() {
        baseWasRecovered = false
    }

    fun loadBase(): PaperBase {
        val raw = prefs().getString(KEY_BASE, null)
        if (raw.isNullOrBlank()) return PaperBase.newGame("Your Country", System.currentTimeMillis())
        return runCatching {
            val decoded = recoverIfErased(decodeBase(JSONObject(raw)))
            // A base that cannot train or build is a base that cannot be played out of. See
            // WorldMap.addMissingEssentials -- it adds only what is structurally required and never
            // moves or removes anything already there.
            val repaired = WorldMap.addMissingEssentials(decoded, decoded.level)
            if (repaired !== decoded) baseWasRecovered = true
            repaired
        }.getOrElse {
            PrismLogger.logWarning(TAG, "The saved base could not be read; starting over")
            PaperBase.newGame("Your Country", System.currentTimeMillis())
        }
    }

    /**
     * Gives the buildings back to a save the old raid code deleted them from.
     *
     * [PaperBase.applyDamage] used to drop any building a raid took to zero, so a single lost raid
     * while the player was away erased the entire base — permanently, at any level. The damage is
     * fixed at the source now, but a save it already happened to is left holding a level, an army
     * and a treasury with nothing on the page: no town hall, and no training camps, which means a
     * soldier cap of zero and no way to ever field an army again. That save cannot be played and
     * cannot be repaired by the player.
     *
     * So a base with no buildings at all, at a level that cannot possibly have none, is relaid to
     * its level. The layout is not the one that was lost — that was never stored anywhere and is
     * genuinely gone — but the size is right, and everything else the player earned is untouched.
     * A level-1 base is left alone: starting with almost nothing is the opening position.
     */
    private fun recoverIfErased(base: PaperBase): PaperBase {
        if (base.level <= 1) return base
        // Either nothing is left, or everything that is left was laid out by an EARLIER version of
        // this recovery -- the first one sized every base at eight buildings whatever the level, so
        // a level-500 country came back as a hamlet. Re-laying one of those is safe precisely
        // because the marker says no human placed any of it.
        val stale = base.buildings.isNotEmpty() &&
            base.buildings.all { it.id.startsWith("recovered") } &&
            base.buildings.none { it.id.startsWith(WorldMap.RECOVERY_MARK) }
        if (base.buildings.isNotEmpty() && !stale) return base
        PrismLogger.logWarning(
            TAG,
            "The saved base had no buildings at level ${base.level}; relaying it to its level",
        )
        baseWasRecovered = true
        return base.copy(
            buildings = WorldMap.layoutFor(base.name, base.level, "recovered:${base.name}").buildings,
        )
    }

    fun saveBase(base: PaperBase) {
        prefs().edit().putString(KEY_BASE, encodeBase(base).toString()).apply()
    }

    private fun encodeBase(base: PaperBase): JSONObject = JSONObject().apply {
        put("name", base.name)
        put("xp", base.xp)
        put("peakXp", base.peakXp)
        put("soldiers", base.soldiers)
        put("razed", base.razed)
        put("won", base.battlesWon)
        put("lost", base.battlesLost)
        put("repelled", base.raidsRepelled)
        put("raided", base.raidsLost)
        put("researched", JSONArray(base.researched.toList()))
        put("laws", JSONArray(base.enactedLaws.toList()))
        base.lawInProgress?.let { put("lawInProgress", it) }
        put("lawFinishesAt", base.lawFinishesAt)
        base.infantryWeaponId?.let { put("infantryWeapon", it) }
        // Written only when the player has actually chosen. A missing key means "whatever is best",
        // which is not the same as an empty list -- that means "infantry alone", and a player who
        // decided to send their soldiers unsupported should find that still true next time.
        base.supportWeaponIds?.let { put("support", JSONArray(it)) }
        base.garrisonWeaponId?.let { put("garrisonWeapon", it) }
        put("civilians", base.civilians)
        put("families", base.families)
        put("lastTaxAt", base.lastTaxAt)
        put("xpCarry", base.xpCarry)
        put("growthCarry", base.growthCarry)
        put("annexed", JSONArray().apply {
            base.annexed.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("level", a.level)
                    put("soldiers", a.soldiers)
                    put("defences", a.defences)
                    put("civilians", a.civilians)
                    put("treasury", a.treasury)
                    put("loyalty", a.loyalty)
                    put("takenAt", a.takenAt)
                    // The province's own buildable base, if it has one -- see AnnexedCountry.base.
                    a.base?.let { put("base", encodeBase(it)) }
                })
            }
        })
        put("buildings", JSONArray().apply {
            base.buildings.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("type", b.typeId)
                    put("x", b.x)
                    put("y", b.y)
                    put("hp", b.hitPoints)
                    put("max", b.maxHitPoints)
                    put("started", b.startedAt)
                    put("finishes", b.finishesAt)
                    put("done", b.complete)
                    b.connectsFrom?.let { put("from", it) }
                    b.connectsTo?.let { put("to", it) }
                })
            }
        })
    }

    private fun decodeBase(json: JSONObject): PaperBase {
        val researched = json.optJSONArray("researched")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()

        val buildings = json.optJSONArray("buildings")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                // A building whose type no longer exists is dropped rather than crashing the save:
                // the catalogue is code and can be edited between versions.
                if (BuildingCatalog.byId(b.optString("type")) == null) return@mapNotNull null
                PlacedBuilding(
                    id = b.optString("id"),
                    typeId = b.optString("type"),
                    x = b.optInt("x"),
                    y = b.optInt("y"),
                    hitPoints = b.optInt("hp", 1),
                    maxHitPoints = b.optInt("max", 1),
                    startedAt = b.optLong("started"),
                    finishesAt = b.optLong("finishes"),
                    complete = b.optBoolean("done", true),
                    connectsFrom = b.optString("from").takeIf { it.isNotBlank() },
                    connectsTo = b.optString("to").takeIf { it.isNotBlank() },
                )
            }
        } ?: emptyList()

        return PaperBase(
            name = json.optString("name", "Your Country"),
            xp = json.optLong("xp", 0),
            // A save written before the high-water mark existed has its level in its balance, so
            // the balance IS the mark. Defaulting to zero would demote every existing player to
            // level one on the update.
            peakXp = json.optLong("peakXp", json.optLong("xp", 0)),
            buildings = buildings,
            researched = researched.filter { WeaponCatalog.byId(it) != null }.toSet(),
            soldiers = json.optInt("soldiers"),
            razed = json.optBoolean("razed", true),
            battlesWon = json.optInt("won"),
            battlesLost = json.optInt("lost"),
            raidsRepelled = json.optInt("repelled"),
            raidsLost = json.optInt("raided"),
            enactedLaws = json.optJSONArray("laws")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
                    .filter { Ideology.law(it) != null }
                    .toSet()
            } ?: emptySet(),
            lawInProgress = json.optString("lawInProgress").takeIf { it.isNotBlank() },
            lawFinishesAt = json.optLong("lawFinishesAt"),
            infantryWeaponId = json.optString("infantryWeapon").takeIf { it.isNotBlank() },
            supportWeaponIds = json.optJSONArray("support")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            },
            garrisonWeaponId = json.optString("garrisonWeapon").takeIf { it.isNotBlank() },
            civilians = json.optInt("civilians"),
            families = json.optInt("families"),
            lastTaxAt = json.optLong("lastTaxAt"),
            xpCarry = json.optDouble("xpCarry", 0.0),
            growthCarry = json.optDouble("growthCarry", 0.0),
            annexed = json.optJSONArray("annexed")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONObject(i) ?: return@mapNotNull null
                    AnnexedCountry(
                        id = a.optString("id"),
                        name = a.optString("name"),
                        level = a.optInt("level", 1),
                        soldiers = a.optInt("soldiers"),
                        defences = a.optDouble("defences", 0.0),
                        civilians = a.optInt("civilians"),
                        treasury = a.optLong("treasury"),
                        loyalty = a.optDouble("loyalty", 0.2),
                        takenAt = a.optLong("takenAt"),
                        base = a.optJSONObject("base")?.let { runCatching { decodeBase(it) }.getOrNull() },
                    )
                }
            } ?: emptyList(),
        )
    }

    // -- Diplomacy ------------------------------------------------------------

    fun relations(): Map<String, WorldMap.Relation> {
        val raw = prefs().getString(KEY_RELATIONS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val value = runCatching { WorldMap.Relation.valueOf(json.getString(key)) }.getOrNull()
                value?.let { key to it }
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    /**
     * Countries that have been hit, and when.
     *
     * Swept on read: a country that has finished burning is no longer a strike, and keeping the
     * record would grow the file forever for a state nothing ever asks about again.
     */
    fun strikes(): Map<String, WorldMap.Strike> {
        val raw = prefs().getString(KEY_STRIKES, null) ?: return emptyMap()
        val now = System.currentTimeMillis()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val row = json.optJSONObject(key) ?: return@mapNotNull null
                val kind = runCatching { WeaponCatalog.Wmd.valueOf(row.optString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                val strike = WorldMap.Strike(
                    kind, row.optLong("at"), row.optBoolean("invasion"),
                    fromId = row.optString("from").takeIf { it.isNotBlank() },
                )
                if (strike.isBurning(now)) key to strike else null
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    fun recordStrike(countryId: String, strike: WorldMap.Strike) {
        val json = JSONObject()
        (strikes() + (countryId to strike)).forEach { (id, s) ->
            json.put(id, JSONObject().apply {
                put("kind", s.kind.name)
                put("at", s.at)
                put("invasion", s.fromInvasion)
                s.fromId?.let { put("from", it) }
            })
        }
        prefs().edit().putString(KEY_STRIKES, json.toString()).apply()
    }

    fun setRelation(countryId: String, relation: WorldMap.Relation) {
        val json = JSONObject()
        relations().forEach { (id, r) -> json.put(id, r.name) }
        json.put(countryId, relation.name)
        prefs().edit().putString(KEY_RELATIONS, json.toString()).apply()
    }

    /**
     * How many times this country has been asked.
     *
     * Feeds the alliance roll's seed, which is what stops a player from tapping "ask" until they
     * get a yes: the same attempt number always gives the same answer, and the number only goes up
     * when an actual attempt is spent.
     */
    fun allianceAttempts(countryId: String): Int {
        val raw = prefs().getString(KEY_ATTEMPTS, null) ?: return 0
        return runCatching { JSONObject(raw).optInt(countryId, 0) }.getOrElse { 0 }
    }

    fun recordAllianceAttempt(countryId: String) {
        val json = runCatching { JSONObject(prefs().getString(KEY_ATTEMPTS, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        json.put(countryId, json.optInt(countryId, 0) + 1)
        prefs().edit().putString(KEY_ATTEMPTS, json.toString()).apply()
    }

    // -- Grudges ---------------------------------------------------------------

    private const val KEY_INVASIONS = "invasions_v1"
    private const val KEY_FAILED = "failed_invasions_v1"

    /** How many times the player has invaded each country, won or lost. Drives the revolt odds. */
    fun invasionsOf(countryId: String): Int = counterOf(KEY_INVASIONS, countryId)

    /** How many of those failed. Drives how likely that country is to come back for the player. */
    fun failedInvasionsOf(countryId: String): Int = counterOf(KEY_FAILED, countryId)

    fun failedInvasions(): Map<String, Int> = countersIn(KEY_FAILED)

    fun recordInvasion(countryId: String, won: Boolean) {
        bumpCounter(KEY_INVASIONS, countryId)
        if (!won) bumpCounter(KEY_FAILED, countryId)
    }

    /** Forgives a country's record. Used when a province revolts, so the slate is not double-counted. */
    fun clearGrudge(countryId: String) {
        listOf(KEY_INVASIONS, KEY_FAILED).forEach { key ->
            val json = runCatching { JSONObject(prefs().getString(key, "{}") ?: "{}") }
                .getOrElse { JSONObject() }
            json.remove(countryId)
            prefs().edit().putString(key, json.toString()).apply()
        }
    }

    private fun counterOf(key: String, id: String): Int =
        runCatching { JSONObject(prefs().getString(key, "{}") ?: "{}").optInt(id, 0) }.getOrElse { 0 }

    private fun countersIn(key: String): Map<String, Int> = runCatching {
        val json = JSONObject(prefs().getString(key, "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.optInt(it, 0) }
    }.getOrElse { emptyMap() }

    private fun bumpCounter(key: String, id: String) {
        val json = runCatching { JSONObject(prefs().getString(key, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        json.put(id, json.optInt(id, 0) + 1)
        prefs().edit().putString(key, json.toString()).apply()
    }

    // -- The inbox ------------------------------------------------------------

    fun inbox(): List<WorldMap.AllianceRequest> {
        val raw = prefs().getString(KEY_INBOX, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WorldMap.AllianceRequest(
                    id = o.optString("id"),
                    fromCountryId = o.optString("from"),
                    fromName = o.optString("name"),
                    fromLevel = o.optInt("level"),
                    sentAt = o.optLong("at"),
                    answered = o.optBoolean("answered"),
                    accepted = o.optBoolean("accepted"),
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveInbox(requests: List<WorldMap.AllianceRequest>) {
        val arr = JSONArray()
        // Keep the last fifty. An inbox that grows forever is a preference file that grows forever.
        requests.takeLast(50).forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("from", r.fromCountryId)
                put("name", r.fromName)
                put("level", r.fromLevel)
                put("at", r.sentAt)
                put("answered", r.answered)
                put("accepted", r.accepted)
            })
        }
        prefs().edit().putString(KEY_INBOX, arr.toString()).apply()
    }

    fun addRequest(request: WorldMap.AllianceRequest) {
        val existing = inbox()
        if (existing.any { it.fromCountryId == request.fromCountryId && !it.answered }) return
        saveInbox(existing + request)
    }

    fun unansweredCount(): Int = inbox().count { !it.answered }

    // -- Odds and ends --------------------------------------------------------

    fun lastGame(): String = prefs().getString(KEY_LAST_GAME, "") ?: ""

    fun setLastGame(id: String) {
        prefs().edit().putString(KEY_LAST_GAME, id).apply()
    }

    fun hasSeenOpeningRaid(): Boolean = prefs().getBoolean(KEY_SEEN_OPENING, false)

    fun markOpeningRaidSeen() {
        prefs().edit().putBoolean(KEY_SEEN_OPENING, true).apply()
    }

    /** Wins and losses, as "w/l". Two small numbers do not need a table. */
    fun chessRecord(): Pair<Int, Int> = recordOf(KEY_CHESS_RECORD)
    fun pongRecord(): Pair<Int, Int> = recordOf(KEY_PONG_RECORD)

    fun recordChess(won: Boolean) = bumpRecord(KEY_CHESS_RECORD, won)
    fun recordPong(won: Boolean) = bumpRecord(KEY_PONG_RECORD, won)

    private fun recordOf(key: String): Pair<Int, Int> {
        val raw = prefs().getString(key, "0/0") ?: "0/0"
        val parts = raw.split("/")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun bumpRecord(key: String, won: Boolean) {
        val (w, l) = recordOf(key)
        val next = if (won) "${w + 1}/$l" else "$w/${l + 1}"
        prefs().edit().putString(key, next).apply()
    }

    fun chessDifficulty(): Chess.Difficulty =
        runCatching { Chess.Difficulty.valueOf(prefs().getString(KEY_CHESS_DIFFICULTY, "")!!) }
            .getOrElse { Chess.Difficulty.STEADY }

    fun setChessDifficulty(d: Chess.Difficulty) {
        prefs().edit().putString(KEY_CHESS_DIFFICULTY, d.name).apply()
    }

    fun pongDifficulty(): Pong.Difficulty =
        runCatching { Pong.Difficulty.valueOf(prefs().getString(KEY_PONG_DIFFICULTY, "")!!) }
            .getOrElse { Pong.Difficulty.KEEN }

    fun setPongDifficulty(d: Pong.Difficulty) {
        prefs().edit().putString(KEY_PONG_DIFFICULTY, d.name).apply()
    }

    /**
     * When the world last ticked.
     *
     * Used to finish construction and run AI raids that happened while the page was closed, so a
     * player who comes back tomorrow finds their buildings up rather than a frozen timer.
     */
    fun lastTick(): Long = prefs().getLong(KEY_LAST_TICK, System.currentTimeMillis())

    fun setLastTick(at: Long) {
        prefs().edit().putLong(KEY_LAST_TICK, at).apply()
    }

    fun resetEverything() {
        prefs().edit().clear().apply()
    }

    private const val TAG = "PrismMinigames"
}
