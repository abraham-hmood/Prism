package com.prism.launcher.pdb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Android's side of the Prism Debug Bridge.
 *
 * The app cannot become adbd's `shell` user, so this class does what an application legitimately
 * can: it asks Android's own installer to install an APK, lists the packages the launcher can see,
 * reads the log the app is allowed to read, and says no to the operations that need a signature
 * permission. That is more useful than a `pm install` that always fails, and it keeps the promise
 * that the bridge never elevates itself behind the user's back.
 */
class AndroidPdbHost(private val context: Context) : PdbHost {

    private val app: Context = context.applicationContext

    override val platform: String = "android"

    override fun serial(): String =
        "pdb-" + runCatching { Pdb.localFingerprint() }.getOrDefault("android").take(12)

    override fun deviceName(): String =
        (Build.MANUFACTURER.orEmpty() + " " + Build.MODEL.orEmpty()).trim()
            .ifBlank { "Android device" }

    override fun incomingDir(): File = File(app.filesDir, "pdb/incoming").apply { mkdirs() }

    override fun documentsDir(): File =
        File(Environment.getExternalStorageDirectory(), "Prism").apply { mkdirs() }

    override fun install(file: File, options: List<String>): PdbInstallResult {
        if (PdbInstall.classify(file) != PdbInstall.Kind.APK) {
            return PdbInstall.install(file, platform, ::incomingDir, options)
        }
        val staged = PdbInstall.copyInto(file, incomingDir())
        if (!canInstall()) {
            openUnknownSourcesSettings()
            return PdbInstallResult(
                "Allow Prism to install unknown apps, then run the install again. " +
                    "Android does not permit an app to bypass that confirmation.",
                1,
            )
        }
        return launchPackageInstaller(staged)
    }

    override fun installSet(files: List<File>, options: List<String>): PdbInstallResult {
        val apks = files.filter { PdbInstall.classify(it) == PdbInstall.Kind.APK }
        if (apks.size != files.size || apks.isEmpty()) {
            return super.installSet(files, options)
        }
        if (!canInstall()) {
            openUnknownSourcesSettings()
            return PdbInstallResult(
                "Allow Prism to install unknown apps, then run the install again.",
                1,
            )
        }
        val staged = apks.map { PdbInstall.copyInto(it, incomingDir()) }
        return commitSession(staged, options)
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            runCatching { app.packageManager.canRequestPackageInstalls() }.getOrDefault(true)

    private fun openUnknownSourcesSettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launchPackageInstaller(file: File): PdbInstallResult {
        val uri = runCatching {
            FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        }.getOrNull() ?: return PdbInstallResult(
            "The package was saved to " + file.absolutePath + ", but Android would not share it.",
            1,
        )
        return runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_TYPE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            PdbInstallResult("Android's installer is open for " + file.name + ". Confirm it on the device.")
        }.getOrElse {
            PdbInstallResult("Could not open Android's installer: " + (it.message ?: "unknown error"), 1)
        }
    }

    private fun commitSession(files: List<File>, options: List<String>): PdbInstallResult {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(files.sumOf { it.length() })
        // `-g` and `-s` reach into installer behaviour that only a system-signed package may set,
        // so say they were skipped rather than pretending the install honoured them.
        val unsupported = options.filter { it == "-g" || it == "-s" }
        val note = if (unsupported.isEmpty()) "" else
            " (" + unsupported.joinToString(" ") + " needs a system installer, so it was skipped.)"
        return runCatching {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                files.forEachIndexed { index, file ->
                    val name = if (index == 0) "base.apk" else "split-" + index + ".apk"
                    session.openWrite(name, 0, file.length()).use { output ->
                        file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(app, PdbInstallReceiver::class.java).putExtra("session", sessionId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(callback.intentSender)
            }
            PdbInstallResult(
                "Android is asking you to allow the install of " + files.size + " APK(s)." + note,
            )
        }.getOrElse {
            PdbInstallResult("Could not start Android's package session: " + (it.message ?: "unknown error"), 1)
        }
    }

    override fun uninstall(packageName: String, keepData: Boolean): PdbInstallResult? {
        if (!packageName.matches(Regex("[A-Za-z0-9._-]+"))) {
            return PdbInstallResult("That is not a package name.", 1)
        }
        return runCatching {
            app.startActivity(
                Intent(Intent.ACTION_DELETE)
                    .setData(Uri.parse("package:" + packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            PdbInstallResult(
                "Android's uninstall confirmation is open for " + packageName +
                    (if (keepData) ". Android does not offer keep-data through this path." else "."),
            )
        }.getOrElse { PdbInstallResult("Could not open the uninstaller: " + (it.message ?: "unknown"), 1) }
    }

    override fun packages(options: List<String>, filter: String): String {
        val pm = app.packageManager
        val thirdParty = options.contains("-3")
        val system = options.contains("-s")
        val disabled = options.contains("-d")
        val enabled = options.contains("-e")
        val withPath = options.contains("-f")
        val apps = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        return apps.asSequence()
            .filter { info ->
                val appInfo = info.applicationInfo
                val isSystem = appInfo != null &&
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                when {
                    thirdParty && system -> false
                    thirdParty -> !isSystem
                    system -> isSystem
                    else -> true
                }
            }
            .filter { info ->
                when {
                    disabled -> info.applicationInfo?.enabled == false
                    enabled -> info.applicationInfo?.enabled != false
                    else -> true
                }
            }
            .filter { info -> filter.isBlank() || info.packageName.contains(filter, true) }
            .sortedBy { it.packageName }
            .joinToString("\n") { info ->
                val suffix = if (withPath) "=" + info.applicationInfo?.sourceDir.orEmpty() else ""
                "package:" + info.packageName + suffix
            }
            .let { if (it.isBlank()) "" else it + "\n" }
    }

    override fun packagePath(packageName: String): String? = runCatching {
        app.packageManager.getApplicationInfo(packageName, 0).sourceDir
    }.getOrNull()

    override fun clearPackage(packageName: String): String? = null

    override fun grant(packageName: String, permission: String): String? = null

    override fun revoke(packageName: String, permission: String): String? = null

    override fun launch(request: List<String>): String? {
        val args = request.toMutableList()
        fun value(flag: String): String? {
            val index = args.indexOf(flag)
            return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
        }
        val packageName = value("-p")
        val component = value("-n")
        val action = value("-a") ?: Intent.ACTION_MAIN
        val data = value("-d")
        val type = value("-t")
        val intent = if (component != null) {
            val pkg = component.substringBefore('/')
            val cls = component.substringAfter('/', "").replaceFirst(".", pkg)
            Intent(action).setClassName(pkg, if (cls.isBlank()) pkg else cls)
        } else if (packageName != null) {
            app.packageManager.getLaunchIntentForPackage(packageName) ?: return "No launch activity for $packageName"
        } else {
            Intent(action)
        }
        if (data != null) intent.data = Uri.parse(data)
        if (type != null) intent.type = type
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            "Activity started."
        }.getOrElse { "Could not start the activity: " + (it.message ?: "unknown") }
    }

    override fun dump(service: String?): String {
        val command = mutableListOf("/system/bin/dumpsys")
        if (!service.isNullOrBlank()) command += service
        val live = runCommand(command, 5_000)
        return buildString {
            appendLine("Prism Android service dump")
            appendLine("device=" + deviceName())
            appendLine("android=" + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            appendLine("manufacturer=" + Build.MANUFACTURER)
            appendLine("model=" + Build.MODEL)
            appendLine("abi=" + Build.SUPPORTED_ABIS.joinToString(", "))
            appendLine("incoming=" + incomingDir().absolutePath)
            if (!live.isNullOrBlank()) {
                appendLine("--- dumpsys ---")
                append(live)
            }
        }
    }

    override fun logcat(args: List<String>): String {
        val command = mutableListOf("/system/bin/logcat", "-d")
        command += args.filterNot { it == "-d" || it == "-c" }
        return runCommand(command, 6_000)
            ?: "logcat is not readable from this application on this Android version."
    }

    override fun bugreport(): String =
        dump(null) + "\n--- logcat ---\n" + logcat(emptyList())

    override fun input(args: List<String>): String? {
        if (args.isEmpty()) return null
        return runCommand(listOf("/system/bin/input") + args, 5_000)
    }

    override fun display(args: List<String>): String? {
        if (args.isEmpty()) return null
        return runCommand(listOf("/system/bin/wm") + args, 5_000)
    }

    override fun screenshot(file: File): String? {
        file.parentFile?.mkdirs()
        val result = runCommand(listOf("/system/bin/screencap", "-p", file.absolutePath), 8_000)
        return if (file.isFile && file.length() > 0) {
            "Screen capture saved to " + file.absolutePath +
                (if (result.isNullOrBlank()) "" else " (" + result.trim() + ")")
        } else {
            null
        }
    }

    override fun screenRecord(file: File, args: List<String>): String? {
        file.parentFile?.mkdirs()
        val seconds = args.firstOrNull { it.startsWith("--time-limit=") }
            ?.substringAfter('=')?.toIntOrNull()
            ?: args.firstOrNull { it == "--time-limit" }
                ?.let { args.getOrNull(args.indexOf(it) + 1)?.toIntOrNull() }
            ?: 10
        val command = mutableListOf(
            "/system/bin/screenrecord",
            "--time-limit",
            seconds.coerceIn(1, 60).toString(),
        )
        command += args.filterNot { it.startsWith("--time-limit") }
        command += file.absolutePath
        val result = runCommand(command, (seconds.coerceIn(1, 60) + 10) * 1_000L)
        return if (file.isFile && file.length() > 0) {
            "Screen recording saved to " + file.absolutePath +
                (if (result.isNullOrBlank()) "" else " (" + result.trim() + ")")
        } else {
            result?.takeIf { it.isNotBlank() } ?: "Screen recording is not permitted for this app."
        }
    }

    override fun reboot(mode: String): String? = null

    override fun properties(): Map<String, String> {
        val fromGetprop = runCommand(listOf("/system/bin/getprop"), 3_000)
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val open = line.indexOf('[')
                val close = line.indexOf(']')
                val secondOpen = line.indexOf('[', close + 1)
                val secondClose = line.lastIndexOf(']')
                if (open == 0 && close > 0 && secondOpen > close && secondClose > secondOpen) {
                    line.substring(1, close) to line.substring(secondOpen + 1, secondClose)
                } else {
                    null
                }
            }
            .toMap()
        return fromGetprop + mapOf(
            "ro.product.model" to Build.MODEL,
            "ro.product.manufacturer" to Build.MANUFACTURER,
            "ro.build.version.release" to Build.VERSION.RELEASE,
            "ro.build.version.sdk" to Build.VERSION.SDK_INT.toString(),
            "ro.product.cpu.abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "ro.serialno" to serial(),
        )
    }

    override fun adbControl(args: List<String>): String? = null

    private fun runCommand(command: List<String>, timeoutMs: Long): String? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText().take(512 * 1024) }
    }.getOrNull()

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
    }
}

/** Receives Android's package-session result. The system installer owns the user prompt. */
class PdbInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        android.util.Log.i(
            "PrismDebugBridge",
            if (status == PackageInstaller.STATUS_SUCCESS) "Package session installed" else
                "Package session finished with $status: $message",
        )
    }
}
