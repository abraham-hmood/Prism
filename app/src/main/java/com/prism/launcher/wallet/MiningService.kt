package com.prism.launcher.wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.WalletCrypto
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Mines, in the background, until told to stop.
 *
 * ## "Unkillable" is not something Android grants, so here is what this actually does
 *
 * There is no such thing as an unkillable Android service, and any code claiming otherwise is
 * either wrong or describing an exploit that will not survive an OS update. What exists is a set of
 * mechanisms that together make a service very hard to lose, and all of them are used here:
 *
 *   - A FOREGROUND SERVICE with an ongoing notification, which is the only category the system
 *     will not reclaim for memory under normal pressure.
 *   - START_STICKY, so if the system does kill it, Android restarts it and it resumes from the
 *     saved configuration rather than dying quietly.
 *   - [onTaskRemoved] restarts it when the user swipes the app away, which otherwise stops it.
 *   - A PARTIAL WAKE LOCK, so hashing continues with the screen off instead of stalling at the
 *     first doze.
 *   - A prompt for battery-optimisation exemption, without which aggressive OEM power managers
 *     (Xiaomi, Huawei, Samsung and others are all notorious) kill it within minutes regardless of
 *     everything above.
 *
 * What still stops it: the user force-stopping the app, the system running critically out of
 * memory, or an OEM power manager that ignores the exemption. Those are not defeatable from inside
 * an app, and claiming otherwise would just mean the user finds out the hard way.
 *
 * ## The notification is a requirement, not a side effect
 *
 * It shows what is being mined and the share count, which is exactly what the user asked for --
 * and it is also the thing that makes the system leave this process alone.
 */
class MiningService : Service() {

    /** Live state, process-wide, so the wallet page can show it without binding. */
    object State {
        @Volatile var running: Boolean = false
        @Volatile var coin: String = ""
        @Volatile var pool: String = ""
        @Volatile var algorithm: String = ""
        /** "pool" or "solo" -- they report progress in fundamentally different units. */
        @Volatile var mode: String = "pool"
        @Volatile var status: String = "Idle"
        val shares = AtomicLong(0)
        val rejected = AtomicLong(0)
        val hashes = AtomicLong(0)
        @Volatile var hashRate: Double = 0.0
        @Volatile var difficulty: Double = 1.0

        fun reset() {
            shares.set(0); rejected.set(0); hashes.set(0)
            hashRate = 0.0; status = "Idle"
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var workers = mutableListOf<Thread>()
    private var client: StratumClient? = null
    @Volatile private var stopping = false

    /** True while this device is fanning work out to mesh peers. */
    @Volatile private var meshCoordinating = false

    /**
     * The extranonce2 mesh peers work under.
     *
     * DELIBERATELY FAR FROM THE LOCAL WORKERS' RANGE. Local threads count from their thread index
     * upward, so starting the mesh counter at zero too would have peers and local cores hashing
     * identical headers -- the same work done twice, and the same share found twice, with the pool
     * rejecting the duplicate.
     */
    private val meshExtraNonce2 = java.util.concurrent.atomic.AtomicLong(1_000_000L)

    // Retained so a sticky restart (which arrives with a null intent) can resume the same work.
    private var config: Config? = null

    private data class Config(
        val coin: String,
        val host: String,
        val port: Int,
        val address: String,
        val algorithm: String,
        val threads: Int,
    )

    /**
     * Walks a coin's endpoints, moving on when one will not connect.
     *
     * A pool that has closed and an ISP that blocks pools look identical from here -- the socket
     * simply does not open -- and retrying the same host forever is the wrong answer to both. This
     * advances to the next endpoint on each failure and wraps, so a temporary outage is retried
     * later rather than abandoned, while a permanently dead host stops costing more than one
     * attempt per cycle.
     */
    private class PoolRotation(private val pools: List<MineableCoins.Pool>) {
        private var index = 0

        fun current(): MineableCoins.Pool? = pools.getOrNull(index)

        /** Moves to the next endpoint; true when that wraps back to the first. */
        fun advance(): Boolean {
            if (pools.size <= 1) return true
            index = (index + 1) % pools.size
            return index == 0
        }

        fun size(): Int = pools.size

        fun describe(): String = current()?.toString() ?: "no pool"
    }

    /** Endpoints for a coin, primary first, falling back to whatever the config carries. */
    private fun rotationFor(cfg: Config): PoolRotation {
        val known = MineableCoins.all().firstOrNull { it.symbol.equals(cfg.coin, true) }
        val pools = known?.pools().orEmpty().ifEmpty {
            listOf(MineableCoins.Pool(cfg.host, cfg.port))
        }
        return PoolRotation(pools)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMining()
            return START_NOT_STICKY
        }

        val cfg = intent?.let {
            Config(
                coin = it.getStringExtra(EXTRA_COIN) ?: return@let null,
                host = it.getStringExtra(EXTRA_POOL_HOST) ?: return@let null,
                port = it.getIntExtra(EXTRA_POOL_PORT, 0),
                address = it.getStringExtra(EXTRA_ADDRESS) ?: return@let null,
                algorithm = it.getStringExtra(EXTRA_ALGORITHM) ?: MiningAlgorithms.SHA256D,
                threads = it.getIntExtra(EXTRA_THREADS, defaultThreads()),
            )
        } ?: config ?: run {
            // Restarted with nothing to restart. Stand down rather than spin.
            stopSelf()
            return START_NOT_STICKY
        }
        config = cfg

        if (State.running) stopWorkers()
        startForeground(NOTIFICATION_ID, notification("Starting…", cfg))
        beginMining(cfg)

        // STICKY: unlike a one-shot job, mining is meant to continue indefinitely, so a restart
        // with the saved config is exactly the desired behaviour rather than a repeated action.
        return START_STICKY
    }

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    private fun beginMining(cfg: Config) {
        stopping = false
        State.reset()
        State.running = true
        State.coin = cfg.coin
        State.pool = "${cfg.host}:${cfg.port}"
        State.algorithm = cfg.algorithm
        State.status = "Connecting"

        acquireWakeLock()

        when {
            // PrismCoin has no pools and no external node -- it mines against the chain this
            // device carries, over the mesh. Neither of the other two paths applies.
            cfg.coin.equals(com.prism.launcher.wallet.psc.PrismCoinConsensus.SYMBOL, true) ->
                beginPrismCoin(cfg)
            // Monero speaks a different protocol end to end -- login instead of subscribe, a
            // finished blob instead of header pieces, a compact target compared on the hash's tail.
            // It cannot share the Bitcoin Stratum path.
            cfg.algorithm.equals(MiningAlgorithms.RANDOMX, ignoreCase = true) -> beginMonero(cfg)
            PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO -> beginSolo(cfg)
            // Mesh pooling is the pool path with work fanned out to peers. It falls back rather
            // than refusing: a mesh that is down at the moment mining starts is a temporary
            // condition, and silently mining nothing would look like a broken miner.
            PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_MESH -> {
                if (MeshPool.isAvailable()) {
                    meshCoordinating = true
                    // A coordinator has its own full nonce range to hash and must not also be
                    // taking slices from somebody else; it would be competing with itself.
                    MeshPool.setMemberEnabled(false)
                    beginPool(cfg)
                    State.mode = "mesh"
                } else {
                    State.status = MeshPool.unavailableReason() ?: "Mesh unavailable"
                    PrismLogger.logWarning(
                        TAG, "Mesh pooling unavailable (${State.status}); mining pooled instead."
                    )
                    beginPool(cfg)
                }
            }
            else -> beginPool(cfg)
        }

        thread(name = "prism-mining-stats") {
            var last = 0L
            var lastAt = System.currentTimeMillis()
            while (!stopping) {
                Thread.sleep(5_000)
                val now = System.currentTimeMillis()
                val total = State.hashes.get()
                val elapsed = (now - lastAt) / 1000.0
                if (elapsed > 0) State.hashRate = (total - last) / elapsed
                last = total
                lastAt = now
                updateNotification(cfg)
            }
        }
    }

    /**
     * Solo: poll the node for a template, hash it, submit a whole block if one turns up.
     *
     * NO SHARES EXIST IN THIS MODE, which is why the notification says "solo" rather than showing
     * a share counter that would sit at zero forever and read as a malfunction.
     */
    private fun beginSolo(cfg: Config) {
        State.mode = "solo"
        val coin = CoinRegistry.bySymbol(cfg.coin) ?: run {
            State.status = "Unknown coin ${cfg.coin}"
            return
        }

        // Self-hosting redirects the RPC at the node Prism runs, on loopback. The user's URL field
        // is deliberately ignored here rather than merged -- see PrismSettings.getSelfHostNode.
        val selfHosted = PrismSettings.getSelfHostNode()
        val miner = SoloMiner(
            nodeUrl = if (selfHosted) PrismNodeManager.rpcUrl(coin) else PrismSettings.getSoloNodeUrl(),
            credentials = if (selfHosted) PrismNodeManager.credentials()
                          else PrismSettings.getSoloNodeCredentials(),
            coin = coin,
            payoutAddress = cfg.address,
        )
        if (selfHosted) State.mode = "solo-hosted"

        thread(name = "prism-solo") {
            var extraNonce = 0L
            while (!stopping) {
                val template = miner.fetchTemplate()
                if (template == null) {
                    State.status = miner.lastError ?: "No block template"
                    updateNotification(cfg)
                    Thread.sleep(20_000)
                    continue
                }

                State.status = "Mining block ${template.height}"
                State.difficulty = 0.0
                updateNotification(cfg)

                val coinbase = miner.buildCoinbase(template, extraNonce++)
                val root = miner.merkleRoot(coinbase, template.transactionIds)
                val nTime = template.currentTime

                var nonce = 0L
                val deadline = System.currentTimeMillis() + 30_000
                var hashed = 0L
                // Templates go stale as soon as somebody else finds the block, so this refreshes
                // on a timer rather than exhausting the nonce range against work that is gone.
                while (nonce < 0xFFFFFFFFL && !stopping && System.currentTimeMillis() < deadline) {
                    val header = miner.header(template, root, nTime, nonce)
                    val hash = MiningAlgorithms.hash(cfg.algorithm, header)
                    hashed++
                    if (com.prism.launcher.wallet.ShareTarget.hashToInteger(hash) <= template.target) {
                        if (miner.submitBlock(template, coinbase, nTime, nonce, root)) {
                            State.shares.incrementAndGet()
                            State.status = "Block found at height ${template.height}"
                        } else {
                            State.rejected.incrementAndGet()
                            State.status = miner.lastError ?: "Block rejected"
                        }
                        updateNotification(cfg)
                        break
                    }
                    nonce++
                    if (hashed >= 512) {
                        State.hashes.addAndGet(hashed)
                        hashed = 0
                    }
                }
                State.hashes.addAndGet(hashed)
            }
        }
    }

    /**
     * Mines PrismCoin against the local chain and gossips the result.
     *
     * NO POOL AND NO SHARES: every block found is the miner's, and the reward is derived from the
     * difficulty it was mined at, so one PSC always represents one hour of reference work. The
     * share counter is reused as a BLOCK counter and the notification says so.
     */
    private fun beginPrismCoin(cfg: Config) {
        State.mode = "prismcoin"
        val node = com.prism.launcher.wallet.psc.PrismCoinNode
        node.load(applicationContext)

        if (!node.isEligible()) {
            State.status = "Wallet page not on a desktop slot — not a PrismCoin node"
            updateNotification(cfg)
            return
        }

        thread(name = "prism-psc-miner") {
            var lastCounted = node.hashesAttempted.get()
            while (!stopping) {
                State.status = "Mining at height ${node.chain.height() + 1}"
                State.difficulty = com.prism.launcher.wallet.psc.PrismCoinConsensus
                    .difficultyOf(node.chain.targetForNext(node.chain.tip))

                val found = node.mineOnce(cfg.address)

                // The real count, not a guess from elapsed time -- the old estimate reported a
                // hash rate even while the miner was stalled waiting for a template.
                val now = node.hashesAttempted.get()
                State.hashes.addAndGet(now - lastCounted)
                lastCounted = now

                if (found != null && node.submitMined(applicationContext, found)) {
                    State.shares.incrementAndGet()
                    // The difficulty the block was actually mined at, not a placeholder: the yield
                    // estimate multiplies by this, so a constant 1.0 would under-report every
                    // block ever found by the ratio of real difficulty to one.
                    PrismSettings.recordMinedShare(
                        cfg.coin,
                        com.prism.launcher.wallet.psc.PrismCoinConsensus.difficultyOf(found.target),
                    )
                    State.status = "Found block ${found.height}"
                }
                updateNotification(cfg)
            }
        }
    }

    /**
     * Monero, over its own protocol and RandomX.
     *
     * THIS IS THE ONE WORTH RUNNING. RandomX is deliberately CPU-friendly, so a phone is within a
     * few multiples of a desktop core rather than the 10^14 gap Bitcoin presents. Shares here are
     * a realistic expectation rather than a theoretical one.
     */
    private fun beginMonero(cfg: Config) {
        State.mode = "monero"

        if (!RandomXNative.available) {
            State.status = "RandomX did not load on this device"
            updateNotification(cfg)
            return
        }

        thread(name = "prism-monero") {
            val client = MoneroStratumClient(cfg.host, cfg.port, cfg.address)
            moneroClient = client

            client.onAccepted = { accepted, message ->
                if (accepted) {
                    State.shares.incrementAndGet()
                    PrismSettings.recordMinedShare(cfg.coin, State.difficulty)
                } else {
                    State.rejected.incrementAndGet()
                    State.status = message
                }
                updateNotification(cfg)
            }
            client.onJob = { job ->
                State.difficulty = MoneroStratumClient.difficultyOf(job.target)
                State.status = "Mining block ${job.height}"
                // The seed rotates every couple of thousand blocks; every VM must be rebuilt
                // before the next hash or they hash against a freed cache.
                RandomXNative.useSeed(job.seedHash)
                updateNotification(cfg)
                restartMoneroWorkers(cfg, client)
            }

            while (!stopping) {
                if (!client.connect()) {
                    State.status = client.lastError ?: "Pool unreachable, retrying"
                    updateNotification(cfg)
                    Thread.sleep(15_000)
                    continue
                }
                State.status = "Connected"
                updateNotification(cfg)
                client.listen()
                stopWorkers()
                if (!stopping) {
                    State.status = "Reconnecting"
                    updateNotification(cfg)
                    Thread.sleep(5_000)
                }
            }
            client.close()
            RandomXNative.releaseAll()
        }

        // Monero pools drop idle sockets; a share can take a long time on a phone.
        thread(name = "prism-monero-keepalive") {
            while (!stopping) {
                Thread.sleep(60_000)
                if (moneroClient?.isConnected == true) moneroClient?.keepAlive()
            }
        }
    }

    @Volatile
    private var moneroClient: MoneroStratumClient? = null

    private fun restartMoneroWorkers(cfg: Config, client: MoneroStratumClient) {
        stopWorkers()
        for (index in 0 until cfg.threads) {
            workers.add(thread(name = "prism-monero-$index", isDaemon = true) {
                moneroLoop(cfg, client, index)
            })
        }
    }

    /**
     * One core hashing Monero work.
     *
     * Each thread walks its own slice of the nonce space, and the blob is copied ONCE per job --
     * only four bytes change per attempt, so there is nothing to rebuild.
     */
    private fun moneroLoop(cfg: Config, client: MoneroStratumClient, index: Int) {
        while (!stopping && !Thread.currentThread().isInterrupted) {
            val job = client.currentJob ?: run { Thread.sleep(250); return@run null } ?: continue
            val blob = job.blob.copyOf()
            val activeJobId = job.jobId
            var nonce = index
            var hashed = 0L

            while (!stopping) {
                if (client.currentJob?.jobId != activeJobId) break

                MoneroWork.writeNonce(blob, nonce)
                val hash = try {
                    RandomXNative.hash(job.seedHash, blob)
                } catch (e: Throwable) {
                    PrismLogger.logError(TAG, "RandomX hashing failed", e)
                    null
                }
                if (hash == null) {
                    State.status = "RandomX could not hash"
                    Thread.sleep(1_000)
                    break
                }
                hashed++

                if (MoneroStratumClient.meetsTarget(hash, job.target)) {
                    client.submit(activeJobId, nonce, hash)
                    PrismLogger.logSuccess(TAG, "Monero share submitted at nonce $nonce")
                }

                nonce += cfg.threads
                if (hashed >= 32) {
                    State.hashes.addAndGet(hashed)
                    hashed = 0
                }
            }
            State.hashes.addAndGet(hashed)
        }
        // This thread's VM, disposed by this thread -- the only safe place to do it.
        RandomXNative.releaseThreadVm()
    }

    /**
     * Wires share and job callbacks onto whichever client is live.
     *
     * Takes a supplier rather than the client itself: failover replaces the client, and a callback
     * closed over the old one would keep reporting the difficulty of a connection that has already
     * been dropped.
     */
    private fun attachStratumHandlers(cfg: Config, current: () -> StratumClient) {
        val stratum = current()
        stratum.onAccepted = { accepted ->
            if (accepted) {
                State.shares.incrementAndGet()
                // Persisted immediately: this service is designed to be killed and restarted, so a
                // total held only in memory would quietly reset to zero overnight.
                PrismSettings.recordMinedShare(cfg.coin, current().difficulty)
            } else {
                State.rejected.incrementAndGet()
            }
            updateNotification(cfg)
        }
        stratum.onJob = {
            State.difficulty = current().difficulty
            State.status = "Mining"
            if (meshCoordinating) distributeMeshWork(cfg, current())
            updateNotification(cfg)
        }

        // Shares that came back from peers go up the same connection as this device's own, under
        // the same worker name -- which is the entire point of a proxy, and also why MeshPool
        // verifies them first rather than trusting a peer with this device's share rate.
        MeshPool.onVerifiedShare = { jobId, extraNonce2, nTime, nonce ->
            if (current().submit(jobId, extraNonce2, nTime, nonce)) {
                PrismLogger.logSuccess(TAG, "Submitted a mesh peer's share for ${cfg.coin}")
            }
        }
    }

    /**
     * Hands the current job out to every reachable peer.
     *
     * Sends the 76-byte header PREFIX rather than the coinbase and merkle branches. A peer does not
     * need to rebuild the merkle root -- it only needs something to hash -- and shipping the
     * finished prefix keeps the packet small, keeps the branch-folding in one place, and means a
     * peer cannot get the root wrong and burn its slice on headers that could never be submitted.
     */
    private fun distributeMeshWork(cfg: Config, stratum: StratumClient) {
        val job = stratum.currentJob ?: return
        if (stratum.extraNonce1.isEmpty()) return

        val en2 = meshExtraNonce2.getAndIncrement().toString(16)
            .padStart(stratum.extraNonce2Size * 2, '0')
            .takeLast(stratum.extraNonce2Size * 2)

        // The template is prefix + 4-byte nonce and nothing else, so the first 76 bytes of any
        // snapshot are exactly the prefix.
        val prefix = StratumWork.template(job, stratum.extraNonce1, en2).snapshot(0L)
            .copyOf(StratumWork.template(job, stratum.extraNonce1, en2).size - 4)

        runCatching {
            MeshPool.distribute(
                coin = cfg.coin,
                algorithm = cfg.algorithm,
                jobId = job.jobId,
                prefix = prefix,
                extraNonce2 = en2,
                nTime = job.nTime,
                target = StratumWork.targetFor(stratum.difficulty),
            )
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not hand work to mesh peers: ${it.message}")
        }
    }

    private fun beginPool(cfg: Config) {
        State.mode = "pool"
        thread(name = "prism-stratum") {
            val rotation = rotationFor(cfg)
            val first = rotation.current() ?: MineableCoins.Pool(cfg.host, cfg.port)
            var stratum = StratumClient(first.host, first.port, cfg.address)
            client = stratum
            State.pool = first.toString()
            attachStratumHandlers(cfg) { stratum }

            while (!stopping) {
                if (!stratum.connect()) {
                    // Try the next endpoint before waiting. Only once every one of them has failed
                    // is this actually "the pools are unreachable" rather than "that pool is".
                    val exhausted = rotation.advance()
                    val next = rotation.current()
                    if (next != null) {
                        stratum = StratumClient(next.host, next.port, cfg.address)
                        client = stratum
                        attachStratumHandlers(cfg) { stratum }
                        State.pool = next.toString()
                    }
                    State.status = if (exhausted) {
                        "All ${rotation.size()} pools unreachable, retrying"
                    } else {
                        "Pool unreachable, trying ${rotation.describe()}"
                    }
                    updateNotification(cfg)
                    // Backs off only after a full cycle; switching endpoints is cheap and should
                    // not wait fifteen seconds per attempt.
                    if (exhausted) Thread.sleep(15_000)
                    continue
                }
                State.status = "Connected"
                updateNotification(cfg)
                startWorkers(cfg, stratum)
                stratum.listen()          // blocks until the connection drops
                stopWorkers()
                if (!stopping) {
                    State.status = "Reconnecting"
                    updateNotification(cfg)
                    Thread.sleep(5_000)
                }
            }
            stratum.close()
        }
    }

    private fun startWorkers(cfg: Config, stratum: StratumClient) {
        stopWorkers()
        for (index in 0 until cfg.threads) {
            workers.add(
                thread(name = "prism-miner-$index", isDaemon = true) {
                    hashLoop(cfg, stratum, index)
                }
            )
        }
    }

    /**
     * One core's worth of hashing.
     *
     * Each thread walks its own slice of the nonce space (starting at `index` and stepping by the
     * thread count) so two threads never test the same nonce -- otherwise adding cores would
     * duplicate work rather than divide it.
     */
    private fun hashLoop(cfg: Config, stratum: StratumClient, index: Int) {
        var extraNonce2Counter = index.toLong()
        while (!stopping && !Thread.currentThread().isInterrupted) {
            val job = stratum.currentJob
            if (job == null || stratum.extraNonce1.isEmpty()) {
                Thread.sleep(500)
                continue
            }

            val extraNonce2 = extraNonce2Counter.toString(16)
                .padStart(stratum.extraNonce2Size * 2, '0')
                .takeLast(stratum.extraNonce2Size * 2)
            extraNonce2Counter += cfg.threads

            val target = StratumWork.targetFor(stratum.difficulty)
            val activeJobId = job.jobId

            // One template per thread per extranonce. It is mutable and unsynchronised, so it must
            // never be shared between threads -- see HeaderTemplate.
            val template = StratumWork.template(job, stratum.extraNonce1, extraNonce2)

            var nonce = index.toLong()
            var hashed = 0L
            while (nonce < 0xFFFFFFFFL && !stopping) {
                val header = template.withNonce(nonce)
                val hash = MiningAlgorithms.hash(cfg.algorithm, header)
                hashed++

                // A new job means the current one is stale; finishing it would submit work on a
                // block that is already gone. Checked in batches because reading the volatile job
                // reference per hash costs more than the hash.
                if (hashed and 0xFFF == 0L && stratum.currentJob?.jobId != activeJobId) break

                if (com.prism.launcher.wallet.ShareTarget.meetsTarget(hash, target)) {
                    stratum.submit(
                        activeJobId, extraNonce2, job.nTime,
                        WalletCrypto.toHex(
                            byteArrayOf(
                                (nonce ushr 24).toByte(), (nonce ushr 16).toByte(),
                                (nonce ushr 8).toByte(), nonce.toByte()
                            )
                        )
                    )
                    PrismLogger.logSuccess(TAG, "Share found for ${cfg.coin} at nonce $nonce")
                    break
                }

                nonce += cfg.threads
                // Batched so the counter is not a contention point on every single hash.
                if (hashed >= 512) {
                    State.hashes.addAndGet(hashed)
                    hashed = 0
                }
            }
            State.hashes.addAndGet(hashed)
        }
    }

    /** Releases the coordinator role, so a stopped miner stops answering for the mesh. */
    private fun stopMeshCoordination() {
        if (!meshCoordinating) return
        meshCoordinating = false
        MeshPool.onVerifiedShare = null
        MeshPool.clearJobs()
        // Back to being available to somebody else's pool, which is what this device was before
        // it started coordinating.
        MeshPool.setMemberEnabled(
            PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_MESH
        )
    }

    private fun stopWorkers() {
        workers.forEach { runCatching { it.interrupt() } }
        workers.clear()
    }

    private fun stopMining() {
        stopping = true
        stopMeshCoordination()
        State.running = false
        State.status = "Stopped"
        stopWorkers()
        runCatching { client?.close() }
        runCatching { moneroClient?.close() }
        // 256 MB of RandomX cache and every VM under it, released rather than left held.
        runCatching { RandomXNative.releaseAll() }
        // The node outlives nothing: leaving it running would keep syncing hundreds of gigabytes
        // in the background after the user asked mining to stop.
        PrismNodeManager.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Swiping the app away stops a plain service. Mining is meant to be running when the user is
     * not looking at it, so the service restarts itself.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (State.running && !stopping) {
            val restart = Intent(applicationContext, MiningService::class.java)
            config?.let {
                restart.putExtra(EXTRA_COIN, it.coin)
                    .putExtra(EXTRA_POOL_HOST, it.host)
                    .putExtra(EXTRA_POOL_PORT, it.port)
                    .putExtra(EXTRA_ADDRESS, it.address)
                    .putExtra(EXTRA_ALGORITHM, it.algorithm)
                    .putExtra(EXTRA_THREADS, it.threads)
            }
            runCatching { startForegroundService(restart) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        State.running = false
        stopWorkers()
        runCatching { client?.close() }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Prism::Mining").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun updateNotification(cfg: Config) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(State.status, cfg))
        }
    }

    private fun notification(status: String, cfg: Config): android.app.Notification {
        ensureChannel()

        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MiningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val accepted = State.shares.get()
        val rejected = State.rejected.get()
        val solo = State.mode.startsWith("solo")
        val selfHosted = State.mode == "solo-hosted"

        val detail = buildString {
            if (solo) {
                append("Solo — no pool, whole blocks only")
                append("\nNode: ").append(
                    when {
                        selfHosted -> "hosted by Prism" +
                            if (PrismNodeManager.isRunning()) " (running)" else " (not running)"
                        else -> PrismSettings.getSoloNodeUrl().ifBlank { "not configured" }
                    }
                )
            } else {
                append("Pool: ").append(cfg.host).append(':').append(cfg.port)
            }
            append("\nAlgorithm: ").append(cfg.algorithm)
            // Solo has no shares to count, so calling them shares would be a lie that reads as a
            // broken counter.
            append(if (solo) "\nBlocks found: " else "\nShares: ").append(accepted)
            if (rejected > 0) append("  ·  ").append(rejected).append(" rejected")
            append("\nHash rate: ").append(formatRate(State.hashRate))
            append("\nStatus: ").append(status)
        }

        val title = if (solo) {
            "Mining ${cfg.coin} solo — $accepted block${if (accepted == 1L) "" else "s"}"
        } else {
            "Mining ${cfg.coin} — $accepted share${if (accepted == 1L) "" else "s"}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText("${formatRate(State.hashRate)} · $status")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Mining", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Prism is mining"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PrismMining"
        private const val CHANNEL_ID = "prism_mining"
        private const val NOTIFICATION_ID = 9401

        private const val EXTRA_COIN = "coin"
        private const val EXTRA_POOL_HOST = "pool_host"
        private const val EXTRA_POOL_PORT = "pool_port"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_ALGORITHM = "algorithm"
        private const val EXTRA_THREADS = "threads"

        private const val ACTION_STOP = "com.prism.launcher.MINING_STOP"

        fun formatRate(hashesPerSecond: Double): String = when {
            hashesPerSecond >= 1_000_000 -> "%.2f MH/s".format(hashesPerSecond / 1_000_000)
            hashesPerSecond >= 1_000 -> "%.1f kH/s".format(hashesPerSecond / 1_000)
            hashesPerSecond > 0 -> "%.0f H/s".format(hashesPerSecond)
            else -> "starting…"
        }

        fun start(
            context: Context,
            coin: String,
            host: String,
            port: Int,
            address: String,
            algorithm: String,
            threads: Int = 0,
        ) {
            val intent = Intent(context, MiningService::class.java)
                .putExtra(EXTRA_COIN, coin)
                .putExtra(EXTRA_POOL_HOST, host)
                .putExtra(EXTRA_POOL_PORT, port)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_ALGORITHM, algorithm)
            if (threads > 0) intent.putExtra(EXTRA_THREADS, threads)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MiningService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
