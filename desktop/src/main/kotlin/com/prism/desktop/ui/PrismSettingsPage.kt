package com.prism.desktop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings

/**
 * Prism's own settings, as opposed to Nora's.
 *
 * DELIBERATELY SMALL, AND SAYING SO. Android's `SettingsActivity` is over a thousand lines
 * covering the launcher grid, icon packs, fonts, glow, the browser, the VPN, the mesh, the access
 * point, virtualization and the AI stack. Almost none of those subsystems are ported yet, so a
 * settings screen listing them would be a screen full of switches that change nothing -- which is
 * a worse lie than an obviously short page. What is here is what is actually wired up; the note at
 * the bottom accounts for the rest, in the same spirit as the greyed-out rail entries.
 *
 * Nora's tuning lives on its own page because it is generated from `NoraTuning.PARAMS` rather
 * than hand-written, and mixing eighty generated numeric fields into this page would bury the
 * handful of real launcher settings.
 */
@Composable
fun PrismSettingsPage(mobileMode: Boolean, onMobileModeChange: (Boolean) -> Unit) {
    PageScaffold(
        title = "Prism Settings",
        subtitle = "Desktop build. Options appear here as their subsystems are ported."
    ) {
        SectionHeader("SHELL")
        Card {
            ToggleRow(
                title = "Launcher mode",
                detail = "Use the phone build's navigation model: a horizontal pager over the " +
                    "page slots you assigned, at full window width, instead of the rail. Tab and " +
                    "Shift+Tab change page, down opens the switcher, 1-9 jump straight to a slot.",
                checked = mobileMode,
                onChange = onMobileModeChange,
            )
        }
        SectionFooter(
            "The slots come from the same prism_slots store the Android build writes, so a page " +
                "arrangement made on the phone is the arrangement you get here. The rail is the " +
                "better fit for a mouse; launcher mode is for anyone who uses Prism on a phone " +
                "first and would rather keep the muscle memory. Both shells show the same pages."
        )

        Spacer(Modifier.height(18.dp))

        SectionHeader("WALLPAPER")
        Card {
            CommittingField(
                value = PrismSettings.getWallpaperPath(),
                modifier = Modifier.fillMaxWidth(),
            ) { PrismSettings.setWallpaperPath(it) }
            Hairline()
            InfoRow(
                "Detected",
                com.prism.core.SystemWallpaper().current()?.absolutePath ?: "none found",
                mono = true,
            )
        }
        SectionFooter(
            "Leave the path blank to follow the desktop. Android shows the system wallpaper " +
                "because a launcher window is transparent and the wallpaper is literally behind " +
                "it; no such trick exists here, so Prism finds the file the OS is using and " +
                "draws it. Windows is read from the registry with the transcoded copy as a " +
                "fallback, Linux from gsettings. KDE stores its wallpaper inside a JavaScript " +
                "config blob and is not detected -- set a path there."
        )

        Spacer(Modifier.height(18.dp))

        SectionHeader("STORAGE")
        Card {
            InfoRow("Settings store", PrismSettings.PREFS, mono = true)
            Hairline()
            InfoRow("Data directory", com.prism.core.PrismPlatform.host.dataDir().absolutePath, mono = true)
            Hairline()
            InfoRow("Database", com.prism.launcher.JvmDatabase.defaultFile().absolutePath, mono = true)
        }
        SectionFooter(
            "Prism uses the same store names and keys on every platform, so a settings file or " +
                "database copied from one machine is readable on another."
        )

        Spacer(Modifier.height(18.dp))

        SectionHeader("NOT YET ON DESKTOP")
        Card {
            NotPorted(
                "Local .gguf inference",
                "Needs llama.cpp and nora_conv built for x86-64. Blocked on a 64-bit host " +
                    "compiler -- MinGW.org's gcc is 32-bit only and cannot produce a library a " +
                    "64-bit JVM will load."
            )
            Hairline()
            NotPorted(
                "VPN tunnel, mesh, access point",
                "Needs WinTun on Windows and CAP_NET_ADMIN on Linux, plus an elevated installer. " +
                    "Private tabs work but are not tunnelled, and say so."
            )
            Hairline()
            NotPorted("Nebula social feed", "Storage works; the feed UI is the largest screen left.")
            Hairline()
            NotPorted("Agentic tools UI", "Engine and storage are ported; only the screen is missing.")
            Hairline()
            NotPorted("Virtualization", "Android's AVF is Android-only; desktop would drive QEMU.")
            Hairline()
            NotPorted("WebDAV and .p2p shares", "FTP shares browse; WebDAV needs a PROPFIND-capable client.")
        }
        SectionFooter(
            "Listed rather than hidden so this page reports the state of the port instead of " +
                "implying it is finished."
        )
    }
}

@Composable
private fun NotPorted(title: String, reason: String) {
    InfoRow(title, "")
    Text(
        reason,
        fontSize = 12.sp,
        color = Color(0xFF6C6C78),
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
    )
}
