package com.prism.launcher.language

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the learner's plan, and what they have done against it.
 *
 * ## Why the plan is stored rather than recomputed
 *
 * [PlanBuilder] is deterministic, so recomputing it on every launch would give the same answer —
 * until the day the builder changes. Then every learner's "lesson 9" would silently become a
 * different lesson, and their progress, which is keyed on lesson ids, would be pointing at content
 * they never saw. Storing the plan means an upgrade can change how plans are BUILT without
 * rewriting the plan of somebody who is halfway through one.
 *
 * [PlanBuilder.VERSION] is the escape hatch: when a change is significant enough that old plans
 * genuinely should be replaced, bumping it rebuilds them once, and the learner is told.
 *
 * ## Progress is a set of ids, and that is all
 *
 * Not an index, not a percentage. A learner can do lessons out of order, revisit a level they were
 * placed above, or skip ahead; an index cannot represent any of that, and the first time somebody
 * replays a lesson an index would wind their progress backwards.
 */
object LanguagePlanStore {

    private const val PREFS = "prism_language"

    /**
     * Keys are per language.
     *
     * A learner doing Spanish and Korean has two plans, two sets of completed lessons and two sets
     * of started ones, and sharing any of them would mean finishing lesson A1-3 in Spanish marked it
     * done in Korean. The legacy single-language keys are migrated once on first read.
     */
    private fun planKey(code: String) = "plan_json_$code"
    private fun completedKey(code: String) = "completed_lessons_$code"
    private fun startedKey(code: String) = "started_lessons_$code"
    private fun versionKey(code: String) = "plan_builder_version_$code"

    private const val LEGACY_PLAN = "plan_json"
    private const val LEGACY_COMPLETED = "completed_lessons"
    private const val LEGACY_STARTED = "started_lessons"
    private const val LEGACY_VERSION = "plan_builder_version"

    /**
     * The keys a build with a broken key builder wrote.
     *
     * The interpolation in [planKey] and friends was escaped away, so every language wrote to one
     * key spelled with a literal dollar-code. That is what made switching languages appear to fail:
     * adding a second language overwrote the first one's plan in place, so switching back read the
     * new language's plan and the page never changed. Fixing the builder is most of it; these
     * constants exist to carry the progress that landed there onto the right language rather than
     * silently dropping it.
     */
    private const val BROKEN_PLAN = "plan_json_\$code"
    private const val BROKEN_COMPLETED = "completed_lessons_\$code"
    private const val BROKEN_STARTED = "started_lessons_\$code"
    private const val BROKEN_VERSION = "plan_builder_version_\$code"

    private const val SEPARATOR = ""

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    /** The plan last read, and which language it was for. */
    @Volatile
    private var cached: Pair<String, LearningPlan>? = null

    private fun active(): String = LanguageProfile.activeCode()

    /**
     * Moves a pre-multi-language install onto the per-language keys, once.
     *
     * Without this, the first launch after the update finds no plan for the learner's language,
     * rebuilds one, and silently discards every lesson they had completed — the progress is keyed
     * on lesson ids that the old keys still hold.
     */
    private fun migrateLegacy(code: String) {
        rescueBrokenKeys()

        val p = prefs()
        val legacyPlan = p.getString(LEGACY_PLAN, null) ?: return
        if (p.getString(planKey(code), null) != null) return

        p.edit()
            .putString(planKey(code), legacyPlan)
            .putInt(versionKey(code), p.getInt(LEGACY_VERSION, -1))
            .putString(completedKey(code), p.getString(LEGACY_COMPLETED, "") ?: "")
            .putString(startedKey(code), p.getString(LEGACY_STARTED, "") ?: "")
            .remove(LEGACY_PLAN).remove(LEGACY_VERSION)
            .remove(LEGACY_COMPLETED).remove(LEGACY_STARTED)
            .apply()
        PrismLogger.logInfo(TAG, "Migrated the single-language plan onto $code")
    }

    /**
     * Puts what the shared, misspelled key holds back onto the language it belongs to.
     *
     * The plan stored there names its own target language, so there is no guessing: it is read,
     * asked what it is a plan for, and written to that language's keys. The completed and started
     * sets go with it, because they are only meaningful next to that plan's lesson ids. Runs once —
     * the broken keys are removed afterwards — and does nothing on an install that never saw the
     * broken build.
     */
    private fun rescueBrokenKeys() {
        val p = prefs()
        val stranded = p.getString(BROKEN_PLAN, null) ?: run {
            // A build could have written progress there without a plan surviving; nothing to
            // attribute it to, so drop the orphans rather than leave them to confuse a later read.
            if (p.contains(BROKEN_COMPLETED) || p.contains(BROKEN_STARTED)) {
                p.edit().remove(BROKEN_COMPLETED).remove(BROKEN_STARTED)
                    .remove(BROKEN_VERSION).apply()
            }
            return
        }

        val owner = runCatching { JSONObject(stranded).getString("target") }.getOrNull()
        val editor = p.edit()
            .remove(BROKEN_PLAN).remove(BROKEN_VERSION)
            .remove(BROKEN_COMPLETED).remove(BROKEN_STARTED)

        if (owner != null && p.getString(planKey(owner), null) == null) {
            editor
                .putString(planKey(owner), stranded)
                .putInt(versionKey(owner), p.getInt(BROKEN_VERSION, -1))
                .putString(completedKey(owner), p.getString(BROKEN_COMPLETED, "") ?: "")
                .putString(startedKey(owner), p.getString(BROKEN_STARTED, "") ?: "")
            PrismLogger.logInfo(TAG, "Recovered a plan stranded on the shared key onto $owner")
        }
        editor.apply()
    }

    /**
     * The plan, building it if setup is finished and there is not one yet.
     *
     * Null only when setup has not been completed — there is nothing to build a plan from before
     * then, and inventing one for a learner who has not said what they want to learn would be
     * worse than showing them the setup screen again.
     */
    fun plan(): LearningPlan? = plan(active())

    fun plan(code: String): LearningPlan? {
        if (code.isBlank()) return null
        cached?.let { (cachedCode, cachedPlan) -> if (cachedCode == code) return cachedPlan }
        migrateLegacy(code)

        val stored = prefs().getString(planKey(code), null)
        val storedVersion = prefs().getInt(versionKey(code), -1)

        if (stored != null && storedVersion == PlanBuilder.VERSION) {
            val parsed = runCatching { decode(JSONObject(stored)) }.getOrElse {
                PrismLogger.logWarning(TAG, "Stored plan could not be read; rebuilding")
                null
            }
            if (parsed != null) {
                cached = code to parsed
                return parsed
            }
        }

        if (!LanguageProfile.isSetUp()) return null
        return rebuild(code)
    }

    /** Recomputes from the current answers and replaces whatever was stored. */
    fun rebuild(): LearningPlan? = rebuild(active())

    fun rebuild(code: String): LearningPlan? {
        val target = LanguageCatalog.learnable(code) ?: return null
        val built = PlanBuilder.build(
            PlanBuilder.Input(
                targetCode = target.code,
                nativeCode = LanguageProfile.nativeLanguage().code,
                // Per language: reaching B1 in Spanish says nothing about Korean.
                selfLevel = LanguageProfile.startingLevelFor(code),
                canIntroduce = yesNo("can_introduce"),
                canConverse = yesNo("can_converse"),
                canUnderstand = yesNo("can_understand"),
                motivation = LanguageProfile.answer("motivation"),
                dream = LanguageProfile.answer("dream"),
                pace = LanguageProfile.answer("pace"),
                confidence = LanguageProfile.answer("confidence"),
                wantsPronunciation = LanguageProfile.wantsPronunciationHelp(),
                wantsChallenge = yesNo("challenge") == true,
                practiceStyle = LanguageProfile.answer("practice_style"),
                interests = LanguageProfile.answers("interests"),
                roleplay = LanguageProfile.answers("roleplay"),
                skills = LanguageProfile.answers("skills"),
                minutesPerDay = LanguageProfile.dailyMinutes(),
            )
        )

        prefs().edit()
            .putString(planKey(code), encode(built).toString())
            .putInt(versionKey(code), PlanBuilder.VERSION)
            .apply()
        cached = code to built

        PrismLogger.logInfo(
            TAG,
            "Built a ${built.totalLessons}-lesson plan for ${built.targetCode}: " +
                "${built.placement.code} to ${built.goal.code}, difficulty " +
                String.format("%.2f", built.difficulty)
        )
        return built
    }

    /** Throws the plan and the progress away. Used by "start over" in the page. */
    fun clear() = clear(active())

    fun clear(code: String) {
        cached = null
        prefs().edit()
            .remove(planKey(code)).remove(completedKey(code))
            .remove(startedKey(code)).remove(versionKey(code))
            .apply()
    }

    private fun yesNo(stepId: String): Boolean? = when (LanguageProfile.answer(stepId)) {
        "yes" -> true
        "no" -> false
        else -> null
    }

    // -- Progress -------------------------------------------------------------

    fun completed(): Set<String> = completed(active())

    fun completed(code: String): Set<String> =
        prefs().getString(completedKey(code), null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun isComplete(lessonId: String): Boolean = lessonId in completed()

    fun markComplete(lessonId: String) {
        val code = active()
        val now = completed(code) + lessonId
        prefs().edit().putString(completedKey(code), now.joinToString(SEPARATOR)).apply()
    }

    fun markIncomplete(lessonId: String) {
        val code = active()
        val now = completed(code) - lessonId
        prefs().edit().putString(completedKey(code), now.joinToString(SEPARATOR)).apply()
    }

    /**
     * Lessons opened but not finished.
     *
     * Kept separately from [completed] so the button can say "Continue" rather than "Start" — which
     * is a small thing that matters, because a learner who left halfway through needs to know the
     * app remembers that, not be offered the same beginning again as though nothing happened.
     */
    fun started(): Set<String> =
        prefs().getString(startedKey(active()), null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun isStarted(lessonId: String): Boolean = lessonId in started()

    fun markStarted(lessonId: String) {
        prefs().edit()
            .putString(startedKey(active()), (started() + lessonId).joinToString(SEPARATOR))
            .apply()
    }

    /** Lessons done in a level, over lessons in it. For the ring on the level marker. */
    fun progressOf(level: PlannedLevel): Float {
        if (level.lessons.isEmpty()) return 0f
        val done = completed()
        return level.lessons.count { it.id in done } / level.lessons.size.toFloat()
    }

    // -- Storage --------------------------------------------------------------

    private fun encode(plan: LearningPlan): JSONObject = JSONObject().apply {
        put("version", plan.version)
        put("generatedAt", plan.generatedAt)
        put("target", plan.targetCode)
        put("native", plan.nativeCode)
        put("placement", plan.placement.code)
        put("goal", plan.goal.code)
        put("minutesPerDay", plan.minutesPerDay)
        put("difficulty", plan.difficulty)
        put("newScript", plan.newScript)
        put("paceNote", plan.paceNote)
        put("levels", JSONArray().apply {
            plan.levels.forEach { level ->
                put(JSONObject().apply {
                    put("level", level.level.code)
                    put("known", level.alreadyKnown)
                    put("minutes", level.estimatedMinutes)
                    put("lessons", JSONArray().apply {
                        level.lessons.forEach { lesson ->
                            put(JSONObject().apply {
                                put("id", lesson.id)
                                put("index", lesson.index)
                                put("kind", lesson.kind.name)
                                put("title", lesson.title)
                                put("objective", lesson.objective)
                                lesson.topic?.let { put("topic", it) }
                                put("sessions", lesson.sessions)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun decode(json: JSONObject): LearningPlan {
        val levelsJson = json.getJSONArray("levels")
        val levels = (0 until levelsJson.length()).map { i ->
            val levelJson = levelsJson.getJSONObject(i)
            val level = Cefr.Level.of(levelJson.getString("level"))
            val lessonsJson = levelJson.getJSONArray("lessons")
            PlannedLevel(
                level = level,
                alreadyKnown = levelJson.optBoolean("known", false),
                estimatedMinutes = levelJson.optInt("minutes", 0),
                lessons = (0 until lessonsJson.length()).map { j ->
                    val lessonJson = lessonsJson.getJSONObject(j)
                    PlannedLesson(
                        id = lessonJson.getString("id"),
                        level = level,
                        index = lessonJson.optInt("index", j + 1),
                        kind = runCatching { Cefr.LessonKind.valueOf(lessonJson.getString("kind")) }
                            .getOrDefault(Cefr.LessonKind.CONVERSATION),
                        title = lessonJson.getString("title"),
                        objective = lessonJson.optString("objective", lessonJson.getString("title")),
                        topic = lessonJson.optString("topic", "").ifBlank { null },
                        sessions = lessonJson.optInt("sessions", 1),
                    )
                },
            )
        }

        return LearningPlan(
            version = json.optInt("version", PlanBuilder.VERSION),
            generatedAt = json.optLong("generatedAt", 0L),
            targetCode = json.getString("target"),
            nativeCode = json.optString("native", "en"),
            placement = Cefr.Level.of(json.getString("placement")),
            goal = Cefr.Level.of(json.getString("goal")),
            levels = levels,
            minutesPerDay = json.optInt("minutesPerDay", 15),
            difficulty = json.optDouble("difficulty", 1.0),
            newScript = json.optBoolean("newScript", false),
            paceNote = json.optString("paceNote", ""),
        )
    }

    private const val TAG = "PrismLanguage"
}
