package com.prism.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.language.Cefr
import com.prism.launcher.language.LanguageCatalog
import com.prism.launcher.language.LanguagePlanStore
import com.prism.launcher.language.LanguageProfile
import com.prism.launcher.language.LanguageSetupFlow
import com.prism.launcher.language.LanguageStats
import com.prism.launcher.language.LanguageTutors
import com.prism.launcher.language.LanguageVoices
import com.prism.launcher.language.LearningPlan
import com.prism.launcher.language.LessonRunner
import com.prism.launcher.language.LessonSpec
import com.prism.launcher.language.PictureBank
import com.prism.launcher.language.PlannedLesson
import com.prism.launcher.speech.PrismSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The language tutor. PHASE 94.
 *
 * ## What the phase actually required, and what it did not
 *
 * The plan said "nearly all of it is already in :core and already portable", and that was true of the
 * scheduler, the lexicons, the validators and the prompt construction. It was NOT true of the roster,
 * the plan store, the profile, the setup questionnaire, the learner model or the lesson runner --
 * six files with no Android in them at all that were nonetheless in `:app`, plus `PictureBank`, whose
 * only Android was a `Bitmap` return type. Those moved. What is left in `:app` is five files of
 * `android.view` drawing, which is exactly the remaining work this page replaces.
 *
 * ## Three screens, and why they are one page
 *
 * Setup, the plan, and a lesson in progress. Android makes them an Activity each because that is what
 * Android does with a flow; a desktop window switching its whole content is the same thing without
 * the navigation. The state that decides which is showing is [Screen], and the lesson one holds a
 * [LessonRunner], so leaving a lesson by pressing "back" abandons it the same way closing the Activity
 * does.
 *
 * ## Spoken practice is here rather than deferred, because PHASE 100 landed first
 *
 * The plan said "ship the reading and writing half first rather than blocking the whole course on
 * TTS". That ordering was followed -- speech was done first -- so the tutor talks. Every tutor line
 * goes through `PrismSpeaker.speakAs` with the tutor's own voice, and the WORD BEING TAUGHT goes
 * through a second call with the target language's voice, which is the distinction the Android lesson
 * screen makes and the reason `speakAs` takes a locale separate from a voice: a tutor with an American
 * voice reading a Mandarin word needs to be told it is Mandarin.
 */
@Composable
fun LanguagePage() {
    val colors = LocalPrismColors.current

    var screen by remember { mutableStateOf(if (LanguageProfile.isSetUp()) Screen.PLAN else Screen.SETUP) }
    var lesson by remember { mutableStateOf<PlannedLesson?>(null) }
    var revision by remember { mutableStateOf(0) }

    when (screen) {
        Screen.SETUP -> SetupFlow(
            onDone = { screen = Screen.PLAN; revision++ },
        )

        Screen.PLAN -> PlanScreen(
            revision = revision,
            onOpenLesson = { lesson = it; screen = Screen.LESSON },
            onRedoSetup = { screen = Screen.SETUP },
            onChanged = { revision++ },
        )

        Screen.LESSON -> {
            val chosen = lesson
            if (chosen == null) {
                screen = Screen.PLAN
            } else {
                LessonScreen(
                    planned = chosen,
                    onLeave = { screen = Screen.PLAN; revision++ },
                )
            }
        }
    }
}

private enum class Screen { SETUP, PLAN, LESSON }

// ── Setup ───────────────────────────────────────────────────────────────────

/**
 * The questionnaire, one step at a time.
 *
 * Driven entirely off `LanguageSetupFlow.steps()`, which is the same list the phone walks -- so a
 * question added there appears on both platforms with no change here. The five step KINDS each get a
 * renderer below; anything the flow adds that is not one of them would have to be added, and that is
 * a deliberate compile error rather than a silently skipped question.
 */
@Composable
private fun SetupFlow(onDone: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val steps = remember { LanguageSetupFlow.steps() }

    var index by remember { mutableStateOf(0) }
    var building by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // Re-read per step rather than held: each renderer writes straight to LanguageProfile, which is
    // the store the plan builder reads, so keeping a parallel copy here could only disagree with it.
    val step = steps.getOrNull(index)

    fun advance() {
        if (index + 1 < steps.size) {
            index++
            return
        }
        building = true
        note = "Building your plan…"
        scope.launch {
            val plan = withContext(Dispatchers.IO) {
                LanguageProfile.markSetUp()
                LanguagePlanStore.rebuild()
            }
            // Pictures for the A0 concepts, in the background and fire-and-forget. Same reasoning as
            // the phone: the learner is on a good connection finishing setup, not on a train opening
            // their first picture lesson, and every path through PictureBank degrades to the emoji.
            Thread({ runCatching { PictureBank.prefetchAll() } }, "language-pictures")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            building = false
            if (plan == null) {
                note = "The plan could not be built. Check that a language and a level were chosen."
            } else {
                onDone()
            }
        }
    }

    PageScaffold("Language", "An AI tutor, a CEFR syllabus and a spaced-repetition schedule") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (step == null || building) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(note.ifBlank { "Working…" }, fontSize = 13.sp, color = colors.muted)
                }
                return@Column
            }

            // Where the learner is, because a questionnaire with no end in sight is one people quit.
            LinearProgressIndicator(
                progress = { (index + 1).toFloat() / steps.size },
                color = colors.accent,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Step " + (index + 1) + " of " + steps.size,
                fontSize = 10.sp,
                color = colors.faint,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(14.dp))
            Text(step.title, fontSize = 19.sp, lineHeight = 26.sp)
            step.subtitle?.let {
                Spacer(Modifier.height(5.dp))
                Text(it, fontSize = 12.sp, color = colors.muted, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(14.dp))

            when (step) {
                is LanguageSetupFlow.Single -> SingleStep(step) { advance() }
                is LanguageSetupFlow.Multi -> MultiStep(step) { advance() }
                is LanguageSetupFlow.NameEntry -> TextStep(step.id, step.hint) { advance() }
                is LanguageSetupFlow.Pledge -> TextStep(step.id, step.hint) { advance() }
                is LanguageSetupFlow.Statement -> {
                    Button(onClick = { advance() }) { Text(step.buttonLabel) }
                }
                is LanguageSetupFlow.MinutePicker -> MinutesStep(step) { advance() }
                is LanguageSetupFlow.TutorGrid -> TutorGridStep(step) { advance() }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = 12.sp, color = Color(0xFFFFB4B4), lineHeight = 18.sp)
            }

            if (index > 0) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "back",
                    fontSize = 12.sp,
                    color = colors.faint,
                    modifier = Modifier.clickableRow { index-- }.padding(6.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SingleStep(step: LanguageSetupFlow.Single, onPicked: () -> Unit) {
    val colors = LocalPrismColors.current
    var filter by remember(step.id) { mutableStateOf("") }
    val chosen = LanguageProfile.answer(step.id)

    if (step.searchable) {
        // Only where the flow asks for it: the native-language list is ~90 entries and the others are
        // under ten, where a filter box is noise.
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Type to narrow the list", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }

    val shown = step.choices.filter {
        filter.isBlank() || it.label.contains(filter, ignoreCase = true)
    }

    Card {
        Column {
            shown.forEachIndexed { i, choice ->
                if (i > 0) Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableRow {
                            LanguageProfile.setAnswer(step.id, choice.value)
                            onPicked()
                        }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    choice.emoji?.let {
                        Text(it, fontSize = 17.sp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        choice.label,
                        fontSize = 13.sp,
                        color = if (choice.value == chosen) colors.accent else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    choice.badge?.let { Text(it, fontSize = 10.sp, color = colors.faint) }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    "Nothing matches that.",
                    fontSize = 12.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
    step.footer?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 11.sp, color = colors.faint)
    }
}

@Composable
private fun MultiStep(step: LanguageSetupFlow.Multi, onDone: () -> Unit) {
    val colors = LocalPrismColors.current
    var picked by remember(step.id) { mutableStateOf(LanguageProfile.answers(step.id).toSet()) }

    step.groups.forEach { group ->
        group.header?.let {
            SectionHeader(it.lowercase())
        }
        Card {
            Column {
                group.choices.forEachIndexed { i, choice ->
                    if (i > 0) Hairline()
                    val on = choice.value in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableRow {
                                picked = if (on) picked - choice.value else picked + choice.value
                                LanguageProfile.setAnswers(step.id, picked.toList())
                            }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A filled dot rather than a checkbox: the row is the hit target, and a real
                        // checkbox invites clicking the box specifically.
                        Surface(
                            color = if (on) colors.accent else Color(0xFF2A2A31),
                            shape = CircleShape,
                            modifier = Modifier.size(9.dp),
                        ) {}
                        Spacer(Modifier.width(11.dp))
                        choice.emoji?.let {
                            Text(it, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(choice.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Button(enabled = picked.size >= step.minimum, onClick = onDone) {
        Text(
            if (picked.size >= step.minimum) "Continue"
            else "Choose at least " + step.minimum,
        )
    }
}

@Composable
private fun TextStep(stepId: String, hint: String, onDone: () -> Unit) {
    var value by remember(stepId) { mutableStateOf(LanguageProfile.answer(stepId).orEmpty()) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(hint, fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = value.isNotBlank(),
            onClick = {
                LanguageProfile.setAnswer(stepId, value.trim())
                onDone()
            },
        ) { Text("Continue") }
    }
}

@Composable
private fun MinutesStep(step: LanguageSetupFlow.MinutePicker, onDone: () -> Unit) {
    val colors = LocalPrismColors.current
    var minutes by remember(step.id) {
        mutableStateOf(LanguageProfile.answer(step.id)?.toIntOrNull() ?: step.default)
    }
    Column {
        Row {
            step.options.forEach { option ->
                OutlinedButton(
                    onClick = { minutes = option },
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        option.toString() + " min",
                        fontSize = 12.sp,
                        color = if (option == minutes) colors.accent else colors.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            LanguageProfile.setAnswer(step.id, minutes.toString())
            onDone()
        }) { Text("Continue") }
    }
}

@Composable
private fun TutorGridStep(step: LanguageSetupFlow.TutorGrid, onDone: () -> Unit) {
    val colors = LocalPrismColors.current
    val chosen = LanguageProfile.answer(step.id)
    var previewing by remember { mutableStateOf("") }

    // A GRID, because sixteen portraits in a column is a scroll and the choice is made by looking.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(LanguageTutors.ALL) { tutor ->
            Surface(
                color = if (tutor.id == chosen) Color(0xFF232334) else Color(0xFF1A1A20),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickableRow {
                    LanguageProfile.setAnswer(step.id, tutor.id)
                    // Heard, not just seen. A tutor is a VOICE as much as a face, and the point of
                    // sixteen of them is lost if they are chosen from pictures alone.
                    previewing = tutor.id
                    PrismSpeaker.stop()
                    PrismSpeaker.speakAs(
                        text = "Hello. I am " + tutor.name + ". I will be your tutor.",
                        voiceId = tutor.voice,
                    ) { previewing = "" }
                },
            ) {
                Column(
                    Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TutorFace(tutor.portrait, size = 84.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tutor.name,
                        fontSize = 13.sp,
                        color = if (tutor.id == chosen) colors.accent else colors.onSurface,
                    )
                    Text(tutor.accent, fontSize = 10.sp, color = colors.faint)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tutor.blurb,
                        fontSize = 10.sp,
                        color = colors.faint,
                        lineHeight = 14.sp,
                    )
                    if (tutor.id == previewing) {
                        Spacer(Modifier.height(4.dp))
                        Text("speaking…", fontSize = 9.sp, color = colors.accent)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(enabled = chosen != null, onClick = onDone) {
        Text(if (chosen == null) "Choose a tutor" else "Continue")
    }
}

// ── The plan ────────────────────────────────────────────────────────────────

@Composable
private fun PlanScreen(
    revision: Int,
    onOpenLesson: (PlannedLesson) -> Unit,
    onRedoSetup: () -> Unit,
    onChanged: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var plan by remember { mutableStateOf<LearningPlan?>(null) }
    var completed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var started by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(revision) {
        loading = true
        withContext(Dispatchers.IO) {
            plan = runCatching { LanguagePlanStore.plan() }.getOrNull()
            completed = runCatching { LanguagePlanStore.completed() }.getOrDefault(emptySet())
            started = runCatching { LanguagePlanStore.started() }.getOrDefault(emptySet())
        }
        loading = false
    }

    val target = LanguageProfile.targetLanguage()
    val tutor = LanguageProfile.tutor()

    PageScaffold(
        "Language",
        target?.let { "Learning " + it.english } ?: "An AI tutor on a CEFR syllabus",
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Reading your plan…", fontSize = 13.sp, color = colors.muted)
                }
                return@Column
            }

            val current = plan
            if (current == null) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "There is no plan on this machine yet. A plan is built from the answers " +
                                "to the setup questions and is stored with your profile, so one " +
                                "built on your phone arrives with a profile transfer.",
                            fontSize = 13.sp,
                            color = colors.muted,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRedoSetup) { Text("Set up a language") }
                    }
                }
                return@Column
            }

            // ── Where they are ─────────────────────────────────────────────
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    tutor?.let {
                        TutorFace(it.portrait, size = 64.dp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            (tutor?.name ?: "Your tutor") + " · " +
                                current.placement.name + " → " + current.goal.name,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            LanguageStats.streak().toString() + " day streak · " +
                                LanguageStats.lessonsCompleted() + " lessons · " +
                                LanguageStats.xp() + " XP (" + LanguageStats.rank() + ")",
                            fontSize = 11.sp,
                            color = colors.muted,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            LanguageStats.minutesToday().toString() + " of " +
                                current.minutesPerDay + " minutes today",
                            fontSize = 11.sp,
                            color = if (LanguageStats.goalMet()) colors.accent else colors.faint,
                        )
                    }
                }
            }

            if (current.paceNote.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Said rather than hidden. Somebody who picked "one month" for Japanese from
                    // nothing finds out eventually; being told kindly at the start is the difference
                    // between adjusting and quitting.
                    Text(
                        current.paceNote,
                        fontSize = 11.sp,
                        color = colors.muted,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── The path ───────────────────────────────────────────────────
            current.levels.forEach { level ->
                SectionHeader(
                    level.level.name.lowercase() + " · " + level.level.title.lowercase() +
                        (if (level.alreadyKnown) " · already known" else ""),
                )
                Card {
                    Column {
                        if (level.alreadyKnown) {
                            Text(
                                "Marked known from your placement rather than removed, so it is " +
                                    "there to revisit.",
                                fontSize = 11.sp,
                                color = colors.faint,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        level.lessons.forEachIndexed { i, planned ->
                            if (i > 0) Hairline()
                            LessonRow(
                                planned = planned,
                                done = planned.id in completed,
                                begun = planned.id in started,
                                onOpen = { onOpenLesson(planned) },
                                onToggleDone = {
                                    if (planned.id in completed) {
                                        LanguagePlanStore.markIncomplete(planned.id)
                                    } else {
                                        LanguagePlanStore.markComplete(planned.id)
                                    }
                                    onChanged()
                                },
                            )
                        }
                    }
                }
            }

            SectionFooter(
                "The plan, the scheduler and the lesson generator are the same code the phone runs, " +
                    "and the profile is stored under the same keys — so progress made here shows up " +
                    "there. Difficulty for this pair: " +
                    String.format("%.2f", current.difficulty) +
                    (if (current.newScript) ", with a writing system you do not already read." else ".")
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LanguagePlanStore.rebuild() }
                        onChanged()
                    }
                }) { Text("Rebuild the plan", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRedoSetup) { Text("Change the answers", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LessonRow(
    planned: PlannedLesson,
    done: Boolean,
    begun: Boolean,
    onOpen: () -> Unit,
    onToggleDone: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onOpen).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = when {
                done -> colors.accent
                begun -> Color(0xFF5A5A70)
                else -> Color(0xFF2A2A31)
            },
            shape = CircleShape,
            modifier = Modifier.size(9.dp),
        ) {}
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(planned.title, fontSize = 13.sp)
            Text(
                planned.objective,
                fontSize = 11.sp,
                color = colors.faint,
                lineHeight = 16.sp,
            )
            planned.topic?.let {
                Text("through " + it, fontSize = 10.sp, color = Color(0xFF6E6E7A))
            }
        }
        Text(
            planned.kind.name.lowercase(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF6E6E7A),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (done) "undo" else "mark done",
            fontSize = 10.sp,
            color = colors.faint,
            modifier = Modifier.clickableRow(onToggleDone).padding(4.dp),
        )
    }
}

// ── A lesson in progress ────────────────────────────────────────────────────

/**
 * One lesson, running.
 *
 * ## The runner owns the conversation, not this screen
 *
 * `LessonRunner` holds the transcript, decides when the lesson is finished, walks the picture list at
 * A0 and retries the model when what comes back does not validate. This screen sends what the learner
 * typed and renders what comes back. That division is why the same lesson behaves identically here and
 * on the phone -- including the retry-with-the-problem-named behaviour, which is where most of the
 * quality lives.
 *
 * ## Two voices per turn, deliberately
 *
 * The tutor's line goes out in the tutor's own voice. The WORD BEING TAUGHT goes out separately with
 * the target language's voice and locale, because a tutor with an American voice reading a Mandarin
 * word in that voice is not a pronunciation model -- it is the mistake `speakAs`'s separate locale
 * parameter exists to prevent.
 */
@Composable
private fun LessonScreen(planned: PlannedLesson, onLeave: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var runner by remember(planned.id) { mutableStateOf<LessonRunner?>(null) }
    var turns by remember(planned.id) { mutableStateOf<List<Line>>(emptyList()) }
    var picture by remember(planned.id) { mutableStateOf<LessonSpec.PictureItem?>(null) }
    var draft by remember(planned.id) { mutableStateOf("") }
    var thinking by remember(planned.id) { mutableStateOf(false) }
    var finished by remember(planned.id) { mutableStateOf(false) }
    var report by remember(planned.id) { mutableStateOf("") }
    var problem by remember(planned.id) { mutableStateOf("") }
    var dictation by remember(planned.id) { mutableStateOf("") }

    val tutor = remember { LanguageProfile.tutor() ?: LanguageTutors.ALL.first() }
    val plan = remember { LanguagePlanStore.plan() }
    val startedAt = remember(planned.id) { System.currentTimeMillis() }

    /** Says a tutor line, then the taught word in the language it is actually in. */
    fun speak(say: String, teach: String?) {
        PrismSpeaker.stop()
        val nativeCode = plan?.nativeCode ?: LanguageProfile.nativeLanguage().code
        val targetCode = plan?.targetCode ?: LanguageProfile.activeCode()
        PrismSpeaker.speakAs(
            text = say,
            voiceId = LanguageVoices.tutorVoice(nativeCode, tutor),
            speed = LanguageProfile.speakingSpeed(),
            localeTag = LanguageVoices.tutorLocaleTag(nativeCode, tutor),
        ) {
            if (teach.isNullOrBlank()) return@speakAs
            // Null voice is not an oversight: it means "no voice of this tutor's gender exists in
            // this language", and PrismSpeaker routes those to the system engine WITH the locale
            // rather than reading the word in the wrong language.
            PrismSpeaker.speakAs(
                text = teach,
                voiceId = LanguageVoices.targetVoice(targetCode, tutor),
                speed = LanguageProfile.speakingSpeed(),
                localeTag = LanguageVoices.localeTag(targetCode),
            )
        }
    }

    LaunchedEffect(planned.id) {
        LanguagePlanStore.markStarted(planned.id)
        val built = withContext(Dispatchers.IO) { LessonRunner.forLesson(planned.id) }
        if (built == null) {
            problem = "That lesson is no longer part of your plan."
            return@LaunchedEffect
        }
        runner = built
        thinking = true
        val opening = runCatching { built.open() }.getOrNull()
        thinking = false
        if (opening == null) {
            problem = "The tutor could not start. Check that a model is available on the Models page."
            return@LaunchedEffect
        }
        turns = listOf(Line(fromTutor = true, text = opening.say, teach = opening.teach, hint = opening.hint))
        picture = opening.picture
        speak(opening.say, opening.teach)
    }

    PageScaffold(planned.title, planned.objective) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← leave the lesson",
                    fontSize = 12.sp,
                    color = colors.faint,
                    modifier = Modifier.clickableRow {
                        PrismSpeaker.stop()
                        onLeave()
                    }.padding(4.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    planned.level.name + " · " + planned.kind.name.lowercase(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF6E6E7A),
                )
            }
            Spacer(Modifier.height(10.dp))

            if (problem.isNotBlank()) {
                Surface(
                    color = Color(0xFF331A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        problem,
                        fontSize = 12.sp,
                        color = Color(0xFFFFB4B4),
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                return@Column
            }

            // ── The picture, at A0 ─────────────────────────────────────────
            picture?.let { item ->
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (val p = PictureBank.picture(item)) {
                            is PictureBank.Picture.Emoji -> Text(p.glyph, fontSize = 64.sp)
                            is PictureBank.Picture.Photo -> {
                                // Decoded here: PictureBank hands back bytes so it can live in
                                // :core, and each platform decodes them its own way.
                                val bytes = remember(p.file.absolutePath) { PictureBank.bytes(p.file) }
                                val image = remember(p.file.absolutePath) {
                                    bytes?.let { runCatching { decodeToPainterBytes(it) }.getOrNull() }
                                }
                                if (image != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = image,
                                        contentDescription = item.word,
                                        modifier = Modifier.height(180.dp),
                                    )
                                } else {
                                    Text("🔤", fontSize = 56.sp)
                                }
                            }
                            // No picture: the word alone still teaches, and a blank panel looks
                            // broken rather than empty.
                            PictureBank.Picture.None -> Text("🔤", fontSize = 56.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(item.word, fontSize = 20.sp)
                        item.romanisation?.let {
                            Text(it, fontSize = 12.sp, color = colors.muted)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── Transcript ─────────────────────────────────────────────────
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                turns.forEach { line ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = if (line.fromTutor) Arrangement.Start else Arrangement.End,
                    ) {
                        Surface(
                            color = if (line.fromTutor) Color(0xFF1C1C24) else Color(0xFF232334),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = if (line.fromTutor) 60.dp else 0.dp)
                                .padding(start = if (line.fromTutor) 0.dp else 60.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(line.text, fontSize = 13.sp, lineHeight = 19.sp)
                                line.teach?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(5.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(it, fontSize = 15.sp, color = colors.accent)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "hear it",
                                            fontSize = 10.sp,
                                            color = colors.faint,
                                            modifier = Modifier.clickableRow {
                                                val targetCode = plan?.targetCode
                                                    ?: LanguageProfile.activeCode()
                                                PrismSpeaker.stop()
                                                PrismSpeaker.speakAs(
                                                    text = it,
                                                    voiceId = LanguageVoices.targetVoice(targetCode, tutor),
                                                    speed = LanguageProfile.speakingSpeed(),
                                                    localeTag = LanguageVoices.localeTag(targetCode),
                                                )
                                            }.padding(3.dp),
                                        )
                                    }
                                }
                                line.hint?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
                                }
                            }
                        }
                    }
                }
                if (thinking) {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tutor.name + " is thinking…", fontSize = 11.sp, color = colors.faint)
                    }
                }
            }

            if (report.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF14301F),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        report,
                        fontSize = 12.sp,
                        color = Color(0xFF9BE8B4),
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (dictation.isNotBlank()) {
                Text(dictation, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
            }

            Spacer(Modifier.height(8.dp))

            if (finished) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        PrismSpeaker.stop()
                        onLeave()
                    }) { Text("Back to the plan") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Say it back", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    // SPOKEN PRACTICE. Whisper transcribes into the same field, so a learner can
                    // answer out loud -- which is the half of the phase that needed PHASE 100's
                    // groundwork and the half a reading-only lesson cannot do.
                    MicButton(
                        enabled = !thinking,
                        onText = { heard -> draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard },
                        onStatus = { dictation = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = draft.isNotBlank() && !thinking && runner != null,
                        onClick = {
                            val active = runner ?: return@Button
                            val said = draft.trim()
                            draft = ""
                            turns = turns + Line(fromTutor = false, text = said, teach = null, hint = null)
                            thinking = true
                            scope.launch {
                                val next = runCatching { active.reply(said) }.getOrNull()
                                thinking = false
                                if (next == null) {
                                    problem = "The tutor stopped answering."
                                    return@launch
                                }
                                turns = turns + Line(
                                    fromTutor = true,
                                    text = next.say,
                                    teach = next.teach,
                                    hint = next.hint,
                                )
                                picture = next.picture ?: picture
                                speak(next.say, next.teach)

                                if (next.done) {
                                    finished = true
                                    val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L)
                                        .toInt().coerceAtLeast(1)
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching { active.finish(minutes) }.getOrNull()
                                    }
                                    LanguagePlanStore.markComplete(planned.id)
                                    report = buildString {
                                        append("Lesson finished")
                                        outcome?.report?.score?.let { append(" — scored ").append(it) }
                                        outcome?.let { append(" — ").append(it.xpEarned).append(" XP") }
                                        append(".")
                                        outcome?.report?.didWell?.let { append("  ").append(it) }
                                        outcome?.report?.toWorkOn?.let { append("  Work on: ").append(it) }
                                        if (outcome?.usedTemplate == true) {
                                            // Said rather than hidden: a lesson the model could not
                                            // carry is a different experience, and pretending it was
                                            // the same one is how a broken model goes unnoticed.
                                            append(
                                                "  Part of this lesson came from a template because " +
                                                    "the model's replies did not validate.",
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text("Send") }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** One rendered exchange. The runner keeps the authoritative transcript; this is for display. */
private data class Line(
    val fromTutor: Boolean,
    val text: String,
    val teach: String?,
    val hint: String?,
)

/**
 * Bytes to something Compose can draw.
 *
 * `ImageIO` rather than `PrismPlatform.images`, which is a WRITER -- see `RasterCodec`, whose read
 * side is deliberately absent because nothing in :core needed to decode until now. The pictures here
 * are JPEGs and PNGs from Wikimedia Commons, which the JDK reads without help, and this goes through
 * `BufferedImage` for the same reason `PrismImage.toComposeBitmap` does: that is what Compose
 * Desktop's `toComposeImageBitmap` accepts.
 */
private fun decodeToPainterBytes(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap {
    val buffered = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        ?: throw IllegalStateException("No decoder for this picture")
    return buffered.toComposeImageBitmap()
}
