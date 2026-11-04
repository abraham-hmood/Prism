package com.prism.launcher.browser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import com.android.apksig.ApkSigner
import com.prism.launcher.PrismLogger
import com.prism.launcher.webapp.WebAppPackage
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Turns a website into an installed Android app: the browser's "Install as app".
 *
 * ## How
 *
 * Prism ships a template APK, `assets/webapp-shell.apk`, built from `:webapp-shell`: one activity
 * whose layout is a WebView, which opens whatever `assets/webapp.properties` names. Making an app for
 * a site is then four steps, all on the phone:
 *
 *  1. COPY the template, leaving out any old signature.
 *  2. REWRITE its compiled manifest ([BinaryManifest]) with a package name of the site's own and
 *     the site's title as the label, and add `assets/webapp.properties` with the URL.
 *  3. SIGN it with apksig (v2 and v3; Android 11+ refuses an APK with neither) using a key Prism
 *     generates once and keeps. One key for every web app is what lets a site be installed again
 *     over its earlier copy instead of failing as a signature conflict.
 *  4. INSTALL it through PackageInstaller. Android always asks the user to confirm an install made
 *     by an app, and the first time it also asks them to let Prism install apps at all.
 *
 * The package name is derived from the URL, so installing the same page twice updates one app
 * rather than leaving two identical icons.
 */
object WebAppBuilder {

    private const val TAG = "PrismWebApp"

    /** The template's launcher activity; the package around it changes per site. */
    private const val SHELL_ACTIVITY = WebAppPackage.SHELL_ACTIVITY
    private const val SHELL_ASSET = "webapp-shell.apk"

    private const val ACTION_INSTALL_STATUS = "com.prism.launcher.WEBAPP_INSTALL_STATUS"
    private const val EXTRA_PACKAGE = "webapp_package"

    /** What happened, for the browser to tell the user. */
    sealed interface Outcome {
        data class Installed(val component: ComponentName) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** The package a site's app gets: stable for the same page, different for every other one. */
    fun packageFor(url: String): String = WebAppPackage.packageFor(url)

    /**
     * Builds the signed APK for [url] and returns it, or throws with a reason a user can read.
     * Slow (it copies and signs a few hundred kilobytes) -- call it off the main thread.
     */
    fun build(context: Context, url: String, title: String): File {
        val label = title.trim().ifBlank { hostLabel(url) }.take(50)
        val packageName = packageFor(url)
        val work = File(context.cacheDir, "webapps").apply { mkdirs() }
        val template = File(work, "template.apk")
        val unsigned = File(work, "$packageName-unsigned.apk")
        val signed = File(work, "$packageName.apk")

        context.assets.open(SHELL_ASSET).use { input ->
            template.outputStream().use { input.copyTo(it) }
        }
        WebAppPackage.assemble(template, unsigned, packageName, label, url)

        val (key, certificate) = signingKey(context)
        signed.delete()
        ApkSigner.Builder(
            listOf(
                ApkSigner.SignerConfig.Builder(
                    "prism", com.android.apksig.KeyConfig.Jca(key), listOf(certificate),
                ).build(),
            ),
        )
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        unsigned.delete()
        template.delete()
        PrismLogger.logInfo(TAG, "Built $packageName for $url (${signed.length() / 1024} KB)")
        return signed
    }

    private fun hostLabel(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Web app"

    /**
     * Prism's signing key for web apps, created the first time it is needed.
     *
     * KEPT IN PRISM'S PRIVATE FILES, not the Android Keystore. apksig signs through the ordinary
     * JCA, and the key only ever vouches for apps this phone built for itself; what matters is that
     * it stays the SAME key, so a site can be installed again as an update of its earlier copy.
     */
    @Synchronized
    private fun signingKey(context: Context): Pair<PrivateKey, X509Certificate> {
        val dir = File(context.filesDir, "webapps").apply { mkdirs() }
        val keyFile = File(dir, "signer.key")
        val certFile = File(dir, "signer.crt")
        if (keyFile.isFile && certFile.isFile) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return key to cert
            }.onFailure { PrismLogger.logWarning(TAG, "The web-app signing key would not load; making a new one") }
        }

        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = org.bouncycastle.asn1.x500.X500Name("CN=Prism Web Apps, O=Prism")
        val now = System.currentTimeMillis()
        val holder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 24 * 3600_000L),
            // Thirty years: an app signed with an expired certificate still installs, but there
            // is no reason to find out on somebody's phone.
            java.util.Date(now + 30L * 365 * 24 * 3600_000L),
            name,
            pair.public,
        ).build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(holder.encoded.inputStream()) as X509Certificate
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }

    /**
     * Installs [apk] as [packageName], and reports once: installed, or why not.
     *
     * The confirmation screen Android shows is started from here when the session asks for it, so
     * the caller only ever sees the final answer.
     */
    fun install(context: Context, apk: File, packageName: String, onDone: (Outcome) -> Unit) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = runCatching { installer.createSession(params) }.getOrElse {
            onDone(Outcome.Failed("Android would not start the install: ${it.message}"))
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm == null) {
                            finish(ctx, Outcome.Failed("Android asked for confirmation but gave no screen for it"))
                        } else {
                            runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { finish(ctx, Outcome.Failed("Could not show the install prompt")) }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        finish(ctx, Outcome.Installed(ComponentName(packageName, SHELL_ACTIVITY)))
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        finish(
                            ctx,
                            Outcome.Failed(
                                when (status) {
                                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled"
                                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install"
                                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                                        "An app with the same name but a different signature is installed"
                                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
                                    else -> "The install failed" + (message?.let { ": $it" } ?: "")
                                },
                            ),
                        )
                    }
                }
            }

            private fun finish(ctx: Context, outcome: Outcome) {
                runCatching { ctx.unregisterReceiver(this) }
                runCatching { apk.delete() }
                if (outcome is Outcome.Failed) PrismLogger.logWarning(TAG, "$packageName: ${outcome.reason}")
                else PrismLogger.logInfo(TAG, "Installed $packageName")
                onDone(outcome)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        runCatching {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(ACTION_INSTALL_STATUS).setPackage(app.packageName)
                    .putExtra(EXTRA_PACKAGE, packageName)
                // MUTABLE: the installer fills the status extras into this intent.
                val pending = PendingIntent.getBroadcast(
                    app, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(pending.intentSender)
            }
        }.onFailure {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            onDone(Outcome.Failed("Could not hand the app to Android: ${it.message}"))
        }
    }
}
