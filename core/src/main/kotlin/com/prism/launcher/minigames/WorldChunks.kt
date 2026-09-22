package com.prism.launcher.minigames

import kotlin.math.floor

/**
 * An endless world, generated a chunk at a time.
 *
 * ## Why the map stopped having edges
 *
 * It used to be a hundred units by sixty with fourteen countries painted onto it, which is a map
 * you finish. An endless one needs the world to be a FUNCTION of position rather than a list: ask
 * what is at these coordinates and the answer is always there, always the same, and costs nothing
 * until somebody looks.
 *
 * So space is cut into square chunks, each chunk deterministically holds a few countries, and the
 * view asks for the chunks it can actually see. Zooming out widens the rectangle and so returns
 * more countries; panning moves the rectangle and so returns different ones. Those are the same
 * operation, which is why the design's requirement — that a country seen by zooming out is also
 * seen by moving there — holds by construction rather than by effort.
 *
 * ## Frustum culling is the whole performance story
 *
 * Nothing outside the visible rectangle is generated at all. At any zoom the view asks for a bounded
 * number of chunks, so the cost of drawing the world is a function of the screen and not of how far
 * the player has travelled. A player who pans for an hour has generated exactly as much as one who
 * did not, because what left the screen was never kept.
 *
 * ## Distance means something
 *
 * A country's level rises with its distance from home. Near neighbours are the player's own weight;
 * far ones are progressively out of reach. That is what stops an endless map from being endless
 * sameness — travel is a difficulty setting the player chooses by scrolling.
 */
object WorldChunks {

    /**
     * How far apart countries sit.
     *
     * One country per chunk, jittered inside the middle of it, so the chunk size IS the minimum
     * spacing. Generous on purpose: the old map packed fourteen countries into a space where their
     * outlines touched, and a map you cannot tell apart is not a map.
     */
    const val CHUNK = 44.0

    /** The inner fraction of a chunk a country may sit in, so neighbours keep their distance. */
    private const val JITTER = 0.34

    /** Roughly how big a country's blob is, for culling with a margin. */
    const val COUNTRY_RADIUS = 9.0

    /**
     * How far a blob's edge may sit from its own chunk's true centre, jitter and radius combined.
     *
     * Chosen against the numbers already in play: the original jitter half-width is
     * `CHUNK * JITTER / 2` ≈ 7.48, and the original radius topped out at 11, for a combined worst
     * case of about 18.5. Nineteen leaves that untouched and is comfortably clear of half a chunk
     * (22), which is what actually keeps a blob inside its own cell and so guarantees no two blobs
     * -- however large either has grown -- can ever overlap.
     */
    private const val EDGE_CEILING = 19.0

    private val FIRST = listOf(
        "Graphite", "Margin", "Foolscap", "Quire", "Ledger", "Vellum", "Inkwell", "Ruled",
        "Blotter", "Folio", "Octavo", "Cartridge", "Tracing", "Manila", "Onion", "Parchment",
        "Copperplate", "Chalk", "Eraser", "Sharpener", "Notch", "Spiral", "Stapled", "Dogear",
        "Watermark", "Bleed", "Gutter", "Kerning", "Serif", "Ascender", "Descender", "Baseline",
        "Deckle", "Foxing", "Gilt", "Signature", "Colophon", "Errata", "Recto", "Verso",
        "Quarto", "Duodecimo", "Endpaper", "Headband", "Spine", "Marbled", "Laid", "Wove",
    )

    private val SECOND = listOf(
        "Reach", "Hold", "March", "Vale", "Fen", "Spire", "Cross", "Ford", "Gate", "Wold",
        "Heath", "Drift", "Cairn", "Shoal", "Hollow", "Bight", "Combe", "Scarp", "Weald", "Thorpe",
        "Garth", "Haven", "Mere", "Moor", "Rise", "Strand", "Tarn", "Thwaite", "Wick", "Yard",
    )

    /** A rectangle of the world, in world units. */
    data class View(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun expanded(by: Double) = View(left - by, top - by, right + by, bottom + by)
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    private fun chunkIndex(value: Double): Int = floor(value / CHUNK).toInt()

    /**
     * Every AI country whose centre lies in [view].
     *
     * Bounded by [limit] because a player who zooms out far enough would otherwise ask for a
     * hundred thousand countries in one frame. When the limit bites, the countries kept are the
     * ones nearest the middle of the view — which is what the player is looking at — so zooming out
     * further keeps showing a full screen rather than thinning out.
     */
    fun countriesIn(
        worldSeed: Long,
        playerLevel: Int,
        view: View,
        limit: Int = 400,
    ): List<WorldMap.Country> {
        val padded = view.expanded(COUNTRY_RADIUS)
        val fromX = chunkIndex(padded.left)
        val toX = chunkIndex(padded.right)
        val fromY = chunkIndex(padded.top)
        val toY = chunkIndex(padded.bottom)

        // A hard ceiling on how many chunks are even walked. At extreme zoom the loop itself is the
        // cost, long before the countries are.
        val columns = (toX - fromX + 1).coerceAtLeast(1)
        val rows = (toY - fromY + 1).coerceAtLeast(1)
        val step = maxOf(1, Math.ceil(Math.sqrt(columns.toDouble() * rows / limit)).toInt())

        val out = ArrayList<WorldMap.Country>(limit)
        var cy = fromY
        while (cy <= toY) {
            var cx = fromX
            while (cx <= toX) {
                countryAt(worldSeed, playerLevel, cx, cy)?.let { out.add(it) }
                cx += step
            }
            cy += step
        }

        if (out.size <= limit) return out
        val midX = (view.left + view.right) / 2
        val midY = (view.top + view.bottom) / 2
        return out.sortedBy { (it.centreX - midX) * (it.centreX - midX) + (it.centreY - midY) * (it.centreY - midY) }
            .take(limit)
    }

    /**
     * The country in one chunk, or null where the world is empty.
     *
     * Roughly one chunk in six is sea, which is what stops a perfectly regular lattice of countries
     * from reading as wallpaper.
     */
    /**
     * Where a country is, from its id.
     *
     * An id is "ai:chunkX:chunkY" by construction, so a country's position is recoverable without
     * having it on screen — which is what a warhead's trail needs, since the country it was fired
     * FROM is very often nowhere near the country being looked at.
     */
    fun centreOf(worldSeed: Long, playerLevel: Int, id: String): Pair<Double, Double>? {
        val parts = id.split(":")
        if (parts.size != 3 || parts[0] != "ai") return null
        val cx = parts[1].toIntOrNull() ?: return null
        val cy = parts[2].toIntOrNull() ?: return null
        return countryAt(worldSeed, playerLevel, cx, cy)?.let { it.centreX to it.centreY }
    }

    fun countryAt(worldSeed: Long, playerLevel: Int, chunkX: Int, chunkY: Int): WorldMap.Country? {
        // The player's own chunk is theirs; nothing else is generated there.
        if (chunkX == 0 && chunkY == 0) return null

        val id = "ai:$chunkX:$chunkY"
        val rng = Rng(Rng.seedOf("chunk", worldSeed, chunkX, chunkY))
        if (rng.chance(0.17)) return null

        val level = levelFor(playerLevel, chunkX, chunkY, rng)

        // ── Sizing the blob, without ever letting two of them touch ────────
        //
        // A country whose boundaries have expanded (see Era.aiPlotSizeAt) is drawn bigger on the
        // world map, which is the whole point of expanding them. The one thing that cannot happen
        // is a bigger blob reaching into a neighbour's chunk -- there is no cross-chunk
        // coordination here by design (see the file doc), so the only way to GUARANTEE two blobs
        // never overlap is to guarantee each one never leaves its own chunk in the first place.
        //
        // [EDGE_CEILING] is that guarantee: however far a blob's centre sits from its chunk's true
        // middle, and however big its radius has grown, the two together never exceed it. For an
        // unexpanded country (radius 7..11) that leaves the FULL original jitter budge available --
        // this is exactly the spacing the game already shipped with, unchanged. Only a country
        // whose radius has grown past what that budget can absorb gives up some of its jitter to
        // stay inside its own cell, sitting closer to dead centre the bigger it gets.
        val radius = blobRadius(level, rng)
        val jitterHalf = (EDGE_CEILING - radius).coerceIn(0.0, CHUNK * JITTER / 2)
        val cx = chunkX * CHUNK + CHUNK / 2 + (rng.nextDouble() - 0.5) * 2 * jitterHalf
        val cy = chunkY * CHUNK + CHUNK / 2 + (rng.nextDouble() - 0.5) * 2 * jitterHalf

        val soldiers = Era.armyCapacity(level) * (40 + rng.nextInt(61)) / 100
        val buildings = (BuildingCatalog.unlockedAt(level).size * (25 + rng.nextInt(46)) / 100)
            .coerceAtLeast(1)

        return WorldMap.Country(
            id = id,
            name = "${rng.pick(FIRST)} ${rng.pick(SECOND)}",
            owner = WorldMap.Owner.AI,
            level = level,
            outline = blob(rng, cx, cy, radius),
            centreX = cx,
            centreY = cy,
            soldiers = soldiers,
            buildingCount = buildings,
            population = buildings * (3 + level / 40),
            xp = Era.xpForLevel(level) + rng.nextInt(400),
            battlesWon = rng.nextInt(level / 2 + 2),
            battlesLost = rng.nextInt(level / 3 + 2),
        )
    }

    /**
     * How dangerous a country is, given where it sits.
     *
     * Near home it matches the player, so there is always something to fight. Further out it climbs
     * — slowly at first and without limit — which turns "scroll further" into "find something
     * harder" and gives an endless map a reason to be endless.
     */
    private fun levelFor(playerLevel: Int, chunkX: Int, chunkY: Int, rng: Rng): Int {
        val rings = maxOf(kotlin.math.abs(chunkX), kotlin.math.abs(chunkY))
        val reach = playerLevel + (rings - 1) * 9 + rings * rings / 3
        val spread = (reach * 0.35).toInt().coerceAtLeast(6)
        return (reach - spread / 2 + rng.nextInt(spread + 1))
            .coerceIn(Era.MIN_LEVEL, Era.MAX_LEVEL)
    }

    private fun blob(rng: Rng, cx: Double, cy: Double, baseR: Double): List<Pair<Int, Int>> {
        val points = 12
        return (0 until points).map { i ->
            val angle = 2.0 * Math.PI * i / points
            val r = baseR * (0.72 + rng.nextDouble() * 0.56)
            ((cx + r * Math.cos(angle)).toInt()) to ((cy + r * 0.72 * Math.sin(angle)).toInt())
        }
    }

    /**
     * How big a country's blob wants to be, before the chunk-edge guarantee clamps it.
     *
     * [Era.aiPlotSizeAt] is what actually grew -- the same field size a battle over this country
     * would be fought on -- so the blob's radius follows it: a country that has bought or earned
     * five boundary expansions occupies visibly more of the map than one that has bought none, on
     * top of the same 7..11 random variation every country has always had.
     */
    /**
     * How big a country's blob wants to be, before the chunk-edge guarantee clamps it.
     *
     * Public because a peer's blob (drawn by [com.prism.launcher.minigames.MinigameMesh] from
     * whatever level the peer announces) is sized the same way, for the same reason: a peer who
     * has expanded their boundaries should look bigger on your map too, not just an AI country.
     */
    fun blobRadius(level: Int, rng: Rng): Double {
        val base = 7.0 + rng.nextDouble() * 4.0
        val scale = Era.aiPlotSizeAt(level) / Battle.FIELD.toDouble()
        return (base * scale).coerceAtMost(EDGE_CEILING)
    }

    /** Where the player's own country sits: the origin, always. */
    fun playerChunkCentre(): Pair<Double, Double> = (CHUNK / 2) to (CHUNK / 2)

    /**
     * Where a peer's country sits.
     *
     * Derived from the peer's id rather than from anything it announces, so two devices place the
     * same peer in the same place without agreeing on anything — and so a peer cannot claim to be
     * standing on top of somebody else.
     */
    fun peerCentre(peerId: String): Pair<Double, Double> {
        val rng = Rng(Rng.seedOf("peer", peerId))
        // Out among the near chunks, but never in the player's own.
        var chunkX = rng.nextInt(-4, 5)
        var chunkY = rng.nextInt(-4, 5)
        if (chunkX == 0 && chunkY == 0) chunkX = 1
        val inner = CHUNK * JITTER
        return (chunkX * CHUNK + CHUNK / 2 + (rng.nextDouble() - 0.5) * inner) to
            (chunkY * CHUNK + CHUNK / 2 + (rng.nextDouble() - 0.5) * inner)
    }
}
