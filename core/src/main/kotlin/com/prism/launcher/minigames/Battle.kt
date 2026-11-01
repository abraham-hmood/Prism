package com.prism.launcher.minigames

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A raid, simulated one tick at a time.
 *
 * ## Why this is deterministic
 *
 * The same seed, the same base and the same orders always produce the same fight, down to which
 * stick figure falls over when. Three things in the design need that and none of them work without
 * it: another player can WATCH your battle over the mesh without either device streaming anything
 * (both run the same simulation from the same seed), a replay can be stored as a seed and a list of
 * orders rather than as a film, and a defence that happens while you are asleep can be recomputed
 * and shown to you afterwards exactly as it happened.
 *
 * So: no wall clock, no [kotlin.random.Random.Default], no iteration over hash sets. Every random
 * number comes from [Rng], and every collection that matters is a list in a fixed order.
 *
 * ## The model
 *
 * Attackers are dropped at the edge of the paper and walk toward whatever they have been told to
 * attack. Each has a weapon from [WeaponCatalog], which is where its reach, damage and rate come
 * from. Defences shoot back inside their own range; a defender's other buildings just stand there
 * and take it. The raid ends when the attackers are dead, the timer runs out, or the town hall and
 * half the base are gone.
 *
 * ## Orders
 *
 * "If you are the one doing the battle, you can control where your soldiers attack." That is
 * [Order]: a target point, applied to the squad that has not been given one, at a tick. The
 * simulation reads them; nothing else in the fight is under player control, which keeps a battle
 * short enough to watch.
 */
object Battle {

    const val TICKS_PER_SECOND = 10
    const val MAX_SECONDS = 180
    const val MAX_TICKS = TICKS_PER_SECOND * MAX_SECONDS

    /** The paper is 32 cells square. Big enough for a level-500 base, small enough to see. */
    /**
     * The size, in cells, of the field every country starts on.
     *
     * No longer the size every BATTLE happens on: a country that has expanded its boundaries (see
     * [PaperBase.plotSize]) is fought over on its own, larger field. [FIELD] remains what the
     * opening position, the AI generator's baseline and every un-expanded country still use.
     */
    const val FIELD = 32

    /**
     * How far a weapon actually reaches on a page this size.
     *
     * The catalogue's ranges run to twenty-four cells, which is fine as a STAT — it is what makes a
     * railgun feel like a railgun next to a bow — and catastrophic as a distance on a
     * thirty-two-cell field. Taken literally, every defence covers the whole base from the middle
     * and every attacker can hit the town hall from the edge, so a battle becomes one simultaneous
     * exchange on tick zero and whoever the loop happens to run first wins. A level-500 raid
     * finished in a single frame with both sides annihilated.
     *
     * So the nominal range is compressed onto the board. The mapping is monotonic, which is the
     * property that matters: a bow still outranges an axe and a trebuchet still outranges both, and
     * nothing reaches more than about a third of the way across. Attackers now have to walk in, and
     * defences engage them as they arrive, which is the shape a raid is supposed to have.
     */
    fun effectiveRange(weapon: WeaponCatalog.Weapon): Double =
        1.2 + weapon.range * 0.44

    /**
     * A uniform grid over the field, so "what is near me" stops costing a full scan.
     *
     * ## Why this had to exist
     *
     * Both hot loops were quadratic. Every attacker looking for a target scanned every standing
     * building, and every defence looking for something to shoot scanned every attacker. At the
     * scale the game reached — five hundred soldiers against three hundred buildings and a hundred
     * emplacements — that is a hundred and eighty thousand distance checks per tick and several
     * hundred ticks a battle, which on a phone is seconds of frozen UI.
     *
     * The field is only thirty-two cells square, so a bucket per cell is a 1,024-entry array that
     * costs nothing to rebuild and turns both loops into a look at the handful of cells within
     * reach. The result is identical — the same nearest target, chosen the same way — because the
     * candidates are still compared by the same distance; there are simply far fewer of them.
     */
    private class Grid(fieldSize: Int, private val cell: Int = 4) {
        private val span = (fieldSize + cell - 1) / cell
        private val buckets = Array(span * span) { ArrayList<Int>(8) }

        fun clear() = buckets.forEach { it.clear() }

        private fun index(x: Double, y: Double): Int {
            val gx = (x / cell).toInt().coerceIn(0, span - 1)
            val gy = (y / cell).toInt().coerceIn(0, span - 1)
            return gy * span + gx
        }

        fun add(x: Double, y: Double, id: Int) = buckets[index(x, y)].add(id)

        /** Visits everything within [reach] of the point, plus a little slop at the bucket edges. */
        inline fun near(x: Double, y: Double, reach: Double, visit: (Int) -> Unit) {
            val rings = (reach / cell).toInt() + 1
            val gx = (x / cell).toInt()
            val gy = (y / cell).toInt()
            for (by in (gy - rings)..(gy + rings)) {
                if (by < 0 || by >= span) continue
                for (bx in (gx - rings)..(gx + rings)) {
                    if (bx < 0 || bx >= span) continue
                    val bucket = buckets[by * span + bx]
                    for (i in bucket.indices) visit(bucket[i])
                }
            }
        }

        /** Everything, for the case where nothing is in reach and the nearest is wanted anyway. */
        inline fun all(visit: (Int) -> Unit) {
            for (bucket in buckets) for (i in bucket.indices) visit(bucket[i])
        }
    }

    /** An instruction from the attacking player: send this squad here, from this tick on. */
    data class Order(val atTick: Int, val squad: Int, val targetX: Int, val targetY: Int)

    /** One attacker on the page. */
    data class Fighter(
        val index: Int,
        val squad: Int,
        /** How many real soldiers this figure stands for. See [MAX_FIGHTERS]. */
        val strength: Int = 1,
        var x: Double,
        var y: Double,
        var hitPoints: Int,
        val maxHitPoints: Int,
        val weaponId: String,
        var cooldown: Int = 0,
        var targetId: String? = null,
        var orderX: Double? = null,
        var orderY: Double? = null,
        /**
         * The circle a flying, piloted weapon holds around whatever it is attacking, and how far
         * round it currently is. Zero means "not orbiting yet" — every ground unit stays at zero
         * for its whole life, and an aircraft's is reset to zero whenever it picks a new target, so
         * the next time it is in range the circle is locked in from wherever it actually is rather
         * than snapped to some fixed radius. See [stepAttacker].
         */
        var orbitRadius: Double = 0.0,
        var orbitAngle: Double = 0.0,
    ) {
        val represented: Int get() = strength
        val alive: Boolean get() = hitPoints > 0
        val weapon: WeaponCatalog.Weapon get() = WeaponCatalog.byId(weaponId) ?: WeaponCatalog.STARTER
    }

    /** A defending building that shoots. Garrison soldiers are modelled as one of these too. */
    data class Emplacement(
        val buildingId: String,
        val x: Double,
        val y: Double,
        val weaponId: String,
        var cooldown: Int = 0,
    ) {
        val weapon: WeaponCatalog.Weapon get() = WeaponCatalog.byId(weaponId) ?: WeaponCatalog.STARTER
    }

    /**
     * A garrison soldier who has come out of the barracks to fight.
     *
     * The old model put the defenders inside their buildings as fixed [Emplacement]s, which made
     * an invasion look like an empty base shooting back. These are actual bodies: they leave their
     * posts, close on the nearest attacker, and shoot. Like attackers they are grouped when a
     * country has thousands of soldiers, so a large garrison does not cost thousands of objects.
     */
    data class Guard(
        val index: Int,
        var x: Double,
        var y: Double,
        val strength: Int,
        var hitPoints: Int,
        val maxHitPoints: Int,
        val weaponId: String,
        var cooldown: Int = 0,
        var target: Int = -1,
    ) {
        val alive: Boolean get() = hitPoints > 0
        val weapon: WeaponCatalog.Weapon get() = WeaponCatalog.byId(weaponId) ?: WeaponCatalog.STARTER
    }

    /**
     * What does not change during a battle, hoisted out of the frames.
     *
     * A frame used to carry a full copy of every fighter and a fresh map of every building's hit
     * points. At a hundred and twenty soldiers and a hundred and twenty buildings over four hundred
     * frames that is a hundred thousand short-lived objects and eight megabytes of garbage per
     * simulation — and the simulation is re-run every time the player gives an order. Everything
     * constant lives here instead, and a frame is three primitive arrays.
     */
    data class Cast(
        /** Which squad each fighter belongs to, in index order. */
        val squads: IntArray,
        /** What each fighter is carrying. Allied contingents may differ from the main army. */
        val weaponIds: List<String>,
        /** The order [Frame.buildingHp] is in. */
        val buildingIds: List<String>,
        val maxFighterHp: Int,
        /** The sortieing garrison, if this raid has one. Empty for the old static-garrison battle. */
        val guardWeaponIds: List<String> = emptyList(),
        val maxGuardHp: Int = 1,
    ) {
        val size: Int get() = squads.size
        val guardCount: Int get() = guardWeaponIds.size

        fun weaponOf(index: Int): WeaponCatalog.Weapon =
            WeaponCatalog.byId(weaponIds.getOrNull(index) ?: "") ?: WeaponCatalog.STARTER
    }

    /**
     * One frame, as flat as it can be.
     *
     * [positions] is x then y per fighter; [fighterHp] and [buildingHp] are index-parallel to
     * [Cast.squads] and [Cast.buildingIds]. A building at zero or less has been rubbed out, so
     * there is no separate destroyed set to keep in step with the hit points.
     */
    class Frame(
        val tick: Int,
        val positions: FloatArray,
        val fighterHp: IntArray,
        val buildingHp: IntArray,
        val shots: List<Shot>,
        /** x then y per defender, in [Cast.guardWeaponIds] order. Empty when none sortied. */
        val guardPositions: FloatArray = FloatArray(0),
        val guardHp: IntArray = IntArray(0),
    ) {
        fun x(index: Int): Float = positions[index * 2]
        fun y(index: Int): Float = positions[index * 2 + 1]
        fun gx(index: Int): Float = guardPositions[index * 2]
        fun gy(index: Int): Float = guardPositions[index * 2 + 1]
        fun alive(index: Int): Boolean = fighterHp[index] > 0
        fun guardAlive(index: Int): Boolean = guardHp.getOrNull(index)?.let { it > 0 } == true
        fun standing(index: Int): Boolean = buildingHp[index] > 0
        fun aliveCount(): Int = fighterHp.count { it > 0 }
        fun guardCount(): Int = guardHp.count { it > 0 }
        fun destroyedCount(): Int = buildingHp.count { it <= 0 }
    }

    /** A whole battle: who was in it, what happened, and how it ended. */
    data class Playback(
        val cast: Cast,
        val frames: List<Frame>,
        val result: Result,
        /**
         * How many simulation ticks each kept frame represents.
         *
         * One for an ordinary battle. Above a few hundred fighters the simulation keeps every
         * second or third tick instead, and the player has to be shown them at the matching rate —
         * otherwise a large assault plays back at double speed and looks like a glitch rather than
         * a battle.
         */
        val ticksPerFrame: Int = 1,
    ) {
        /** Milliseconds a frame should be held for, so playback runs at real time either way. */
        val frameMillis: Long get() = 1000L * ticksPerFrame / TICKS_PER_SECOND
    }

    /** A line to draw this frame: somebody shot at somebody. */
    data class Shot(
        val fromX: Double, val fromY: Double,
        val toX: Double, val toY: Double,
        val fromAttacker: Boolean,
        val splash: Int,
    )

    data class Result(
        val won: Boolean,
        val destructionPercent: Int,
        val townHallDown: Boolean,
        val attackersLost: Int,
        val attackersSent: Int,
        val ticks: Int,
        val buildingDamage: Map<String, Int>,
        val xpForAttacker: Long,
        val xpForDefender: Long,
        val stars: Int,
    )

    /**
     * Runs a whole raid and hands back every frame.
     *
     * Frames are kept rather than streamed because the caller always wants them all: the attacker
     * watches it live, the defender watches the same thing later, and a mesh spectator recomputes
     * it from the seed. At 10 ticks a second for at most three minutes that is 1,800 frames of a
     * few dozen small objects, which is nothing next to holding the drawing itself.
     */
    /**
     * The most frames a playback is allowed to hold.
     *
     * Nine hundred at ten a second is a minute and a half of real time, and a battle longer than
     * that is decimated rather than truncated -- see the note in [simulate]. Chosen so the worst
     * case, five thousand fighters, stays under about sixty megabytes of positions rather than
     * running to several hundred.
     */
    private const val MAX_FRAMES = 900

    /** The most tracers a single frame carries. Past this they overlap and cannot be told apart. */
    private const val MAX_SHOTS_PER_FRAME = 240

    /**
     * The most figures one side ever simulates or draws.
     *
     * A five-thousand-soldier invasion used to create five thousand Fighter objects, step every
     * one of them ten times a second, and then draw them all. The player sees a hundred figures on
     * a phone screen at once; everything past that is invisible work. Above this many soldiers each
     * figure represents a whole file of them, with the hit points and the damage to match, so the
     * battle results are the same size while the work is bounded.
     */
    private const val MAX_FIGHTERS = 400

    /** The most garrison figures that sortie. Same reasoning as [MAX_FIGHTERS]. */
    private const val MAX_GUARDS = 48

    fun simulate(
        defender: PaperBase,
        attackerLevel: Int,
        attackerSoldiers: Int,
        attackerWeaponId: String,
        orders: List<Order>,
        seed: Long,
        /**
         * The crewed detachments fielded alongside the infantry: tanks, aircraft, guns, launchers.
         *
         * Each takes [PaperBase.DETACHMENT_SHARE] of the army, replacing that many foot soldiers
         * rather than adding to them. Defaults to empty so every existing caller and every test
         * still describes exactly the army it used to.
         */
        attackerSupportIds: List<String> = emptyList(),
        /**
         * How much better trained and equipped this army is, from the attacker's own MILITARY
         * buildings -- barracks, stables, drill yards and the rest of the catalogue that is not
         * the training camp itself. [PaperBase.militaryBonus] is where this actually comes from;
         * it defaults to 1.0 (no change) so every existing caller and test describes exactly the
         * army it always did.
         */
        attackerMilitaryBonus: Double = 1.0,
        /**
         * Whether the defending garrison comes out to fight. Off by default so the older,
         * deterministic tests still describe the battle they were written against; the game itself
         * always turns it on.
         */
        defenderSortie: Boolean = false,
    ): Playback {
        val rng = Rng(seed)
        // Whatever this defender's own plot has grown to. A country that has expanded its
        // boundaries is fought over on that bigger field: attackers spawn at ITS edges, not at the
        // edge of the field every country started with, or an expanded base would find invaders
        // starting somewhere in the middle of it.
        val fieldSize = defender.plotSize
        val targets = defender.standing.toMutableList()
        val hp = targets.associate { it.id to it.hitPoints }.toMutableMap()
        val startingHp = hp.toMap()
        val destroyed = mutableSetOf<String>()

        val squads = 4
        // See MAX_FIGHTERS. The army is represented by at most that many figures; each one carries
        // the strength (and therefore the hit points and damage) of everybody it stands for.
        val group = ((attackerSoldiers + MAX_FIGHTERS - 1) / MAX_FIGHTERS).coerceAtLeast(1)
        val figureCount = ((attackerSoldiers + group - 1) / group).coerceAtLeast(0)
        val fighters = ArrayList<Fighter>(figureCount)
        val weapon = WeaponCatalog.byId(attackerWeaponId) ?: WeaponCatalog.STARTER
        val fighterHp = ((40 + attackerLevel * 7) * attackerMilitaryBonus).toInt().coerceAtLeast(1)

        /**
         * What each fighter is, worked out before the loop.
         *
         * The detachments are laid out at the FRONT of the list rather than sprinkled through it,
         * and because squads are assigned round-robin by index this puts a share of every
         * detachment into every squad — so each of the four approaches has its own armour and air
         * cover instead of one corner getting all the tanks.
         */
        val support = attackerSupportIds.mapNotNull { WeaponCatalog.byId(it) }
        val perDetachment = (attackerSoldiers * PaperBase.DETACHMENT_SHARE).toInt()
        val crews = ArrayList<WeaponCatalog.Weapon>(support.size * perDetachment)
        if (perDetachment > 0) {
            support.forEach { vehicle -> repeat(perDetachment) { crews.add(vehicle) } }
        }

        repeat(figureCount) { i ->
            val strength = (attackerSoldiers - i * group).coerceAtMost(group)
            // The crew index follows the original soldier index, so detachments stay at the front
            // of the line and are still spread across all four squads.
            val mine = crews.getOrNull(i * group) ?: weapon
            val squad = i % squads
            // One squad per edge, so the four order buttons map onto four places a player can see.
            val along = rng.nextDouble() * (fieldSize - 4) + 2
            val (sx, sy) = when (squad) {
                0 -> along to 0.5
                1 -> (fieldSize - 0.5) to along
                2 -> along to (fieldSize - 0.5)
                else -> 0.5 to along
            }
            fighters.add(
                Fighter(
                    index = i, squad = squad, strength = strength, x = sx, y = sy,
                    // A crew is harder to kill than the soldier it replaces, which is most of why
                    // anybody builds one.
                    hitPoints = (fighterHp * mine.crewSurvivability * strength).toInt(),
                    maxHitPoints = (fighterHp * mine.crewSurvivability * strength).toInt(),
                    weaponId = mine.id,
                    // A staggered first shot. With every cooldown starting at zero, eighty soldiers
                    // fire on the same tick and the fight is decided before anybody has moved.
                    cooldown = rng.nextInt(mine.cooldownTicks + 1),
                )
            )
        }

        val emplacements = buildEmplacements(defender, includeGarrison = !defenderSortie)
        val guards = if (defenderSortie) buildSortie(defender) else emptyList()
        val ordersByTick = orders.groupBy { it.atTick }
        val frames = ArrayList<Frame>()

        val fighterGrid = Grid(fieldSize)
        val targetGrid = Grid(fieldSize)

        /**
         * How many ticks are actually kept as frames.
         *
         * A five-hundred-strong assault produces frames of four thousand bytes, and at ten a
         * second for three minutes that is seven megabytes of playback nobody looks at frame by
         * frame. Above a few hundred fighters every second tick is kept and the playback runs at
         * half rate, which is invisible on screen and halves both the memory and the copying.
         */
        var frameEvery = when {
            fighters.size > 600 -> 3
            fighters.size > 250 -> 2
            else -> 1
        }
        // Fixed once: the frames index into this rather than carrying their own copy of the keys.
        val buildingOrder = defender.standing.map { it.id }

        var tick = 0
        while (tick < MAX_TICKS) {
            ordersByTick[tick]?.forEach { order ->
                fighters.forEach { f ->
                    if (f.squad == order.squad && f.alive) {
                        f.orderX = order.targetX.toDouble()
                        f.orderY = order.targetY.toDouble()
                        f.targetId = null
                    }
                }
            }

            val shots = ArrayList<Shot>()

            // The index of what is still standing, rebuilt once a tick rather than scanned per
            // fighter. See Grid.
            targetGrid.clear()
            targets.forEachIndexed { index, b ->
                if (b.id !in destroyed) targetGrid.add(b.centreX(), b.centreY(), index)
            }

            // Attackers act, in index order, so the simulation is reproducible.
            fighters.forEach { f ->
                if (!f.alive) return@forEach
                stepAttacker(f, targets, targetGrid, guards, hp, destroyed, shots, rng, fieldSize)
            }

            fighterGrid.clear()
            fighters.forEachIndexed { index, f -> if (f.alive) fighterGrid.add(f.x, f.y, index) }

            // The defenders sortie: they leave their posts, close on the nearest attacker, and
            // shoot. A garrison that never moved was the thing that made an invasion look staged.
            guards.forEach { g -> if (g.alive) stepGuard(g, fighters, shots, rng, fieldSize) }

            // Defences answer. Same reason for the fixed order.
            emplacements.forEach { e ->
                if (e.buildingId in destroyed) return@forEach
                stepDefence(e, fighters, fighterGrid, shots, rng)
            }

            // Rub out anything that reached zero.
            val newlyDead = hp.filter { it.value <= 0 && it.key !in destroyed }.keys.toList().sorted()
            if (newlyDead.isNotEmpty()) {
                destroyed.addAll(newlyDead)
                targets.removeAll { it.id in newlyDead }
                fighters.forEach { if (it.targetId in newlyDead) it.targetId = null }
            }

            val keepFrame = tick % frameEvery == 0
            if (!keepFrame) {
                val anyLeft = fighters.any { it.alive }
                if (!anyLeft || targets.isEmpty()) break
                tick++
                continue
            }

            // ── Keeping the playback bounded ─────────────────────────────
            //
            // A frame is four bytes a coordinate plus four a hit point, so five thousand fighters
            // is sixty kilobytes of frame, and a long battle is hundreds of frames. Left alone that
            // is tens of megabytes of playback that nobody scrubs through, held for the length of
            // the battle and collected all at once afterwards -- which is felt as a stutter rather
            // than seen as a number.
            //
            // So the playback is decimated in place when it gets too long: every second frame is
            // dropped and the rate doubled, which is exactly what would have happened had the
            // battle's length been known at the start. Memory is therefore bounded by MAX_FRAMES
            // whatever the army size and however long the fight runs, and the playback still covers
            // the whole battle at a slightly coarser step.
            if (frames.size >= MAX_FRAMES) {
                var write = 0
                for (read in frames.indices step 2) {
                    frames[write] = frames[read]
                    write++
                }
                while (frames.size > write) frames.removeAt(frames.size - 1)
                frameEvery *= 2
            }

            val positions = FloatArray(fighters.size * 2)
            val fighterHp = IntArray(fighters.size)
            fighters.forEachIndexed { index, f ->
                positions[index * 2] = f.x.toFloat()
                positions[index * 2 + 1] = f.y.toFloat()
                fighterHp[index] = f.hitPoints
            }
            val guardPositions = FloatArray(guards.size * 2)
            val guardHp = IntArray(guards.size)
            guards.forEachIndexed { index, g ->
                guardPositions[index * 2] = g.x.toFloat()
                guardPositions[index * 2 + 1] = g.y.toFloat()
                guardHp[index] = g.hitPoints
            }
            frames.add(
                Frame(
                    tick = tick,
                    positions = positions,
                    fighterHp = fighterHp,
                    guardPositions = guardPositions,
                    guardHp = guardHp,
                    buildingHp = IntArray(buildingOrder.size) { i ->
                        val id = buildingOrder[i]
                        if (id in destroyed) 0 else (hp[id] ?: 0)
                    },
                    // Capped: past a couple of hundred tracers a frame they are drawn over each
                    // other anyway, and the renderer already batches and thins them. Keeping
                    // thousands of Shot objects a frame that nothing can see is pure cost. The list
                    // is built fresh each tick, so it can be handed over rather than copied.
                    shots = if (shots.size <= MAX_SHOTS_PER_FRAME) shots else shots.subList(0, MAX_SHOTS_PER_FRAME).toList(),
                )
            )

            val anyAttackerLeft = fighters.any { it.alive }
            if (!anyAttackerLeft || targets.isEmpty()) break
            tick++
        }

        val result = score(
            defender = defender,
            startingHp = startingHp,
            endingHp = hp,
            destroyed = destroyed,
            fighters = fighters,
            attackerLevel = attackerLevel,
            ticks = frames.size,
        )
        return Playback(
            cast = Cast(
                squads = IntArray(fighters.size) { fighters[it].squad },
                weaponIds = fighters.map { it.weaponId },
                buildingIds = buildingOrder,
                maxFighterHp = fighterHp,
                guardWeaponIds = guards.map { it.weaponId },
                maxGuardHp = guards.maxOfOrNull { it.maxHitPoints } ?: 1,
            ),
            frames = frames,
            result = result,
            ticksPerFrame = frameEvery,
        )
    }

    // -- One actor's turn -----------------------------------------------------

    private fun stepAttacker(
        f: Fighter,
        targets: List<PlacedBuilding>,
        grid: Grid,
        guards: List<Guard>,
        hp: MutableMap<String, Int>,
        destroyed: Set<String>,
        shots: MutableList<Shot>,
        rng: Rng,
        fieldSize: Int,
    ) {
        if (f.cooldown > 0) f.cooldown--

        // A soldier standing in front of you is the thing you shoot at, not the building behind
        // them. Without this the garrison could fire into an army that never shot back.
        val reachNow = effectiveRange(f.weapon)
        var inReach: Guard? = null
        var closest = reachNow
        guards.forEach { g ->
            if (g.alive) {
                val d = hypot(g.x - f.x, g.y - f.y)
                if (d <= closest) {
                    closest = d
                    inReach = g
                }
            }
        }
        if (inReach != null) {
            if (f.cooldown <= 0) {
                val dealt = (f.weapon.damage * f.strength * (0.9 + rng.nextDouble() * 0.2))
                    .toInt().coerceAtLeast(1)
                inReach.hitPoints -= dealt
                shots.add(Shot(f.x, f.y, inReach.x, inReach.y, fromAttacker = true, splash = 0))
                f.cooldown = f.weapon.cooldownTicks
            }
            return
        }

        val current = f.targetId?.let { id -> targets.firstOrNull { it.id == id } }
        val target = current ?: pickTarget(f, targets, grid, destroyed)?.also {
            f.targetId = it.id
            // A new target means a new circle: locked in from scratch the next time this fighter
            // is close enough to fly it, not carried over from whatever it was just attacking.
            f.orbitRadius = 0.0
        }

        if (target == null) {
            // Nothing left worth hitting: walk to the order point, or stand still.
            moveToward(f, f.orderX ?: f.x, f.orderY ?: f.y, speed = SPEED, fieldSize = fieldSize)
            return
        }

        val dist = hypot(target.centreX() - f.x, target.centreY() - f.y)
        val reach = effectiveRange(f.weapon)

        if (dist > reach) {
            moveToward(f, target.centreX(), target.centreY(), speed = SPEED, fieldSize = fieldSize)
            return
        }

        // A flying, piloted weapon does not stop dead the way infantry, armour and guns do — it
        // circles the target and keeps firing on the way round, which is what an actual strafing
        // run looks like. Every OTHER weapon class holds its ground once it is close enough to
        // hit, which is still correct for something that walks, rolls or sits on a tripod.
        if (f.weapon.weaponClass == WeaponCatalog.WeaponClass.AIRCRAFT) {
            orbitTarget(f, target, dist, reach, fieldSize)
        }

        if (f.cooldown > 0) return

        val dealt = f.weapon.buildingDamage.let { base ->
            // A small spread so two identical raids do not read as a spreadsheet, bounded so the
            // outcome is never a coin flip.
            (base * (0.9 + rng.nextDouble() * 0.2)).toInt().coerceAtLeast(1)
        }
        hp[target.id] = (hp[target.id] ?: 0) - dealt

        if (f.weapon.splash > 0) {
            // Only the buckets the blast can actually reach, rather than every building on the
            // paper. A splash of four is a couple of cells; scanning three hundred buildings for it
            // was most of the cost of a late-game battle.
            grid.near(target.centreX(), target.centreY(), f.weapon.splash.toDouble()) { index ->
                val other = targets[index]
                if (other.id != target.id && other.id !in destroyed) {
                    val d = hypot(other.centreX() - target.centreX(), other.centreY() - target.centreY())
                    if (d <= f.weapon.splash) {
                        hp[other.id] = (hp[other.id] ?: 0) - (dealt / 2).coerceAtLeast(1)
                    }
                }
            }
        }

        shots.add(
            Shot(f.x, f.y, target.centreX(), target.centreY(), fromAttacker = true, splash = f.weapon.splash)
        )
        f.cooldown = f.weapon.cooldownTicks
    }

    private fun stepDefence(
        e: Emplacement,
        fighters: List<Fighter>,
        grid: Grid,
        shots: MutableList<Shot>,
        rng: Rng,
    ) {
        if (e.cooldown > 0) {
            e.cooldown--
            return
        }
        val reach = effectiveRange(e.weapon)

        // The nearest attacker inside reach, found through the grid rather than by walking five
        // hundred fighters. Identical answer; a fraction of the work.
        var best: Fighter? = null
        var bestDistance = Double.MAX_VALUE
        grid.near(e.x, e.y, reach) { index ->
            val f = fighters[index]
            if (f.alive) {
                val d = hypot(f.x - e.x, f.y - e.y)
                if (d <= reach && d < bestDistance) {
                    bestDistance = d
                    best = f
                }
            }
        }
        val target = best ?: return

        val dealt = (e.weapon.damage * (0.9 + rng.nextDouble() * 0.2)).toInt().coerceAtLeast(1)
        target.hitPoints -= dealt

        if (e.weapon.splash > 0) {
            grid.near(target.x, target.y, e.weapon.splash.toDouble()) { index ->
                val other = fighters[index]
                if (other.index != target.index && other.alive &&
                    hypot(other.x - target.x, other.y - target.y) <= e.weapon.splash
                ) {
                    other.hitPoints -= (dealt / 2).coerceAtLeast(1)
                }
            }
        }

        shots.add(Shot(e.x, e.y, target.x, target.y, fromAttacker = false, splash = e.weapon.splash))
        e.cooldown = e.weapon.cooldownTicks
    }

    private const val SPEED = 0.16

    private fun moveToward(f: Fighter, tx: Double, ty: Double, speed: Double, fieldSize: Int) {
        val dx = tx - f.x
        val dy = ty - f.y
        val d = hypot(dx, dy)
        if (d < 1e-6) return
        f.x = (f.x + dx / d * speed).coerceIn(0.0, fieldSize.toDouble())
        f.y = (f.y + dy / d * speed).coerceIn(0.0, fieldSize.toDouble())
    }

    /** Radians a fighter's orbit advances each tick. A touch under one full circle every four seconds. */
    private const val ORBIT_ANGULAR_SPEED = 0.16

    /**
     * Holds an aircraft on a circle around [target] instead of letting it come to a stop.
     *
     * The circle is locked in the first tick the fighter is close enough to use — at whatever
     * radius and bearing it actually arrived at, from [dist] and its real position — and only the
     * ANGLE moves after that. Anything else (snapping to one fixed radius, or aiming straight at
     * the centre every tick) would show up as the aircraft's position jumping the instant it enters
     * range, which is the exact stop-dead behaviour this exists to replace.
     */
    private fun orbitTarget(f: Fighter, target: PlacedBuilding, dist: Double, reach: Double, fieldSize: Int) {
        if (f.orbitRadius <= 0.0) {
            f.orbitRadius = dist.coerceIn(1.2, (reach * 0.85).coerceAtLeast(1.2))
            f.orbitAngle = kotlin.math.atan2(f.y - target.centreY(), f.x - target.centreX())
        }
        f.orbitAngle += ORBIT_ANGULAR_SPEED
        f.x = (target.centreX() + f.orbitRadius * kotlin.math.cos(f.orbitAngle))
            .coerceIn(0.0, fieldSize.toDouble())
        f.y = (target.centreY() + f.orbitRadius * kotlin.math.sin(f.orbitAngle))
            .coerceIn(0.0, fieldSize.toDouble())
    }

    /**
     * What a soldier goes for.
     *
     * An explicit order wins: if the player has pointed a squad at a corner of the base, that is
     * where it goes, and the nearest building to that point is what it hits. Without an order,
     * soldiers behave the way every player expects them to — nearest thing first — with defences
     * weighted slightly closer so a squad walking past a turret does not ignore it.
     */
    private fun pickTarget(
        f: Fighter,
        targets: List<PlacedBuilding>,
        grid: Grid,
        destroyed: Set<String>,
    ): PlacedBuilding? {
        val fromX = f.orderX ?: f.x
        val fromY = f.orderY ?: f.y

        var best: PlacedBuilding? = null
        var bestScore = Double.MAX_VALUE

        fun consider(index: Int) {
            val b = targets[index]
            if (b.id in destroyed) return
            val d = hypot(b.centreX() - fromX, b.centreY() - fromY)
            val score = if (b.type?.isDefensive == true) d * 0.75 else d
            if (score < bestScore) {
                bestScore = score
                best = b
            }
        }

        // Widening rings, so a fighter standing next to a building looks at a handful of cells
        // rather than at the whole base. The full sweep is the fallback for the case where the
        // nearest standing building is genuinely far away — which is rare, and correct when it is.
        listOf(6.0, 14.0).forEach { reach ->
            if (best != null) return@forEach
            grid.near(fromX, fromY, reach) { consider(it) }
        }
        if (best == null) grid.all { consider(it) }
        return best
    }

    /** Defences, plus the garrison, as things that shoot. */
    private fun buildEmplacements(
        defender: PaperBase,
        includeGarrison: Boolean = true,
    ): List<Emplacement> {
        val garrisonWeapon = defender.garrisonWeapon().id
        val list = ArrayList<Emplacement>()

        val stagger = Rng(Rng.seedOf("emplacements", defender.name, defender.standing.size))
        val reload = (WeaponCatalog.byId(garrisonWeapon)?.cooldownTicks ?: 10) + 1

        defender.standing.forEach { b ->
            if (b.type?.isDefensive == true) {
                list.add(
                    Emplacement(b.id, b.centreX(), b.centreY(), garrisonWeapon, stagger.nextInt(reload)),
                )
            }
        }

        // Home soldiers defend, and they defend the WHOLE base. Posting them only at the military
        // buildings put every one of them in the middle, which left the outer ring — the farms and
        // mines an attacker meets first — completely unguarded: a lone attacker could destroy the
        // edge of a base at leisure while its entire garrison stood around the town hall.
        //
        // Military buildings are still where soldiers are quartered, so they are listed first and
        // get the first soldiers; everything else is a post once those are full.
        val posts = (
            defender.standing.filter { it.type?.category == BuildingCatalog.Category.MILITARY } +
                defender.standing.filter { it.type?.isDefensive == true } +
                defender.standing.filter {
                    it.type?.category != BuildingCatalog.Category.MILITARY &&
                        it.type?.isDefensive != true
                }
            ).ifEmpty { listOfNotNull(defender.townHall) }

        if (includeGarrison && posts.isNotEmpty() && defender.soldiers > 0) {
            repeat(defender.soldiers.coerceAtMost(60)) { i ->
                val post = posts[i % posts.size]
                list.add(
                    Emplacement(
                        buildingId = post.id,
                        x = post.centreX() + ((i % 3) - 1) * 0.6,
                        y = post.centreY() + ((i / 3 % 3) - 1) * 0.6,
                        weaponId = garrisonWeapon,
                        cooldown = stagger.nextInt(reload),
                    )
                )
            }
        }
        return list
    }

    /**
     * The defenders, out of their barracks and on the field.
     *
     * A country with five thousand soldiers cannot put five thousand figures on a phone, so the
     * same file-grouping the attackers use applies here. Each guard stands for a file of them, and
     * a file fights and dies as one.
     */
    private fun buildSortie(defender: PaperBase): List<Guard> {
        if (defender.soldiers <= 0) return emptyList()
        val group = ((defender.soldiers + MAX_GUARDS - 1) / MAX_GUARDS).coerceAtLeast(1)
        val count = ((defender.soldiers + group - 1) / group).coerceAtLeast(0)
        val posts = defender.standing.filter {
            it.type?.category == BuildingCatalog.Category.MILITARY || it.type?.isDefensive == true
        }.ifEmpty {
            defender.standing.filter { it.type?.category != BuildingCatalog.Category.MILITARY }
        }.ifEmpty { listOfNotNull(defender.townHall) }
        if (posts.isEmpty()) return emptyList()

        val weapon = defender.garrisonWeapon()
        val baseHp = (30 + defender.level * 6).coerceAtLeast(20)
        val stagger = Rng(Rng.seedOf("sortie", defender.name, defender.soldiers))
        return (0 until count).map { i ->
            val post = posts[i % posts.size]
            val strength = (defender.soldiers - i * group).coerceAtMost(group)
            Guard(
                index = i,
                x = post.centreX() + ((i % 3) - 1) * 0.8,
                y = post.centreY() + ((i / 3 % 3) - 1) * 0.8,
                strength = strength,
                hitPoints = baseHp * strength,
                maxHitPoints = baseHp * strength,
                weaponId = weapon.id,
                cooldown = stagger.nextInt((weapon.cooldownTicks + 1).coerceAtLeast(2)),
            )
        }
    }

    /** One garrison soldier's turn: find an attacker, close on them, then shoot. */
    private fun stepGuard(
        g: Guard,
        attackers: List<Fighter>,
        shots: MutableList<Shot>,
        rng: Rng,
        fieldSize: Int,
    ) {
        if (g.cooldown > 0) g.cooldown--
        var target: Fighter? = g.target.takeIf { attackers.getOrNull(it)?.alive == true }
            ?.let { attackers[it] }
        if (target == null) {
            var best = 16.0
            attackers.forEach { f ->
                if (f.alive) {
                    val d = hypot(f.x - g.x, f.y - g.y)
                    if (d <= best) {
                        best = d
                        target = f
                    }
                }
            }
            g.target = target?.index ?: -1
        }
        val chosen = target ?: return

        val dist = hypot(chosen.x - g.x, chosen.y - g.y)
        val reach = effectiveRange(g.weapon)
        if (dist > reach) {
            moveToward(g, chosen.x, chosen.y, speed = 0.13, fieldSize = fieldSize)
            return
        }
        if (g.cooldown > 0) return

        val dealt = (g.weapon.damage * g.strength * (0.9 + rng.nextDouble() * 0.2))
            .toInt().coerceAtLeast(1)
        chosen.hitPoints -= dealt
        shots.add(Shot(g.x, g.y, chosen.x, chosen.y, fromAttacker = false, splash = g.weapon.splash))
        g.cooldown = g.weapon.cooldownTicks
    }

    private fun moveToward(g: Guard, tx: Double, ty: Double, speed: Double, fieldSize: Int) {
        val dx = tx - g.x
        val dy = ty - g.y
        val d = hypot(dx, dy)
        if (d < 1e-6) return
        g.x = (g.x + dx / d * speed).coerceIn(0.0, fieldSize.toDouble())
        g.y = (g.y + dy / d * speed).coerceIn(0.0, fieldSize.toDouble())
    }

    // -- Scoring --------------------------------------------------------------

    /**
     * Who won, and what it was worth.
     *
     * Stars are the familiar three: something destroyed, the town hall down, everything flattened.
     * A raid counts as won at one star or better, because a raid that took half a base and came
     * home is not a defeat and telling a player it was is how a game teaches people not to attack.
     *
     * The XP on both sides is deliberately asymmetric. An attacker risks an army and gains most of
     * the swing; a defender who holds gains less than the attacker would have, because defending is
     * the passive half and paying it equally would make sitting still the optimal strategy.
     */
    private fun score(
        defender: PaperBase,
        startingHp: Map<String, Int>,
        endingHp: Map<String, Int>,
        destroyed: Set<String>,
        fighters: List<Fighter>,
        attackerLevel: Int,
        ticks: Int,
    ): Result {
        val totalHp = startingHp.values.sum().coerceAtLeast(1)
        val lostHp = startingHp.entries.sumOf { (id, was) ->
            val now = if (id in destroyed) 0 else (endingHp[id] ?: was).coerceAtLeast(0)
            (was - now).coerceAtLeast(0)
        }
        val percent = (lostHp * 100 / totalHp).coerceIn(0, 100)
        val townHallDown = defender.townHall?.id?.let { it in destroyed } ?: true

        // The familiar three, on the thresholds the genre settled on. The middle one used to be
        // fifteen percent, which sounds modest and is not: a base spread over a wide plot has
        // outlying farms worth that much between them, so a single soldier with a club could walk
        // to the edge, chew one building for three minutes and be told he had won a raid.
        val stars = when {
            percent >= 100 -> 3
            townHallDown && percent >= 50 -> 2
            townHallDown || percent >= 50 -> 1
            else -> 0
        }
        val won = stars >= 1

        val attackersLost = fighters.filter { !it.alive }.sumOf { it.represented }
        val defenderLevel = defender.level
        val stake = 40L + defenderLevel * 9L

        val xpAttacker = if (won) {
            stake * stars + percent * (2L + defenderLevel / 8)
        } else {
            -(stake / 2 + attackersLost * 3L)
        }
        val xpDefender = if (won) {
            -(stake / 3 + percent * (1L + defenderLevel / 20))
        } else {
            stake / 2 + attackersLost * 4L
        }

        val damage = startingHp.mapValues { (id, was) ->
            if (id in destroyed) was else (was - (endingHp[id] ?: was)).coerceAtLeast(0)
        }.filterValues { it > 0 }

        return Result(
            won = won,
            destructionPercent = percent,
            townHallDown = townHallDown,
            attackersLost = attackersLost.coerceAtMost(fighters.sumOf { it.represented }),
            attackersSent = fighters.sumOf { it.represented },
            ticks = ticks,
            buildingDamage = damage,
            xpForAttacker = xpAttacker,
            xpForDefender = xpDefender,
            stars = stars,
        )
    }

    /**
     * The opening raid: the one that flattens a level-1 town hall before the player has done
     * anything.
     *
     * Hand-built rather than simulated, because there is nothing to simulate — one building, no
     * defences, no army. What matters is that the player arrives at a ruin and understands
     * immediately that somebody did it to them.
     */
    fun openingRaidNarrative(): List<String> = listOf(
        "They came over the ruled line at the top of the page.",
        "Six stick figures with torches. Your town hall was one square of pencil.",
        "It is a smudge now.",
        "You have a builder, a rubber, and the rest of the paper.",
    )

    fun maxSeconds(): Int = MAX_SECONDS

    /** Used by the UI to size its scrubber without knowing the tick rate. */
    fun secondsOf(ticks: Int): Double = ticks.toDouble() / TICKS_PER_SECOND

    internal fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double =
        max(abs(ax - bx), abs(ay - by))
}
