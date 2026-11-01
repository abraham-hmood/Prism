package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings

/**
 * The message networks Prism can actually talk to, and what each one needs to do it.
 *
 * ## Three APIs, three different things
 *
 * These are not three interchangeable "personal chat" integrations, and pretending otherwise is
 * how the previous version ended up as a set of handoff links. Each network's public interface
 * belongs to a different product:
 *
 *  - **WhatsApp Cloud API** sends and receives messages on behalf of a WhatsApp Business number.
 *    It needs a Meta app, a business phone number id and an access token. It cannot read a
 *    personal WhatsApp account's existing chats; inbound messages arrive through a webhook.
 *  - **Messenger Platform** sends and receives on behalf of a Facebook Page. It needs a Page id
 *    and a Page access token. Like WhatsApp, the page it speaks for is separate from a personal
 *    profile.
 *  - **Signal** has no first-party server API. The maintained route is `signal-cli` /
 *    `signal-cli-rest-api`, running as a linked device, and Prism talks to that local bridge.
 *
 * The UI says which one the user is connecting instead of calling all three "your messages".
 * That is not hedging: sending from a business number to a friend is a different act from sending
 * from a personal account, and a screen that hides the difference would surprise the recipient.
 *
 * ## Keys can come from a build
 *
 * A value in the environment wins over the stored value. The desktop build can therefore be
 * launched with the owner's tokens already set (see [Key.env]), while the settings screen still
 * offers a field for a key the build did not provide. An environment key is shown as present and
 * read-only; writing over it would silently do nothing at the next launch.
 */
object MessagingApps {

    /** One credential a provider needs. */
    data class Key(
        /** Stable storage field, e.g. `access_token`. */
        val id: String,
        val label: String,
        /** Environment variable checked before the stored value. */
        val env: String,
        /** Whether a missing value prevents the provider from working at all. */
        val required: Boolean = true,
        /** A hint for the field, e.g. the shape of a page id. */
        val hint: String = "",
        /** Masked in the UI. The token is still stored, but never shown back in clear. */
        val secret: Boolean = false,
    )

    /**
     * A provider.
     *
     * [api] is false for a transport the system owns rather than one Prism can call. RCS is that
     * case: Android exposes no third-party RCS send API, so Prism can show and hand off a carrier
     * RCS thread but cannot put an RCS message on the wire itself. The row still exists so the
     * user can see the feature and its real boundary instead of hunting for a switch that is
     * absent.
     */
    data class App(
        val id: String,
        val label: String,
        val packageName: String,
        /** Where the user creates the credentials, or the bridge's setup page. */
        val portalUrl: String,
        val keys: List<Key>,
        val note: String,
        val api: Boolean = true,
        /** How this provider receives messages, shown in Settings. */
        val receive: String = "Webhook",
        /** Where a conversation can be opened outside Prism. */
        val webUrl: String = "",
        val appUri: String = "",
    ) {

        /** Whether every required credential is present, from either source. */
        fun configured(): Boolean = keys.filter { it.required }.all { value(it).isNotBlank() }

        /** The required fields that are still empty, for an actionable settings line. */
        fun missing(): List<Key> = keys.filter { it.required && value(it).isBlank() }

        fun value(key: Key): String = MessagingApps.value(id, key)

        /** The same, by storage field name, for call sites that do not hold the [Key]. */
        fun value(keyId: String): String = MessagingApps.value(id, keyId)
    }

    private val WHATSAPP = App(
        id = "whatsapp",
        label = "WhatsApp",
        packageName = "com.whatsapp",
        portalUrl = "https://developers.facebook.com/apps/",
        keys = listOf(
            Key(
                id = "access_token",
                label = "Access token",
                env = "WHATSAPP_ACCESS_TOKEN",
                hint = "Meta app → WhatsApp → API setup",
                secret = true,
            ),
            Key(
                id = "phone_number_id",
                label = "Phone number id",
                env = "WHATSAPP_PHONE_NUMBER_ID",
                hint = "The business number's id, not the phone number",
            ),
            Key(
                id = "verify_token",
                label = "Webhook verify token",
                env = "WHATSAPP_VERIFY_TOKEN",
                required = false,
                hint = "Any secret you also enter in the Meta webhook form",
            ),
            Key(
                id = "app_secret",
                label = "App secret",
                env = "WHATSAPP_APP_SECRET",
                required = false,
                hint = "Used to verify X-Hub-Signature-256 on incoming webhooks",
                secret = true,
            ),
            Key(
                id = "business_account_id",
                label = "Business account id",
                env = "WHATSAPP_BUSINESS_ACCOUNT_ID",
                required = false,
                hint = "Optional; used to label the account",
            ),
        ),
        note = "Sends and receives as a WhatsApp Business number. Existing personal chats are not " +
            "readable through this API; inbound messages arrive on Prism's webhook.",
        receive = "Webhook (Prism receiver)",
        appUri = "whatsapp://send",
        webUrl = "https://web.whatsapp.com/",
    )

    private val MESSENGER = App(
        id = "messenger",
        label = "Facebook Messenger",
        packageName = "com.facebook.orca",
        portalUrl = "https://developers.facebook.com/apps/",
        keys = listOf(
            Key(
                id = "page_access_token",
                label = "Page access token",
                env = "MESSENGER_PAGE_ACCESS_TOKEN",
                hint = "Messenger → Settings → Access tokens",
                secret = true,
            ),
            Key(
                id = "page_id",
                label = "Page id",
                env = "MESSENGER_PAGE_ID",
                hint = "The Facebook Page Prism speaks for",
            ),
            Key(
                id = "verify_token",
                label = "Webhook verify token",
                env = "MESSENGER_VERIFY_TOKEN",
                required = false,
                hint = "Any secret you also enter in the Meta webhook form",
            ),
            Key(
                id = "app_secret",
                label = "App secret",
                env = "MESSENGER_APP_SECRET",
                required = false,
                hint = "Used to verify X-Hub-Signature-256 on incoming webhooks",
                secret = true,
            ),
        ),
        note = "Sends and receives as a Facebook Page. Messenger's API does not expose a personal " +
            "profile or personal group chats; a page conversation is what this connects.",
        receive = "Polling + webhook",
        appUri = "fb-messenger://",
        webUrl = "https://www.messenger.com/",
    )

    private val SIGNAL = App(
        id = "signal",
        label = "Signal",
        packageName = "org.thoughtcrime.securesms",
        portalUrl = "https://github.com/bbernhard/signal-cli-rest-api",
        keys = listOf(
            Key(
                id = "bridge_url",
                label = "Bridge URL",
                env = "SIGNAL_BRIDGE_URL",
                hint = "http://127.0.0.1:8080",
            ),
            Key(
                id = "phone_number",
                label = "Linked number",
                env = "SIGNAL_PHONE_NUMBER",
                hint = "+15551234567",
            ),
            Key(
                id = "bridge_token",
                label = "Bridge token",
                env = "SIGNAL_BRIDGE_TOKEN",
                required = false,
                hint = "Only if your bridge is behind a token",
                secret = true,
            ),
        ),
        note = "Uses signal-cli-rest-api running as a linked Signal device. Signal publishes no " +
            "first-party server API; this is the maintained client bridge, and it can send to " +
            "groups as well as people.",
        receive = "Bridge polling",
        appUri = "sgnl://",
        webUrl = "https://signal.org/download/",
    )

    /**
     * RCS, carried by Prism instead of handed to another app.
     *
     * Carrier RCS has no third-party send API, so there is nothing here to call. What CAN be built
     * without a provider is a rich thread carried over the mesh that Prism already owns: it is in
     * the same conversation list, it groups, and it is answered in Prism. That is [RcsChat].
     *
     * The note says what it reaches rather than implying it is a carrier account. A switch that
     * silently opened Google Messages was the previous behaviour and is exactly what this replaces.
     */
    private val RCS = App(
        id = RcsChat.APP_ID,
        label = "RCS",
        packageName = "",
        portalUrl = "",
        keys = emptyList(),
        note = "Prism RCS sends over the mesh, sealed with your wallet, so it reaches your own " +
            "Prism devices. Carrier RCS is not available to third-party apps and is not used here.",
        api = true,
        receive = "Prism mesh",
    )

    val ALL: List<App> = listOf(WHATSAPP, MESSENGER, SIGNAL, RCS)

    fun byId(id: String): App? = ALL.firstOrNull { it.id == id }

    /** A transport Prism can put a message on itself, rather than one it only opens. */
    fun isApi(id: String): Boolean = byId(id)?.api == true

    fun key(appId: String, keyId: String): Key? =
        byId(appId)?.keys?.firstOrNull { it.id == keyId }

    /**
     * Reads [key] for [appId], preferring the build's environment.
     *
     * The environment is read on each call rather than cached: a desktop launched with a token
     * should pick it up, and a test that sets an environment cannot restart the JVM. The values
     * are small strings, so this is not a hot path.
     */
    fun value(appId: String, key: Key): String {
        val fromEnvironment = runCatching { System.getenv(key.env) }.getOrNull()?.trim().orEmpty()
        if (fromEnvironment.isNotBlank()) return fromEnvironment
        return PrismSettings.getMessagingApiValue(appId, key.id)
    }

    fun value(appId: String, keyId: String): String {
        val spec = key(appId, keyId) ?: return ""
        return value(appId, spec)
    }

    fun fromEnvironment(appId: String, key: Key): Boolean =
        runCatching { System.getenv(key.env) }.getOrNull()?.isNotBlank() == true

    fun setValue(appId: String, keyId: String, value: String) =
        PrismSettings.setMessagingApiValue(appId, keyId, value)

    /** A one-line state for Settings, naming the first missing field instead of just failing. */
    fun status(appId: String): String {
        val app = byId(appId) ?: return "Unknown service"
        if (!app.api) return "Handled by the system messaging app"
        val missing = app.missing()
        if (missing.isEmpty()) {
            val env = app.keys.count { fromEnvironment(appId, it) }
            return if (env == app.keys.size) "Connected from the environment"
            else if (env > 0) "Connected · ${env} key(s) from environment"
            else "Connected"
        }
        return "Needs " + missing.joinToString(", ") { it.label.lowercase() }
    }

    /** Every provider whose required credentials are present. */
    fun configured(): List<App> = ALL.filter { it.api && it.configured() }

    /** The environment names the build looks for, for a compact settings hint. */
    fun environmentNames(appId: String): List<String> =
        byId(appId)?.keys?.map { it.env }.orEmpty()
}
