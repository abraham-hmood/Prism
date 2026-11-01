package com.prism.launcher.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.prism.launcher.update.UpdateSandbox.ArchiveFacts
import java.io.File
import java.security.MessageDigest

/**
 * Reads the Android facts an APK quarantine cannot get from :core.
 *
 * The package name, version code and permissions live in binary AndroidManifest.xml, which only the
 * platform's parser understands. The signing certificate is read through PackageManager too, because
 * APK Signature Scheme v2/v3 stores it in a block no ordinary zip reader can see.
 */
object ApkQuarantine {

    data class Inspected(val facts: ArchiveFacts, val signerSha256: String)

    fun inspect(context: Context, file: File): Inspected {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or signingFlags()
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        }
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, signingFlags())
            }
        }.getOrNull()

        val installedSigner = installed?.let(::signerSha256).orEmpty()
        val archiveSigner = archive?.let(::signerSha256).orEmpty()
        val installedPermissions = installed?.requestedPermissions?.toSet().orEmpty()
        val newPermissions = archive?.requestedPermissions
            ?.filterNot { it in installedPermissions }
            .orEmpty()
        // Prism itself watches package installs and hosts virtual apps through a DexClassLoader,
        // so these markers exist in every legitimate build. Reading the installed APK lets the
        // quarantine flag only capabilities that are NEW to the update.
        val installedMarkers = runCatching {
            UpdateSandbox.markersIn(File(context.packageCodePath))
        }.getOrDefault(emptySet())

        return Inspected(
            facts = ArchiveFacts(
                packageName = archive?.packageName.orEmpty(),
                versionCode = versionCode(archive),
                versionName = archive?.versionName.orEmpty(),
                requestedPermissions = newPermissions,
                installedSignerSha256 = installedSigner,
                installedPackageName = context.packageName,
                installedVersionCode = versionCode(installed),
                isDebuggable = archive?.applicationInfo?.let { info ->
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                } ?: false,
                installedMarkers = installedMarkers,
            ),
            signerSha256 = archiveSigner,
        )
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerSha256(info: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return ""
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        val certificate = signatures?.firstOrNull() ?: return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo?): Long {
        if (info == null) return 0L
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else info.versionCode.toLong()
    }
}
