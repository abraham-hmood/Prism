package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * User-created voices: the recorded speaker's reference audio, plus where Kokoro speaks from.
 *
 * ## What is stored, and what is not
 *
 * A clone is not a Kokoro style tensor -- none exists for an arbitrary speaker, as [VoiceCloner]
 * explains -- so nothing here writes one for a NEW voice. What is stored is:
 *
 *  - the reference recording itself (`<id>.ref`, PCM16 at [VoiceCloner.WORK_RATE], trimmed and
 *    levelled) -- the engine-neutral part. Every engine can derive whatever it needs from it, so
 *    a clone works through whichever [CloneEngine] is active and switching engines needs no
 *    re-record;
 *  - the median pitch of that recording (registry field `pitch`), used by [VoicePitch.match] to
 *    move Kokoro's base-voice pitch onto the speaker's;
 *  - the id of a published voice -- Kokoro speaks the words through it before any engine runs;
 *  - per-engine cached fingerprints where an engine wants one (OpenVoice's 256-value tone
 *    direction in `<id>.tone`, kNN-VC's feature matrix in `<id>.knnfeat`), computed lazily if
 *    the clone was saved while another engine was active.
 *
 * Old clones from before this design keep their style tensor on disk and still speak; they have
 * no reference recording, so they speak through OpenVoice (the only engine that ever saw them).
 *
 * ## Languages
 *
 * A clone's language names the voice family it speaks through. It is stored as a display string so
 * a user-added language -- "Klingon", "Swahili", anything typed into the add-language dialog -- is
 * no harder to keep than a built-in one. The optional code ties the entry to a
 * [KokoroVoices.Language] when there is one, which is what picks the published voice.
 */
object ClonedVoices {

    const val ID_PREFIX = "xc_"

    data class Entry(
        val id: String,
        val displayName: String,
        /** The built-in language, or null when this voice's language was typed by the user. */
        val language: KokoroVoices.Language?,
        /** The user-typed language name, or null for a built-in one. */
        val customLanguage: String?,
        val gender: KokoroVoices.Gender,
        /** The published voice this clone speaks through; null only on legacy tensor clones. */
        val baseVoiceId: String?,
        /** Median F0 of the recording in Hz; 0 for legacy entries that never stored it. */
        val pitchHz: Float = 0f,
    ) {
        /** What every row shows: the built-in display name, or the name the user gave it. */
        val languageDisplay: String
            get() = customLanguage ?: language?.display ?: "—"
    }

    /** Re-read whenever the registry file's stamp changes. */
    private data class Cache(val stamp: Pair<Long, Long>, val entries: List<Entry>, val custom: List<String>)

    private var cache: Cache? = null

    private fun registryFile(): File = File(KokoroInstall.homeDir(), "cloned_voices.json")

    private fun stampOf(file: File): Pair<Long, Long> = file.lastModified() to file.length()

    /** Every clone. */
    fun all(): List<Entry> = readCache().entries

    private fun readCache(): Cache {
        val file = registryFile()
        if (!file.isFile) return Cache(-1L to 0L, emptyList(), emptyList()).also { cache = it }
        val stamp = stampOf(file)
        cache?.let { if (it.stamp == stamp) return it }
        val parsed = runCatching { parse(file, stamp) }.getOrElse {
            Cache(stamp, emptyList(), emptyList())
        }
        cache = parsed
        return parsed
    }

    private fun parse(file: File, stamp: Pair<Long, Long>): Cache {
        val root = JSONObject(file.readText())
        val entries = buildList {
            val array = root.optJSONArray("voices") ?: return@buildList
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (!id.startsWith(ID_PREFIX)) continue
                val code = item.optString("language", "").firstOrNull()
                val language = code?.let { KokoroVoices.Language.of(it) }
                val custom = item.optString("languageName", "").ifEmpty { null }
                val gender = if (item.optString("gender") == "male") {
                    KokoroVoices.Gender.MALE
                } else {
                    KokoroVoices.Gender.FEMALE
                }
                add(
                    Entry(
                        id = id,
                        displayName = item.optString("name", id),
                        language = language,
                        customLanguage = custom,
                        gender = gender,
                        baseVoiceId = item.optString("base", "").ifEmpty { null },
                        pitchHz = item.optDouble("pitch", 0.0).toFloat().coerceAtLeast(0f),
                    ),
                )
            }
        }
        val customLanguages = buildList {
            val array = root.optJSONArray("customLanguages") ?: return@buildList
            for (i in 0 until array.length()) {
                array.optString(i, "").trim().ifEmpty { null }?.let { add(it) }
            }
        }
        return Cache(stamp, entries, customLanguages)
    }

    fun find(id: String): Entry? = all().firstOrNull { it.id == id }

    fun isCloned(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** The stored tone direction, or null when this is a legacy tensor-only clone. */
    fun toneFor(id: String): FloatArray? {
        val file = toneFile(id)
        if (!file.isFile || file.length() < 256L * 4) return null
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (buffer.remaining() < 256) return null
        return FloatArray(256) { buffer.get() }
    }

    /** Writes OpenVoice's tone direction for [id] (`<id>.tone`, raw 256 LE floats). */
    fun writeTone(id: String, tone: FloatArray) {
        KokoroInstall.voicesDir().mkdirs()
        val bytes = ByteBuffer.allocate(tone.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in tone) bytes.putFloat(value)
        toneFile(id).writeBytes(bytes.array())
    }

    /**
     * The reference recording of [id] -- trimmed, levelled speech at [VoiceCloner.WORK_RATE] --
     * or null for a clone saved before recordings were kept (legacy tone-only clones).
     */
    fun refFor(id: String): FloatArray? {
        val file = refFile(id)
        if (!file.isFile || file.length() < 2) return null
        val bytes = file.readBytes()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    internal fun writeRef(id: String, samples: FloatArray) {
        KokoroInstall.voicesDir().mkdirs()
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) {
            bytes.putShort((value * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        refFile(id).writeBytes(bytes.array())
    }

    /**
     * An engine's cached per-voice artifact ([suffix] is engine-chosen: `knnfeat`, ...), or null
     * when it has not been computed. Layout: int32 LE count, then that many LE floats.
     */
    fun cachedFloats(id: String, suffix: String): FloatArray? {
        val file = cacheFile(id, suffix)
        if (!file.isFile || file.length() < 4) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        if (count <= 0 || bytes.size < 4 + count * 4) return null
        return FloatArray(count) { buffer.float }
    }

    /** Stores an engine's cached artifact; see [cachedFloats] for the layout. */
    fun writeCache(id: String, suffix: String, values: FloatArray) {
        KokoroInstall.voicesDir().mkdirs()
        val bytes = ByteBuffer.allocate(4 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(values.size)
        for (value in values) bytes.putFloat(value)
        cacheFile(id, suffix).writeBytes(bytes.array())
    }

    /** The published voice a new clone speaks through, given the language the user picked. */
    fun baseVoiceFor(
        language: KokoroVoices.Language?,
        gender: KokoroVoices.Gender,
    ): KokoroVoices.Voice? {
        val exact = language?.let { VoiceCloner.baseVoiceFor(it, gender) }
        return exact ?: VoiceCloner.baseVoiceFor(KokoroVoices.Language.AMERICAN_ENGLISH, gender)
    }

    /**
     * Stores [region] (at [sampleRate]) and the speaker's [pitchHz] as a new voice.
     *
     * Downloads the ACTIVE engine's models and the base voice if they are missing -- [onStage]
     * hears about it -- then hands the recording to the engine for its per-voice preparation and
     * writes the registry. If preparation fails, everything written so far is rolled back: a
     * half-registered voice that cannot speak is worse than no voice. Returns the new id, or
     * throws with a message worth showing.
     */
    fun save(
        displayName: String,
        language: KokoroVoices.Language?,
        customLanguage: String?,
        gender: KokoroVoices.Gender,
        region: FloatArray,
        sampleRate: Int,
        pitchHz: Float,
        onStage: (String) -> Unit = {},
    ): String {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Give this voice a name" }

        val base = baseVoiceFor(language, gender)
            ?: throw IllegalStateException("No published voice is available to build on")

        val engine = CloneEngines.active()
        if (!engine.isInstalled()) onStage("Fetching ${engine.label}…")
        engine.ensureInstalled { model, copied, total ->
            if (total > 0) {
                onStage("Fetching ${model.label}… ${(copied * 100 / total).toInt()}%")
            }
        }?.let {
            PrismPlatform.log.error(TAG, "Could not fetch the ${engine.label} models: $it")
            throw IllegalStateException("Could not fetch the conversion models: $it")
        }

        if (!KokoroInstall.isVoiceInstalled(base.id)) {
            onStage("Fetching ${base.displayName}…")
            KokoroInstall.downloadVoice(base.id)?.let {
                PrismPlatform.log.error(TAG, "Could not fetch base voice ${base.id}: $it")
                throw IllegalStateException("Could not fetch ${base.displayName}: $it")
            }
        }

        val reference = if (sampleRate == VoiceCloner.WORK_RATE) region
        else VoicePitch.resample(region, sampleRate, VoiceCloner.WORK_RATE)

        val id = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
        writeRef(id, reference)
        writeRegistry(all() + Entry(id, name, language, customLanguage, gender, base.id, pitchHz))

        try {
            onStage("Listening to your voice…")
            engine.prepare(id, reference, onStage)
        } catch (failure: Throwable) {
            PrismPlatform.log.error(TAG, "Could not prepare the voice with ${engine.label}", failure)
            remove(id)
            throw if (failure is IllegalStateException) failure
            else IllegalStateException("Could not read that voice: ${failure.message}")
        }

        PrismPlatform.log.info(TAG, "Saved cloned voice $id ($name) on ${base.id} via ${engine.id}")
        return id
    }

    fun remove(id: String) {
        if (!isCloned(id)) return
        // Every per-voice artifact any engine has written: tone, reference, feature caches, and
        // the legacy style tensor, which lives under a different name in the same directory.
        KokoroInstall.voicesDir().listFiles()
            ?.filter { it.name.startsWith("$id.") }
            ?.forEach { runCatching { it.delete() } }
        KokoroInstall.voiceFile(id).delete() // legacy clones only; a no-op for new ones
        writeRegistry(all().filter { it.id != id })
    }

    /** Languages the user has typed in, in the order they were added. */
    fun customLanguages(): List<String> = readCache().custom

    fun addCustomLanguage(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val existing = customLanguages()
        if (existing.any { it.equals(trimmed, ignoreCase = true) }) return trimmed
        writeCustomLanguages(existing + trimmed)
        return trimmed
    }

    private fun toneFile(id: String): File = File(KokoroInstall.voicesDir(), "$id.tone")

    private fun refFile(id: String): File = File(KokoroInstall.voicesDir(), "$id.ref")

    private fun cacheFile(id: String, suffix: String): File =
        File(KokoroInstall.voicesDir(), "$id.$suffix")

    private fun writeCustomLanguages(languages: List<String>) {
        val root = readRoot()
        root.put("customLanguages", JSONArray().also { array -> languages.forEach { array.put(it) } })
        writeRoot(root)
    }

    internal fun writeRegistry(entries: List<Entry>) {
        val root = readRoot()
        val voices = JSONArray()
        entries.forEach { entry ->
            val item = JSONObject()
                .put("id", entry.id)
                .put("name", entry.displayName)
                .put("gender", if (entry.gender == KokoroVoices.Gender.MALE) "male" else "female")
            entry.language?.let { item.put("language", it.code.toString()) }
            entry.customLanguage?.let { item.put("languageName", it) }
            entry.baseVoiceId?.let { item.put("base", it) }
            if (entry.pitchHz > 0f) item.put("pitch", entry.pitchHz.toDouble())
            voices.put(item)
        }
        root.put("voices", voices)
        writeRoot(root)
    }

    private fun readRoot(): JSONObject {
        val file = registryFile()
        return if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        else JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        KokoroInstall.homeDir().mkdirs()
        registryFile().writeText(root.toString())
        cache = null
    }

    private const val TAG = "PrismSpeech"
}
