package com.prism.launcher.minigames

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * DOOM, as a launcher cabinet.
 *
 * ## A tribute, not a port
 *
 * This has no WAD loader and no id assets: it is a ray-cast level with imps, grunts, a shotgun and
 * an exit, built from the same primitives the rest of the arcade uses. A real DOOM engine needs a
 * megabyte of level data, a palette and the original game files; what makes "a launcher running
 * DOOM" funny is that the launcher is doing it at all, not that it is bit-exact.
 *
 * ## Three floors, one session
 *
 * A run is three generated floors. The view creates a new [DoomGame] for each floor with the same
 * run seed plus the floor number, so the building changes but the run's score keeps going. The map
 * is deliberately more open than the arcade cabinets: a corridor shooter with one-cell hallways is
 * a shooting gallery, not a level.
 */
class DoomGame(
    val runSeed: Long,
    val level: Int = 1,
    val difficulty: Int = 1,
) : FpsArena(
    FpsMap.generate(
        seed = runSeed + level * 104729L,
        width = 40 + level * 4,
        height = 40 + level * 4,
        roomCount = 9 + level * 2,
        roomMin = 4,
        roomMax = 9,
        courtyard = level >= 2,
    )
) {
    private val weapons = FpsWeapons.DOOM
    private val levelRandom = Random(runSeed xor (level * 7919L))
    private var fieldTimer = 0.0
    private var field: IntArray = IntArray(map.width * map.height) { Int.MAX_VALUE }
    private var clearedAnnounced = false

    init {
        val player = addPlayer(0, "DOOMGUY", isBot = false)
        player.maxHealth = 100f
        player.health = 100f
        player.weapon = 0
        player.magazine = weapons[0].magazine
        player.reserve = weapons[0].reserve

        val count = 6 + level * 4 + difficulty * 2
        repeat(count) { index ->
            val spawn = spawnAwayFromPlayers(8.0) ?: return@repeat
            val imp = index % 4 == 0
            addEnemy(
                kind = if (imp) FpsSprites.IMP else FpsSprites.GRUNT,
                x = spawn.first,
                y = spawn.second,
                health = (if (imp) 58f else 42f) + level * 12f,
                speed = (if (imp) 0.72 else 1.05) + level * 0.04,
                damage = (if (imp) 6f else 10f) + difficulty * 1.5f,
                ranged = imp,
                attackInterval = if (imp) 1.35 else 0.85,
            )
        }

        // A shotgun within sight of the start makes the first thirty seconds teach the good weapon.
        val cells = map.openCells()
        fun place(kind: Int, amount: Float, weapon: Int, label: String, minFromSpawn: Double) {
            repeat(80) {
                val index = cells[levelRandom.nextInt(cells.size)]
                val x = map.cellX(index) + 0.5
                val y = map.cellY(index) + 0.5
                if (hypot(x - map.spawnX, y - map.spawnY) < minFromSpawn) return@repeat
                if (map.isExit(map.cellX(index), map.cellY(index))) return@repeat
                if (pickups.any { hypot(it.x - x, it.y - y) < 1.2 }) return@repeat
                addPickup(x, y, kind, amount, weapon, label)
                return
            }
        }
        place(FpsSprites.WEAPON, 0f, 1, "SHOTGUN", 3.0)
        place(FpsSprites.WEAPON, 0f, 2, "CHAINGUN", 15.0)
        place(FpsSprites.HEALTH, 30f, -1, "", 6.0)
        place(FpsSprites.HEALTH, 30f, -1, "", 6.0)
        place(FpsSprites.ARMOR, 35f, -1, "", 8.0)
        place(FpsSprites.AMMO, 32f, -1, "", 4.0)
        place(FpsSprites.AMMO, 32f, -1, "", 4.0)
        message("FLOOR $level — ${enemies.size} HOSTILES", 3.0)
        events.add(FpsEvent(FpsSound.WAVE, player.x, player.y, 0.7f))
    }

    fun update(dt: Float, input: FpsInput) {
        if (status != FpsStatus.RUNNING) {
            time += dt
            tickMessage()
            return
        }
        val delta = dt.toDouble().coerceAtMost(0.05)
        time += delta
        tickMessage()

        val player = human
        movePlayer(player, input, delta)
        if (input.reload) triggerReload(player, weapons)
        if (input.switchWeapon >= 0) switchWeapon(player, input.switchWeapon, weapons)
        if (input.fire) {
            if (player.magazine <= 0 && player.reloadTimer <= 0.0) triggerReload(player, weapons)
            fire(player, weapons)
        }

        fieldTimer -= delta
        if (fieldTimer <= 0.0) {
            fieldTimer = 0.35
            val target = nearestLivingPlayer(player.x, player.y) ?: player
            field = map.distanceField(floor(target.x).toInt(), floor(target.y).toInt())
        }

        for (enemy in enemies) {
            if (!enemy.alive) continue
            if (enemy.dying > 0.0) continue
            val target = nearestLivingPlayer(enemy.x, enemy.y) ?: continue
            chase(enemy, delta, field, target, speedScale = 1.0 + level * 0.04)
        }
        updateProjectiles(delta)
        updatePickups(delta)
        updateSubtitles(delta)
        tickEnemyVisuals(delta)

        val alive = enemies.count { it.alive }
        if (alive == 0 && !clearedAnnounced) {
            clearedAnnounced = true
            message("FLOOR CLEAR — FIND THE EXIT", 4.0)
            events.add(FpsEvent(FpsSound.EXIT, map.exitX, map.exitY, 0.9f))
            pickups.forEach { it.active = true }
        }
        if (alive == 0 && hypot(player.x - map.exitX, player.y - map.exitY) < 1.2) {
            status = FpsStatus.WON
            score += 500 + level * 250
            message("FLOOR $level CLEARED", 4.0)
            events.add(FpsEvent(FpsSound.EXIT, player.x, player.y, 1f))
        }
        if (player.health <= 0f) {
            status = FpsStatus.LOST
            player.downed = false
            message("YOU DIED — the floor keeps its secrets", 4.0)
        }
        if (input.use) {
            // USE is the same as touching the exit, for players who expect an interact key.
            if (alive == 0 && hypot(player.x - map.exitX, player.y - map.exitY) < 2.0) {
                status = FpsStatus.WON
                score += 500 + level * 250
            }
        }
    }

    override fun finishReload(player: FpsPlayer) {
        val weapon = weapons.getOrNull(player.weapon) ?: return
        val needed = (weapon.magazine - player.magazine).coerceAtLeast(0)
        val take = min(needed, player.reserve)
        player.magazine += take
        player.reserve -= take
        player.reloadTimer = 0.0
    }

    override fun onAmmoPickup(player: FpsPlayer, pickup: FpsPickup): Boolean {
        val weapon = weapons.getOrNull(player.weapon) ?: return false
        val maxReserve = weapon.reserve * 2
        if (player.reserve >= maxReserve) return false
        player.reserve = min(maxReserve, player.reserve + pickup.amount.toInt())
        return true
    }

    override fun onWeaponPickup(player: FpsPlayer, pickup: FpsPickup): Boolean {
        val index = pickup.weapon
        if (index !in weapons.indices) return false
        if (index == player.weapon && player.magazine >= weapons[index].magazine) return false
        player.weapon = index
        player.magazine = weapons[index].magazine
        player.reserve = weapons[index].reserve
        player.reloadTimer = 0.0
        message("${weapons[index].name} ACQUIRED", 2.0)
        events.add(FpsEvent(FpsSound.PICKUP, player.x, player.y, 1f))
        return true
    }

    override fun onPlayerDown(player: FpsPlayer, source: String) {
        // DOOM is one life. A downed state would need a teammate, and there is nobody else here.
        player.health = 0f
        player.downed = false
    }

    val intensity: Float
        get() {
            if (status != FpsStatus.RUNNING) return 0f
            val player = human
            val nearest = enemies.filter { it.alive }.minOfOrNull { hypot(it.x - player.x, it.y - player.y) } ?: 20.0
            val threat = (1.0 - (nearest / 12.0)).coerceIn(0.0, 1.0)
            val health = (1.0 - player.health.toDouble() / player.maxHealth).coerceIn(0.0, 1.0)
            return (0.18 + threat * 0.55 + health * 0.27).coerceIn(0.0, 1.0).toFloat()
        }

    fun scene(): FpsScene {
        val player = human
        val sprites = ArrayList<FpsSprite>(enemies.size + pickups.size + 1)
        for (enemy in enemies) {
            if (!enemy.alive && enemy.state == 3) continue
            sprites.add(
                FpsSprite(
                    x = enemy.x,
                    y = enemy.y,
                    kind = if (enemy.kind == FpsSprites.IMP) FpsSprites.IMP else FpsSprites.GRUNT,
                    size = if (enemy.kind == FpsSprites.IMP) 0.92 else 0.78,
                    alpha = if (enemy.dying > 0.0) (enemy.dying / 0.6).coerceIn(0.0, 1.0).toFloat() else 1f,
                    health = (enemy.health / enemy.maxHealthValue).coerceIn(0f, 1f),
                )
            )
        }
        for (pickup in pickups) {
            if (!pickup.active) continue
            sprites.add(FpsSprite(pickup.x, pickup.y, pickup.kind, 0.38, label = pickup.label))
        }
        if (enemies.none { it.alive }) {
            sprites.add(FpsSprite(map.exitX, map.exitY, FpsSprites.EXIT, 0.75))
        }
        for (projectile in projectiles) {
            sprites.add(FpsSprite(projectile.x, projectile.y, FpsSprites.PROJECTILE, 0.18))
        }
        val weapon = weapons.getOrNull(player.weapon) ?: weapons[0]
        return FpsScene(
            map = map,
            cameraX = player.x,
            cameraY = player.y,
            cameraAngle = player.angle,
            pitch = player.pitch,
            sprites = sprites,
            health = player.health,
            armor = player.armor,
            downed = false,
            bleedout = 0.0,
            ammoMag = player.magazine,
            ammoReserve = player.reserve,
            weaponName = weapon.name,
            weaponIndex = player.weapon,
            weaponCount = weapons.size,
            wave = level,
            waveLabel = "FLOOR $level",
            points = score,
            kills = player.kills,
            time = time,
            message = message,
            objective = if (enemies.any { it.alive }) {
                "${enemies.count { it.alive }} HOSTILES"
            } else {
                "FIND THE EXIT"
            },
            subtitles = subtitleLines(),
            crosshair = true,
            muzzle = player.muzzle,
            hurt = player.hurt,
            flash = player.muzzle * 0.35f,
            status = when (status) {
                FpsStatus.RUNNING -> 0
                FpsStatus.WON -> 1
                FpsStatus.LOST -> 2
            },
            score = score,
        )
    }
}
