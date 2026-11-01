package com.prism.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.datasetcraft.AudioAutofill
import com.prism.launcher.datasetcraft.DatasetCraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane

/**
 * DatasetCraft: the datasets that have been built here, then the form that builds one.
 *
 * A DATASET IS ITS CSV. Every edit is written the moment it is made ([DatasetCraft.save]), so the file
 * this page lists is the file a trainer reads -- there is no unsaved state to lose and no Save button
 * whose absence would be a bug.
 *
 * THE KIND DECIDES THE COLUMNS. Choosing one on the radio group adds that kind's columns rather than
 * replacing the current ones, so a dataset that turns out to be about something else keeps what was
 * already typed in it.
 */
@Composable
fun DatasetCraftPage() {
    var datasets by remember { mutableStateOf(DatasetCraft.all()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val open = openId?.let { id -> datasets.firstOrNull { it.id == id } ?: DatasetCraft.find(id) }

    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "DatasetCraft",
            subtitle = when {
                creating -> "Choose what the dataset is about, then fill it."
                open != null -> "${open.kind.label} · ${open.rowCount} row(s), kept as a CSV."
                datasets.isEmpty() -> "No datasets yet. The plus starts the first one."
                else -> "${datasets.size} ${if (datasets.size == 1) "dataset" else "datasets"}, " +
                    "each one a CSV in Prism's data folder."
            },
            actions = {
                if (creating || open != null) {
                    IconButton(onClick = { creating = false; openId = null; datasets = DatasetCraft.all() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the datasets")
                    }
                }
            },
        ) {
            val current = open
            when {
                creating -> KindChooser(
                    onCreate = { kind ->
                        val made = DatasetCraft.create("", kind)
                        creating = false
                        datasets = DatasetCraft.all()
                        openId = made.id
                    },
                )
                current != null -> DatasetPanel(
                    dataset = current,
                    onChanged = { datasets = DatasetCraft.all() },
                    onDeleted = { openId = null; datasets = DatasetCraft.all() },
                    scope = scope,
                )
                else -> DatasetList(datasets = datasets, onOpen = { openId = it.id })
            }
        }

        if (!creating && open == null) {
            Surface(
                color = LocalPrismColors.current.accent,
                shape = RoundedCornerShape(
                    if (LocalPrismColors.current.pill) 999.dp else LocalPrismColors.current.buttonRadius,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(52.dp)
                    .clickableRow { creating = true },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "New dataset", tint = LocalPrismColors.current.background)
                }
            }
        }
    }
}

@Composable
private fun DatasetList(datasets: List<DatasetCraft.CraftedDataset>, onOpen: (DatasetCraft.CraftedDataset) -> Unit) {
    val colors = LocalPrismColors.current
    if (datasets.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize()) {
        items(datasets, key = { it.id }) { dataset ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableRow { onOpen(dataset) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(colors.cardRadius), modifier = Modifier.size(40.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Storage, null, tint = colors.accent, modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(dataset.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOf(
                            dataset.kind.label,
                            "${dataset.rowCount} row(s)",
                            dataset.columns.joinToString(" · "),
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (dataset.remoteRows > 0) {
                        Text("${dataset.remoteRows} to download", fontSize = 11.sp, color = colors.accent)
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(dataset.note, fontSize = 11.sp, color = colors.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Hairline()
        }
    }
}

/** The kinds, asked for before anything is written: the choice decides the columns. */
@Composable
private fun KindChooser(onCreate: (DatasetCraft.Kind) -> Unit) {
    val colors = LocalPrismColors.current
    var kind by remember { mutableStateOf(DatasetCraft.Kind.TEXT) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("WHAT IS THIS DATASET ABOUT?")
        DatasetCraft.Kind.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableRow { kind = option }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option == kind,
                    onClick = { kind = option },
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
                )
                Column(Modifier.weight(1f)) {
                    Text(option.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(option.note, fontSize = 12.sp, color = colors.muted)
                }
                // A game dataset accepts more than a hundred extensions. Listing all of them here
                // would push the label off the row, so the tail is a count rather than a wall.
                val formats = option.extensions.sorted()
                val preview = formats.take(4).joinToString(", ") +
                    if (formats.size > 4) " +${formats.size - 4}" else ""
                Text(
                    preview,
                    fontSize = 11.sp,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            Hairline()
        }
        Spacer(Modifier.height(16.dp))
        DesktopActionButton(Icons.Filled.Add, "Start the dataset", destructive = false) {
            onCreate(kind)
        }
    }
}

@Composable
private fun DatasetPanel(
    dataset: DatasetCraft.CraftedDataset,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = LocalPrismColors.current
    var kind by remember(dataset.id) { mutableStateOf(dataset.kind) }
    var busy by remember(dataset.id) { mutableStateOf("") }
    var filling by remember(dataset.id) { mutableStateOf(false) }
    var autoFill by remember(dataset.id) { mutableStateOf(dataset.autoFillAudio) }

    /**
     * Listens to the clips on a worker, then writes the answers once.
     *
     * The page stays usable while it runs; a second request is ignored by the busy line rather than
     * starting a second whisper pass over the same rows.
     */
    fun fillAudio(force: Boolean = false) {
        if (!dataset.kind.autoTranscribes) return
        if (!force && !autoFill) return
        // A run installs the model on its first use, so the line it shows is not always
        // "Listening": a flag, not a string match, is what keeps a second click from starting a
        // second pass over the same CSV.
        if (filling) return
        filling = true
        busy = "Listening to the clips…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AudioAutofill.fill(dataset) { line, _ ->
                        // A stage that arrives after the run finished must not overwrite the
                        // summary it was replaced by.
                        scope.launch(Dispatchers.Main) { if (filling) busy = line }
                    }
                }
                filling = false
                dataset.note = result.summary
                DatasetCraft.save(dataset)
                busy = result.summary
                onChanged()
            } finally {
                filling = false
            }
        }
    }

    // Opening a dataset whose box is already ticked finishes any rows added since it was last
    // touched. Import and URL/download actions below call fillAudio as well, so a checked dataset
    // stays filled while it is being built rather than only on the next visit.
    LaunchedEffect(dataset.id) {
        if (autoFill && dataset.kind.autoTranscribes) fillAudio()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("WHAT KIND OF DATA")
        DatasetCraft.Kind.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickableRow {
                if (option != kind) {
                    kind = option
                    dataset.kind = option
                    // A set that has just become speech gets automatic transcription: that is the
                    // moment its rows stopped being files and became training examples with no
                    // words in them yet.
                    autoFill = option.autoTranscribes
                    dataset.autoFillAudio = option.autoTranscribes
                    DatasetCraft.save(dataset)
                    // Added, not swapped: a caption typed for an image set is still a caption.
                    option.columns.forEach { DatasetCraft.addColumn(dataset, it) }
                    onChanged()
                    if (option.autoTranscribes) fillAudio()
                }
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = option == kind,
                    onClick = {
                        if (option != kind) {
                            kind = option
                            dataset.kind = option
                            autoFill = option.autoTranscribes
                            dataset.autoFillAudio = option.autoTranscribes
                            DatasetCraft.save(dataset)
                            option.columns.forEach { DatasetCraft.addColumn(dataset, it) }
                            onChanged()
                            if (option.autoTranscribes) fillAudio()
                        }
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
                )
                Text("${option.label} — ${option.note}", fontSize = 13.sp, color = colors.onSurface)
            }
        }

        // AUTO TRANSCRIPTION. Audio and video both carry speech, and the CSV a trainer reads needs
        // the words in its transcript column rather than beside each clip. The feature is here, on
        // the dataset, rather than hidden in the kind radio: what it fills is the gap a labelless
        // folder leaves.
        if (kind.autoTranscribes) {
            SectionHeader("AUTO TRANSCRIPTION")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = autoFill,
                    onCheckedChange = { checked ->
                        autoFill = checked
                        dataset.autoFillAudio = checked
                        DatasetCraft.save(dataset)
                        if (checked) fillAudio()
                    },
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent),
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        "Transcribe files that have no text",
                        fontSize = 13.sp,
                        color = colors.onSurface,
                    )
                    Text(
                        "Audio files are heard with the transcription engine; a video's audio " +
                            "track is used the same way. The first run downloads a 142 MB model " +
                            "once. Only blank text is filled, so a typed transcript is never " +
                            "replaced.",
                        fontSize = 12.sp,
                        color = colors.muted,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopActionButton(Icons.Filled.PlayArrow, "Transcribe missing now", destructive = false) {
                    fillAudio(force = true)
                }
            }
        }

        SectionHeader("NAME")
        CommittingField(dataset.name, modifier = Modifier.fillMaxWidth()) { value ->
            DatasetCraft.rename(dataset, value)
            onChanged()
        }

        SectionHeader("WHAT IT IS FOR")
        CommittingField(dataset.description, modifier = Modifier.fillMaxWidth()) { value ->
            dataset.description = value
            DatasetCraft.save(dataset)
            onChanged()
        }

        SectionHeader("COLUMNS")
        SectionFooter("The file column is what every row must have. Right-click a column to remove it.")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dataset.columns.forEach { name ->
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.rightClickable {
                        if (name.equals(DatasetCraft.PATH_COLUMN, true)) {
                            JOptionPane.showMessageDialog(
                                null,
                                "Every row needs its ${DatasetCraft.PATH_COLUMN} column.",
                                "That column stays",
                                JOptionPane.INFORMATION_MESSAGE,
                            )
                        } else {
                            DatasetCraft.removeColumn(dataset, name)
                            onChanged()
                        }
                    },
                ) {
                    Text(name, fontSize = 12.sp, color = colors.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            DesktopActionButton(Icons.Filled.Add, "Column", destructive = false) {
                val name = JOptionPane.showInputDialog(null, "Column name", "Add a column", JOptionPane.PLAIN_MESSAGE)
                if (!name.isNullOrBlank() && !DatasetCraft.addColumn(dataset, name)) {
                    // Reported rather than silent: a column that does not appear is a column the user
                    // typed twice.

                    JOptionPane.showMessageDialog(null, "That column is already there.", "No change", JOptionPane.INFORMATION_MESSAGE)
                }
                onChanged()
            }
        }

        SectionHeader("FILES")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopActionButton(Icons.Filled.Folder, "Import files or a folder", destructive = false) {
                val picked = pickDatasetFiles()
                if (picked.isNotEmpty()) {
                    busy = "Copying ${picked.size} file(s) in…"
                    scope.launch {
                        val rows = withContext(Dispatchers.IO) { DatasetCraft.importFiles(dataset, picked) }
                        busy = "$rows row(s) imported. ${dataset.note}"
                        onChanged()
                        // Importing speech is the moment its transcripts are made, whatever the
                        // standing preference says: the rows exist now and have no words yet.
                        if (dataset.kind.autoTranscribes) fillAudio(force = true)
                    }
                }
            }
            DesktopActionButton(Icons.Filled.Link, "Add a row from a URL", destructive = false) {
                val url = JOptionPane.showInputDialog(
                    null,
                    "The file's URL. It downloads into this dataset, named as the link names it.",
                    "Add a row from a URL",
                    JOptionPane.PLAIN_MESSAGE,
                )
                if (!url.isNullOrBlank()) {
                    if (DatasetCraft.addUrlRow(dataset, url) == null) {
                        JOptionPane.showMessageDialog(null, "That is not an http(s) URL.", "Not added", JOptionPane.WARNING_MESSAGE)
                    }
                    onChanged()
                }
            }
            DesktopActionButton(Icons.Filled.Download, "Download the URL rows", destructive = false) {
                if (dataset.remoteRows == 0) {
                    busy = "No row names a URL."
                } else {
                    busy = "Downloading ${dataset.remoteRows} file(s)…"
                    scope.launch {
                        val fetched = withContext(Dispatchers.IO) { DatasetCraft.materialise(dataset.folder) }
                        dataset.note = "$fetched file(s) downloaded"
                        DatasetCraft.save(dataset)
                        busy = "$fetched file(s) downloaded."
                        onChanged()
                        if (dataset.kind.autoTranscribes) fillAudio(force = true)
                    }
                }
            }
        }

        SectionHeader("ROWS")
        SectionFooter("Type straight into a cell; it is written when you press Enter or leave the field.")
        dataset.rows.forEachIndexed { index, row ->
            Surface(
                color = colors.surfaceRaised,
                shape = RoundedCornerShape(colors.cardRadius),
                border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.path,
                            fontSize = 13.sp,
                            color = if (row.isUrl) colors.accent else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("↑", fontSize = 14.sp, color = colors.muted, modifier = Modifier.clickableRow {
                            DatasetCraft.moveRow(dataset, row, -1); onChanged()
                        }.padding(horizontal = 6.dp))
                        Text("↓", fontSize = 14.sp, color = colors.muted, modifier = Modifier.clickableRow {
                            DatasetCraft.moveRow(dataset, row, 1); onChanged()
                        }.padding(horizontal = 6.dp))
                        Text("✕", fontSize = 14.sp, color = colors.destructive, modifier = Modifier.clickableRow {
                            DatasetCraft.removeRow(dataset, row); onChanged()
                        }.padding(horizontal = 6.dp))
                    }
                    dataset.columns
                        .filterNot { it.equals(DatasetCraft.PATH_COLUMN, true) }
                        .forEach { column ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(column, fontSize = 11.sp, color = colors.faint, modifier = Modifier.width(110.dp))
                                CommittingField(
                                    value = row.value(column),
                                    modifier = Modifier.weight(1f),
                                ) { value ->
                                    DatasetCraft.setValue(dataset, row, column, value)
                                    onChanged()
                                }
                            }
                        }
                }
            }
        }
        if (dataset.rows.isEmpty()) {
            SectionFooter("No rows yet. Import the files, add a row from a URL, or drop a CSV in and import it.")
        }

        Spacer(Modifier.height(12.dp))
        Text(dataset.summary(), fontSize = 12.sp, color = colors.muted)
        if (busy.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(busy, fontSize = 12.sp, color = colors.accent)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopActionButton(Icons.Filled.Delete, "Delete dataset", destructive = true) {
                val answer = JOptionPane.showConfirmDialog(
                    null,
                    "Remove \"${dataset.name}\" and every file it holds?",
                    "Delete dataset",
                    JOptionPane.OK_CANCEL_OPTION,
                )
                if (answer == JOptionPane.OK_OPTION) {
                    DatasetCraft.delete(dataset)
                    onDeleted()
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** A theme-coloured control, matching the one ModelCraft's page uses. */
@Composable
private fun DesktopActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.buttonRadius),
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier.clickableRow { onClick() },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (destructive) colors.destructive else colors.accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = colors.onSurface)
        }
    }
}

/**
 * Files or folders, filtered to nothing on purpose.
 *
 * A dataset of speech is clips and a CSV; the CSV is half of it, and a filter that hid it would make the
 * import half-work. [DatasetCraft.importFiles] reports what it could not use rather than the picker
 * refusing to offer it.
 */
private fun pickDatasetFiles(): List<File> {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import dataset files or a folder"
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
    val picked = mutableListOf<File>()
    chooser.selectedFiles.filterNotNull().forEach { file ->
        if (file.isDirectory) {
            file.listFiles()?.filter { it.isFile }?.let { picked.addAll(it) }
        } else {
            picked.add(file)
        }
    }
    return picked
}
