package com.prism.launcher.journal

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The Journal: dated diary entries and the recipes kept beside them.
 *
 * ## One page, two kinds of writing
 *
 * A diary is a record of a day and a recipe is a procedure for a kitchen, but the shape is the
 * same: a title, a body made of ordered lines, a date, and a reason to come back to it. Keeping
 * them in one store means one page, one search box and one backup; separating them would have made
 * the user remember which of the two tabs a note lived in.
 *
 * ## Why a plain JSON file
 *
 * The journal is human-scale -- hundreds of entries, not millions -- and it is the user's writing.
 * A JSON file in the app's data directory can be copied, grepped and read by the Time Machine
 * archive without a database engine, and it does not need a schema migration every time a field
 * (mood, servings, tags) is added. The whole file is rewritten atomically on every change, which at
 * this size costs a few milliseconds.
 *
 * ## Not encrypted
 *
 * Unlike the wallet or the mail vault, a diary is not a secret Prism is holding on the user's
 * behalf; it is a document they expect to find in their own backup. Saying that here is better
 * than implying a lock that does not exist.
 */
object Journal {

    data class Entry(
        val id: String,
        val title: String,
        val body: String,
        val mood: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        val day: String get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(createdAt))
    }

    data class Recipe(
        val id: String,
        val title: String,
        val ingredients: List<String>,
        val steps: List<String>,
        val minutes: Int,
        val servings: Int,
        val tags: List<String>,
        val favourite: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        val totalMinutes: Int get() = minutes.coerceAtLeast(0)
    }

    /** The moods offered by the editor. Free text is allowed; these are shortcuts, not a schema. */
    val MOODS = listOf("calm", "good", "tired", "low", "angry", "grateful")

    private const val FILE = "journal.json"

    private val lock = Any()
    private var loaded = false
    private val entries = LinkedHashMap<String, Entry>()
    private val recipes = LinkedHashMap<String, Recipe>()

    private fun file(): File = File(PrismPlatform.host.dataDir(), FILE)

    // ── Diary ──────────────────────────────────────────────────────────────

    fun entries(): List<Entry> = synchronized(lock) {
        ensureLoaded()
        entries.values.sortedByDescending { it.createdAt }
    }

    fun entriesByDay(): List<Pair<String, List<Entry>>> = entries()
        .groupBy { it.day }
        .toSortedMap(compareByDescending { it })
        .map { (day, list) -> day to list }

    fun entry(id: String): Entry? = synchronized(lock) {
        ensureLoaded()
        entries[id]
    }

    fun addEntry(title: String, body: String, mood: String = ""): Entry = synchronized(lock) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            title = title.trim().take(160),
            body = body.trim(),
            mood = mood.trim(),
            createdAt = now,
            updatedAt = now,
        )
        entries[entry.id] = entry
        saveLocked()
        entry
    }

    fun updateEntry(
        id: String,
        title: String,
        body: String,
        mood: String,
    ): Entry? = synchronized(lock) {
        ensureLoaded()
        val existing = entries[id] ?: return null
        val updated = existing.copy(
            title = title.trim().take(160),
            body = body.trim(),
            mood = mood.trim(),
            updatedAt = System.currentTimeMillis(),
        )
        entries[id] = updated
        saveLocked()
        updated
    }

    fun deleteEntry(id: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val removed = entries.remove(id) != null
        if (removed) saveLocked()
        removed
    }

    /** Full-text search over title and body, case-insensitive. */
    fun searchEntries(query: String): List<Entry> = synchronized(lock) {
        ensureLoaded()
        val q = query.trim()
        if (q.isEmpty()) return@synchronized entries()
        entries.values
            .filter { it.title.contains(q, true) || it.body.contains(q, true) || it.mood.contains(q, true) }
            .sortedByDescending { it.createdAt }
    }

    // ── Recipes ────────────────────────────────────────────────────────────

    fun recipes(): List<Recipe> = synchronized(lock) {
        ensureLoaded()
        recipes.values.sortedWith(
            compareByDescending<Recipe> { it.favourite }.thenBy { it.title.lowercase() }
        )
    }

    fun recipe(id: String): Recipe? = synchronized(lock) {
        ensureLoaded()
        recipes[id]
    }

    fun addRecipe(
        title: String,
        ingredients: List<String>,
        steps: List<String>,
        minutes: Int = 0,
        servings: Int = 2,
        tags: List<String> = emptyList(),
    ): Recipe = synchronized(lock) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val recipe = Recipe(
            id = UUID.randomUUID().toString(),
            title = title.trim().take(160),
            ingredients = ingredients.map { it.trim() }.filter { it.isNotEmpty() },
            steps = steps.map { it.trim() }.filter { it.isNotEmpty() },
            minutes = minutes.coerceIn(0, 24 * 60),
            servings = servings.coerceIn(1, 100),
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            favourite = false,
            createdAt = now,
            updatedAt = now,
        )
        recipes[recipe.id] = recipe
        saveLocked()
        recipe
    }

    fun updateRecipe(
        id: String,
        title: String,
        ingredients: List<String>,
        steps: List<String>,
        minutes: Int,
        servings: Int,
        tags: List<String>,
    ): Recipe? = synchronized(lock) {
        ensureLoaded()
        val existing = recipes[id] ?: return null
        val updated = existing.copy(
            title = title.trim().take(160),
            ingredients = ingredients.map { it.trim() }.filter { it.isNotEmpty() },
            steps = steps.map { it.trim() }.filter { it.isNotEmpty() },
            minutes = minutes.coerceIn(0, 24 * 60),
            servings = servings.coerceIn(1, 100),
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            updatedAt = System.currentTimeMillis(),
        )
        recipes[id] = updated
        saveLocked()
        updated
    }

    fun toggleFavourite(id: String): Recipe? = synchronized(lock) {
        ensureLoaded()
        val existing = recipes[id] ?: return null
        val updated = existing.copy(
            favourite = !existing.favourite,
            updatedAt = System.currentTimeMillis(),
        )
        recipes[id] = updated
        saveLocked()
        updated
    }

    fun deleteRecipe(id: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val removed = recipes.remove(id) != null
        if (removed) saveLocked()
        removed
    }

    fun searchRecipes(query: String): List<Recipe> = synchronized(lock) {
        ensureLoaded()
        val q = query.trim()
        if (q.isEmpty()) return@synchronized recipes()
        recipes.values
            .filter { recipe ->
                recipe.title.contains(q, true) ||
                    recipe.tags.any { it.contains(q, true) } ||
                    recipe.ingredients.any { it.contains(q, true) }
            }
            .sortedWith(compareByDescending<Recipe> { it.favourite }.thenBy { it.title.lowercase() })
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            val entryArray = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entryArray.length()) {
                val o = entryArray.getJSONObject(i)
                val entry = Entry(
                    id = o.optString("id", ""),
                    title = o.optString("title", ""),
                    body = o.optString("body", ""),
                    mood = o.optString("mood", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
                if (entry.id.isNotBlank()) entries[entry.id] = entry
            }
            val recipeArray = root.optJSONArray("recipes") ?: JSONArray()
            for (i in 0 until recipeArray.length()) {
                val o = recipeArray.getJSONObject(i)
                val recipe = Recipe(
                    id = o.optString("id", ""),
                    title = o.optString("title", ""),
                    ingredients = strings(o.optJSONArray("ingredients")),
                    steps = strings(o.optJSONArray("steps")),
                    minutes = o.optInt("minutes", 0),
                    servings = o.optInt("servings", 2),
                    tags = strings(o.optJSONArray("tags")),
                    favourite = o.optBoolean("favourite", false),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
                if (recipe.id.isNotBlank()) recipes[recipe.id] = recipe
            }
        }.onFailure {
            PrismPlatform.log.warn("Journal", "Could not read the journal: ${it.message}")
        }
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) out.add(array.getString(i))
        return out
    }

    private fun saveLocked() {
        runCatching {
            val root = JSONObject()
            val entryArray = JSONArray()
            for (entry in entries.values) {
                entryArray.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("title", entry.title)
                        .put("body", entry.body)
                        .put("mood", entry.mood)
                        .put("createdAt", entry.createdAt)
                        .put("updatedAt", entry.updatedAt)
                )
            }
            val recipeArray = JSONArray()
            for (recipe in recipes.values) {
                val ingredients = JSONArray().apply { recipe.ingredients.forEach { put(it) } }
                val steps = JSONArray().apply { recipe.steps.forEach { put(it) } }
                val tags = JSONArray().apply { recipe.tags.forEach { put(it) } }
                recipeArray.put(
                    JSONObject()
                        .put("id", recipe.id)
                        .put("title", recipe.title)
                        .put("ingredients", ingredients)
                        .put("steps", steps)
                        .put("minutes", recipe.minutes)
                        .put("servings", recipe.servings)
                        .put("tags", tags)
                        .put("favourite", recipe.favourite)
                        .put("createdAt", recipe.createdAt)
                        .put("updatedAt", recipe.updatedAt)
                )
            }
            root.put("entries", entryArray).put("recipes", recipeArray)
            val target = file()
            val tmp = File(target.parentFile, FILE + ".part")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure {
            PrismPlatform.log.error("Journal", "Could not save the journal", it)
        }
    }
}
