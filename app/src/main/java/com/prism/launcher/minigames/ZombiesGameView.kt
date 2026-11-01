package com.prism.launcher.minigames

import android.content.Context

/**
 * Wave-survival Zombies, solo or on the Prism mesh.
 *
 * A host runs the world and publishes snapshots; a joiner only sends controls. That split is what
 * keeps four phones agreeing about who is downed on a UDP mesh. When there is no mesh the same
 * rules run locally with three AI survivors, so the game is never a menu with a dead "multiplayer"
 * button.
 */
class ZombiesGameView(
    context: Context,
    private val role: Int,
    private val playerName: String = "SURVIVOR",
    seed: Long = System.currentTimeMillis(),
    playerId: Int = 0,
    private val onFinished: (won: Boolean, score: Int) -> Unit,
) : FpsGameView(context) {

    companion object {
        const val ROLE_SOLO = 0
        const val ROLE_HOST = 1
        const val ROLE_CLIENT = 2
    }

    private var game = ZombiesGame(
        runSeed = seed,
        localName = playerName,
        playerId = playerId,
        aiTeammates = if (role == ROLE_CLIENT) 0 else 3,
        client = role == ROLE_CLIENT,
    )
    private val remoteIds = LinkedHashSet<Int>()
    private var stopped = false

    init {
        if (role == ROLE_HOST) game.networked = true
    }

    override val sessionLabel: String
        get() = when (role) {
            ROLE_HOST -> "ZOMBIES · MESH HOST"
            ROLE_CLIENT -> "ZOMBIES · MESH"
            else -> "ZOMBIES"
        }

    override fun currentScene(): FpsScene = game.scene()

    override fun advance(dt: Float, input: FpsInput) {
        when (role) {
            ROLE_CLIENT -> {
                pollNetwork()
                ZombieNet.sendInput(input)
                ZombieNet.latestSnapshot()?.let { snapshot ->
                    if (snapshot.seed == game.runSeed) {
                        game.applySnapshot(snapshot)
                    } else {
                        game = ZombiesGame(
                            runSeed = snapshot.seed,
                            localName = playerName,
                            playerId = ZombieNet.localId,
                            aiTeammates = 0,
                            client = true,
                        )
                        game.applySnapshot(snapshot)
                    }
                }
                game.update(dt, emptyMap())
            }
            else -> {
                pollNetwork()
                val inputs = HashMap<Int, FpsInput>(remoteIds.size + 1)
                inputs[0] = input
                for (id in remoteIds) inputs[id] = ZombieNet.inputFor(id)
                game.update(dt, inputs)
                if (role == ROLE_HOST) ZombieNet.publish(game.snapshot())
            }
        }
    }

    private fun pollNetwork() {
        if (role == ROLE_HOST) {
            for ((id, name) in ZombieNet.drainJoins()) {
                remoteIds.add(id)
                game.addRemotePlayer(id, name)
                game.networked = true
            }
            for (id in ZombieNet.drainLeaves()) remoteIds.remove(id)
        } else if (role == ROLE_CLIENT) {
            val welcome = ZombieNet.takeWelcome() ?: return
            if (welcome.seed != game.runSeed || welcome.id != ZombieNet.localId) {
                game = ZombiesGame(
                    runSeed = welcome.seed,
                    localName = playerName,
                    playerId = welcome.id,
                    aiTeammates = 0,
                    client = true,
                )
            }
            game.networked = true
        }
    }

    override fun drainEvents(): List<FpsEvent> = game.takeEvents()

    override fun drainSpeech(): List<FpsSpeech> = game.takeSpeech()

    override fun onDetachedFromWindow() {
        if (role != ROLE_SOLO) ZombieNet.stop()
        super.onDetachedFromWindow()
    }

    override fun onGameFinished(won: Boolean, score: Int) {
        if (stopped) return
        stopped = true
        if (role != ROLE_SOLO) ZombieNet.stop()
        MinigameStore.recordArcade("zombies", score)
        onFinished(won, score)
    }

    override fun onResultTapped() {
        val scene = currentScene()
        if (scene.status != 0) endSession(scene.status == 1, scene.score)
    }

    override fun musicIntensity(scene: FpsScene): Float = game.intensity

    override fun helpText(): String =
        "LEFT STICK  move\nRIGHT STICK look\nFIRE        shoot\nRELOAD      magazine\nSWAP        next weapon\nUSE         revive a downed survivor"

    override fun resultTitle(status: Int): String =
        if (status == 1) "THE HORDE FALLS" else "THE HORDE WON"
}
