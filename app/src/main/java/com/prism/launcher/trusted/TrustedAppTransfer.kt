package com.prism.launcher.trusted

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.P2pModelRegistry
import com.prism.launcher.virtualization.VirtualizedAppVault
import com.prism.launcher.vpn.PrismSocket
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * Moving an Android app between trusted devices, for virtualization.
 *
 * ## Why the APK does not travel on the trust transport
 *
 * A trust share is one UDP datagram with a 16 KB ceiling. An APK is tens to hundreds of megabytes. So the
 * datagram carries the ANNOUNCEMENT — package, label, version, size — and the bytes come over the TCP
 * PRISM_CONNECT channel, fetched by whichever device decided it wanted them. That is the same split the
 * model shop already uses between [P2pModelRegistry] and `P2pModelTransfer`, and reusing it means reusing
 * a tunnel that is known to work rather than inventing chunking and reassembly over datagrams.
 *
 * ## Why there are two routes rather than one
 *
 * Because a modern app is not one file. A Play app has a base APK and then per-density, per-ABI and
 * per-language splits, and a device that received only the base would have something that installs and
 * then crashes looking for resources. So `/trusted-app/<pkg>` lists what the app consists of and
 * `/trusted-app/<pkg>/<file>` serves one of them. A single-file transfer would have had to either refuse
 * every split app or deliver a broken one.
 *
 * ## Why nothing is installed automatically
 *
 * An APK that arrives over the network and installs itself is the exact shape of the thing Prism's
 * virtualization rules exist to prevent, and Play Protect has to be able to see it. So a received app
 * lands in a staging directory, is listed as offered by the device that sent it, and is installed or
 * virtualized by the user from the Virtualization page. "Automatically receiving" the app means the bytes
 * arrive without being asked for again — not that code runs without anybody deciding.
 */
object TrustedAppTransfer {

    private const val TAG = "PrismTrust"
    private const val PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Where apps received from a trusted device wait. */
    fun stagingDir(context: Context): File =
        File(context.filesDir, "trust/incoming-apps").apply { mkdirs() }

    fun stagingDir(context: Context, pkg: String): File =
        File(stagingDir(context), sanitize(pkg)).apply { mkdirs() }

    /** Packages received from a trusted device and not yet dealt with. */
    fun received(context: Context): List<String> =
        stagingDir(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.listFiles().orEmpty().any { f -> f.extension.equals("apk", true) } }
            .map { it.name }
            .sorted()

    // ── Announcing ─────────────────────────────────────────────────────────

    /**
     * Announces this device's apps to one trusted device.
     *
     * EVERY LAUNCHABLE APP, not only the ones already in the vault. The instruction is that a paired
     * device lists what the phone has so the user can pick one to virtualize, and a list limited to apps
     * they had already virtualized would only ever show them what they had already done.
     *
     * LAUNCHABLE is the filter that remains, and it is not arbitrary: a package with no launcher intent
     * is a service, a provider or a vendor component. None of them can be "opened" on the other device,
     * and listing four hundred of them would bury the twenty the user recognises.
     *
     * Name and size only. The icon is served on request -- see [serve] -- because two hundred icons do
     * not fit in a datagram transport.
     */
    fun announceTo(context: Context, fingerprint: String): Int {
        val manager = context.packageManager
        val launchable = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            manager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .filter { it != context.packageName }
        }.getOrDefault(emptyList())

        if (launchable.isEmpty()) return 0

        val summaries = launchable.mapNotNull { pkg ->
            runCatching {
                val info = manager.getApplicationInfo(pkg, 0)
                val sources = buildList {
                    info.sourceDir?.let { add(java.io.File(it)) }
                    info.splitSourceDirs?.forEach { add(java.io.File(it)) }
                }.filter { it.isFile }
                TrustedSharing.AppSummary(
                    pkg = pkg,
                    label = manager.getApplicationLabel(info).toString().ifBlank { pkg },
                    versionName = runCatching {
                        manager.getPackageInfo(pkg, 0).versionName.orEmpty()
                    }.getOrDefault(""),
                    bytes = sources.sumOf { it.length() },
                    splits = sources.size.coerceAtLeast(1),
                )
            }.getOrNull()
        }

        val sent = TrustedSharing.announceApps(fingerprint, summaries)
        if (sent > 0) PrismLogger.logInfo(TAG, "Announced $sent app(s) to a trusted device")
        return sent
    }

    /** Announces to every device paired for apps. Used when the installed set changes. */
    fun announceAll(context: Context): Int =
        TrustedDevices.recipientsFor(TrustedDevices.Kind.VIRTUAL_APPS)
            .sumOf { announceTo(context, it.fingerprint) }

    // ── Serving ────────────────────────────────────────────────────────────

    /**
     * Answers `GET /trusted-app/...` for a peer.
     *
     * AUTHORISED PER REQUEST, against the peer's address, and against OUTGOING rather than incoming: the
     * question is whether this device agreed to send that device apps. A route that served any caller
     * would hand every app in the vault to anything that could reach port 8080.
     *
     * Returns true when it handled the request (including refusing it).
     */
    fun serve(context: Context, peerIp: String, requestPath: String, output: OutputStream): Boolean {
        if (!requestPath.startsWith(PREFIX)) return false

        val authorised = TrustedDevices.recipientsFor(TrustedDevices.Kind.VIRTUAL_APPS)
            .any { it.lastIp == peerIp }
        if (!authorised) {
            PrismLogger.logWarning(TAG, "Refused an app request from $peerIp -- not a trusted recipient")
            respond(output, 403, "This device has not agreed to send you apps.")
            return true
        }

        val rest = requestPath.removePrefix(PREFIX).trim('/')
        if (rest.isEmpty()) {
            respond(output, 400, "Name a package.")
            return true
        }

        val pkg = rest.substringBefore('/')
        val fileName = rest.substringAfter('/', "")

        // The icon, which is what a catalogue needs and what is asked for first. Served from the
        // package manager rather than the vault: the whole point is that the far side can show an app
        // it has not downloaded.
        if (fileName == ICON) {
            val png = iconPng(context, pkg)
            if (png == null) {
                respond(output, 404, "No icon for that package.")
            } else {
                respondBody(output, "image/png", png)
            }
            return true
        }

        // APKs come from where the system keeps them, with the vault's copy preferred when there is
        // one: a vault copy is already read-only and is exactly what was virtualized here.
        val apks = apksFor(context, pkg).ifEmpty { installedApks(context, pkg) }
        if (apks.isEmpty()) {
            respond(output, 404, "That app is not on this device.")
            return true
        }

        if (fileName.isEmpty()) {
            // The listing. Names and sizes, so the far side knows what it is about to fetch and can tell
            // a truncated transfer from a finished one.
            val body = JSONObject().apply {
                put("pkg", pkg)
                put(
                    "files",
                    JSONArray().also { array ->
                        apks.forEach { apk ->
                            array.put(JSONObject().apply {
                                put("name", apk.name)
                                put("bytes", apk.length())
                            })
                        }
                    },
                )
            }.toString()
            respondBody(output, "application/json", body.toByteArray())
            return true
        }

        // The file. Matched by NAME against the listing rather than resolved as a path, so nothing
        // outside this package's own directory can be asked for however the request is spelled.
        val apk = apks.firstOrNull { it.name == fileName }
        if (apk == null) {
            respond(output, 404, "No such file in that app.")
            return true
        }
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.android.package-archive\r\n" +
                "Content-Length: ${apk.length()}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        apk.inputStream().use { it.copyTo(output, 64 * 1024) }
        output.flush()
        PrismLogger.logInfo(TAG, "Sent ${apk.name} of $pkg to $peerIp")
        return true
    }

    // ── Fetching ───────────────────────────────────────────────────────────

    /**
     * Downloads an announced app. Blocking; call it off the main thread.
     *
     * Returns the files received, empty on failure. A part-finished download leaves `.part` files behind
     * and no APK, so a later attempt starts clean rather than finding half a file and believing it.
     */
    fun fetch(context: Context, app: TrustedApps.Offered): List<File> {
        val peerIp = TrustedDevices.byFingerprint(app.deviceFingerprint)?.lastIp.orEmpty()
        if (peerIp.isBlank()) {
            PrismLogger.logWarning(TAG, "No address for " + app.deviceName + "; cannot fetch " + app.pkg)
            return emptyList()
        }

        val listing = request(peerIp, TrustedSharing.appFetchPath(app.pkg)) { input, length ->
            readFully(input, length).toString(Charsets.UTF_8)
        } ?: run {
            PrismLogger.logWarning(TAG, "Could not list ${app.pkg} on ${peerIp}")
            return emptyList()
        }

        val names = runCatching {
            val array = JSONObject(listing).optJSONArray("files")
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                array?.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        if (names.isEmpty()) {
            PrismLogger.logWarning(TAG, "${app.deviceName} listed no files for ${app.pkg}")
            return emptyList()
        }

        val target = TrustedApps.apkDir(app).apply { mkdirs() }
        val written = mutableListOf<File>()
        for (name in names) {
            // A name from the wire, used as a file name. Anything with a separator in it is rejected
            // rather than sanitised: a peer has no business naming a path here, and quietly rewriting it
            // would hide the attempt.
            if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                PrismLogger.logWarning(TAG, "Refused a file name from ${peerIp}: $name")
                return emptyList()
            }
            val partial = File(target, "$name.part")
            // `request` returns null on failure, so the body returns a value to tell the two apart -- a
            // Unit-returning body would make a failed transfer indistinguishable from a finished one.
            val ok = request(peerIp, TrustedSharing.appFetchPath(app.pkg) + "/" + name) { input, length ->
                partial.outputStream().use { out -> copyExactly(input, out, length) }
                true
            } == true
            if (!ok) {
                PrismLogger.logWarning(TAG, "Transfer of $name for ${app.pkg} failed")
                partial.delete()
                return emptyList()
            }
            val finished = File(target, name)
            if (finished.exists()) finished.delete()
            if (!partial.renameTo(finished)) {
                partial.delete()
                return emptyList()
            }
            written += finished
        }

        // Read-only for the same reason the vault's own backups are: ART refuses to load a dex file the
        // calling app can still write to.
        VirtualizedAppVault.markReadOnly(written)
        // Recorded where TrustedApps expects it, so "is it downloaded" is answered by the files being
        // there rather than by a flag that could disagree with them.
        PrismLogger.logInfo(
            TAG,
            "Received ${written.size} APK(s) for ${app.pkg} from ${app.deviceName}",
        )
        return written
    }

    /**
     * Opens a downloaded app through Prism's virtualization.
     *
     * TWO CASES, AND THE DIFFERENCE MATTERS. When the package is already installed on this phone, the
     * APKs go into the vault and Prism runs it virtualized -- that is the ordinary path and nothing
     * leaves Prism. When it is NOT installed, Android has to install it first, and that is handed to the
     * system installer rather than done quietly: the user confirms it, Play Protect scans it, and the
     * phone's own rules about unknown sources apply. An APK that arrived over the network and installed
     * itself is exactly the thing those rules exist to prevent, and Prism having a mesh is not a reason
     * to be exempt from them.
     *
     * Returns a sentence for the UI.
     */
    fun virtualize(context: Context, app: TrustedApps.Offered): String? {
        val files = TrustedApps.apkDir(app).listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        if (files.isEmpty()) return "Nothing downloaded for " + app.label + " yet."

        val installed = runCatching {
            context.packageManager.getApplicationInfo(app.pkg, 0)
            true
        }.getOrDefault(false)

        if (!installed) {
            val base = files.firstOrNull { it.name.contains("base", ignoreCase = true) } ?: files.first()
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", base,
                )
            }.getOrNull() ?: return "Downloaded, but Android would not share the file to install it."

            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                )
            }.onFailure { return "Could not open the installer: " + it.message }

            return app.label + " has to be installed once before Prism can virtualize it. " +
                (if (files.size > 1) "It has " + files.size + " split APKs, and Android's installer " +
                    "takes only the base one from here — a split app may need installing from the phone " +
                    "it came from. " else "") +
                "Play Protect will check it."
        }

        // Installed: back it up into the vault so the virtualized copy runs from encrypted storage, then
        // hand over to the launcher the rest of Prism uses.
        VirtualizedAppVault.backUpApk(context, app.pkg)
            ?: return "Could not put " + app.label + " into the vault."

        val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return app.label + " is in the vault, but it has nothing to launch."
        val component = launch.component
            ?: return app.label + " is in the vault, but Android did not name its main activity."

        // Virtualization applies to every app when the setting is on -- see
        // VirtualizedAppLauncher.shouldVirtualize -- so there is no per-app flag to set here. Saying
        // when it is OFF is worth more than setting something silently.
        val on = PrismSettings.getVirtualizeAndroidApps()
        return app.label + " is in the vault and will open from the app drawer (" +
            component.shortClassName.trimStart('.') + ")." +
            (if (on) " It runs inside Prism." else " Turn on app virtualization in Settings to run it " +
                "inside Prism rather than normally.")
    }

    /** Downloads one app's icon into [target]. Small and quick; used to fill an apps list. */
    fun fetchIcon(app: TrustedApps.Offered, target: File): File? {
        val peerIp = TrustedDevices.byFingerprint(app.deviceFingerprint)?.lastIp.orEmpty()
        if (peerIp.isBlank()) return null
        val bytes = request(peerIp, TrustedSharing.appFetchPath(app.pkg) + "/icon") { input, length ->
            readFully(input, length)
        } ?: return null
        if (bytes.isEmpty()) return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    /** One request over the PRISM_CONNECT tunnel, with the response body handed to [read]. */
    private fun <T> request(peerIp: String, path: String, read: (InputStream, Long) -> T): T? {
        var socket: PrismSocket? = null
        return try {
            socket = PrismSocket()
            // The reserved marker, so the far side's proxy dispatches to the host that knows this route
            // instead of looking the name up as a website.
            socket.setHostHint(P2pModelRegistry.MODEL_HOST_DOMAIN)
            socket.connect(InetSocketAddress(peerIp, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            socket.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }

            val input = socket.getInputStream()
            val length = readHeadersAndLength(input) ?: return null
            read(input, length)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Request to $peerIp$path failed", e)
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private const val PREFIX = "/trusted-app/"

    private const val ICON = "icon"

    private fun apksFor(context: Context, pkg: String): List<File> =
        VirtualizedAppVault.apkDir(context, pkg).listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("apk", true) }
            .sortedBy { it.name }

    /** The APKs Android itself holds for an installed package, base and splits. */
    private fun installedApks(context: Context, pkg: String): List<File> = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        buildList {
            info.sourceDir?.let { add(File(it)) }
            info.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    /**
     * An app's icon as a PNG.
     *
     * Drawn into a bitmap rather than read as a file, because an icon is not a file: it is a drawable
     * that may be a vector, an adaptive icon with two layers, or a colour. Rendering it is the only way
     * to get the same picture the launcher shows.
     */
    private fun iconPng(context: Context, pkg: String): ByteArray? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val size = ICON_PX
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        java.io.ByteArrayOutputStream().also { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
        }.toByteArray()
    }.getOrNull()

    /** Big enough for a desktop list at any reasonable scaling, small enough to be a few kilobytes. */
    private const val ICON_PX = 128

    private fun sanitize(pkg: String): String = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Reads the status line and headers, returning the declared body length, or null on a non-200. */
    private fun readHeadersAndLength(input: InputStream): Long? {
        val status = readLine(input) ?: return null
        if (!status.contains(" 200")) {
            PrismLogger.logWarning(TAG, "Peer answered: $status")
            return null
        }
        var length: Long? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                length = parts[1].trim().toLongOrNull()
            }
        }
        // Refused rather than read to EOF: without a length there is no way to tell a finished transfer
        // from a truncated one, and an APK that is short by a block installs as nothing useful.
        if (length == null) PrismLogger.logWarning(TAG, "Peer sent no Content-Length")
        return length
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (builder.isEmpty()) null else builder.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) builder.append(c.toChar())
        }
        return builder.toString()
    }

    private fun readFully(input: InputStream, length: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyExactly(input, out, length)
        return out.toByteArray()
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw java.io.IOException("Stream ended $remaining bytes early")
            out.write(buffer, 0, read)
            remaining -= read
        }
        out.flush()
    }

    private fun respond(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        output.write(
            ("HTTP/1.1 $code Error\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }

    private fun respondBody(output: OutputStream, type: String, body: ByteArray) {
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }
}
