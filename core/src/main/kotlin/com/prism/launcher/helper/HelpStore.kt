package com.prism.launcher.helper

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.launcher.helper.HelpMoney.format
import java.io.File

/**
 * The requests this device knows about, on disk.
 *
 * ## One file, owned by this device
 *
 * There is no server and no account: a request is a document this device writes and gossips, and a
 * request from a peer is a document this device remembers. Both live in the same list because both
 * are shown by the same two lists -- "ask for help" and "give help" -- and splitting local from remote
 * would have meant two merges, two sorts and two places for the progress arithmetic to disagree.
 *
 * ## Synchronised, because two writers is the normal case
 *
 * Gossip arrives on the mesh's dispatch thread while the page writes from the UI thread. Concurrent
 * read-modify-write on a JSON file loses whichever update finished second, which for this feature
 * would mean a contribution vanishing from a progress bar -- the one number donors are looking at.
 * Every entry point takes the same lock and re-reads inside it.
 *
 * A peer's copy is never trusted to be newer than ours: [merge] goes through [HelpRequest.merged],
 * which unions contributions by tx id and lets the author's own fields win.
 */
object HelpStore {

    private const val FILE = "help_requests.json"

    private val lock = Any()

    private fun file() = File(PrismPlatform.host.dataDir(), FILE)

    fun all(): List<HelpRequest> = synchronized(lock) { read() }

    /** Requests this device posted. */
    fun mine(): List<HelpRequest> = all().filter { it.authorId == HelpIdentity.id() }

    /** Requests this device has paid into, newest first. */
    fun helped(): List<HelpRequest> =
        all().filter { request -> request.contributions.any { it.donorId == HelpIdentity.id() } }

    /** Everything else: what the Give Help list shows. */
    fun fromOthers(): List<HelpRequest> = all().filter { it.authorId != HelpIdentity.id() }

    fun find(id: String): HelpRequest? = all().firstOrNull { it.id == id }

    /** What this device has given one particular request, for the detail view. */
    fun givenTo(request: HelpRequest): Long =
        request.contributions.filter { it.donorId == HelpIdentity.id() }
            .fold(0L) { sum, item -> sum + item.amountMicros }

    fun post(request: HelpRequest): HelpRequest = synchronized(lock) {
        val list = read().toMutableList()
        val index = list.indexOfFirst { it.id == request.id }
        if (index >= 0) list[index] = list[index].merged(request) else list.add(request)
        write(list)
        list.first { it.id == request.id }
    }

    /**
     * Records a payment and returns the request it landed on.
     *
     * Idempotent on the tx id, so a contribution that was recorded locally and then heard again over
     * the mesh does not double the progress.
     */
    fun addContribution(requestId: String, contribution: HelpContribution): HelpRequest? =
        synchronized(lock) {
            val list = read().toMutableList()
            val index = list.indexOfFirst { it.id == requestId }
            if (index < 0) return null
            val current = list[index]
            val exists = current.contributions.any {
                it.id == contribution.id ||
                    (contribution.txId.isNotBlank() && it.txId == contribution.txId)
            }
            if (!exists) {
                list[index] = current.copy(contributions = current.contributions + contribution)
                write(list)
            }
            list[index]
        }

    /**
     * Folds peer announcements in.
     *
     * @return how many requests were new or changed, so the page can tell whether to redraw.
     */
    fun merge(remote: List<HelpRequest>): Int = synchronized(lock) {
        if (remote.isEmpty()) return 0
        val list = read().toMutableList()
        var changed = 0
        for (incoming in remote) {
            val index = list.indexOfFirst { it.id == incoming.id }
            if (index < 0) {
                list.add(incoming)
                changed++
            } else {
                val before = list[index]
                val after = before.merged(incoming)
                if (after != before) {
                    list[index] = after
                    changed++
                }
            }
        }
        if (changed > 0) write(list)
        changed
    }

    /** Withdraws a request this device posted. Peers simply stop hearing it. */
    fun remove(id: String): Boolean = synchronized(lock) {
        val list = read().toMutableList()
        val removed = list.removeAll { it.id == id && it.authorId == HelpIdentity.id() }
        if (removed) write(list)
        removed
    }

    // ── Disk ───────────────────────────────────────────────────────────────

    private fun read(): List<HelpRequest> = runCatching {
        val source = file()
        if (!source.isFile) return emptyList()
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { HelpRequest.fromJson(it) }
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    private fun write(list: List<HelpRequest>) {
        runCatching {
            val array = JSONArray()
            // Only requests that still matter to this device are kept: its own, the ones it paid
            // into, and the ones still open. A stranger's completed bill is dead weight in a file that
            // is read on every render.
            for (request in list.sortedByDescending { it.createdAt }) {
                val keep = request.authorId == HelpIdentity.id() ||
                    !request.funded ||
                    request.contributions.any { it.donorId == HelpIdentity.id() }
                if (keep) array.put(request.toJson())
            }
            val target = file()
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(array.toString())
            if (!temporary.renameTo(target)) {
                target.writeText(array.toString())
                temporary.delete()
            }
        }.onFailure {
            PrismPlatform.log.warn("PrismHelper", "Could not save help requests: ${it.message}")
        }
    }

    /** One line for the page's header, e.g. "3 open · $86.40 still needed". */
    fun summary(requests: List<HelpRequest>): String {
        val open = requests.filter { !it.funded }
        if (open.isEmpty()) return "No open requests"
        val needed = open.fold(0L) { sum, item -> sum + item.remainingMicros }
        return "${open.size} open · ${format(needed)} still needed"
    }
}
