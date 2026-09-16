package com.prism.launcher

import android.content.Context
import com.prism.core.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks whether models being sold on the mesh are simply public downloads.
 *
 * ## What it is actually looking for
 *
 * "Is this on Hugging Face" is not the question, because nearly every open model is -- that is
 * where models live, and asking it that way would take down almost every honest listing. The
 * question is whether THIS listing is a repackaging of something anybody can already fetch for
 * nothing. So a listing is flagged when its name matches a public repository closely enough that
 * a buyer paying for it is being sold something free.
 *
 * ## Its accuracy, stated plainly
 *
 * Name matching catches the obvious cases and misses the rest. A seller who renames a model, or
 * requantises it so no file hash lines up, will pass. A seller who legitimately publishes their own
 * work to Hugging Face and also sells it here will be flagged wrongly. Hash comparison would be
 * stronger for exact copies and is not implemented -- the registry does not carry file digests yet.
 * This is a deterrent, not a proof, and it should not be described to users as one.
 *
 * ## Why it drives payment rather than refunds
 *
 * A cleared scan is what releases a held purchase; see [ModelPurchaseLedger] for why payment waits
 * rather than being reversed.
 */
object ModelListingScanner {

    private const val PREFS = "prism_model_scanner"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_VERIFY_ON_PURCHASE = "verify_on_purchase"
    private const val KEY_VERIFY_ON_SALE = "verify_on_sale"

    /** The default cadence, and the floor the user cannot go below. */
    const val DEFAULT_INTERVAL_HOURS = 2
    const val MINIMUM_INTERVAL_HOURS = 1

    /**
     * "Check this listing now" -- a buyer asking a seller to run its own scan immediately.
     *
     * Carries the model name and the listing id. It is a REQUEST, not an instruction: the seller
     * runs its own check against the real Hugging Face and GitHub and decides for itself. A peer
     * that could be told to delist by an incoming packet would hand every seller's shopfront to
     * whoever felt like sending one.
     */
    const val OPCODE_VERIFY_NOW: Byte = 0x0F

    /**
     * Seller to buyer: I checked, this listing is mine to sell, settle whenever you like.
     *
     * A PROMPT, NOT AN AUTHORISATION. The buyer runs its own lookups before paying and would reach
     * the same verdict on its own at the next sweep; this only removes the wait. Treating a
     * seller's word as proof would let the one party with a motive to lie wave its own listing
     * through, which is the exact thing the scanner exists to prevent.
     */
    const val OPCODE_SALE_APPROVED: Byte = 0x15

    private val http = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalHours(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
            .coerceAtLeast(MINIMUM_INTERVAL_HOURS)

    fun setIntervalHours(context: Context, hours: Int) {
        prefs(context).edit()
            .putInt(KEY_INTERVAL_HOURS, hours.coerceAtLeast(MINIMUM_INTERVAL_HOURS))
            .apply()
    }

    /**
     * Whether a purchase should be checked the moment it is made, rather than at the next sweep.
     *
     * STORED, NOT PASSED PER PURCHASE. It is a standing preference about how this person likes to
     * buy -- somebody who wants the wait gone wants it gone every time -- so the checkout reads it
     * back on the next model instead of defaulting to off again.
     */
    fun verifyOnPurchase(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERIFY_ON_PURCHASE, false)

    fun setVerifyOnPurchase(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERIFY_ON_PURCHASE, enabled).apply()
    }

    /**
     * Seller side: check a listing the instant somebody buys it, rather than on the interval.
     *
     * When this is on the interval is not merely unused, it is meaningless -- every sale is checked
     * as it happens, so a periodic sweep has nothing left to find. The settings screen keeps the
     * interval visible but disabled for that reason, so the cadence you would fall back to is still
     * legible instead of vanishing.
     */
    fun verifyOnSale(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VERIFY_ON_SALE, false)

    fun setVerifyOnSale(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERIFY_ON_SALE, enabled).apply()
    }

    /** What an immediate check concluded. */
    sealed class Immediate {
        /** The listing is not a repackaged public download; payment has been sent. */
        data object Settled : Immediate()

        /** It matches a public repository. The purchase is cancelled and nothing was sent. */
        data object Cancelled : Immediate()

        /** Neither host answered, so nothing was decided. The purchase stays held. */
        data class Undecided(val reason: String) : Immediate()
    }

    /**
     * Checks one purchase immediately instead of waiting for the cadence.
     *
     * Blocking; call it off the main thread.
     *
     * SCOPED TO ONE PURCHASE, deliberately. [runOnce] sweeps every held purchase and stamps the
     * last-run clock, which would silently push the next scheduled sweep hours into the future --
     * so a buyer who checked out twice in a row would have delayed the check on everything else
     * they were waiting on. This decides one purchase and leaves the schedule alone.
     */
    fun verifyPurchaseNow(
        context: Context,
        purchase: ModelPurchaseLedger.Purchase,
    ): Immediate {
        val public = isPubliclyAvailable(purchase.modelName)
            ?: return Immediate.Undecided(
                "Neither Hugging Face nor GitHub answered, so this purchase stays held and will " +
                    "be checked again on the next sweep. No coins have been sent."
            )
        if (public) {
            ModelPurchaseLedger.update(
                context, purchase.listingId, ModelPurchaseLedger.State.CANCELLED
            )
            PrismLogger.logWarning(
                "ModelShop",
                "Immediate check: '${purchase.modelName}' matches a public repository; " +
                    "purchase cancelled and nothing was sent."
            )
            return Immediate.Cancelled
        }
        return if (broadcastHeldPayment(context, purchase)) {
            ModelPurchaseLedger.update(context, purchase.listingId, ModelPurchaseLedger.State.PAID)
            Immediate.Settled
        } else {
            Immediate.Undecided(
                "The listing cleared its check, but the payment could not be broadcast. It stays " +
                    "held and will settle on the next sweep."
            )
        }
    }

    /**
     * Asks the seller to run its own check on this listing now.
     *
     * Fire-and-forget: the buyer's own decision never depends on the answer, because a seller
     * self-reporting "mine is fine" would be worth nothing. What this buys is that a listing the
     * seller SHOULD have taken down gets taken down at purchase time rather than up to an interval
     * later, so the next buyer does not repeat the mistake.
     */
    fun requestSellerCheck(listing: com.prism.launcher.mesh.P2pModelListings.Listing) {
        runCatching {
            com.prism.launcher.mesh.PrismMeshService.sendToPeer(
                listing.peerIp,
                OPCODE_VERIFY_NOW,
                JSONObject().apply {
                    put("id", listing.id)
                    put("name", listing.name)
                }.toString(),
            )
        }.onFailure {
            PrismLogger.logWarning(
                "ModelShop", "Could not ask ${listing.peerIp} to re-check its listing: ${it.message}"
            )
        }
    }

    /**
     * Seller side: a buyer has asked this device to check one of its own listings now.
     *
     * Blocking; the mesh service calls it off its own thread.
     *
     * ONLY EVER ACTS ON ITS OWN LISTINGS, and only ever by delisting. The incoming packet supplies
     * an id to look up and nothing else that is trusted -- the name checked is the one this device
     * stored, not the one the packet claims -- so the worst a malicious request can do is make this
     * device perform a lookup it was going to perform anyway.
     */
    fun onVerifyRequest(context: Context, payload: String, peerIp: String? = null) {
        val id = runCatching { JSONObject(payload).optString("id") }.getOrNull().orEmpty()
        if (id.isBlank()) return
        val mine = ModelListingStore.all(context).firstOrNull { it.id == id } ?: return

        val buyerIp = peerIp
        val public = isPubliclyAvailable(mine.name) ?: return   // undecided: leave it alone

        if (!public) {
            // Clean. Tell the buyer so their coins move now instead of at their next sweep --
            // but only if this seller asked for sales to be settled that way.
            if (verifyOnSale(context) && buyerIp != null) {
                runCatching {
                    com.prism.launcher.mesh.PrismMeshService.sendToPeer(
                        buyerIp, OPCODE_SALE_APPROVED,
                        JSONObject().apply { put("listing", id) }.toString(),
                    )
                }
                PrismLogger.logSuccess(
                    "ModelShop",
                    "'${mine.name}' checked clean on sale; asked $buyerIp to settle now."
                )
            }
            return
        }

        ModelListingStore.remove(context, id)
        ModelListingStore.announce(context)
        PrismLogger.logWarning(
            "ModelShop",
            "A buyer asked for an immediate check: '${mine.name}' matches a public repository, " +
                "so the listing has been withdrawn."
        )
        notifySellerWithdrawn(context, mine.name)
    }

    /**
     * Buyer side: the seller says its listing checked out.
     *
     * RE-CHECKED HERE ANYWAY. The message decides only WHEN this device looks, never what it
     * concludes: [verifyPurchaseNow] runs this device's own lookups and settles or cancels on its
     * own verdict. Otherwise a seller could approve its own repackaged model by sending one packet.
     *
     * Blocking; the mesh service calls it off its own thread.
     */
    fun onSaleApproved(context: Context, payload: String) {
        val id = runCatching { JSONObject(payload).optString("listing") }.getOrNull().orEmpty()
        if (id.isBlank()) return
        val purchase = ModelPurchaseLedger.held(context).firstOrNull { it.listingId == id } ?: return

        when (val outcome = verifyPurchaseNow(context, purchase)) {
            is Immediate.Settled -> PrismLogger.logSuccess(
                "ModelShop",
                "Seller asked for immediate settlement of '${purchase.modelName}' and this " +
                    "device's own check agreed; payment sent."
            )
            is Immediate.Cancelled -> PrismLogger.logWarning(
                "ModelShop",
                "Seller asked for immediate settlement of '${purchase.modelName}', but this " +
                    "device found it publicly available. Purchase cancelled; nothing was sent."
            )
            is Immediate.Undecided -> PrismLogger.logInfo(
                "ModelShop", "Immediate settlement deferred: ${outcome.reason}"
            )
        }
    }

    /**
     * Tells the seller their listing was pulled, and why.
     *
     * A withdrawal the seller never hears about is the worst version of this: their model quietly
     * stops selling and the store gives no reason. Prism only lets people sell their own work, so
     * the message says which model and what it matched.
     */
    private fun notifySellerWithdrawn(context: Context, modelName: String) {
        runCatching {
            val channelId = "prism_model_listings"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "Model listings",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Shown when one of your listings is withdrawn" }
                )
            }
            val text = "'$modelName' was found on GitHub or Hugging Face, so it has been removed " +
                "from the store. Prism only allows selling models you made yourself — anything " +
                "already downloadable for free cannot be sold here."
            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Listing withdrawn: $modelName")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(modelName.hashCode(), notification)
        }.onFailure {
            PrismLogger.logWarning("ModelShop", "Could not notify the seller: ${it.message}")
        }
    }

    fun dueNow(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_RUN, 0L)
        val gap = intervalHours(context) * 60L * 60L * 1000L
        return System.currentTimeMillis() - last >= gap
    }

    /**
     * Runs one pass over every held purchase. Blocking; call it off the main thread.
     *
     * @return the number of purchases settled, and the number cancelled.
     */
    fun runOnce(context: Context): Pair<Int, Int> {
        prefs(context).edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()

        var settled = 0
        var cancelled = 0
        for (purchase in ModelPurchaseLedger.held(context)) {
            val public = isPubliclyAvailable(purchase.modelName)
            if (public == null) {
                // Neither host answered. Leaving it held is the conservative choice: paying on a
                // failed lookup would settle a purchase nothing has actually vouched for.
                continue
            }
            if (public) {
                ModelPurchaseLedger.update(
                    context, purchase.listingId, ModelPurchaseLedger.State.CANCELLED
                )
                cancelled++
                PrismLogger.logWarning(
                    "ModelShop",
                    "Listing '${purchase.modelName}' matches a public repository; " +
                        "purchase cancelled and nothing was sent."
                )
            } else {
                val sent = broadcastHeldPayment(context, purchase)
                if (sent) {
                    ModelPurchaseLedger.update(
                        context, purchase.listingId, ModelPurchaseLedger.State.PAID
                    )
                    settled++
                }
            }
        }
        return settled to cancelled
    }

    /**
     * Whether a model of this name is downloadable for free from Hugging Face or GitHub.
     *
     * Returns null when neither could be reached, which the caller treats as "do not decide yet"
     * rather than as a clean result.
     */
    fun isPubliclyAvailable(modelName: String): Boolean? {
        val needle = modelName.trim()
        if (needle.isEmpty()) return false

        val hf = search(
            "https://huggingface.co/api/models?search=" +
                java.net.URLEncoder.encode(needle, "UTF-8") + "&limit=5",
            "modelId"
        )
        val gh = search(
            "https://api.github.com/search/repositories?q=" +
                java.net.URLEncoder.encode(needle, "UTF-8") + "&per_page=5",
            "full_name"
        )
        if (hf == null && gh == null) return null
        return matches(needle, hf.orEmpty()) || matches(needle, gh.orEmpty())
    }

    private fun matches(needle: String, candidates: List<String>): Boolean {
        val n = normalise(needle)
        if (n.length < 4) return false
        return candidates.any { candidate ->
            val c = normalise(candidate.substringAfterLast('/'))
            c == n || (c.length >= 4 && (c.contains(n) || n.contains(c)))
        }
    }

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun search(url: String, field: String): List<String>? = runCatching {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Prism")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val out = ArrayList<String>()
            if (body.trimStart().startsWith("[")) {
                val array = com.prism.core.json.JSONArray(body)
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString(field)?.takeIf { it.isNotBlank() }
                        ?.let { out.add(it) }
                }
            } else {
                val items = JSONObject(body).optJSONArray("items") ?: return out
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.optString(field)?.takeIf { it.isNotBlank() }
                        ?.let { out.add(it) }
                }
            }
            out
        }
    }.getOrNull()

    /**
     * Pays the seller now that the listing has cleared.
     *
     * The transaction is built here rather than at checkout because PrismCoin nonces must be
     * sequential and a held payment never advances them -- see [ModelPayments].
     */
    private fun broadcastHeldPayment(
        context: Context,
        purchase: ModelPurchaseLedger.Purchase
    ): Boolean = runCatching { ModelPayments.settle(context, purchase) }.getOrDefault(false)
}
