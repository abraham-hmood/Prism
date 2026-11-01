package com.prism.launcher.minigames

import android.content.Context

/**
 * DOOM on the launcher.
 *
 * Three floors, generated from one seed, then a summary. The joke is the point — a launcher with a
 * first-person shooter inside it — but the run is real: hitscan weapons, line-of-sight-seeking
 * imps, a shotgun behind the first corner, and an exit that only opens when the floor is clear.
 *
 * The floor transition deliberately does NOT end the session. [EchoSessionLock] stays held across
 * floors 1–3; otherwise the Minigames page would unlock itself between every level and the player
 * could walk away mid-run.
 */
class DoomGameView(
    context: Context,
    private val runSeed: Long = System.currentTimeMillis(),
    private var floor: Int = 1,
    private val difficulty: Int = 1,
    private val onFinished: (won: Boolean, score: Int) -> Unit,
) : FpsGameView(context) {

    private var game = DoomGame(runSeed, floor, difficulty)

    override val sessionLabel: String
        get() = "DOOM · FLOOR $floor"

    override fun currentScene(): FpsScene = game.scene()

    override fun advance(dt: Float, input: FpsInput) {
        game.update(dt, input)
    }

    override fun drainEvents(): List<FpsEvent> = game.takeEvents()

    override fun drainSpeech(): List<FpsSpeech> = game.takeSpeech()

    override fun onGameFinished(won: Boolean, score: Int) {
        MinigameStore.recordArcade("doom", score)
        onFinished(won, score)
    }

    override fun onResultTapped() {
        val scene = currentScene()
        when (scene.status) {
            1 -> {
                if (floor < 3) {
                    floor++
                    game = DoomGame(runSeed, floor, difficulty)
                    continueSession()
                } else {
                    endSession(true, scene.score)
                }
            }
            2 -> endSession(false, scene.score)
        }
    }

    override fun musicIntensity(scene: FpsScene): Float = game.intensity

    override fun helpText(): String =
        "LEFT STICK  move\nRIGHT STICK look\nFIRE        shoot\nRELOAD      magazine\nSWAP        next weapon\nUSE         exit, when it is clear"

    override fun resultTitle(status: Int): String = when {
        status == 1 && floor < 3 -> "FLOOR $floor CLEARED"
        status == 1 -> "MISSION COMPLETE"
        else -> "YOU DIED"
    }
}
