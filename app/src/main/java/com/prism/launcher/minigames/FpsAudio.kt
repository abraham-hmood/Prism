package com.prism.launcher.minigames

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * DOOM and Zombies' effects.
 *
 * The WAVs are generated once into the cache and loaded by [SoundPool]. A custom Zombies pack can
 * replace any of them by dropping a same-named file into `sounds/`; the generated pack is the
 * fallback. The pool is small on purpose: a horde of growls is a wall of noise, not a mix.
 */
class FpsAudio(private val context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<MinigameSfx.Sound, Int>()
    private var released = false

    init {
        val directory = File(context.cacheDir, "prism-fps-sfx")
        val generated = MinigameSfx.writeDefaults(directory)
        val custom = customSounds()
        for ((sound, file) in generated) {
            val chosen = custom[sound] ?: file
            val id = runCatching { pool.load(chosen.absolutePath, 1) }.getOrDefault(0)
            if (id != 0) ids[sound] = id
        }
    }

    private fun customSounds(): Map<MinigameSfx.Sound, File> {
        val enabled = runCatching { PrismSettings.getZombiesUseCustomAssets() }.getOrDefault(false)
        val path = runCatching { PrismSettings.getZombiesAssetPath() }.getOrDefault("")
        if (!enabled || path.isBlank()) return emptyMap()
        val directory = File(File(path), "sounds")
        if (!directory.isDirectory) return emptyMap()
        return MinigameSfx.Sound.entries.mapNotNull { sound ->
            val file = File(directory, sound.file)
            if (file.isFile) sound to file else null
        }.toMap()
    }

    fun play(event: FpsEvent, listenerX: Double, listenerY: Double) {
        if (released) return
        val sound = when (event.sound) {
            FpsSound.SHOT_PISTOL -> MinigameSfx.Sound.SHOT
            FpsSound.SHOT_SHOTGUN -> MinigameSfx.Sound.SHOTGUN
            FpsSound.SHOT_SMG -> MinigameSfx.Sound.SHOT
            FpsSound.SHOT_RIFLE -> MinigameSfx.Sound.SHOTGUN
            FpsSound.HIT -> MinigameSfx.Sound.STEP
            FpsSound.ZOMBIE, FpsSound.GROAN -> MinigameSfx.Sound.ZOMBIE
            FpsSound.STEP -> MinigameSfx.Sound.STEP
            FpsSound.RELOAD -> MinigameSfx.Sound.RELOAD
            FpsSound.HURT -> MinigameSfx.Sound.HURT
            FpsSound.WAVE -> MinigameSfx.Sound.WAVE
            FpsSound.REVIVE -> MinigameSfx.Sound.REVIVE
            FpsSound.EMPTY -> MinigameSfx.Sound.EMPTY
            FpsSound.PICKUP, FpsSound.BUY -> MinigameSfx.Sound.PICKUP
            FpsSound.EXIT -> MinigameSfx.Sound.REVIVE
            FpsSound.SCREAM -> MinigameSfx.Sound.SCREAM
            FpsSound.SWITCH -> MinigameSfx.Sound.SWITCH
        }
        val id = ids[sound] ?: return
        val distance = kotlin.math.hypot(event.x - listenerX, event.y - listenerY)
        val falloff = (1.0 / (1.0 + distance * 0.16)).toFloat().coerceIn(0.08f, 1f)
        val volume = (event.volume * falloff).coerceIn(0f, 1f)
        if (volume <= 0.02f) return
        var rate = (0.94f + (((event.x + event.y).toFloat() % 0.12f) + 0.12f) % 0.12f).coerceIn(0.9f, 1.1f)
        // A sprint step is the same transient at a higher pitch; a custom step.wav still applies.
        if (event.running) rate *= 1.34f
        runCatching { pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { pool.release() }
    }
}
