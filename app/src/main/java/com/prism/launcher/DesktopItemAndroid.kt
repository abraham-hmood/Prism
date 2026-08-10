package com.prism.launcher

import android.content.ComponentName

/**
 * Puts `ComponentName` back on top of the portable [DesktopItem.App].
 *
 * [DesktopItem.App] holds a plain string in :core, because `ComponentName` is an Android class.
 * On Android that string is still exactly `ComponentName.flattenToString()`, so the type is
 * recoverable -- and recovering it here means the fifteen-odd call sites that hand a
 * `ComponentName` straight to `PackageManager` read the same as they did before the move, rather
 * than each growing its own unflatten-and-null-check.
 *
 * This is the shape the whole port keeps taking: the portable representation goes in :core, and
 * the platform adds a thin adapter that restores its own native vocabulary.
 */
val DesktopItem.App.component: ComponentName
    get() = ComponentName.unflattenFromString(appId)
        // Only reachable if something wrote a malformed id. A ComponentName naming the string as
        // both package and class is wrong, but it is inert -- PackageManager will fail to resolve
        // it and the caller's existing catch handles that -- whereas throwing here would take out
        // the whole desktop grid over one bad cell.
        ?: ComponentName(appId, appId)

/** Builds a [DesktopItem.App] from Android's native identity for an app. */
fun desktopApp(component: ComponentName): DesktopItem.App =
    DesktopItem.App(component.flattenToString())
