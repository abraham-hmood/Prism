package com.prism.launcher.files

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.prism.launcher.DiagnosticsActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.media.DocumentImport
import com.prism.launcher.media.ImportedText
import com.prism.launcher.nora.IosUi
import com.prism.launcher.storage.BurnOs
import com.prism.launcher.storage.FileSystems
import com.prism.launcher.storage.FormatSpec
import com.prism.launcher.storage.PartitionPlan
import com.prism.launcher.storage.PlannedPartition
import com.prism.launcher.storage.WifiConfigStore
import com.prism.launcher.storage.WifiCredentials
import com.prism.launcher.storage.formatBytes
import java.io.File

/**
 * Every storage dialog, in the same iOS design language as the rest of Prism's pages.
 *
 * ## One sheet, one decision
 *
 * The long-press menu, the partition editor and the burn setup are separate sheets rather than one
 * crowded dialog. Formatting is destructive and irreversible, so it gets a screen where the
 * partition layout, the file system and the label are visible together before the button is pressed;
 * a mis-tap in a list of rows must not be able to erase a card.
 *
 * ## Nothing pretends
 *
 * When the device has no root, the Format sheet says so before showing the controls and disables the
 * button that would fail, instead of starting a progress bar that ends in "failed". When a file
 * system's mkfs tool is missing, the reason is printed on the sheet. Every operation that does run
 * opens a result sheet with the commands it executed and a link to System Diagnostics.
 */
object StorageSheets {

    private val main = Handler(Looper.getMainLooper())

    // ── Volume menu ────────────────────────────────────────────────────────

    /**
     * The long-press menu for an SD card or USB drive.
     *
     * Format, Burn and Unmount are the actions that only make sense for removable media; Open and
     * Add to Desktop are there so this is also the place for the ordinary things.
     */
    fun showVolumeMenu(
        context: Context,
        volume: RemovableVolume,
        onOpen: () -> Unit,
        onChanged: () -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        val sheet = Sheet(context, volume.displayName, "${volume.kind.title} · ${volume.capacityLine()}")
        // The file grid suppresses the tap that follows a long press until its menu is gone, so it
        // needs to know when this sheet closes.
        sheet.dialog.setOnDismissListener { onDismiss() }

        if (volume.isMounted) {
            sheet.body.addView(
                sheet.row("Open", "Browse the files on this drive") { sheet.dismiss(); onOpen() },
            )
            sheet.body.addView(sheet.divider())
        }
        sheet.body.addView(
            sheet.row("Add to Desktop", "Put a shortcut on a desktop page") {
                sheet.dismiss()
                addToDesktop(context, volume)
            },
        )
        sheet.body.addView(sheet.divider())
        sheet.body.addView(
            sheet.row("Format…", "File system, label and partition layout") {
                sheet.dismiss(); showFormat(context, volume, onChanged)
            },
        )
        sheet.body.addView(sheet.divider())
        sheet.body.addView(
            sheet.row("Burn image…", "Write an ISO or IMG to this drive") {
                sheet.dismiss(); showBurn(context, volume, onChanged)
            },
        )
        sheet.body.addView(sheet.divider())
        sheet.body.addView(
            sheet.row(
                "Unmount",
                if (volume.isMounted) "Eject safely before removing the drive"
                else "This drive is already unmounted",
                tint = IosUi.destructive(context),
                enabled = volume.isMounted,
            ) { sheet.dismiss(); confirmUnmount(context, volume, onChanged) },
        )
        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.show()
    }

    private fun addToDesktop(context: Context, volume: RemovableVolume) {
        val root = volume.root ?: return
        (context as? com.prism.launcher.LauncherActivity)?.addToDesktop(
            com.prism.launcher.DesktopItem.DirectoryRef(root.absolutePath, volume.displayName),
        )
    }

    private fun confirmUnmount(context: Context, volume: RemovableVolume, onChanged: () -> Unit) {
        val sheet = Sheet(context, "Unmount ${volume.displayName}?", "No files are deleted.")
        sheet.body.addView(
            sheet.paragraph(
                "Android will detach the drive. Apps reading from it lose access, so save anything " +
                    "open before ejecting.",
            ),
        )
        sheet.addButton("Unmount", filled = true, tint = IosUi.destructive(context)) {
            sheet.dismiss()
            runOperation(
                context = context,
                title = "Unmounting",
                subtitle = volume.displayName,
                onDone = { result -> onChanged(); showResult(context, result) },
            ) {
                StorageAdmin.unmount(volume, StorageAdmin.privilege(context, refresh = true))
            }
        }
        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.show()
    }

    // ── Format ─────────────────────────────────────────────────────────────

    fun showFormat(context: Context, volume: RemovableVolume, onChanged: () -> Unit) {
        FormatSheet(context, volume, onChanged).show()
    }

    /**
     * The partition editor and formatter.
     *
     * State lives here rather than in view tags because the same plan is read by the partition bar,
     * the +/- buttons and the Format button; a stale copy in any one of them would format something
     * other than what the user is looking at.
     */
    private class FormatSheet(
        private val context: Context,
        private val volume: RemovableVolume,
        private val onChanged: () -> Unit,
    ) {
        private val sheet = Sheet(context, "Format", "${volume.displayName} · ${volume.capacityLine()}")
        // Painted from the cached answer, then corrected by [probeAccess] on a worker thread.
        private var status = StorageAdmin.cachedStatus()
        private var tools: Set<String> = emptySet()
        private var toolsProbed = false
        private lateinit var accessNote: TextView

        private var spec = FormatSpec(fileSystem = FileSystems.FAT32.id, label = suggestedLabel())
        private var plan = PartitionPlan.wholeVolume(volume.totalBytes, spec)
        private var selected = 0
        private var syncingLabel = false

        private lateinit var mapView: PartitionMapView
        private lateinit var filesystemRow: LinearLayout
        private lateinit var allocationRow: LinearLayout
        private lateinit var labelField: EditText
        private lateinit var plusButton: TextView
        private lateinit var minusButton: TextView
        private lateinit var selectionLabel: TextView
        private lateinit var warning: TextView
        private lateinit var formatButton: TextView

        fun show() {
            build()
            sheet.show()
            probeAccess()
        }

        private fun pad(value: Int): Int = IosUi.dp(context, value.toFloat())

        private fun build() {
            if (volume.totalBytes <= 0) {
                sheet.body.addView(
                    sheet.warning(
                        "The size of this drive could not be read, so a partition layout cannot be " +
                            "built. Insert the drive so Android mounts it, then open Format again.",
                    ),
                )
                sheet.addButton("Close", filled = false) { sheet.dismiss() }
                return
            }

            accessNote = sheet.note("Checking whether Prism can write to this drive…")
            sheet.body.addView(accessNote)

            warning = sheet.warning("").apply { visibility = View.GONE }
            sheet.body.addView(warning)

            sheet.body.addView(sheet.sectionHeader("File system"))
            val fsCard = sheet.card()
            filesystemRow = sheet.row(
                FileSystems.byId(spec.fileSystem).displayName,
                "Choose what the drive will be formatted as",
            ) { pickFileSystem() }
            fsCard.addView(filesystemRow)
            fsCard.addView(sheet.divider())
            allocationRow = sheet.row(allocationText(), "Cluster or block size") {
                pickAllocationUnit()
            }
            fsCard.addView(allocationRow)
            sheet.body.addView(fsCard)
            sheet.body.addView(sheet.footer("FAT32 reads everywhere but cannot hold a file over 4 GB."))

            sheet.body.addView(sheet.sectionHeader("Volume label"))
            labelField = sheet.textField("Label", spec.label) { value ->
                if (syncingLabel) return@textField
                spec = spec.copy(label = value)
                if (selected != PartitionMapView.FREE) {
                    plan = plan.rename(
                        selected,
                        FileSystems.sanitizeLabel(FileSystems.byId(spec.fileSystem), value),
                    )
                }
            }
            sheet.body.addView(sheet.card().apply { addView(labelField) })
            sheet.body.addView(
                sheet.footer(
                    "Up to ${FileSystems.byId(spec.fileSystem).maxLabel} characters for this file system.",
                ),
            )

            sheet.body.addView(sheet.sectionHeader("Options"))
            val options = sheet.card()
            options.addView(
                sheet.switchRow(
                    "Fast format",
                    "Write metadata only. Off means a full surface check, which takes much longer.",
                    initial = spec.quick,
                ) { checked -> spec = spec.copy(quick = checked) }.view,
            )
            sheet.body.addView(options)

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad(4), pad(14), pad(4), pad(4))
            }
            header.addView(TextView(context).apply {
                text = if (IosUi.mode(context).typography.uppercaseHeaders) "PARTITIONS" else "Partitions"
                textSize = 13f
                letterSpacing = 0.06f
                setTextColor(IosUi.secondaryLabel(context))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            plusButton = sheet.roundButton("+") { onPlus() }
            minusButton = sheet.roundButton("−") { onMinus() }
            buttons.addView(plusButton)
            buttons.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, pad(6))
            })
            buttons.addView(minusButton)
            header.addView(buttons)
            sheet.body.addView(header)

            val partitionsCard = sheet.card()
            mapView = PartitionMapView(context).apply {
                totalBytes = volume.totalBytes
                this.plan = this@FormatSheet.plan
                selected = this@FormatSheet.selected
                onSelect = { index ->
                    this@FormatSheet.selected = index
                    syncSelection()
                }
                onPlanChanged = { updated ->
                    this@FormatSheet.plan = updated
                    this.plan = updated
                    syncSelection()
                }
            }
            partitionsCard.addView(mapView)
            selectionLabel = TextView(context).apply {
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(pad(4), pad(8), pad(4), pad(2))
            }
            partitionsCard.addView(selectionLabel)
            sheet.body.addView(partitionsCard)
            sheet.body.addView(
                sheet.footer(
                    "Tap a partition to select it, or the hatched area for free space. Drag a " +
                        "boundary to resize. + adds a partition on free space or changes the " +
                        "selected partition's type; − deletes the selected partition.",
                ),
            )

            syncSelection()

            sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
            formatButton = sheet.addButton("Format ${volume.displayName}", filled = true) {
                applyPlan()
            }
            formatButton.isEnabled = status.canFormat
            formatButton.alpha = if (status.canFormat) 1f else 0.4f
        }

        // ── File system and options ───────────────────────────────────────

        private fun pickFileSystem() {
            val choices = FileSystems.all.map { fs ->
                val missing = toolsProbed && fs.mkfsBinary != null && fs.mkfsBinary !in tools
                Choice(
                    title = fs.displayName,
                    subtitle = if (missing) "${fs.note} (${fs.mkfsBinary} is not on this device)"
                    else fs.note.ifBlank { "Supported by ${fs.mkfsBinary}" },
                    enabled = !missing,
                )
            }
            val current = FileSystems.all.indexOfFirst { it.id == spec.fileSystem }
            showPicker(context, "File system", choices, current) { index ->
                val chosen = FileSystems.all[index]
                spec = spec.copy(fileSystem = chosen.id, allocationUnit = 0)
                // The dropdown is the plan's default: every partition follows it until the +
                // button overrides one, which is the only place a per-partition type can be set.
                plan = plan.copy(
                    partitions = plan.partitions.map { it.copy(fileSystem = chosen.id) },
                )
                mapView.plan = plan
                refreshRow(filesystemRow, chosen.displayName, "Choose what the drive will be formatted as")
                refreshRow(allocationRow, allocationText(), "Cluster or block size")
                updateToolWarning()
                syncSelection()
            }
        }

        private fun pickAllocationUnit() {
            val fs = FileSystems.byId(spec.fileSystem)
            val units = FileSystems.allocationUnitChoices(fs)
            val choices = units.map { unit ->
                Choice(
                    title = if (unit == 0) "Default" else formatBytes(unit.toLong()),
                    subtitle = if (unit == 0) "Let ${fs.mkfsBinary} choose" else "Allocation unit",
                )
            }
            val current = units.indexOf(spec.allocationUnit).coerceAtLeast(0)
            showPicker(context, "Allocation unit", choices, current) { index ->
                spec = spec.copy(allocationUnit = units[index])
                refreshRow(allocationRow, allocationText(), "Cluster or block size")
            }
        }

        private fun allocationText(): String =
            if (spec.allocationUnit == 0) "Default" else formatBytes(spec.allocationUnit.toLong())

        private fun updateToolWarning() {
            if (!toolsProbed || !::formatButton.isInitialized) return
            val fs = FileSystems.byId(spec.fileSystem)
            val binary = fs.mkfsBinary
            val missing = binary != null && binary !in tools
            val partitionToolsMissing = plan.partitions.size > 1 && "parted" !in tools
            when {
                missing -> {
                    warning.visibility = View.VISIBLE
                    warning.text = "$binary is not installed on this device, so ${fs.displayName} " +
                        "cannot be created here. Install the package that provides it, or choose " +
                        "another file system."
                }
                partitionToolsMissing -> {
                    warning.visibility = View.VISIBLE
                    warning.text = "Changing the partition table needs `parted`, which this device " +
                        "does not have. A single full-drive partition can still be formatted."
                }
                else -> warning.visibility = View.GONE
            }
            val enabled = status.canFormat && !missing && !partitionToolsMissing
            formatButton.isEnabled = enabled
            formatButton.alpha = if (enabled) 1f else 0.4f
        }

        /**
         * Probes privilege and mkfs availability together, off the UI thread.
         *
         * The privilege check runs `su -c id`, which blocks until the grant prompt is answered. On
         * the UI thread that is an ANR waiting to happen, and it is the first thing this sheet needs,
         * so it happens here and the widgets are corrected when the answer arrives.
         */
        private fun probeAccess() {
            Thread {
                val fresh = runCatching { StorageAdmin.privilege(context, refresh = true) }
                    .getOrDefault(StorageAdmin.cachedStatus())
                val binaries = FileSystems.all.mapNotNull { it.mkfsBinary }.distinct() +
                    listOf("parted", "dd")
                val found = runCatching { StorageAdmin.availableTools(fresh, binaries) }
                    .getOrDefault(emptySet())
                main.post {
                    status = fresh
                    tools = found
                    toolsProbed = true
                    renderAccessNote()
                    updateToolWarning()
                    PrismLogger.logInfo(
                        "Storage",
                        "Privilege ${fresh.privilege}; format tools found: ${found.sorted()}; " +
                            "missing: ${binaries.filterNot { it in found }.sorted()}",
                    )
                }
            }.apply { isDaemon = true }.start()
        }

        private fun renderAccessNote() {
            if (!::accessNote.isInitialized) return
            if (status.canFormat) {
                accessNote.text = "Root shell available -- Prism can rewrite this drive's partitions."
                accessNote.setTextColor(IosUi.secondaryLabel(context))
                accessNote.background = GradientDrawable().apply {
                    setColor(IosUi.fill(context))
                    cornerRadius = pad(12).toFloat()
                }
            } else {
                accessNote.text =
                    "This install cannot format removable media. Android gives an ordinary app no " +
                        "way to write a partition table or run mkfs; this device reports: " +
                        "${status.detail}.\n\nGrant Prism root and reopen this sheet. The layout " +
                        "below can still be planned now."
                accessNote.setTextColor(IosUi.destructive(context))
                accessNote.background = GradientDrawable().apply {
                    setColor(blend(IosUi.destructive(context), 0x1F))
                    cornerRadius = pad(12).toFloat()
                    setStroke(pad(1), blend(IosUi.destructive(context), 0x55))
                }
            }
        }

        // ── Partitions ────────────────────────────────────────────────────

        private fun onPlus() {
            if (selected == PartitionMapView.FREE) {
                addPartition()
                return
            }
            val index = selected
            val choices = FileSystems.all.map { fs ->
                val missing = toolsProbed && fs.mkfsBinary != null && fs.mkfsBinary !in tools
                Choice(fs.displayName, fs.note.ifBlank { fs.id }, enabled = !missing)
            }
            val current = FileSystems.all.indexOfFirst { it.id == plan.partitions[index].fileSystem }
            showPicker(context, "Reformat partition ${index + 1}", choices, current) { choice ->
                val chosen = FileSystems.all[choice]
                plan = plan.reformat(index, chosen.id, plan.partitions[index].label)
                mapView.plan = plan
                syncSelection()
            }
        }

        private fun onMinus() {
            if (selected == PartitionMapView.FREE) return
            if (plan.partitions.size <= 1) {
                sheet.flash("A drive needs at least one partition. Add another before deleting this one.")
                return
            }
            plan = plan.remove(selected)
            mapView.plan = plan
            selected = selected.coerceAtMost(plan.partitions.lastIndex)
            mapView.selected = selected
            syncSelection()
        }

        private fun addPartition() {
            val free = plan.freeBytes(volume.totalBytes)
            if (free < PartitionPlan.MINIMUM_PARTITION_BYTES) {
                sheet.flash("No free space is left. Shrink a partition by dragging its edge first.")
                return
            }
            val dialog = Sheet(context, "Add partition", "${formatBytes(free)} available")
            var chosenFs = spec.fileSystem
            val sizeField = dialog.textField("Size in MB", (free / (1024 * 1024)).toString())
            val fsRow = dialog.row(
                FileSystems.byId(chosenFs).displayName,
                "What the new partition will be formatted as",
            )
            fsRow.isClickable = true
            fsRow.setOnClickListener {
                val choices = FileSystems.all.map { Choice(it.displayName, it.note.ifBlank { it.id }) }
                val current = FileSystems.all.indexOfFirst { it.id == chosenFs }
                showPicker(context, "File system", choices, current) { index ->
                    chosenFs = FileSystems.all[index].id
                    refreshRow(fsRow, FileSystems.all[index].displayName, "What the new partition will be formatted as")
                }
            }
            dialog.body.addView(sizeField)
            dialog.body.addView(fsRow)
            dialog.addButton("Add", filled = true) {
                val megabytes = sizeField.text.toString().toLongOrNull() ?: 0
                if (megabytes < 16) {
                    dialog.flash("A partition must be at least 16 MB.")
                    return@addButton
                }
                val bytes = (megabytes * 1024 * 1024).coerceAtMost(free)
                plan = plan.copy(
                    partitions = plan.partitions + PlannedPartition(
                        sizeBytes = bytes,
                        fileSystem = chosenFs,
                        label = FileSystems.sanitizeLabel(FileSystems.byId(chosenFs), spec.label),
                    ),
                )
                mapView.plan = plan
                selected = plan.partitions.lastIndex
                mapView.selected = selected
                dialog.dismiss()
                syncSelection()
            }
            dialog.addButton("Cancel", filled = false) { dialog.dismiss() }
            dialog.show()
        }

        /** Rewrites the selection-dependent text and the +/- states. */
        private fun syncSelection() {
            if (!::selectionLabel.isInitialized) return
            selectionLabel.text = if (selected == PartitionMapView.FREE) {
                "Free space · ${formatBytes(plan.freeBytes(volume.totalBytes))}. " +
                    "+ adds a partition here; − does nothing."
            } else {
                val partition = plan.partitions.getOrNull(selected) ?: return
                "Partition ${selected + 1} · ${partition.fs.displayName} · " +
                    "${formatBytes(partition.sizeBytes)}. + changes its type; − deletes it."
            }
            minusButton.isEnabled = selected != PartitionMapView.FREE && plan.partitions.size > 1
            minusButton.alpha = if (minusButton.isEnabled) 1f else 0.35f
            plusButton.isEnabled = selected == PartitionMapView.FREE ||
                plan.partitions.getOrNull(selected) != null
            syncingLabel = true
            labelField.setText(
                if (selected == PartitionMapView.FREE) spec.label
                else plan.partitions.getOrNull(selected)?.label.orEmpty(),
            )
            labelField.setSelection(labelField.text.length)
            syncingLabel = false
            updateToolWarning()
        }

        // ── Apply ─────────────────────────────────────────────────────────

        private fun applyPlan() {
            val problem = plan.problem(volume.totalBytes)
            if (problem != null) {
                sheet.flash(problem)
                return
            }
            val snapshot = plan
            val activeSpec = spec
            sheet.dismiss()
            runOperation(
                context = context,
                title = "Formatting",
                subtitle = "${volume.displayName} · ${FileSystems.byId(activeSpec.fileSystem).displayName}",
                onDone = { result -> onChanged(); showResult(context, result) },
            ) { report ->
                StorageAdmin.format(context, volume, snapshot, activeSpec, status) { text, percent ->
                    report(text, percent)
                }
            }
        }

        private fun suggestedLabel(): String = FileSystems.sanitizeLabel(
            FileSystems.FAT32,
            volume.displayName.replace(Regex("[^A-Za-z0-9 _-]"), " ").trim().ifBlank { "PRISM" },
        )
    }

    // ── Burn ───────────────────────────────────────────────────────────────

    fun showBurn(context: Context, volume: RemovableVolume, onChanged: () -> Unit) {
        BurnSheet(context, volume, onChanged).show()
    }

    private class BurnSheet(
        private val context: Context,
        private val volume: RemovableVolume,
        private val onChanged: () -> Unit,
    ) {
        private val sheet = Sheet(context, "Burn image", "${volume.displayName} · ${volume.capacityLine()}")
        private var status = StorageAdmin.cachedStatus()
        private val picker = DocumentImport(context)
        private lateinit var accessNote: TextView

        private var image: File? = null
        private var imageName: String = ""
        private var copying = false
        private var os = BurnOs.LINUX
        /** Cached because reading it runs a root shell, which must not happen on the UI thread. */
        private var savedNetworks: List<WifiConfigStore.SavedWifi> = emptyList()

        private lateinit var imageRow: LinearLayout
        private lateinit var osRow: LinearLayout
        private lateinit var wifiSwitch: SwitchRow
        private lateinit var wifiFields: LinearLayout
        private lateinit var ssidField: EditText
        private lateinit var passwordField: EditText
        private lateinit var wifiNote: TextView
        private lateinit var burnButton: TextView
        private lateinit var labelField: EditText

        fun show() {
            build()
            sheet.show()
            probeAccess()
        }

        private fun build() {
            accessNote = sheet.note("Checking how this install can write to the drive…")
            sheet.body.addView(accessNote)

            sheet.body.addView(sheet.sectionHeader("Image"))
            val imageCard = sheet.card()
            imageRow = sheet.row("Choose ISO or IMG", "Open the system file picker") {
                if (copying) return@row
                picker.open(arrayOf("*/*")) { uris -> uris.firstOrNull()?.let { copyImage(it) } }
            }
            imageCard.addView(imageRow)
            sheet.body.addView(imageCard)
            sheet.body.addView(
                sheet.footer(
                    "ISO9660 images can be copied without root and can carry the Wi-Fi files. A raw " +
                        ".img needs root, because it is written sector by sector.",
                ),
            )

            sheet.body.addView(sheet.sectionHeader("Volume label"))
            labelField = sheet.textField("New label", suggestedLabel())
            sheet.body.addView(sheet.card().apply { addView(labelField) })
            sheet.body.addView(
                sheet.footer("Applied after the write when this device can relabel the file system."),
            )

            sheet.body.addView(sheet.sectionHeader("Operating system"))
            val osCard = sheet.card()
            osRow = sheet.row(os.displayName, os.detail) { pickOs() }
            osCard.addView(osRow)
            sheet.body.addView(osCard)

            sheet.body.addView(sheet.sectionHeader("Wireless settings"))
            wifiFields = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val currentSsid = StorageAdmin.currentSsid(context)
            ssidField = sheet.textField("Network name (SSID)", currentSsid)
            passwordField = sheet.textField("Password", "", password = true)
            wifiNote = TextView(context).apply {
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(pad(2), pad(6), pad(2), pad(4))
                text = if (status.privilege == StorageAdmin.Privilege.ROOT) {
                    "Prism can read the saved password from this device."
                } else {
                    "Root is needed to read the saved password, so type it here. Android does not " +
                        "expose Wi-Fi passwords to apps."
                }
            }
            wifiFields.addView(ssidField)
            wifiFields.addView(passwordField)
            wifiFields.addView(wifiNote)
            wifiFields.addView(
                sheet.row("Use saved password", "Read it from this device's Wi-Fi store") {
                    readSavedPassword()
                },
            )

            wifiSwitch = sheet.switchRow(
                "Copy Wi-Fi settings",
                "Write this device's network and password to the drive",
                initial = false,
            ) { checked ->
                wifiFields.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) loadSavedNetworks()
            }
            if (wifiNote.text.isNullOrBlank()) wifiNote.text = wifiHint()
            val wifiCard = sheet.card()
            wifiCard.addView(wifiSwitch.view)
            wifiCard.addView(wifiFields)
            sheet.body.addView(wifiCard)
            sheet.body.addView(
                sheet.footer(
                    "Linux: wpa_supplicant.conf lands in the drive's root, which Raspberry Pi OS and " +
                        "similar images read on first boot. Windows: a netsh profile plus a one-click " +
                        "installer. macOS: a configuration profile to install on the Mac.",
                ),
            )

            sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
            burnButton = sheet.addButton("Burn ${volume.displayName}", filled = true) { startBurn() }
            burnButton.isEnabled = false
            burnButton.alpha = 0.4f
        }

        private fun pad(value: Int): Int = IosUi.dp(context, value.toFloat())

        private fun wifiHint(): String = if (status.privilege == StorageAdmin.Privilege.ROOT) {
            "Prism can read the saved password from this device."
        } else {
            "Root is needed to read the saved password, so type it here. Android does not expose " +
                "Wi-Fi passwords to apps."
        }

        /** Same non-blocking privilege probe as the Format sheet, for the same reason. */
        private fun probeAccess() {
            Thread {
                val fresh = runCatching { StorageAdmin.privilege(context, refresh = true) }
                    .getOrDefault(StorageAdmin.cachedStatus())
                main.post {
                    status = fresh
                    renderAccessNote()
                    if (::wifiNote.isInitialized) wifiNote.text = wifiHint()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun renderAccessNote() {
            if (!::accessNote.isInitialized) return
            val raw = status.canBurnRaw
            val mounted = volume.isMounted
            accessNote.text = when {
                mounted && raw ->
                    "The drive's current contents are replaced. A raw image is written directly to the device."
                mounted -> "The drive's current contents are replaced. This install has no root, so an " +
                    "ISO is copied file by file and a raw .img cannot be written."
                raw -> "The drive is not mounted. A raw image can still be written with root; a " +
                    "file-level ISO copy cannot."
                else -> "The drive is not mounted, and this install has no root, so there is nothing " +
                    "Prism can write here. Insert the drive and try again."
            }
            accessNote.setTextColor(
                if (raw || mounted) IosUi.secondaryLabel(context) else IosUi.destructive(context),
            )
            accessNote.background = GradientDrawable().apply {
                if (raw || mounted) {
                    setColor(IosUi.fill(context))
                    cornerRadius = pad(12).toFloat()
                } else {
                    setColor(blend(IosUi.destructive(context), 0x1F))
                    cornerRadius = pad(12).toFloat()
                    setStroke(pad(1), blend(IosUi.destructive(context), 0x55))
                }
            }
        }

        private fun suggestedLabel(): String = FileSystems.sanitizeLabel(
            FileSystems.FAT32,
            volume.displayName.replace(Regex("[^A-Za-z0-9 _-]"), " ").trim().ifBlank { "PRISM" },
        )

        private fun pickOs() {
            val choices = BurnOs.entries.map { Choice(it.displayName, it.detail) }
            showPicker(context, "Operating system", choices, BurnOs.entries.indexOf(os)) { index ->
                os = BurnOs.entries[index]
                refreshRow(osRow, os.displayName, os.detail)
            }
        }

        private fun loadSavedNetworks() {
            if (savedNetworks.isNotEmpty()) return
            wifiNote.text = "Reading this device's Wi-Fi store…"
            Thread {
                val saved = runCatching { StorageAdmin.savedWifi(status) }.getOrDefault(emptyList())
                main.post {
                    savedNetworks = saved
                    if (saved.isEmpty()) {
                        wifiNote.text = if (status.privilege == StorageAdmin.Privilege.ROOT) {
                            "No saved networks could be read. Type the password."
                        } else {
                            "Root is required to read it. Type the password instead."
                        }
                    } else {
                        wifiNote.text = "${saved.size} saved network(s) read from this device."
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun readSavedPassword() {
            loadSavedNetworks()
            wifiNote.text = "Looking for a saved password…"
            Thread {
                // The cache may still be filling; read again rather than racing it.
                val saved = runCatching { StorageAdmin.savedWifi(status) }.getOrDefault(emptyList())
                main.post {
                    if (saved.isNotEmpty()) savedNetworks = saved
                    val best = WifiConfigStore.bestFor(savedNetworks, ssidField.text.toString())
                    if (best == null) {
                        wifiNote.text = if (status.privilege == StorageAdmin.Privilege.ROOT) {
                            "No saved network matched. Type the password."
                        } else {
                            "Root is required to read it. Type the password instead."
                        }
                    } else {
                        ssidField.setText(best.ssid)
                        passwordField.setText(best.password)
                        wifiNote.text = "Read '${best.ssid}' from this device (${best.securityName()})."
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun buildCredentials(): WifiCredentials? {
            val ssid = ssidField.text.toString().trim()
            if (ssid.isEmpty()) return null
            val saved = savedNetworks.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }
            return WifiCredentials(
                ssid = ssid,
                password = passwordField.text.toString().ifBlank { saved?.password.orEmpty() },
                bssid = saved?.bssid.orEmpty(),
                security = saved?.securityName() ?: "WPA2",
                hidden = saved?.hidden ?: false,
            )
        }

        private fun copyImage(uri: android.net.Uri) {
            val name = ImportedText.name(context, uri)
            val directory = File(context.cacheDir, "burn").apply { mkdirs() }
            // A previous image is dead weight in the cache and can be gigabytes.
            runCatching { directory.listFiles()?.forEach { it.deleteRecursively() } }
            val file = File(directory, name)
            copying = true
            refreshRow(imageRow, "Copying $name…", "Preparing the image")
            Thread {
                val ok = runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching false
                    input.use { source -> file.outputStream().use { out -> source.copyTo(out, 1024 * 1024) } }
                    true
                }.getOrElse {
                    PrismLogger.logWarning("Storage", "Could not copy $name: ${it.message}")
                    false
                }
                main.post {
                    copying = false
                    if (ok) {
                        image = file
                        imageName = name
                        refreshRow(imageRow, name, formatBytes(file.length()))
                        burnButton.isEnabled = true
                        burnButton.alpha = 1f
                    } else {
                        refreshRow(imageRow, "Choose ISO or IMG", "The file could not be read")
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun startBurn() {
            val file = image
            if (file == null || !file.exists()) {
                sheet.flash("Choose an ISO or IMG first.")
                return
            }
            val copyWifi = wifiSwitch.toggle.isChecked
            val credentials = if (copyWifi) buildCredentials() else null
            if (copyWifi && credentials == null) {
                sheet.flash("Enter the network name, or turn off Copy Wi-Fi settings.")
                return
            }
            val label = labelField.text.toString().trim()
            val name = imageName.ifBlank { file.name }
            sheet.dismiss()
            runOperation(
                context = context,
                title = "Burning",
                subtitle = "$name → ${volume.displayName}",
                onDone = { result ->
                    onChanged()
                    if (label.isNotBlank() && result.ok) relabel(label)
                    showResult(context, result)
                },
            ) { report ->
                StorageAdmin.burn(
                    context = context,
                    volume = volume,
                    image = file,
                    displayName = name,
                    os = os,
                    credentials = credentials,
                    uiStatus = status,
                ) { text, percent -> report(text, percent) }
            }
        }

        /**
         * Applies the label after a successful burn, when the device can.
         *
         * A raw write reproduces the image's own label; a file-level copy leaves the volume that was
         * already formatted. The outcome is logged either way, because a label that silently did not
         * apply is the kind of detail somebody notices much later.
         */
        private fun relabel(label: String) {
            Thread {
                val result = runCatching { StorageAdmin.relabel(volume, label, status) }.getOrNull()
                if (result != null) PrismLogger.logInfo("Storage", "Relabel: $result")
            }.apply { isDaemon = true }.start()
        }
    }

    // ── Result ─────────────────────────────────────────────────────────────

    /** The outcome of an operation: what happened, the commands, and where to read more. */
    fun showResult(context: Context, operation: StorageOperation) {
        val sheet = Sheet(
            context,
            if (operation.ok) "${operation.title} complete" else "${operation.title} failed",
            null,
        )
        sheet.body.addView(
            sheet.paragraph(operation.message).apply {
                setTextColor(if (operation.ok) IosUi.label(context) else IosUi.destructive(context))
            },
        )
        if (operation.steps.isNotEmpty()) {
            sheet.body.addView(sheet.sectionHeader("What ran"))
            val details = TextView(context).apply {
                text = operation.steps.takeLast(24).joinToString("\n")
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(IosUi.secondaryLabel(context))
                setTextIsSelectable(true)
            }
            val scroll = ScrollView(context).apply {
                addView(details)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(context, 150f),
                )
            }
            sheet.body.addView(sheet.card().apply { addView(scroll) })
        }
        sheet.body.addView(sheet.footer("The same detail is in System Diagnostics, under Storage."))
        sheet.addButton("Diagnostics", filled = false) {
            sheet.dismiss()
            runCatching { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }
        }
        sheet.addButton("Close", filled = true) { sheet.dismiss() }
        sheet.show()
    }

    // ── Progress runner ────────────────────────────────────────────────────

    /**
     * Runs a storage operation on a worker thread behind a progress sheet.
     *
     * The sheet refuses to close while the command runs: a `parted` session interrupted by a tap
     * leaves a half-written partition table, which is the one failure mode worth blocking a back
     * press for.
     */
    private fun runOperation(
        context: Context,
        title: String,
        subtitle: String,
        onDone: (StorageOperation) -> Unit,
        block: ((String, Int) -> Unit) -> StorageOperation,
    ) {
        val sheet = Sheet(context, title, subtitle)
        val statusText = TextView(context).apply {
            text = "Preparing…"
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 4f), 0, IosUi.dp(context, 10f))
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = IosUi.progressDrawable(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(context, 6f),
            )
        }
        sheet.body.addView(statusText)
        sheet.body.addView(bar)
        sheet.body.addView(sheet.footer("This window closes when the operation finishes."))
        sheet.show(dismissible = false)

        Thread {
            val result = runCatching {
                block { text, percent ->
                    main.post {
                        if (sheet.isShowing) {
                            statusText.text = text
                            bar.progress = percent.coerceIn(0, 100)
                        }
                    }
                }
            }.getOrElse {
                PrismLogger.logError("Storage", "$title crashed", it)
                StorageOperation(false, title, "$title failed unexpectedly: ${it.message}")
            }
            main.post {
                sheet.dismissForcefully()
                onDone(result)
            }
        }.apply { isDaemon = true }.start()
    }

    // ── Picker ─────────────────────────────────────────────────────────────

    data class Choice(
        val title: String,
        val subtitle: String = "",
        val enabled: Boolean = true,
    )

    /** A sheet of options with a tick on the current one. */
    fun showPicker(
        context: Context,
        title: String,
        choices: List<Choice>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val sheet = Sheet(context, title, null)
        val card = sheet.card()
        choices.forEachIndexed { index, choice ->
            if (index > 0) card.addView(sheet.divider())
            card.addView(
                sheet.row(
                    choice.title,
                    choice.subtitle,
                    enabled = choice.enabled,
                    tint = if (index == selected) IosUi.accent(context) else IosUi.label(context),
                    trailing = if (index == selected) "✓" else null,
                ) {
                    sheet.dismiss()
                    onPick(index)
                },
            )
        }
        sheet.body.addView(card)
        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.show()
    }

    // ── The sheet itself ───────────────────────────────────────────────────

    private data class RowParts(val title: TextView, val subtitle: TextView?)

    internal fun refreshRow(row: LinearLayout, title: String, subtitle: String) {
        val parts = row.tag as? RowParts ?: return
        parts.title.text = title
        parts.subtitle?.text = subtitle
    }

    private fun blend(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    internal class SwitchRow(val view: LinearLayout, val toggle: Switch)

    /**
     * The bottom sheet every dialog above is built from.
     *
     * It owns the window, the rounded card, the scroll area and the button column; the content is a
     * plain `LinearLayout` so a screen can be assembled from rows, cards and fields without a layout
     * file per dialog.
     */
    internal class Sheet(
        private val context: Context,
        title: String,
        subtitle: String?,
    ) {
        val dialog = Dialog(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val scroller = ScrollView(context).apply { clipToPadding = false; addView(body) }
        private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val flashLine = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.destructive(context))
            setPadding(dp(4), dp(6), dp(4), 0)
            visibility = View.GONE
        }

        val isShowing: Boolean get() = dialog.isShowing
        private var dismissible = true

        init {
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val theme = IosUi.mode(context)
            // The radius, the page padding and the border all come from the active theme, so a
            // square-cornered theme gives a square sheet and an iOS theme gives a rounded one.
            val topRadius = dp(theme.shape.card + 8).toFloat()
            root.background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadii = floatArrayOf(
                    topRadius, topRadius, topRadius, topRadius,
                    0f, 0f, 0f, 0f,
                )
                if (theme.shape.border > 0) {
                    setStroke(dp(theme.shape.border), IosUi.separator(context))
                }
            }
            root.setPadding(dp(theme.density.page), dp(10), dp(theme.density.page), dp(18))

            root.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(IosUi.separator(context))
                    cornerRadius = dp(3).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10)
                }
            })
            root.addView(TextView(context).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(IosUi.label(context))
            })
            if (!subtitle.isNullOrBlank()) {
                root.addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, dp(2), 0, dp(6))
                })
            }

            root.addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            root.addView(flashLine)
            root.addView(buttons)

            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.45f)
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dialog.setCanceledOnTouchOutside(true)
        }

        fun show(dismissible: Boolean = true) {
            this.dismissible = dismissible
            dialog.setCancelable(dismissible)
            dialog.setCanceledOnTouchOutside(dismissible)
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    if (dismissible) dismiss()
                    true
                } else {
                    false
                }
            }
            dialog.show()
            // Wrap the content, but never taller than the screen: a sheet that runs off the top
            // hides its title and warnings, which are exactly the parts that must be read.
            root.post {
                if (!dialog.isShowing) return@post
                val width = context.resources.displayMetrics.widthPixels
                val max = (context.resources.displayMetrics.heightPixels * 0.9f).toInt()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                // THE ACTION ROW MUST SURVIVE THE CLAMP. A long form (host-folder has name,
                // protocol, port, credentials, protocol fields and two notes) measures taller
                // than the window, and a plain wrap-content scroller simply paints over the
                // buttons below it -- "Start hosting" existed but was off the bottom of the
                // screen. Give the scroller exactly the room left over after the title, the
                // flash line and the buttons, then measure the sheet again against that.
                if (root.measuredHeight > max) {
                    val chrome = (root.measuredHeight - scroller.measuredHeight).coerceAtLeast(0)
                    scroller.layoutParams = (scroller.layoutParams ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )).apply {
                        height = (max - chrome).coerceAtLeast(dp(120))
                    }
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                }
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(root.measuredHeight, max),
                )
            }
        }

        /** Closes a progress sheet even though it refuses a back press or an outside tap. */
        fun dismissForcefully() {
            dismissible = true
            dialog.setCancelable(true)
            runCatching { dialog.dismiss() }
        }

        fun dismiss() {
            if (!dismissible) return
            runCatching { dialog.dismiss() }
        }

        // ── Components ────────────────────────────────────────────────

        fun card(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val theme = IosUi.mode(context)
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(theme.shape.card).toFloat()
                if (theme.shape.border > 0) {
                    setStroke(dp(theme.shape.border), IosUi.separator(context))
                }
            }
            setPadding(dp(theme.density.page), dp(2), dp(theme.density.page), dp(2))
        }

        fun sectionHeader(text: String): TextView = TextView(context).apply {
            this.text = if (IosUi.mode(context).typography.uppercaseHeaders) text.uppercase() else text
            textSize = 13f
            letterSpacing = 0.06f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(dp(4), dp(16), dp(4), dp(6))
        }

        fun footer(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(dp(4), dp(7), dp(4), 0)
            setLineSpacing(0f, 1.15f)
        }

        fun paragraph(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(IosUi.label(context))
            setPadding(dp(2), dp(8), dp(2), dp(2))
            setLineSpacing(0f, 1.2f)
        }

        fun note(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.15f)
        }

        fun warning(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(IosUi.destructive(context))
            background = GradientDrawable().apply {
                setColor(blend(IosUi.destructive(context), 0x1F))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), blend(IosUi.destructive(context), 0x55))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.15f)
        }

        fun divider(): View = View(context).apply {
            background = GradientDrawable().apply { setColor(IosUi.separator(context)) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, IosUi.dp(context, 0.5f)),
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
        }

        /**
         * A tappable row: title, optional detail underneath, optional trailing glyph.
         *
         * The title/detail pair is tagged so a selection can be reflected without rebuilding the
         * sheet -- rebuilding mid-dialog loses scroll position and the keyboard.
         */
        fun row(
            title: String,
            subtitle: String = "",
            enabled: Boolean = true,
            tint: Int = IosUi.label(context),
            trailing: String? = null,
            onClick: (() -> Unit)? = null,
        ): LinearLayout {
            val titleView = TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(if (enabled) tint else IosUi.tertiaryLabel(context))
            }
            val subtitleView = if (subtitle.isNotBlank()) TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(1), 0, 0)
            } else {
                null
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                subtitleView?.let { addView(it) }
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(11), dp(2), dp(11))
                addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (trailing != null) {
                    addView(TextView(context).apply {
                        text = trailing
                        textSize = 16f
                        setTextColor(tint)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ))
                }
                isEnabled = enabled
                isClickable = enabled && onClick != null
                tag = RowParts(titleView, subtitleView)
                if (onClick != null && enabled) {
                    setOnClickListener { onClick() }
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(blend(IosUi.label(context), 0x22)), null, null,
                    )
                } else if (!enabled) {
                    alpha = 0.5f
                }
            }
            return container
        }

        fun textField(
            hint: String,
            initial: String,
            password: Boolean = false,
            numeric: Boolean = false,
            onChange: ((String) -> Unit)? = null,
        ): EditText = EditText(context).apply {
            this.hint = hint
            setText(initial)
            textSize = 16f
            setSingleLine()
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = IosUi.fieldBackground(context)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
            if (onChange != null) {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                        onChange(s?.toString().orEmpty())
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
        }

        fun switchRow(
            title: String,
            subtitle: String,
            initial: Boolean,
            onChange: (Boolean) -> Unit,
        ): SwitchRow {
            val toggle = Switch(context).apply {
                isChecked = initial
                showText = false
                thumbTintList = ColorStateList.valueOf(IosUi.accent(context))
                trackTintList = ColorStateList.valueOf(IosUi.separator(context))
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(9), dp(2), dp(9))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title
                        textSize = 16f
                        setTextColor(IosUi.label(context))
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(IosUi.secondaryLabel(context))
                        setPadding(0, dp(1), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle)
                setOnClickListener { toggle.isChecked = !toggle.isChecked }
            }
            toggle.setOnCheckedChangeListener { _, checked -> onChange(checked) }
            return SwitchRow(container, toggle)
        }

        fun roundButton(glyph: String, onClick: () -> Unit): TextView = TextView(context).apply {
            text = glyph
            textSize = 20f
            gravity = Gravity.CENTER
            val theme = IosUi.mode(context)
            setTextColor(IosUi.palette(context).onAccent.toInt())
            background = GradientDrawable().apply {
                setColor(IosUi.accent(context))
                cornerRadius = dp(if (theme.shape.pill) 999 else theme.shape.button).toFloat()
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { onClick() }
        }

        fun addButton(
            text: String,
            filled: Boolean,
            tint: Int = IosUi.accent(context),
            onClick: () -> Unit,
        ): TextView = TextView(context).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = filled
            setTextColor(if (filled) IosUi.palette(context).onAccent.toInt() else tint)
            background = if (filled) {
                IosUi.filledBackground(context, tint)
            } else {
                GradientDrawable().apply {
                    setColor(blend(tint, 0x1F))
                    cornerRadius = dp(
                        if (IosUi.mode(context).shape.pill) 999 else IosUi.mode(context).shape.button,
                    ).toFloat()
                }
            }
            isClickable = true
            isFocusable = true
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(9) }
            setOnClickListener { onClick() }
            buttons.addView(this)
        }

        fun flash(message: String) {
            flashLine.text = message
            flashLine.visibility = View.VISIBLE
        }

        private fun dp(value: Int): Int = IosUi.dp(context, value.toFloat())
    }
}
