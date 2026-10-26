package com.prism.launcher.characters

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.core.PrismPlatform
import java.io.File

/**
 * One character's transcript.
 *
 * ## Why every character gets its own file
 *
 * A character is a persona layered over Sam, Nora or Aether, and those three each keep exactly one
 * conversation of their own. Character conversations are therefore not the backend's history --
 * they are the screen's history, and two characters on the same backend must not share a
 * transcript or one persona's remarks would appear in the other's chat. The file lives beside the
 * character's imported model and image, keyed by its id.
 *
 * ## Why a flat JSON file rather than a Room table
 *
 * The same reason as Nora's transcript: Prism's Room database migrates destructively, so adding a
 * table means bumping the schema and wiping every other table on upgrade. A transcript is a small,
 * append-only list and does not justify that.
 */
object CharacterChatStore {

    data class Line(
        val text: String,
        val fromUser: Boolean,
        val at: Long = System.currentTimeMillis(),
    )

    /** Bounded like the other transcripts: a chat that grows forever eventually costs a scroll. */
    private const val MAX_LINES = 400

    @Synchronized
    fun load(characterId: String): List<Line> {
        val file = fileFor(characterId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString("text")
                if (text.isBlank()) return@mapNotNull null
                Line(
                    text = text,
                    fromUser = item.optBoolean("sent"),
                    at = item.optLong("ts", System.currentTimeMillis()),
                )
            }
        }.getOrElse {
            PrismPlatform.log.error("Characters", "Could not read ${file.absolutePath}", it)
            emptyList()
        }
    }

    @Synchronized
    fun append(characterId: String, line: Line) {
        val all = (load(characterId) + line).takeLast(MAX_LINES)
        write(characterId, all)
    }

    @Synchronized
    fun clear(characterId: String) {
        fileFor(characterId).delete()
    }

    private fun write(characterId: String, lines: List<Line>) {
        val file = fileFor(characterId)
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (line in lines) {
                array.put(
                    JSONObject().apply {
                        put("text", line.text)
                        put("sent", line.fromUser)
                        put("ts", line.at)
                    }
                )
            }
            file.writeText(array.toString())
        }.onFailure {
            PrismPlatform.log.error("Characters", "Could not write ${file.absolutePath}", it)
        }
    }

    private fun fileFor(characterId: String): File =
        File(CharacterStore.assetDir(characterId), "conversation.json")
}
