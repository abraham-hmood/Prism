package com.prism.launcher.appstore

import com.prism.core.MeshMembership
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.social.LykeStore
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import java.io.File
import java.math.BigInteger
import java.util.UUID

/**
 * The publisher side of the App Store: the one-time fee, the account, and the apps a developer has
 * published.
 *
 * ## The fee
 *
 * Publishing costs 25 PrismCoin once. On a device that is serving the meshnet the fee is kept by
 * that device. On a client it is sent to the serving device's announced PrismCoin payout address.
 * The address is not guessed: it comes from the same capability announcement the compute market
 * already carries, and if the host has not announced one the unlock refuses rather than inventing a
 * destination. There is no escrow here and no ability to reverse a broadcast transaction, which is
 * why the confirmation dialog states the destination before signing.
 *
 * ## The fingerprint
 *
 * The first upload records [AppFingerprint.Identity]. Every later upload must match it, or it is
 * rejected before a file is copied. For an APK that means the same signing certificate as the
 * installed Prism build; for an EXE or AppImage it means the same file hash as the first upload.
 * This is the part that stops a different person from publishing an update under an existing app's
 * name.
 */
object PublisherStore {

    private const val PREFS = "prism_app_store"
    private const val KEY_PAID = "publisher_pass_paid"
    private const val KEY_PAID_AT = "publisher_pass_paid_at"
    private const val KEY_PAID_TO = "publisher_pass_paid_to"
    private const val KEY_ACCOUNT = "publisher_account"
    private const val KEY_APPS = "published_apps_v1"

    const val PRICE_PSC = 25
    private val PRICE_MINOR: BigInteger =
        BigInteger.valueOf(PRICE_PSC.toLong()).multiply(BigInteger.TEN.pow(8))
    private val FEE: BigInteger = BigInteger.ZERO

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    enum class Format(val label: String, val extensions: List<String>) {
        APK("Android APK", listOf("apk")),
        EXE("Windows executable", listOf("exe", "msi")),
        APPIMAGE("Linux AppImage", listOf("appimage")),
        BINARY("Linux binary / object", listOf("o", "bin", "run")),
        ARCHIVE("Package archive", listOf("deb", "rpm", "zip")),
    }

    data class PublishedApp(
        val id: String,
        val name: String,
        val version: String,
        val format: Format,
        val platform: String,
        val packageName: String,
        val fingerprint: AppFingerprint.Identity,
        val filePath: String,
        val iconPath: String,
        val publisherName: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun file(): File = File(filePath)
        fun icon(): File? = iconPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("format", format.name)
            .put("platform", platform)
            .put("packageName", packageName)
            .put("fingerprint", fingerprint.value)
            .put("fingerprintKind", fingerprint.kind.name)
            .put("signerSubject", fingerprint.signerSubject)
            .put("filePath", filePath)
            .put("iconPath", iconPath)
            .put("publisherName", publisherName)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)

        companion object {
            fun fromJson(value: JSONObject): PublishedApp? {
                val id = value.optString("id")
                val name = value.optString("name")
                if (id.isBlank() || name.isBlank()) return null
                val format = runCatching {
                    Format.valueOf(value.optString("format", Format.APK.name))
                }.getOrDefault(Format.APK)
                val kind = runCatching {
                    AppFingerprint.Identity.Kind.valueOf(
                        value.optString("fingerprintKind", AppFingerprint.Identity.Kind.FILE_HASH.name)
                    )
                }.getOrDefault(AppFingerprint.Identity.Kind.FILE_HASH)
                return PublishedApp(
                    id = id,
                    name = name,
                    version = value.optString("version", "1.0"),
                    format = format,
                    platform = value.optString("platform", "android"),
                    packageName = value.optString("packageName"),
                    fingerprint = AppFingerprint.Identity(
                        value.optString("fingerprint"),
                        kind,
                        value.optString("signerSubject"),
                    ),
                    filePath = value.optString("filePath"),
                    iconPath = value.optString("iconPath"),
                    publisherName = value.optString("publisherName", "publisher"),
                    createdAt = value.optLong("createdAt"),
                    updatedAt = value.optLong("updatedAt"),
                )
            }
        }
    }

    data class Result(val ok: Boolean, val message: String, val app: PublishedApp? = null)

    // ── The pass ───────────────────────────────────────────────────────────

    fun isPaid(): Boolean = prefs().getBoolean(KEY_PAID, false)

    /**
     * Whether this device may publish, with or without a pass.
     *
     * THE DEVICE SERVING THE MESH PAYS NOTHING. It is the one the fee is paid TO on a client, so
     * charging it as well would mean the host buying its own coins back and the 25 PSC going
     * nowhere. A connected client still needs the one-time pass; a server never does, even after
     * its mesh role changes, because [isPaid] stays true if it ever did pay.
     */
    fun hasPublisherAccess(): Boolean = isServing() || isPaid()

    fun paidAt(): Long = prefs().getLong(KEY_PAID_AT, 0L)

    fun paidTo(): String = prefs().getString(KEY_PAID_TO, "").orEmpty()

    /** The Lyke account name is the publisher identity; there is no second account system. */
    fun accountName(): String {
        val stored = prefs().getString(KEY_ACCOUNT, "").orEmpty()
        if (stored.isNotBlank()) return stored
        val name = runCatching { LykeStore.userName() }.getOrDefault("prism-publisher")
        prefs().edit().putString(KEY_ACCOUNT, name).apply()
        return name
    }

    fun renameAccount(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        if (!runCatching { LykeStore.userName() }.getOrDefault("").equals(trimmed, ignoreCase = true)) {
            // The App Store name follows Lyke. Keep the two in step rather than creating a third
            // identity somebody has to remember.
            runCatching { LykeStore.confirmProfile(trimmed, PrismSettings.getLykeAvatar()) }
        }
        prefs().edit().putString(KEY_ACCOUNT, trimmed).apply()
        return true
    }

    /**
     * Whether publishing can be offered at all.
     *
     * The App Store itself is mesh-gated, so this is the second gate: a wallet must exist before a
     * fee can be charged, and the user must be able to see the destination.
     */
    fun publisherBlocker(): String? {
        if (!MeshMembership.isOnMeshnet()) {
            return "Publishing needs the Prism meshnet. Serve one, or connect to one."
        }
        // The server publishes for free and needs no wallet at all.
        if (hasPublisherAccess()) return null
        val address = runCatching {
            CoinRegistry.bySymbol("PSC")?.let { WalletVault.addressFor(it) }
        }.getOrNull()
        if (address.isNullOrBlank()) {
            return "A PrismCoin wallet is needed for the 25 PSC publishing fee."
        }
        if (!isServing()) {
            val host = hostPayoutAddress()
            if (host.isNullOrBlank()) {
                return "The serving device has not announced a PrismCoin payout address yet. " +
                    "Ask its owner to open the Wallet page on it, then try again."
            }
        }
        return null
    }

    /** True when this device is the node serving the meshnet. */
    fun isServing(): Boolean =
        PrismSettings.getMeshEnabled() && PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER

    /**
     * Charges the one-time fee if it has not been charged, then records the pass.
     *
     * The [confirm] callback receives the destination and amount so the caller can show it before
     * anything is signed. Returning false cancels. This is deliberately a callback rather than a
     * boolean flag: money leaving a wallet should be confirmed with the actual address on screen.
     */
    fun unlock(confirm: (destination: String, amount: String) -> Boolean): Result {
        if (hasPublisherAccess()) {
            return Result(
                true,
                if (isServing()) "This device serves the mesh, so it can publish without a fee."
                else "Publisher access is already unlocked.",
            )
        }
        if (!MeshMembership.isOnMeshnet()) {
            return Result(false, "Publishing needs the Prism meshnet.")
        }

        val server = isServing()
        if (server) {
            // Unreachable while hasPublisherAccess covers isServing(), kept so a future change to
            // that rule cannot quietly start charging the host.
            return Result(true, "This device serves the mesh, so it can publish without a fee.")
        }
        val destination = hostPayoutAddress() ?: return Result(
            false,
            "The serving device has not announced a PrismCoin payout address. Open the Wallet " +
                "page on the server, then try again.",
        )
        if (!confirm(destination, "$PRICE_PSC PSC")) return Result(false, "Payment cancelled.")

        if (!server) {
            val coin = CoinRegistry.bySymbol("PSC")
                ?: return Result(false, "PrismCoin is not registered on this device.")
            val seed = WalletVault.seed()
                ?: return Result(false, "The wallet is locked. Unlock it on the Wallet page first.")
            val account = runCatching { WalletVault.account(coin, seed) }.getOrNull()
                ?: return Result(false, "The PrismCoin account could not be derived.")
            PrismCoinNode.load()
            val chain = PrismCoinNode.chain
            if (chain.balanceOf(account.address) < PRICE_MINOR.add(FEE)) {
                return Result(false, "Not enough PrismCoin. The fee is $PRICE_PSC PSC.")
            }
            val tx = PscTransaction(
                from = account.address,
                to = destination,
                amount = PRICE_MINOR,
                fee = FEE,
                nonce = chain.nonceOf(account.address),
                publicKey = WalletCrypto.compressedPublicKey(account.privateKey),
                note = "Prism App Store publisher pass",
            ).sign(account.privateKey)
            when (val submitted = chain.submit(tx)) {
                is PscChain.TxResult.Rejected ->
                    return Result(false, "The payment was rejected: ${submitted.reason}")
                else -> Unit
            }
            PrismCoinNode.broadcastTransaction(tx)
            PrismCoinNode.save()
        }

        prefs().edit()
            .putBoolean(KEY_PAID, true)
            .putLong(KEY_PAID_AT, System.currentTimeMillis())
            .putString(KEY_PAID_TO, destination)
            .apply()
        return Result(true, "Publisher access unlocked. The account name is ${accountName()}.")
    }

    /** Where the 25 PSC would go, for the confirmation the UI must show before signing. */
    fun feeDestination(): String? {
        if (isServing()) return "this device (mesh server)"
        return hostPayoutAddress()
    }

    private fun hostPayoutAddress(): String? {
        val own = runCatching {
            CoinRegistry.bySymbol("PSC")?.let { WalletVault.addressFor(it) }
        }.getOrNull().orEmpty()
        val bootstrap = PrismSettings.getMeshBootstrapAddress()
        val peers = runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())
            .filter { it.payoutAddress.isNotBlank() && it.payoutAddress != own }
        return peers.firstOrNull { it.peerIp == bootstrap }?.payoutAddress
            ?: peers.singleOrNull()?.payoutAddress
    }

    // ── Published apps ─────────────────────────────────────────────────────

    fun publishedApps(): List<PublishedApp> {
        val raw = prefs().getString(KEY_APPS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let(PublishedApp::fromJson)
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
    }

    fun find(id: String): PublishedApp? = publishedApps().firstOrNull { it.id == id }

    private fun write(apps: List<PublishedApp>) {
        val array = JSONArray()
        apps.forEach { array.put(it.toJson()) }
        prefs().edit().putString(KEY_APPS, array.toString()).apply()
    }

    fun formatOf(file: File): Format? {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return Format.entries.firstOrNull { extension in it.extensions }
    }

    /**
     * Publishes a new app, or updates an existing one when [existingId] is set.
     *
     * [signerSha256] is the APK signing certificate supplied by the phone's PackageManager. It is
     * required for an APK update; without it the update cannot be pinned and is refused.
     */
    fun publish(
        name: String,
        version: String,
        packageFile: File,
        iconFile: File?,
        existingId: String? = null,
        signerSha256: String = "",
        packageName: String = "",
    ): Result {
        if (!hasPublisherAccess()) {
            return Result(false, "Unlock the publisher first; the pass costs $PRICE_PSC PSC.")
        }
        val cleanedName = name.trim()
        if (cleanedName.isBlank()) return Result(false, "Give the app a name.")
        if (cleanedName.length > 80) return Result(false, "The name is longer than 80 characters.")
        if (!packageFile.isFile) return Result(false, "The package file could not be read.")
        if (packageFile.length() > 1024L * 1024 * 1024) return Result(false, "The package is larger than 1 GB.")
        val format = formatOf(packageFile)
            ?: return Result(false, "Use an APK, EXE, MSI, AppImage, .o/.bin or a .deb/.rpm/.zip package.")

        val identity = runCatching { AppFingerprint.of(packageFile, signerSha256) }.getOrElse {
            return Result(false, "The fingerprint could not be read: ${it.message}")
        }
        if (format == Format.APK && signerSha256.isBlank() &&
            identity.kind != AppFingerprint.Identity.Kind.CERTIFICATE
        ) {
            return Result(
                false,
                "This APK has no readable signing certificate. Android must supply its signer before it can be published.",
            )
        }

        val all = publishedApps().toMutableList()
        val existingIndex = existingId?.let { id -> all.indexOfFirst { it.id == id } } ?: -1
        val existing = all.getOrNull(existingIndex)
        if (existing != null && !canUpdateIdentity(existing, identity)) {
            return Result(
                false,
                "The uploaded package does not have ${existing.name}'s fingerprint. Only the original " +
                    "publisher can update this app.",
            )
        }
        if (existing == null && existingId != null) return Result(false, "That app is no longer in your list.")

        val root = File(PrismPlatform.host.dataDir(), "appstore/published")
        if (!root.isDirectory && !root.mkdirs()) return Result(false, "The publisher folder could not be created.")
        val id = existing?.id ?: ("app-" + UUID.randomUUID().toString().take(12))
        val appFolder = File(root, id).apply { mkdirs() }
        val extension = packageFile.name.substringAfterLast('.', "bin").lowercase()
        val destination = File(appFolder, "package-$id.$extension")
        val iconDestination = if (iconFile != null && iconFile.isFile) {
            val iconExtension = iconFile.name.substringAfterLast('.', "png").lowercase()
            File(appFolder, "icon-$id.$iconExtension").also { target ->
                runCatching { iconFile.copyTo(target, overwrite = true) }
            }
        } else {
            existing?.iconPath?.takeIf { it.isNotBlank() }?.let(::File)
        }

        runCatching {
            if (existing != null && existing.filePath != destination.absolutePath) {
                File(existing.filePath).takeIf { it.isFile }?.delete()
            }
            packageFile.copyTo(destination, overwrite = true)
        }.onFailure {
            return Result(false, "The package could not be stored: ${it.message}")
        }

        val now = System.currentTimeMillis()
        val record = PublishedApp(
            id = id,
            name = cleanedName,
            version = version.trim().ifBlank { existing?.version ?: "1.0" },
            format = format,
            platform = when (format) {
                Format.APK -> "android"
                Format.EXE -> "windows"
                else -> "linux"
            },
            packageName = packageName.trim().ifBlank { existing?.packageName.orEmpty() },
            fingerprint = identity,
            filePath = destination.absolutePath,
            iconPath = iconDestination?.absolutePath.orEmpty(),
            publisherName = accountName(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existingIndex >= 0) all[existingIndex] = record else all.add(record)
        write(all)
        return Result(
            true,
            if (existing == null) "Published $cleanedName." else "Updated $cleanedName to ${record.version}.",
            record,
        )
    }

    /**
     * Whether [candidate] may replace [existing]'s package.
     *
     * An APK is pinned to its signing certificate and nothing weaker is accepted. A Windows or
     * Linux binary has no certificate Prism can read in this build, so its first upload records
     * the file hash for display and the listing's publisher account carries continuity: the same
     * account may publish a new build, and the listing shows the new hash. That is a weaker claim
     * than the Android pin and the UI labels it as one instead of pretending a file hash stays
     * constant across versions -- which would have made every desktop update impossible.
     */
    fun canUpdateIdentity(existing: PublishedApp, candidate: AppFingerprint.Identity): Boolean {
        if (AppFingerprint.matches(existing.fingerprint, candidate)) return true
        return existing.fingerprint.kind == AppFingerprint.Identity.Kind.FILE_HASH &&
            candidate.kind == AppFingerprint.Identity.Kind.FILE_HASH &&
            existing.publisherName.equals(accountName(), ignoreCase = true)
    }

    /** Changes the listing's name, version or icon without replacing the package. */
    fun updateMetadata(
        existingId: String,
        name: String,
        version: String,
        iconFile: File? = null,
    ): Result {
        if (!hasPublisherAccess()) return Result(false, "Unlock the publisher first.")
        val cleanedName = name.trim()
        if (cleanedName.isBlank()) return Result(false, "Give the app a name.")
        if (cleanedName.length > 80) return Result(false, "The name is longer than 80 characters.")
        val all = publishedApps().toMutableList()
        val index = all.indexOfFirst { it.id == existingId }
        if (index < 0) return Result(false, "That app is no longer in your list.")
        val existing = all[index]
        val appFolder = File(existing.filePath).parentFile
            ?: return Result(false, "The app folder is missing.")
        val iconDestination = if (iconFile != null && iconFile.isFile) {
            val extension = iconFile.name.substringAfterLast('.', "png").lowercase()
            File(appFolder, "icon-$existingId.$extension").also { target ->
                runCatching { iconFile.copyTo(target, overwrite = true) }
            }
        } else {
            existing.iconPath.takeIf { it.isNotBlank() }?.let(::File)
        }
        val updated = existing.copy(
            name = cleanedName,
            version = version.trim().ifBlank { existing.version },
            iconPath = iconDestination?.absolutePath.orEmpty(),
            publisherName = accountName(),
            updatedAt = System.currentTimeMillis(),
        )
        all[index] = updated
        write(all)
        return Result(true, "Listing updated.", updated)
    }

    fun remove(id: String): Boolean {
        val all = publishedApps().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val app = all.removeAt(index)
        runCatching { File(app.filePath).delete() }
        runCatching { app.icon()?.delete() }
        runCatching { File(app.filePath).parentFile?.delete() }
        write(all)
        return true
    }

    /** Clears the pass. Exists for tests and for a publisher who resets their device. */
    fun forgetPass() {
        prefs().edit().remove(KEY_PAID).remove(KEY_PAID_AT).remove(KEY_PAID_TO).apply()
    }
}
