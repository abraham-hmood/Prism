package com.prism.launcher.clipboard

/**
 * Whether Prism can see a copy made in another app WITHOUT being in the foreground.
 *
 * ## The rule this encodes, and where it comes from
 *
 * Since Android 10 the system clipboard service answers a read only when one of these is true:
 * the calling uid has window focus, the calling package is the default input method, or the
 * caller is a system service (shell, content capture, augmented autofill, a virtual-device
 * owner) holding a signature permission Prism cannot be granted. Listener callbacks are gated
 * by the SAME check -- `ClipboardService.sendClipChangedBroadcast` runs `clipboardAccessAllowed`
 * per registered listener before dispatching -- so a background `OnPrimaryClipChangedListener`
 * does not fire either, and registering one is not an alternative to this decision.
 *
 * Two consequences the feature is built on:
 *
 * 1. The check is by package, not by component. Every process in `com.prism.launcher` passes
 *    while ANY component of it is the default IME, so the watcher can be an ordinary service
 *    rather than something that only runs while the keyboard is visible.
 * 2. Being the default IME is a state the user grants through the system's keyboard picker on
 *    every Android version Prism supports, with no special permission. It is the only route to
 *    instant background sharing that does not involve stealing focus with an overlay.
 *
 * ## Why it lives in :core
 *
 * It is the one part of the feature that is a decision rather than an Android binding, and both
 * platforms' settings screens show its outcome. Keeping it free of `android.*` means the three
 * states can be tested without a device.
 */
object ClipboardWatchPolicy {

    enum class State {
        /** The user has not turned live sharing on. */
        OFF,

        /** On, but Android will not deliver copies until Prism Writer is the default keyboard. */
        NEEDS_KEYBOARD,

        /** On, and this app is the default IME: copies arrive immediately, in the background. */
        LIVE,
    }

    /**
     * @param enabled what the user chose, persisted in settings.
     * @param defaultImeComponent `Settings.Secure.DEFAULT_INPUT_METHOD`, e.g.
     *   `"com.prism.launcher/.writer.PrismWriterService"`, or null/blank when the device has no
     *   keyboard selected.
     * @param ownPackage this app's package name.
     */
    fun state(enabled: Boolean, defaultImeComponent: String?, ownPackage: String): State {
        if (!enabled) return State.OFF
        if (ownPackage.isBlank()) return State.OFF
        val imePackage = packageOf(defaultImeComponent) ?: return State.NEEDS_KEYBOARD
        return if (imePackage == ownPackage) State.LIVE else State.NEEDS_KEYBOARD
    }

    /**
     * The package half of a flattened `ComponentName`, which is the only half the clipboard
     * service compares. Accepts the bare package form too, because a few OEM keyboards write
     * one, and returns null for the empty string so "no keyboard selected" is not mistaken for
     * a match.
     */
    fun packageOf(component: String?): String? =
        component?.trim()?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }
}
