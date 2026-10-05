package com.prism.launcher.trusted

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.nora.IosUi
import java.io.File

/**
 * The trusted-device list, and the flow for adding one.
 *
 * ## The flow, and why it has four steps rather than one
 *
 * Add → pick a device from the mesh → choose what to share → the OTHER device asks its own user. Each step
 * exists because it answers a question the previous one cannot:
 *
 * 1. **Which device.** The mesh lists everything on the network, including strangers' phones. Trusting one
 *    is nothing like buying compute from it, so the choice is explicit.
 * 2. **What to share.** Kinds are individual because they are wildly different in sensitivity — a clipboard
 *    is not a text message history. An all-or-nothing switch would push people into agreeing to more than
 *    they meant.
 * 3. **Their consent.** The far side prompts its own user. This is the step that makes the feature safe:
 *    without it, one device could decide that another will store its owner's private data.
 * 4. **They choose too.** The receiver may accept a subset of what was offered.
 *
 * ## Why nothing is shared until step 4 completes
 *
 * [TrustedDevices.recipientsFor] only returns confirmed devices, so an offer that has been sent and not
 * answered shares nothing. A pairing left half-finished is inert rather than leaking, which is the failure
 * mode to want.
 */
class TrustedDevicesActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrustedDevices.install(File(filesDir, "trust"))

        val pad = IosUi.dp(this, 16f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Trusted devices"
            textSize = 24f
            setTextColor(IosUi.label(this@TrustedDevicesActivity))
        })

        column.addView(TextView(this).apply {
            text = "Devices you have agreed to share private data with. This is a much stronger thing " +
                "than being a peer on the meshnet: a peer can buy your spare compute without ever " +
                "reading anything, because nothing readable crosses. A trusted device receives your " +
                "texts, your browsing, your clipboard and your apps.\n\n" +
                "Both sides have to agree. You choose what to offer; they choose what to accept, and " +
                "nothing moves until they have."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@TrustedDevicesActivity))
            setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 10f), 0, 0)
        })

        column.addView(IosUi.sectionHeader(this, "PAIRED"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
            setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 10f), 0, 0)
        }
        column.addView(status)

        column.addView(IosUi.filledButton(this, "Add a trusted device").apply {
            setOnClickListener { pickDevice() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@TrustedDevicesActivity, 14f) })

        column.addView(IosUi.sectionFooter(
            this,
            "Everything shared is encrypted with a key derived from your wallet's recovery phrase, so " +
                "other devices on the meshnet carry the packets without being able to read them. That " +
                "means a trusted device also needs the SAME recovery phrase restored on it — otherwise " +
                "it receives your data and cannot open it, which the list will tell you."
        ))

        column.addView(IosUi.sectionHeader(this, "THIS DEVICE"))
        column.addView(TextView(this).apply {
            text = "${TrustedDevices.localName()}\n" +
                "Identity: ${TrustedDevices.localFingerprint().take(16)}…\n" +
                "Mesh address: ${MeshComputeRegistry.localIp().ifBlank { "not on a mesh" }}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(this@TrustedDevicesActivity))
        })
        column.addView(IosUi.sectionFooter(
            this,
            "The identity is derived from your recovery phrase and this device's name, not from your " +
                "wallet address — an address is public on the chain, and using it here would link your " +
                "devices to your balance for anyone watching the mesh."
        ))

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(IosUi.groupedBackground(this@TrustedDevicesActivity))
                addView(
                    ScrollView(this@TrustedDevicesActivity).apply { addView(column) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Offers that arrived while this screen was closed are answered here rather than only through the
        // app-wide prompt: somebody who has just opened this page is already thinking about pairing.
        TrustedDevices.pendingOffers().firstOrNull()?.let { TrustedDeviceOffers.prompt(this, it) { refresh() } }
    }

    private fun refresh() {
        val devices = TrustedDevices.all()
        list.removeAllViews()

        if (devices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No trusted devices yet."
                textSize = 13f
                setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
                setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 8f), 0, 0)
            })
        } else {
            devices.sortedByDescending { it.lastSeen }.forEach { list.addView(deviceCard(it)) }
        }

        // Apps a trusted device has handed over. Listed here rather than silently staged: an APK that
        // arrived over the network and is waiting to be installed is something the user should be told
        // about, and this is the screen where they agreed to receive it.
        val waiting = TrustedAppTransfer.received(this)
        if (waiting.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "Apps received, waiting for you"
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
                setPadding(
                    IosUi.dp(this@TrustedDevicesActivity, 4f),
                    IosUi.dp(this@TrustedDevicesActivity, 18f),
                    0,
                    IosUi.dp(this@TrustedDevicesActivity, 4f),
                )
            })
            val card = IosUi.card(this)
            waiting.forEach { pkg ->
                val files = TrustedAppTransfer.stagingDir(this, pkg).listFiles().orEmpty()
                    .filter { it.extension.equals("apk", true) }
                card.addView(TextView(this).apply {
                    text = pkg + System.lineSeparator() + files.size + " file(s), " +
                        (files.sumOf { it.length() } shr 20) + " MB"
                    textSize = 14f
                    setTextColor(IosUi.label(this@TrustedDevicesActivity))
                    setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 6f), 0, 0)
                })
            }
            card.addView(TextView(this).apply {
                text = "Nothing installs by itself. Open Virtualization to put one of these through " +
                    "Prism, which is where Play Protect and the sandbox rules apply."
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
                setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 8f), 0, 0)
            })
            list.addView(card)
        }

        val offers = TrustedDevices.pendingOffers()
        status.text = buildString {
            append(TrustedDevices.summary())
            if (!com.prism.launcher.cloud.CloudVault.isReady()) {
                append("\n\nNo wallet on this device, so nothing can be encrypted or decrypted yet. " +
                    "Pairing can be set up now; data will not move until a wallet exists.")
            }
            if (offers.isNotEmpty()) {
                append("\n\n${offers.size} device(s) have offered to share with you.")
            }
        }
    }

    private fun deviceCard(device: TrustedDevices.Trust): View {
        val card = IosUi.card(this)

        card.addView(TextView(this).apply {
            text = (if (device.online) "● " else "○ ") + device.name
            textSize = 15f
            setTextColor(
                if (device.online) IosUi.accent(this@TrustedDevicesActivity)
                else IosUi.label(this@TrustedDevicesActivity)
            )
        })
        card.addView(TextView(this).apply {
            text = device.describe() + " · " + device.lastIp.ifBlank { "no address" }
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@TrustedDevicesActivity))
            setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 4f), 0, 0)
        })

        if (device.confirmed) {
            TrustedDevices.Kind.entries.forEach { kind ->
                val sending = kind in device.outgoing
                val receiving = kind in device.incoming
                if (!sending && !receiving) return@forEach
                card.addView(TextView(this).apply {
                    text = "  ${kind.label}: " + listOfNotNull(
                        if (sending) "sending" else null,
                        if (receiving) "receiving" else null,
                    ).joinToString(" and ")
                    textSize = 11f
                    setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
                })
            }
        }

        card.isClickable = true
        card.setOnClickListener { editSharing(device) }
        card.setOnLongClickListener {
            PrismDialogFactory.show(
                this,
                "Stop trusting ${device.name}?",
                "Nothing further is sent to it or accepted from it. Anything it has already received " +
                    "stays on that device — revoking cannot reach back and delete it.\n\n" +
                    "This works even if the device is switched off, which is when you most want it.",
                positiveText = "Stop trusting",
                onPositive = {
                    TrustedDevices.revoke(device.fingerprint)
                    refresh()
                },
            )
            true
        }
        return card
    }

    /**
     * Step 1: which device.
     *
     * Lists what the mesh can currently see, minus anything already paired. A device that is already in the
     * list would otherwise appear twice and a second offer to it would be a second row.
     */
    private fun pickDevice() {
        // ON THE OVERLAY, NOT MERELY ON THE MESH. isOnMesh() is true whenever the gossip listener is
        // running, which it is on bare Wi-Fi -- so it was never the right question for pairing. See
        // MeshMembership: the test is an address in the mesh subnet, on both ends.
        if (!com.prism.core.MeshMembership.isOnOverlay()) {
            PrismDialogFactory.show(
                this,
                "Not on a meshnet",
                com.prism.core.MeshMembership.explain(),
                positiveText = "OK",
                negativeText = null,
            )
            return
        }

        val paired = TrustedDevices.all().map { it.lastIp }.toSet()
        val market = runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())
        // Filtered to the overlay as well as gated in TrustedDevices.offer. Both, because a picker that
        // listed a device and then refused to pair with it would be worse than not listing it.
        val candidates = com.prism.core.MeshMembership.overlayPeers(PrismMeshService.activePeerIps())
            .filterNot { it in paired }
            .map { ip ->
                val peer = market.firstOrNull { it.peerIp == ip }
                Triple(ip, peer?.deviceName ?: ip, peer?.platformLabel() ?: "unknown device")
            }

        if (candidates.isEmpty()) {
            PrismDialogFactory.show(
                this,
                "No other devices",
                "Nothing else is on this meshnet right now, or everything on it is already paired. A " +
                    "device appears here once it is running Prism on the same mesh.",
                positiveText = "OK",
                negativeText = null,
            )
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(this@TrustedDevicesActivity, 4f)
            setPadding(p, p, p, p)
        }
        candidates.forEach { (ip, name, platform) ->
            column.addView(TextView(this).apply {
                text = "$name\n$platform · $ip"
                textSize = 14f
                setTextColor(IosUi.label(this@TrustedDevicesActivity))
                setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 12f), 0,
                    IosUi.dp(this@TrustedDevicesActivity, 12f))
                isClickable = true
                setOnClickListener { chooseKinds(ip, name, platform) }
            })
            column.addView(IosUi.hairline(this))
        }

        PrismDialogFactory.show(
            this,
            "Devices on the meshnet",
            message = "",
            customView = ScrollView(this).apply { addView(column) },
            positiveText = null,
            negativeText = "Cancel",
        )
    }

    /** Step 2: what to share with them. */
    private fun chooseKinds(peerIp: String, peerName: String, peerPlatform: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(this@TrustedDevicesActivity, 4f)
            setPadding(p, p, p, p)
        }

        column.addView(TextView(this).apply {
            text = "What should $peerName automatically receive from this device?"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@TrustedDevicesActivity))
        })

        val boxes = TrustedDevices.Kind.entries.map { kind ->
            val box = CheckBox(this).apply {
                text = kind.label
                textSize = 15f
                setTextColor(IosUi.label(this@TrustedDevicesActivity))
            }
            column.addView(box)
            column.addView(TextView(this).apply {
                text = "    ${kind.detail}"
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
            })
            kind to box
        }

        column.addView(TextView(this).apply {
            text = "\n$peerName will be asked whether it wants each of these. Whatever it accepts is " +
                "then shared BOTH ways — this phone sends it, and takes the same back. Nothing moves " +
                "until somebody on that device says yes."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
        })

        PrismDialogFactory.show(
            this,
            "Share with $peerName",
            message = "",
            customView = ScrollView(this).apply { addView(column) },
            positiveText = "Send the request",
            onPositive = {
                val chosen = boxes.filter { it.second.isChecked }.map { it.first }.toSet()
                if (chosen.isEmpty()) {
                    toast("Nothing selected, so nothing was offered.")
                    return@show
                }
                val code = TrustedDevices.offer(peerIp, peerName, peerPlatform, chosen)
                if (code == null) {
                    toast("Could not reach $peerName just now; the offer will be retried.")
                } else {
                    showPairingCode(code, peerName)
                }
                refresh()
            },
        )
    }

    /** Changing what an existing pairing shares. Re-offers, because the far side has to agree again. */
    private fun editSharing(device: TrustedDevices.Trust) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(this@TrustedDevicesActivity, 4f)
            setPadding(p, p, p, p)
        }
        val boxes = TrustedDevices.Kind.entries.map { kind ->
            val box = CheckBox(this).apply {
                text = kind.label
                textSize = 15f
                isChecked = kind in device.outgoing
                setTextColor(IosUi.label(this@TrustedDevicesActivity))
            }
            column.addView(box)
            kind to box
        }
        column.addView(TextView(this).apply {
            text = "\nAdding something re-asks ${device.name}: they have to agree to receive it, and " +
                "a change here does not grant itself."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
        })

        PrismDialogFactory.show(
            this,
            "Sharing with ${device.name}",
            message = "",
            customView = column,
            positiveText = "Save",
            onPositive = {
                val chosen = boxes.filter { it.second.isChecked }.map { it.first }.toSet()
                if (chosen.isEmpty()) {
                    // Nothing outgoing is not the same as untrusted: they may still be sending to us.
                    TrustedDevices.upsert(device.copy(outgoing = emptySet()))
                } else if (device.lastIp.isNotBlank()) {
                    TrustedDevices.offer(device.lastIp, device.name, device.platform, chosen)
                        ?.let { showPairingCode(it, device.name) }
                }
                refresh()
            },
        )
    }

    /**
     * Shows the code the other device has to be told.
     *
     * BIG AND MONOSPACED, because it is read off this screen and typed on another one, and the alphabet
     * contains characters that look alike at a normal size -- l against 1, O against 0.
     *
     * Not dismissible by tapping away: the code exists for exactly as long as this pairing is waiting,
     * and somebody who closed it by accident would have nothing to type and no way back to it.
     */
    private fun showPairingCode(code: String, peerName: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val p = IosUi.dp(this@TrustedDevicesActivity, 10f)
            setPadding(p, p, p, p)
        }

        column.addView(TextView(this).apply {
            text = code
            textSize = 34f
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.18f
            setTextColor(IosUi.accent(this@TrustedDevicesActivity))
            gravity = android.view.Gravity.CENTER
            setPadding(0, IosUi.dp(this@TrustedDevicesActivity, 6f), 0, IosUi.dp(this@TrustedDevicesActivity, 10f))
        })

        column.addView(TextView(this).apply {
            text = "Nothing is shared until $peerName asks for this code and somebody types it " +
                "correctly.\n\nIt is never sent over the network, so answering it proves whoever is " +
                "accepting can see this screen — which is the one thing an offer on its own cannot " +
                "prove. It is also the key your recovery phrase travels under, if that device has no " +
                "wallet yet."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@TrustedDevicesActivity))
        })

        PrismDialogFactory.show(
            this,
            "Type this on $peerName",
            message = "",
            customView = column,
            positiveText = "Done",
            onPositive = {},
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
