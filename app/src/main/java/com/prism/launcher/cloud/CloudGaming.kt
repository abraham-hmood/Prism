package com.prism.launcher.cloud

import android.content.Context
import android.view.Surface
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.PayLaterPolicy
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Playing a Steam game that is running on a PC on the meshnet.
 *
 * ## Why only desktops, and only ones with Steam
 *
 * A phone cannot host a game worth streaming. Prism can run Windows programs on Android through Wine
 * under PRoot, and that is genuinely useful for tools — but it is emulating an x86 userspace on an ARM
 * CPU with no GPU driver behind it, so a game runs at a few frames a second if it runs at all. Offering
 * phone hosting would mean advertising sessions that are unplayable and billing for them.
 *
 * A desktop with Steam is the opposite case: a real GPU, a real x86 CPU, and a library of games already
 * installed and already licensed to the person whose machine it is. So the host list is exactly the
 * machines that can actually do it — [MeshComputeRegistry.Peer.canHostGames], which is a desktop
 * platform tag plus a Steam install.
 *
 * Steam specifically, rather than "any installed game", for a practical reason: Steam has a machine
 * -readable library. Its `libraryfolders.vdf` and `appmanifest_*.acf` files say what is installed and
 * what it is called, on every platform it runs on, without Prism having to guess from directory names.
 *
 * ## What is on which side
 *
 * This file is the CLIENT. It finds hosts, asks one to start a title, and opens the display connection.
 * The host half — enumerating the Steam library, launching a game, and exposing its display — runs in
 * Prism desktop, which does not have it yet. Until a desktop peer appears on the mesh the list is empty
 * and the page says so, which is honest rather than broken.
 *
 * Android's answer to a `game-start` request is therefore a refusal, and that refusal is served rather
 * than the route being removed: the route is how a desktop peer will answer, and a phone that is asked
 * should say what it is rather than time out.
 *
 * ## The sandbox question, deliberately left open
 *
 * A peer asking a PC to launch a program is a peer asking a PC to launch a program. On the host side
 * that needs to be confined — a session should not be able to reach the rest of the machine, the
 * owner's files, or Steam's own credentials. None of that confinement exists yet, and it belongs in the
 * desktop host where the process is actually spawned. It is called out here so the gap is recorded
 * where the feature is described rather than discovered later.
 */
object CloudGaming {

    private const val TAG = "PrismCloudGaming"

    // ── Finding hosts ──────────────────────────────────────────────────────

    /**
     * Every PC on the mesh that could host a game.
     *
     * Filtered from the compute market rather than discovered separately, because a machine that is
     * not selling its compute has not offered anything — and cloud gaming is compute. The two lists
     * are the same list with a different filter on it.
     */
    fun eligiblePeers(): List<MeshComputeRegistry.Peer> =
        runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())
            .filter { it.canHostGames }
            .sortedByDescending { it.score }

    /**
     * Desktops that are on the mesh but cannot host, and why.
     *
     * Shown alongside the usable list because "my PC is right there and it is not in the list" is the
     * first thing anyone will hit, and the answer is almost always that Steam was not found or that the
     * machine is not sharing its compute. Saying so beats an empty list.
     */
    fun ineligibleDesktops(): List<Pair<MeshComputeRegistry.Peer, String>> =
        runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())
            .filter { it.isDesktop && !it.hasSteam }
            .map { it to "no Steam install found on that machine" }

    fun emptyReason(context: Context): String = when {
        !PrismSettings.getCloudEnabled() ->
            "Mesh cloud is off. Turn it on under Other."
        !com.prism.launcher.mesh.PrismMeshService.isOnMesh() ->
            "Not on a Prism meshnet. Cloud gaming runs on a PC you own that is on the same mesh."
        MeshComputeRegistry.all().none { it.isDesktop } ->
            "No PC on this mesh. Cloud gaming needs a desktop or laptop running Prism and sharing " +
                "its compute — phones cannot host a playable game, so they are not listed."
        eligiblePeers().isEmpty() ->
            "A PC is on the mesh but Steam was not found on it. Cloud gaming streams games from a " +
                "Steam library."
        else -> ""
    }

    /** One host and the titles it is offering. */
    data class Host(val peer: MeshComputeRegistry.Peer, val titles: List<String>)

    /**
     * Asks each eligible PC for its Steam library.
     *
     * Blocking, one round trip per host, so it is a button rather than something that runs on every page
     * view. Only eligible peers are asked: probing every phone on the mesh to be told "not a PC" is a
     * request per phone for an answer the gossip already gave.
     */
    fun discover(context: Context): List<Host> {
        if (!PrismSettings.getCloudEnabled()) return emptyList()
        return eligiblePeers().mapNotNull { peer ->
            val offer = CloudWire.offer(peer.peerIp) ?: return@mapNotNull null
            if (!offer.hostsGames) null else Host(peer, offer.games)
        }
    }

    // ── Starting a session ─────────────────────────────────────────────────

    sealed class Launch {
        data class Started(val peerIp: String, val port: Int, val socket: Socket) : Launch()
        data class Failed(val reason: String) : Launch()
    }

    /**
     * Starts [title] on [peer] and opens the display connection.
     *
     * Returns the socket rather than a renderer because the caller owns the `Surface`. A plain [Socket]
     * and not a `PrismSocket`: the display port is a raw RFB endpoint on the mesh, not an HTTP route
     * behind the PRISM_CONNECT handshake, and the tunnel's request framing would corrupt the display
     * protocol's own handshake.
     */
    fun play(
        context: Context,
        peer: MeshComputeRegistry.Peer,
        title: String,
        width: Int,
        height: Int,
    ): Launch {
        if (!peer.canHostGames) {
            return Launch.Failed("${peer.deviceName} is not a PC with Steam.")
        }
        if (!CloudVault.isReady()) return Launch.Failed(CloudVault.unavailableReason())

        val verdict = PayLaterPolicy.check(PayLaterPolicy.Work.CLOUD_GAMING)
        if (verdict is PayLaterPolicy.Verdict.Blocked) return Launch.Failed(verdict.reason)

        val port = CloudWire.startGame(peer.peerIp, title, width, height)
            ?: return Launch.Failed("${peer.deviceName} would not start it.")

        // A game takes time to get as far as putting a window up, and Steam may be launching too.
        // Connecting the instant the host answers gets a refused connection on a port whose display
        // server has not finished binding.
        val deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            val socket = runCatching {
                Socket().apply {
                    connect(InetSocketAddress(peer.peerIp, port), 4_000)
                    // RFB is interactive. Nagle batching small input messages adds a visible delay to
                    // every key press, which on a game is the difference between playable and not.
                    tcpNoDelay = true
                    soTimeout = 0
                }
            }.getOrNull()
            if (socket != null) {
                PayLaterPolicy.ensureMining(PayLaterPolicy.Work.CLOUD_GAMING)
                PrismLogger.logInfo(TAG, "Playing \"$title\" on ${peer.deviceName}:$port")
                return Launch.Started(peer.peerIp, port, socket)
            }
            Thread.sleep(500)
        }
        runCatching { CloudWire.stopGame(peer.peerIp) }
        return Launch.Failed("Started, but its display never answered on port $port.")
    }

    fun stop(peer: MeshComputeRegistry.Peer) {
        runCatching { CloudWire.stopGame(peer.peerIp) }
    }

    /**
     * Hands a live session to the RFB renderer Prism already uses for its own VMs.
     *
     * Here rather than in the activity so the activity does not have to know which class drives a
     * remote display — if the transport is ever replaced with a video codec, this is the one function
     * that changes.
     */
    fun attach(socket: Socket, surface: Surface): Session? = runCatching {
        val renderer = com.prism.launcher.virtualization.VncSurfaceRenderer(socket, surface)
        renderer.start()
        Session(renderer, socket)
    }.getOrNull()

    /**
     * A live session: the renderer, and the input channel back to the host.
     *
     * The constructor is internal because the renderer is. The CLASS is public, because the activity
     * outside this file holds one — what is hidden is the ability to build one from an arbitrary
     * renderer, which is [attach]'s job and nobody else's.
     */
    class Session internal constructor(
        private val renderer: com.prism.launcher.virtualization.VncSurfaceRenderer,
        private val socket: Socket,
    ) : AutoCloseable {

        fun rebind(surface: Surface) = renderer.rebind(surface)

        /**
         * Sends a key, by X11 keysym.
         *
         * Through the renderer rather than the socket directly: RFB is a stream protocol and the
         * renderer already serialises its writes on one thread. A second writer would interleave
         * messages with the frame-update requests and desynchronise the connection — which does not
         * error, it just silently stops the picture updating.
         */
        fun sendKey(keysym: Int, pressed: Boolean) {
            runCatching { renderer.sendKeyEvent(keysym, pressed) }
        }

        /**
         * Sends a pointer position in VIEW coordinates, scaled onto the remote framebuffer here.
         *
         * Scaled in the session rather than by the caller because the session is the only thing that
         * knows the remote size — it changes when the game changes resolution, which a game does on
         * startup. A caller that cached the size once would send coordinates for the menu resolution
         * for the rest of the match.
         */
        fun sendPointer(viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int, buttons: Int) {
            val remoteW = renderer.remoteWidth
            val remoteH = renderer.remoteHeight
            if (remoteW <= 0 || remoteH <= 0 || viewWidth <= 0 || viewHeight <= 0) return
            val x = (viewX / viewWidth * remoteW).toInt()
            val y = (viewY / viewHeight * remoteH).toInt()
            runCatching { renderer.sendPointerEvent(x, y, buttons) }
        }

        val remoteSize: Pair<Int, Int> get() = renderer.remoteWidth to renderer.remoteHeight

        override fun close() {
            runCatching { renderer.stopRenderer() }
            runCatching { socket.close() }
        }
    }

    /** Honest expectations, stated before a session starts rather than discovered during one. */
    fun performanceNote(): String =
        "The picture comes over RFB as rectangles of pixels, so how smooth this is depends on how much " +
            "of the screen changes at once and how good the link is. Strategy games, card games and " +
            "emulators are comfortable; fast shooters are not. Input goes back immediately — it is the " +
            "picture that costs bandwidth, not the controls."

    /** How long to keep trying the display port after the host says it started. */
    private const val CONNECT_WINDOW_MS = 40_000L

    // ── This device as a host: it is not one ───────────────────────────────

    /**
     * Android never hosts.
     *
     * Kept as an explicit refusal rather than a missing route. A desktop peer will answer `game-start`
     * here; a phone that is asked should say what it is, immediately, so the asker can show a reason
     * instead of waiting for a timeout. See the class comment for why a phone is not a viable host.
     */
    fun hostingEnabled(): Boolean = false

    fun hostableTitles(context: Context): List<String> = emptyList()

    fun startHosting(context: Context, title: String, width: Int, height: Int): Int? = null

    fun lastRefusal(): String =
        "This device is a phone. Cloud gaming is hosted by desktops and laptops with Steam."

    fun stopHosting(context: Context) = Unit

    fun hostedStatus(): String =
        "Phones do not host games — a Wine session on an ARM CPU with no GPU is not playable. " +
            "Run Prism on a PC with Steam to host."
}
