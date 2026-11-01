package com.prism.launcher.minigames

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * A wave-survival mode in the spirit of the mode it is named after, without its maps or toys.
 *
 * ## Why it is generated
 *
 * A shipped level would be a download and a copyright problem. This level is a seed: rooms,
 * corridors and a courtyard, all carved by [FpsMap]. That also means the host and every joiner
 * stand in the same building without a single wall crossing the mesh, and the map changes each
 * run without a level list.
 *
 * ## The team is always four
 *
 * Offline, three AI teammates fill the other slots. On a mesh, a joining player takes one of those
 * slots instead. That is why the canvas always shows four HUD plates, and why a solo player is
 * never truly alone -- the mode is built around revive, and a revive needs somebody to do it.
 *
 * ## The wave is the clock
 *
 * There is no exit and no level timer. The run ends when the team does. Between waves the team gets
 * a short breath, a little health, and its ammo topped up; then the next wave is larger, faster and
 * more varied. A brute is not a new boss with a health bar, it is the same chase code with a bigger
 * body and a longer reach.
 */
class ZombiesGame(
    val runSeed: Long,
    val localName: String = "YOU",
    playerId: Int = 0,
    val aiTeammates: Int = 3,
    val client: Boolean = false,
) : FpsArena(
    FpsMap.generate(
        seed = runSeed,
        width = 54,
        height = 54,
        roomCount = 13,
        roomMin = 5,
        roomMax = 11,
        corridorWidth = 2,
        courtyard = true,
    )
) {
    companion object {
        const val PHASE_PREP = 0
        const val PHASE_ACTIVE = 1
        const val PHASE_CLEARED = 2
        const val PHASE_OVER = 3
        const val MAX_PLAYERS = 4
        const val BLEEDOUT = 28.0
        const val REVIVE_SECONDS = 1.35

        val BOT_NAMES = listOf("ROOK", "MARA", "JINX")
        val BOT_LINES = listOf(
            "Rook: Keep the door behind us.",
            "Mara: Ammo's in the corner. Make it count.",
            "Jinx: I hear them in the walls.",
        )
    }

    private val weapons = FpsWeapons.ZOMBIES
    private val waveRandom = Random(runSeed xor 0x5A4B33L)
    private var fieldTimer = 0.0
    private var field = IntArray(map.width * map.height) { Int.MAX_VALUE }
    private var botSpeechAt = 0.0
    private var growlTimer = 1.5
    private var lastWaveLine = 0

    var wave: Int = 1
        private set
    var phase: Int = if (client) PHASE_PREP else PHASE_ACTIVE
    var waveTimer: Double = if (client) 0.0 else 5.0
    var points: Int = 0
        private set
    var downs: Int = 0
        private set
    var revives: Int = 0
        private set
    var networked: Boolean = false
    private var spawnRemaining = 0
    private var spawnTimer = 0.0
    private var spawnInterval = 1.0
    private var killedThisWave = 0

    init {
        this.localId = playerId
        if (client) {
            // A placeholder until the first snapshot arrives, so the page can draw a loading scene
            // without any special case.
            addPlayer(playerId, localName, isBot = false, x = map.spawnX, y = map.spawnY)
        } else {
            val player = addPlayer(0, localName, isBot = false, x = map.spawnX, y = map.spawnY)
            player.maxHealth = 100f
            player.health = 100f
            player.weapon = 0
            player.magazine = weapons[0].magazine
            player.reserve = 240
            for (i in 0 until aiTeammates.coerceIn(0, 3)) {
                val bot = addPlayer(i + 1, BOT_NAMES[i % BOT_NAMES.size], isBot = true)
                bot.maxHealth = 100f
                bot.health = 100f
                bot.weapon = 1
                bot.magazine = weapons[1].magazine
                bot.reserve = 9999
                bot.x = (map.spawnX + (i - 1) * 0.8).coerceIn(1.0, map.width - 2.0)
                bot.y = (map.spawnY + (i - 1) * 0.8).coerceIn(1.0, map.height - 2.0)
            }
            spawnWeaponCrates()
            spawnHealthPickups()
            startWave(first = true)
        }
    }

    /** The join path used by the host when a remote player takes a bot slot. */
    fun addRemotePlayer(id: Int, name: String) {
        if (id !in 1 until MAX_PLAYERS) return
        val existing = players.firstOrNull { it.id == id }
        if (existing != null && !existing.isBot) return
        if (existing != null) {
            players.remove(existing)
        }
        val remote = addPlayer(id, name, isBot = false)
        remote.maxHealth = 100f
        remote.health = 100f
        remote.weapon = 1
        remote.magazine = weapons[1].magazine
        remote.reserve = 240
        message("$name JOINED", 2.5)
        say("$name is with us. Four plates.", players.firstOrNull { it.isBot }?.name ?: "ROOK", 4.0, voiceKey = 11)
    }

    fun removeRemotePlayer(id: Int) {
        val remote = players.firstOrNull { it.id == id && !it.isBot } ?: return
        players.remove(remote)
        if (players.none { it.id == id }) {
            val bot = addPlayer(id, BOT_NAMES[(id - 1).coerceIn(0, BOT_NAMES.lastIndex)], isBot = true)
            bot.maxHealth = 100f
            bot.health = 100f
            bot.weapon = 1
            bot.magazine = weapons[1].magazine
            bot.reserve = 9999
        }
    }

    private fun spawnWeaponCrates() {
        val cells = map.openCells()
        val choices = listOf(
            Triple(1, "VECTOR", 2.5),
            Triple(2, "OLYMPIA", 3.0),
            Triple(3, "GALIL", 3.5),
        )
        choices.forEach { (weapon, label, minDistance) ->
            var placed = false
            repeat(80) {
                if (placed) return@repeat
                val index = cells[waveRandom.nextInt(cells.size)]
                val x = map.cellX(index) + 0.5
                val y = map.cellY(index) + 0.5
                if (hypot(x - map.spawnX, y - map.spawnY) < minDistance) return@repeat
                if (map.isExit(map.cellX(index), map.cellY(index))) return@repeat
                if (pickups.any { hypot(it.x - x, it.y - y) < 1.4 }) return@repeat
                addPickup(x, y, FpsSprites.WEAPON, weapon = weapon, label = label, respawn = 35.0)
                placed = true
            }
        }
    }

    private fun spawnHealthPickups() {
        val cells = map.openCells()
        repeat(3) { index ->
            var placed = false
            repeat(60) {
                if (placed) return@repeat
                val cell = cells[waveRandom.nextInt(cells.size)]
                val x = map.cellX(cell) + 0.5
                val y = map.cellY(cell) + 0.5
                if (hypot(x - map.spawnX, y - map.spawnY) < 4.0) return@repeat
                if (pickups.any { hypot(it.x - x, it.y - y) < 1.2 }) return@repeat
                addPickup(x, y, FpsSprites.HEALTH, 35f, respawn = 18.0)
                if (index == 0) addPickup(x + 0.4, y, FpsSprites.ARMOR, 45f, respawn = 24.0)
                placed = true
            }
        }
    }

    private fun startWave(first: Boolean) {
        phase = PHASE_ACTIVE
        spawnRemaining = 5 + wave * 3 + (if (networked) 3 else 0)
        spawnInterval = (1.15 - wave * 0.045).coerceIn(0.32, 1.15)
        spawnTimer = 0.3
        killedThisWave = 0
        message(if (first) "WAVE $wave — THEY ARE COMING" else "WAVE $wave", 3.0)
        events.add(FpsEvent(FpsSound.WAVE, human.x, human.y, 0.9f))
        lastWaveLine = (lastWaveLine + 1) % BOT_LINES.size
        if (time >= botSpeechAt) {
            botSpeechAt = time + 5.0
            val speaker = players.firstOrNull { it.isBot }?.name ?: "ROOK"
            say(
                when {
                    wave == 1 -> "Here they come. Stay where I can see you."
                    wave == 5 -> "They're getting faster. Watch the left."
                    wave >= 10 -> "This is the big one. Nobody dies alone."
                    else -> listOf(
                        "That's the last of them. Reload.",
                        "More of them. Falling back to the courtyard.",
                        "I'm low. Somebody cover the hall.",
                    )[(lastWaveLine) % 3]
                },
                speaker,
                4.5,
                voiceKey = 11 + lastWaveLine,
            )
        }
    }

    private fun completeWave() {
        phase = PHASE_CLEARED
        waveTimer = 6.5
        points += 100 + wave * 25
        message("WAVE $wave CLEARED — ${ceil(waveTimer).toInt()}s", 6.0)
        events.add(FpsEvent(FpsSound.EXIT, human.x, human.y, 0.8f))
        players.forEach {
            if (it.health > 0f && !it.downed) {
                it.health = min(it.maxHealth, it.health + 25f)
                it.reserve += 60
            }
            if (it.magazine <= 0) finishReload(it)
        }
        pickups.forEach { if (it.kind == FpsSprites.WEAPON) it.active = true }
        val speaker = players.firstOrNull { it.isBot }?.name ?: "ROOK"
        say("That's the last one. Reload — next wave is bigger.", speaker, 4.5, voiceKey = 21)
    }

    fun update(dt: Float, inputs: Map<Int, FpsInput>) {
        val delta = dt.toDouble().coerceAtMost(0.05)
        time += delta
        tickMessage()
        updateSubtitles(delta)
        if (status != FpsStatus.RUNNING) return
        if (client) {
            // The client is a viewer: its world comes from the host's snapshots. Its own movement
            // is sent to the host, and the host sends back where that movement ended up.
            return
        }

        for (player in players) {
            // A dead or downed player never reaches `movePlayer`, so this is the only place their
            // damage flash and muzzle light get to fade. Leaving it to the movement path painted a
            // permanent red screen over a downed player.
            player.hurt = (player.hurt - (delta * 2.2).toFloat()).coerceAtLeast(0f)
            player.muzzle = (player.muzzle - (delta * 6.0).toFloat()).coerceAtLeast(0f)
            if (player.isBot) continue
            if (player.downed) {
                player.bleedout -= delta
                if (player.bleedout <= 0.0) {
                    player.health = 0f
                    player.downed = false
                    player.bleedout = 0.0
                }
                continue
            }
            if (player.health <= 0f) continue
            val input = inputs[player.id] ?: FpsInput()
            movePlayer(player, input, delta)
            if (input.reload) triggerReload(player, weapons)
            if (input.switchWeapon >= 0) switchWeapon(player, input.switchWeapon, weapons)
            if (input.fire) {
                if (player.magazine <= 0 && player.reloadTimer <= 0.0) triggerReload(player, weapons)
                fire(player, weapons)
            }
            if (input.use && player.health > 0f) tryRevive(player, delta)
        }

        updateBotsZombies(delta)
        updateReviveState(delta)
        updateWave(delta)
        updateEnemies(delta)
        updatePickups(delta)
        tickEnemyVisuals(delta)
        growlTimer -= delta
        if (growlTimer <= 0.0) {
            growlTimer = 2.4 + random.nextDouble() * 2.8
            val near = enemies.filter { it.alive }.minByOrNull { hypot(it.x - human.x, it.y - human.y) }
            if (near != null && hypot(near.x - human.x, near.y - human.y) < 14.0) {
                events.add(FpsEvent(FpsSound.ZOMBIE, near.x, near.y, 0.45f))
            }
        }

        if (players.all { it.health <= 0f }) {
            status = FpsStatus.LOST
            phase = PHASE_OVER
            message("THE HORDE WON", 4.0)
        }
    }

    private fun updateWave(delta: Double) {
        when (phase) {
            PHASE_ACTIVE -> {
                if (spawnRemaining > 0) {
                    spawnTimer -= delta
                    if (spawnTimer <= 0.0) {
                        spawnTimer = spawnInterval
                        if (enemies.count { it.alive } < 24) {
                            spawnZombie()
                            spawnRemaining--
                        }
                    }
                } else if (enemies.none { it.alive }) {
                    completeWave()
                }
            }
            PHASE_CLEARED -> {
                waveTimer -= delta
                if (waveTimer <= 0.0) {
                    wave++
                    startWave(first = false)
                }
            }
        }
    }

    private fun spawnZombie() {
        val spawn = spawnAwayFromPlayers(9.0) ?: return
        val roll = random.nextDouble()
        val kind = when {
            wave >= 8 && roll < 0.08 -> FpsSprites.BRUTE
            wave >= 5 && roll < 0.24 -> FpsSprites.SPITTER
            wave >= 2 && roll < 0.50 -> FpsSprites.ZOMBIE
            else -> if (wave >= 3 && roll < 0.65) FpsSprites.ZOMBIE else FpsSprites.ZOMBIE
        }
        val runner = wave >= 3 && random.nextDouble() < 0.30
        val health = when (kind) {
            FpsSprites.BRUTE -> 380f + wave * 95f
            FpsSprites.SPITTER -> 90f + wave * 30f
            else -> if (runner) 70f + wave * 22f else 95f + wave * 28f
        }
        val speed = when (kind) {
            FpsSprites.BRUTE -> 0.52 + wave * 0.018
            FpsSprites.SPITTER -> 0.56 + wave * 0.02
            else -> if (runner) 1.25 + wave * 0.035 else 0.72 + wave * 0.022
        }
        val damage = (if (kind == FpsSprites.BRUTE) 26f else 12f) + wave * 1.6f
        addEnemy(
            kind = kind,
            x = spawn.first,
            y = spawn.second,
            health = health,
            speed = speed.coerceAtMost(2.0),
            damage = damage,
            ranged = kind == FpsSprites.SPITTER,
            attackInterval = if (kind == FpsSprites.BRUTE) 1.2 else 0.95,
        )
    }

    private fun updateEnemies(delta: Double) {
        fieldTimer -= delta
        if (fieldTimer <= 0.0) {
            fieldTimer = 0.32
            val target = players.filter { it.health > 0f && !it.downed }.minByOrNull { it.x + it.y }
                ?: human
            field = map.distanceField(floor(target.x).toInt(), floor(target.y).toInt())
        }
        for (enemy in enemies) {
            if (!enemy.alive || enemy.dying > 0.0) continue
            val target = nearestLivingPlayer(enemy.x, enemy.y) ?: continue
            chase(enemy, delta, field, target, speedScale = 1.0 + wave * 0.01)
        }
        updateProjectiles(delta)
    }

    private fun updateBotsZombies(delta: Double) {
        for (bot in players) {
            if (!bot.isBot || bot.health <= 0f) continue
            if (bot.downed) {
                bot.bleedout -= delta
                if (bot.bleedout <= 0.0) {
                    bot.health = 0f
                    bot.downed = false
                }
                continue
            }
            bot.cooldown = (bot.cooldown - delta).coerceAtLeast(0.0)
            if (bot.reloadTimer > 0.0) {
                bot.reloadTimer -= delta
                if (bot.reloadTimer <= 0.0) finishReload(bot)
            }
            bot.botThink -= delta
            if (bot.botThink > 0.0) continue
            bot.botThink = 0.18
            val downed = players.firstOrNull {
                it !== bot && it.downed && it.health > 0f && hypot(it.x - bot.x, it.y - bot.y) < 5.5
            }
            val threat = enemies.filter { it.alive }.minByOrNull { hypot(it.x - bot.x, it.y - bot.y) }
            if (downed != null && (threat == null || hypot(threat.x - downed.x, threat.y - downed.y) > 4.0)) {
                val distance = hypot(downed.x - bot.x, downed.y - bot.y)
                if (distance < 1.3) {
                    bot.angle = kotlin.math.atan2(downed.y - bot.y, downed.x - bot.x)
                    tryRevive(bot, delta)
                } else {
                    moveBotToward(bot, downed.x, downed.y, 2.0, delta)
                }
                continue
            }
            if (threat != null && hypot(threat.x - bot.x, threat.y - bot.y) < 14.0 &&
                Fps.visible(map, bot.x, bot.y, threat.x, threat.y)
            ) {
                bot.angle = kotlin.math.atan2(threat.y - bot.y, threat.x - bot.x)
                if (bot.reloadTimer <= 0.0 && bot.magazine <= 0) {
                    bot.reloadTimer = weapons[bot.weapon].reload
                } else if (bot.cooldown <= 0.0) {
                    fire(bot, weapons)
                }
                if (hypot(threat.x - bot.x, threat.y - bot.y) > 4.5) {
                    moveBotToward(bot, threat.x, threat.y, 1.5, delta)
                }
            } else {
                val human = players.firstOrNull { !it.isBot && it.health > 0f && !it.downed }
                if (human != null && hypot(human.x - bot.x, human.y - bot.y) > 2.8) {
                    moveBotToward(bot, human.x, human.y, 1.8, delta)
                }
            }
        }
    }

    private fun moveBotToward(bot: FpsPlayer, tx: Double, ty: Double, speed: Double, delta: Double) {
        val angle = kotlin.math.atan2(ty - bot.y, tx - bot.x)
        bot.angle = angle
        val moved = Fps.move(map, bot.x, bot.y, kotlin.math.cos(angle) * speed * delta, kotlin.math.sin(angle) * speed * delta, 0.28)
        bot.x = moved.first
        bot.y = moved.second
    }

    private fun tryRevive(reviver: FpsPlayer, delta: Double) {
        if (reviver.downed || reviver.health <= 0f) return
        val target = players.firstOrNull {
            it !== reviver && it.downed && it.health > 0f && hypot(it.x - reviver.x, it.y - reviver.y) < 1.45
        } ?: return
        target.reviveProgress += delta
        if (target.reviveProgress >= REVIVE_SECONDS) {
            target.downed = false
            target.health = 45f
            target.bleedout = 0.0
            target.reviveProgress = 0.0
            revives++
            points += 75
            message("${target.name} REVIVED", 2.5)
            events.add(FpsEvent(FpsSound.REVIVE, target.x, target.y, 0.9f))
            if (target.id == 0) {
                say("You're up. Stay behind me.", reviver.name, 4.0, voiceKey = 31 + reviver.id)
            }
        }
    }

    private fun updateReviveState(delta: Double) {
        for (player in players) {
            if (!player.downed) continue
            if (players.none { it !== player && it.health > 0f && !it.downed && hypot(it.x - player.x, it.y - player.y) < 1.45 }) {
                player.reviveProgress = (player.reviveProgress - delta * 0.5).coerceAtLeast(0.0)
            }
            if (player.health <= 0f) player.downed = false
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
        val maxReserve = weapon.reserve * 3
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
        if (player.downed) return
        player.downed = true
        player.bleedout = BLEEDOUT
        player.reviveProgress = 0.0
        downs++
        events.add(FpsEvent(FpsSound.HURT, player.x, player.y, 1.0f))
        if (!player.isBot) {
            message("YOU ARE DOWN — a teammate can revive you", 3.0)
            say("${player.name} is down! Cover me!", players.firstOrNull { it.isBot && it.health > 0f }?.name ?: "ROOK", 4.0, voiceKey = 41)
        } else {
            say("${player.name} is down. I'm moving.", players.firstOrNull { !it.isBot && it.health > 0f }?.name ?: "YOU", 4.0, voiceKey = 42)
        }
        if (players.all { it.downed || it.health <= 0f }) {
            status = FpsStatus.LOST
            phase = PHASE_OVER
        }
    }

    override fun onEnemyKilled(enemy: FpsEnemy, by: FpsPlayer?) {
        killedThisWave++
        points += if (enemy.kind == FpsSprites.BRUTE) 150 else 25 + wave * 2
        // A small drop makes the arena feel less like a spreadsheet and more like a place.
        if (waveRandom.nextDouble() < 0.10) {
            addPickup(enemy.x, enemy.y, FpsSprites.AMMO, 28f, respawn = 0.0)
        } else if (waveRandom.nextDouble() < 0.05) {
            addPickup(enemy.x, enemy.y, FpsSprites.HEALTH, 25f, respawn = 0.0)
        }
    }

    val intensity: Float
        get() {
            if (status != FpsStatus.RUNNING) return 0f
            val player = human
            val nearest = enemies.filter { it.alive }.minOfOrNull { hypot(it.x - player.x, it.y - player.y) } ?: 20.0
            val threat = (1.0 - nearest / 13.0).coerceIn(0.0, 1.0)
            val health = (1.0 - player.health.toDouble() / player.maxHealth).coerceIn(0.0, 1.0)
            return (0.14 + scoreHeat() * 0.5 + threat * 0.25 + health * 0.12).coerceIn(0.0, 1.0).toFloat()
        }

    private fun scoreHeat(): Double = (wave / 12.0).coerceIn(0.0, 0.75)

    fun scene(): FpsScene {
        val player = human
        // A permanently dead local player keeps watching the team rather than staring at their own
        // corpse. The camera changes, the HUD still reports zero health, and the run continues
        // until the last teammate falls.
        val viewer = if (player.health <= 0f && !player.downed) {
            players.firstOrNull { it.id != player.id && it.health > 0f && !it.downed } ?: player
        } else {
            player
        }
        val sprites = ArrayList<FpsSprite>(enemies.size + players.size + pickups.size)
        for (enemy in enemies) {
            if (!enemy.alive && enemy.dying <= 0.0) continue
            sprites.add(
                FpsSprite(
                    x = enemy.x,
                    y = enemy.y,
                    kind = enemy.kind,
                    size = when (enemy.kind) {
                        FpsSprites.BRUTE -> 1.25
                        FpsSprites.SPITTER -> 0.9
                        else -> 0.82
                    },
                    alpha = if (enemy.dying > 0.0) (enemy.dying / 0.6).coerceIn(0.0, 1.0).toFloat() else 1f,
                    health = (enemy.health / enemy.maxHealthValue).coerceIn(0f, 1f),
                )
            )
        }
        for (other in players) {
            if (other === player) continue
            sprites.add(
                FpsSprite(
                    other.x, other.y, FpsSprites.TEAMMATE,
                    size = 0.78,
                    alpha = if (other.health <= 0f) 0.25f else 1f,
                    health = (other.health / other.maxHealth).coerceIn(0f, 1f),
                    label = if (other.downed) "DOWN" else other.name,
                )
            )
        }
        for (pickup in pickups) {
            if (!pickup.active) continue
            sprites.add(FpsSprite(pickup.x, pickup.y, pickup.kind, 0.34, label = pickup.label))
        }
        for (projectile in projectiles) {
            sprites.add(FpsSprite(projectile.x, projectile.y, FpsSprites.PROJECTILE, 0.2))
        }
        val weapon = weapons.getOrNull(player.weapon) ?: weapons[0]
        val alive = enemies.count { it.alive }
        return FpsScene(
            map = map,
            cameraX = viewer.x,
            cameraY = viewer.y,
            cameraAngle = viewer.angle,
            pitch = viewer.pitch,
            sprites = sprites,
            health = player.health,
            armor = player.armor,
            downed = player.downed,
            bleedout = player.bleedout,
            ammoMag = player.magazine,
            ammoReserve = player.reserve,
            weaponName = weapon.name,
            weaponIndex = player.weapon,
            weaponCount = weapons.size,
            wave = wave,
            waveLabel = when (phase) {
                PHASE_PREP -> "WAVE $wave IN ${ceil(waveTimer).toInt()}"
                PHASE_CLEARED -> "WAVE $wave CLEARED"
                PHASE_OVER -> "RUN OVER"
                else -> "WAVE $wave"
            },
            points = points,
            kills = player.kills,
            time = time,
            message = message,
            objective = when (phase) {
                PHASE_PREP -> "NEXT WAVE IN ${ceil(waveTimer).toInt()}"
                PHASE_CLEARED -> "REGROUP — ${ceil(waveTimer).toInt()}s"
                PHASE_OVER -> "THE HORDE WON"
                else -> "$alive INFECTED"
            },
            subtitles = subtitleLines(),
            crosshair = true,
            muzzle = player.muzzle,
            hurt = player.hurt,
            flash = player.muzzle * 0.3f,
            status = when (status) {
                FpsStatus.RUNNING -> 0
                FpsStatus.WON -> 1
                FpsStatus.LOST -> 2
            },
            score = points,
            coOp = networked,
            teammates = players.count { it.id != localId },
        )
    }

    // ── Co-op snapshots ───────────────────────────────────────────────────────

    fun snapshot(): ZombieSnapshot = ZombieSnapshot(
        seed = runSeed,
        wave = wave,
        phase = phase,
        waveTimer = waveTimer,
        points = points,
        kills = players.sumOf { it.kills },
        time = time,
        message = message,
        objective = scene().objective,
        subtitles = subtitleLines(),
        status = when (status) {
            FpsStatus.RUNNING -> 0
            FpsStatus.WON -> 1
            FpsStatus.LOST -> 2
        },
        players = players.map { player ->
            ZombiePlayerState(
                id = player.id, name = player.name, x = player.x, y = player.y, angle = player.angle,
                health = player.health, maxHealth = player.maxHealth, downed = player.downed,
                bleedout = player.bleedout, revive = player.reviveProgress, score = player.score,
                kills = player.kills, weapon = player.weapon, magazine = player.magazine,
                reserve = player.reserve, isBot = player.isBot,
            )
        },
        enemies = enemies.filter { it.alive || it.dying > 0.0 }.map { enemy ->
            ZombieEnemyState(
                x = enemy.x, y = enemy.y, angle = enemy.angle, health = enemy.health,
                maxHealth = enemy.maxHealthValue, kind = enemy.kind, dying = enemy.dying,
            )
        },
        pickups = pickups.filter { it.active }.map { pickup ->
            ZombiePickupState(pickup.x, pickup.y, pickup.kind, pickup.weapon, pickup.label)
        },
    )

    fun applySnapshot(snapshot: ZombieSnapshot) {
        if (snapshot.seed != runSeed) return
        wave = snapshot.wave
        phase = snapshot.phase
        waveTimer = snapshot.waveTimer
        points = snapshot.points
        time = snapshot.time
        message = snapshot.message
        messageUntil = snapshot.time + 2.5
        status = when (snapshot.status) {
            1 -> FpsStatus.WON
            2 -> FpsStatus.LOST
            else -> FpsStatus.RUNNING
        }

        val byId = players.associateBy { it.id }
        players.clear()
        for (state in snapshot.players) {
            val player = FpsPlayer(state.id, state.name, state.x, state.y, state.isBot)
            player.angle = state.angle
            player.health = state.health
            player.maxHealth = state.maxHealth
            player.downed = state.downed
            player.bleedout = state.bleedout
            player.reviveProgress = state.revive
            player.score = state.score
            player.kills = state.kills
            player.weapon = state.weapon
            player.magazine = state.magazine
            player.reserve = state.reserve
            players.add(player)
        }
        enemies.clear()
        for (state in snapshot.enemies) {
            val enemy = addEnemy(
                kind = state.kind, x = state.x, y = state.y, health = state.health,
                speed = 0.8, damage = 0f, ranged = state.kind == FpsSprites.SPITTER,
            ) ?: continue
            enemy.maxHealthValue = state.maxHealth
            enemy.angle = state.angle
            enemy.dying = state.dying
        }
        pickups.clear()
        for (state in snapshot.pickups) {
            addPickup(state.x, state.y, state.kind, weapon = state.weapon, label = state.label)
        }
        subtitles.clear()
        for (line in snapshot.subtitles) {
            val split = line.substringBefore(": ", "")
            val speaker = if (split.isBlank()) "RADIO" else split
            val text = if (line.contains(": ")) line.substringAfter(": ") else line
            subtitles.add(FpsSubtitle(text, speaker, time + 3.5))
        }
    }

    fun localPlayer(): FpsPlayer? = players.firstOrNull { it.id == localId }
}

data class ZombiePlayerState(
    val id: Int,
    val name: String,
    val x: Double,
    val y: Double,
    val angle: Double,
    val health: Float,
    val maxHealth: Float,
    val downed: Boolean,
    val bleedout: Double,
    val revive: Double,
    val score: Int,
    val kills: Int,
    val weapon: Int,
    val magazine: Int,
    val reserve: Int,
    val isBot: Boolean,
)

data class ZombieEnemyState(
    val x: Double,
    val y: Double,
    val angle: Double,
    val health: Float,
    val maxHealth: Float,
    val kind: Int,
    val dying: Double,
)

data class ZombiePickupState(
    val x: Double,
    val y: Double,
    val kind: Int,
    val weapon: Int,
    val label: String,
)

data class ZombieSnapshot(
    val seed: Long,
    val wave: Int,
    val phase: Int,
    val waveTimer: Double,
    val points: Int,
    val kills: Int,
    val time: Double,
    val message: String?,
    val objective: String?,
    val subtitles: List<String>,
    val status: Int,
    val players: List<ZombiePlayerState>,
    val enemies: List<ZombieEnemyState>,
    val pickups: List<ZombiePickupState>,
)
