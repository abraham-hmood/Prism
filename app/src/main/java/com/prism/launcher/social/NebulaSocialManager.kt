package com.prism.launcher.social

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.AiManager
import com.prism.launcher.messaging.ImageGenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

object NebulaSocialManager {

    /** Always keep at least this many AI-invented personas around so there's someone to see/message. */
    private const val MIN_BOT_FLOOR = 3

    /** Once the floor is met, chance per generation cycle of inventing a brand new persona anyway. */
    private const val NEW_BOT_CHANCE = 0.2

    /**
     * Entry point for generating new AI content.
     * @param manual If true, ignores the Idle/Charging check (user-initiated).
     */
    suspend fun generateNewContent(context: Context, manual: Boolean = false) = withContext(Dispatchers.IO) {
        val mode = PrismSettings.getAiMode()
        val isCloud = mode == PrismSettings.AI_MODE_CLOUD

        // Check constraints for automatic background generation
        if (!manual) {
            val isCharging = isDeviceCharging(context)
            val isIdle = isDeviceIdle(context)

            if (isCloud) {
                // Cloud can generate in background always, or idle/charging
                // No strict blockade
            } else {
                // Local MUST be idle + charging
                if (!isCharging || !isIdle) return@withContext
            }
        }

        // 1. Make sure there's always someone around, and keep the community growing over time —
        // personas are invented by the AI model itself, never hardcoded.
        val db = AppDatabase.get()
        val existingBotCount = db.socialDao().getAllBots().size
        if (existingBotCount < MIN_BOT_FLOOR || Math.random() < NEW_BOT_CHANCE) {
            generateNewBot(context)
        }

        // 2. Select a random bot to post
        val bots = db.socialDao().getAllBots()
        if (bots.isEmpty()) return@withContext

        val bot = bots.random()

        // 3. Generate content via AiManager
        val prompt = personaVoiceBriefing(bot) +
                     " Write a single short social media post, under 200 characters, in your own voice. " +
                     "It can be about literally anything a real person might post — your day, a hot take, something funny that happened, a hobby, a random thought, a question for your followers — it does NOT have to be about tech, Prism, or the mesh. Only write about that stuff if it's genuinely what you'd be into. Don't use hashtags unless they truly fit your voice. Do not include any @handles or usernames — yours or anyone else's — a fresh post has nobody to address. " +
                     "After the post text, include the tag [VISUAL] followed by a highly descriptive prompt for a realistic image representing this post."

        val (rawResponseText, attachment) = AiManager.getResponse(context, prompt)
        val responseText = sanitizePersonaOutput(rawResponseText, bot)

        // 4. Handle Visual Generation
        val visualPrompt = ImageGenManager.extractVisualPrompt(responseText)
        val finalContent = sanitizePersonaOutput(responseText.substringBefore("[VISUAL]").trim(), bot)

        var finalImageUrl = attachment.first
        if (visualPrompt != null) {
            val generatedUri = ImageGenManager.generateImage(context, visualPrompt)
            if (generatedUri != null) {
                finalImageUrl = generatedUri.toString()
            }
        }

        // 5. Persistence
        val post = SocialPostEntity(
            postId = UUID.randomUUID().toString(),
            authorId = bot.botId,
            authorName = bot.name,
            authorHandle = bot.handle,
            authorAvatarUrl = bot.avatarUrl,
            content = finalContent,
            imageUrl = finalImageUrl,
            likesCount = (10..500).random(),
            repostCount = (1..50).random()
        )

        db.socialDao().insertPost(post)

        // Notify the user if they follow this persona
        if (db.socialDao().isFollowing(bot.botId)) {
            NebulaNotifier.notifyNewPost(context, bot, finalContent)
        }

        // Every generation cycle — manual refresh or the periodic background cycle (SocialBotWorker,
        // interval configured by the user) — also drops a comment from an existing persona onto a
        // randomly selected existing post, so threads keep growing even on posts nobody just made.
        val allPosts = db.socialDao().getAllPosts()
        if (allPosts.isNotEmpty()) {
            val targetPost = allPosts.random()
            val commenterPool = bots.filter { it.botId != targetPost.authorId }.ifEmpty { bots }
            generateBotComment(context, commenterPool.random(), targetPost)
        }
        if (Math.random() < 0.1) {
            generateBotDM(context, bot)
        }
    }

    /**
     * Generates one comment from [bot] — either a top-level reply to [post] (when [parentComment]
     * is null) or a nested reply to [parentComment] (a reply to a reply, and so on). Either way the
     * model is always told what it's actually replying to, so the result stays relevant instead of
     * generically riffing on nothing.
     */
    suspend fun generateBotComment(
        context: Context,
        bot: SocialBotEntity,
        post: SocialPostEntity,
        parentComment: SocialCommentEntity? = null,
        visionTags: String = ""
    ): SocialCommentEntity = withContext(Dispatchers.IO) {
        val db = AppDatabase.get()

        val contextText = if (visionTags.isNotEmpty()) "The user posted an image. It contains: $visionTags. " else ""
        val prompt = if (parentComment == null) {
            personaVoiceBriefing(bot) +
                " Someone named ${post.authorName} (handle ${post.authorHandle}) posted this: '${post.content}'. $contextText" +
                "React to it with a NEW reply of your own — do not repeat, quote, restate, or closely paraphrase their words back at them. Add your own take: agree or disagree, riff on it, crack a joke, ask a follow-up question, share a related thought. If someone couldn't tell your reply apart from the original post, rewrite it. " +
                "You may address them by their handle (${post.authorHandle}) if it feels natural, but you don't have to. Keep it short, direct, and in your own voice — the way you'd actually reply to a friend's post, not a formal response."
        } else {
            personaVoiceBriefing(bot) +
                " ${parentComment.authorName} wrote this comment: '${parentComment.content}'. " +
                "Reply directly to what they said with a NEW reply of your own — do not repeat, quote, restate, or closely paraphrase their words back at them; add your own take, agreement/disagreement, a joke, or a follow-up. Do not use any @handles or usernames in this reply, including theirs — just make it clear from the wording that you're responding to them. " +
                "You don't know the original post this thread is under, so don't reference or guess at it — stay focused only on their comment. Keep it short and in your own voice."
        }

        val sourceText = parentComment?.content ?: post.content
        val (rawCommentText, _) = AiManager.getResponse(context, prompt)
        var finalText = sanitizePersonaOutput(rawCommentText, bot)

        // Weak local models sometimes ignore the "don't repeat it back" instruction outright —
        // if that happened, force exactly one retry with a blunter correction rather than posting
        // a near-duplicate of what it's supposedly reacting to.
        if (isNearDuplicate(finalText, sourceText)) {
            val retryPrompt = "$prompt\n\nYour previous attempt just repeated the original text almost word-for-word — that is not a reply, that's copying. Write a completely different, genuinely new reaction in your own words."
            val (retryRaw, _) = AiManager.getResponse(context, retryPrompt)
            finalText = sanitizePersonaOutput(retryRaw, bot)
        }

        val comment = SocialCommentEntity(
            postId = post.postId,
            parentCommentId = parentComment?.commentId,
            authorId = bot.botId,
            authorName = bot.name,
            authorHandle = bot.handle,
            authorAvatarUrl = bot.avatarUrl,
            content = finalText
        )
        db.socialDao().insertComment(comment)
        comment
    }

    /**
     * Generates one new top-level comment on [post] from a random existing persona — used by the
     * post-detail view's pull-to-refresh, so revisiting a thread can surface new activity without
     * running the full [generateNewContent] cycle (which also invents new bots/posts).
     */
    suspend fun generateCommentOnPost(context: Context, post: SocialPostEntity): SocialCommentEntity? = withContext(Dispatchers.IO) {
        val bots = AppDatabase.get().socialDao().getAllBots()
        if (bots.isEmpty()) return@withContext null
        val pool = bots.filter { it.botId != post.authorId }.ifEmpty { bots }
        generateBotComment(context, pool.random(), post)
    }

    /**
     * Generates one new nested reply to [parentComment] from a random existing persona — used by
     * the reply-detail view's pull-to-refresh. [post] carries the original post's content so the
     * reply stays grounded in the whole thread, not just the immediate comment.
     */
    suspend fun generateReplyToComment(context: Context, post: SocialPostEntity, parentComment: SocialCommentEntity): SocialCommentEntity? = withContext(Dispatchers.IO) {
        val bots = AppDatabase.get().socialDao().getAllBots()
        if (bots.isEmpty()) return@withContext null
        val pool = bots.filter { it.botId != parentComment.authorId }.ifEmpty { bots }
        generateBotComment(context, pool.random(), post, parentComment)
    }

    suspend fun generateBotDM(context: Context, bot: SocialBotEntity) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get()
        val prompt = personaVoiceBriefing(bot) +
                     " Send a short, casual direct message to the user to kick off a conversation — something in your own voice, not a generic greeting. Under 150 characters."

        val (rawDmText, _) = AiManager.getResponse(context, prompt)
        val msg = SocialMessageEntity(
            chatId = bot.botId,
            senderId = bot.botId,
            content = sanitizePersonaOutput(rawDmText, bot)
        )
        db.socialDao().insertMessage(msg)
    }

    suspend fun handleUserMessage(
        context: Context, chatId: String, content: String,
        onToken: ((String) -> Unit)? = null, onReasoning: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get()

        // Always persist what the user typed first — never let a missing/stale bot record
        // silently swallow the message with no feedback.
        db.socialDao().insertMessage(SocialMessageEntity(chatId = chatId, senderId = "user", content = content))

        val bot = db.socialDao().getBot(chatId)
        if (bot == null) {
            db.socialDao().insertMessage(SocialMessageEntity(
                chatId = chatId,
                senderId = "system",
                content = "This persona no longer exists, so it can't reply. Try messaging someone from the Nebula feed."
            ))
            return@withContext
        }

        // Generate AI response
        val prompt = personaVoiceBriefing(bot) +
                     " Reply to the user's message: '$content'. Stay in character, keep it personal and conversational — like you're texting a friend, not answering a support ticket."

        val (rawResponseText, _) = AiManager.getResponse(context, prompt, onToken = onToken, onReasoning = onReasoning)
        val responseText = sanitizePersonaOutput(rawResponseText, bot)
        db.socialDao().insertMessage(SocialMessageEntity(chatId = chatId, senderId = bot.botId, content = responseText))
    }

    private fun isDeviceCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isDeviceIdle(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pm.isDeviceIdleMode || !pm.isInteractive
        } else {
            !pm.isInteractive
        }
    }

    /**
     * Invents a brand new AI persona (name/handle/personaType/personality/bio) via the active AI
     * model — local or cloud, both go through [AiManager.getResponse] — and stores it. The
     * invented [SocialBotEntity.name]/[SocialBotEntity.handle] are always what gets used as the
     * persona's identity (never a hardcoded placeholder like "Prism AI") — if the model's
     * response can't be parsed into at least a usable name, this cycle is skipped entirely rather
     * than falling back to a generic identity (mirrors SocialBotWorker's try/catch + retry
     * pattern).
     */
    private suspend fun generateNewBot(context: Context): SocialBotEntity? {
        val db = AppDatabase.get()
        val existing = db.socialDao().getAllBots()
        val existingHandles = existing.map { it.handle.removePrefix("@").lowercase() }.toSet()
        val existingNames = existing.map { it.name.lowercase() }.toSet()

        val prompt = "Invent a brand new, unique fictional person for a social network called Nebula — a full individual with their own name, voice, and interests, not a company/brand/assistant account. " +
                     (if (existingNames.isNotEmpty()) "Make them clearly different from these existing users: ${existingNames.take(15).joinToString(", ")}. " else "") +
                     "Respond with EXACTLY these five lines and nothing else, no extra commentary:\n" +
                     "Name: <a unique, believable human display name>\n" +
                     "Handle: <a unique handle, lowercase letters/numbers/underscores only, no spaces, no @>\n" +
                     "Persona: <one or two words describing their vibe, e.g. tech visionary, gamer, artist>\n" +
                     "Personality: <2-3 sentences covering what they're actually into day to day, their tone/speaking style (e.g. sarcastic and terse, warm and rambly, deadpan, upbeat with lots of emoji, all-lowercase texting energy, etc.), and one quirk or recurring thing about them>\n" +
                     "Bio: <one short sentence bio, under 100 characters>"

        val (rawResponseText, _) = try {
            AiManager.getResponse(context, prompt)
        } catch (e: Exception) {
            return null
        }
        val responseText = sanitizePersonaOutput(rawResponseText)

        var name: String? = null
        var handle: String? = null
        var persona: String? = null
        var personality: String? = null
        var bio: String? = null
        responseText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Name:", ignoreCase = true) -> name = line.substringAfter(":").trim()
                line.startsWith("Handle:", ignoreCase = true) -> handle = line.substringAfter(":").trim()
                line.startsWith("Persona:", ignoreCase = true) -> persona = line.substringAfter(":").trim()
                line.startsWith("Personality:", ignoreCase = true) -> personality = line.substringAfter(":").trim()
                line.startsWith("Bio:", ignoreCase = true) -> bio = line.substringAfter(":").trim()
            }
        }

        // No usable name means the model's response couldn't be parsed at all — skip this
        // cycle rather than inventing a fake/blank persona.
        val finalName = name?.takeIf { it.isNotBlank() } ?: return null

        var finalHandle = slugifyHandle(handle?.takeIf { it.isNotBlank() } ?: finalName)
        if (finalHandle.isBlank()) finalHandle = "user${(1000..9999).random()}"
        if (finalHandle in existingHandles) finalHandle = "$finalHandle${(10..99).random()}"

        val finalPersona = persona?.takeIf { it.isNotBlank() } ?: "creator"
        val finalPersonality = personality?.takeIf { it.isNotBlank() }
            ?: "Talks casually about being a $finalPersona — keeps things upbeat, down to earth, and never sounds like a corporate or assistant account."
        val finalBio = bio?.takeIf { it.isNotBlank() } ?: "Synthesizing experiences as a $finalPersona in the Prism mesh."

        var avatarUri: android.net.Uri? = null
        if (PrismSettings.isLocalImageModelImported() || PrismSettings.getAiMode() == PrismSettings.AI_MODE_CLOUD) {
            avatarUri = ImageGenManager.generateImage(
                context,
                "A realistic, high-quality profile portrait of a person who is a $finalPersona, futuristic style, neon lighting, digital art."
            )
        }

        val bot = SocialBotEntity(
            botId = "bot_${UUID.randomUUID()}",
            name = finalName,
            handle = "@$finalHandle",
            bio = finalBio,
            avatarUrl = avatarUri?.toString() ?: "https://i.pravatar.cc/150?u=$finalHandle",
            personaType = finalPersona,
            personality = finalPersonality
        )
        db.socialDao().insertBot(bot)
        return bot
    }

    /**
     * The shared voice/identity instruction every persona-driven prompt (post, comment, DM,
     * reply) is built on top of — this is what keeps a given AI user's writing consistent with
     * who they are instead of drifting into generic assistant phrasing, and it's model-agnostic:
     * the same briefing is sent whether the active engine is a small local GGUF model, an Ollama
     * model, or a cloud model, so persona consistency doesn't depend on which backend is active.
     *
     * Deliberately does NOT phrase this as "You are Name (@handle), write a post" — that framing
     * strongly invites models (especially instruction-tuned ones) to echo the name/handle back as
     * a self-introduction or transcript-style "Name: ..." prefix. The identity is given as
     * background context only, with an explicit, repeated instruction not to state it.
     */
    private fun personaVoiceBriefing(bot: SocialBotEntity): String {
        val personality = bot.personality.takeIf { it.isNotBlank() }
            ?: "A ${bot.personaType} who keeps things casual and down to earth."
        return "For context only, never to be repeated in your answer: you're roleplaying as a real person on a social app, privately known here as ${bot.name}. Personality: $personality " +
               "Write purely in character, the way this specific person would actually type online: contractions, their own voice and quirks, never corporate or assistant-speak (never say things like \"I'd be happy to\", \"As an AI\", \"Sure, here's\", \"I hope this helps\"). " +
               "CRITICAL RULES: (1) Never say your own name or username, and never introduce yourself (\"Hi, I'm ...\", a \"- Name\" sign-off, or starting your message with your name followed by a colon like a chat transcript) — the app already shows who's posting, so saying it again is redundant and makes you sound like a robot. Only reference your own name if something in the conversation genuinely requires it, e.g. someone directly asks who you are. (2) Respond with ONLY the raw message text itself — no labels, no name prefix, no quotation marks around it."
    }

    /**
     * Strips the meta-commentary some models (especially small local ones) prepend or wrap
     * persona output in despite being told not to — e.g. "Sure, here's a post:", wrapping the
     * whole thing in quotes, or self-introducing / transcript-style name-prefixing ("Name: hey
     * everyone...") — so none of it leaks into what's actually posted/sent. Pass [bot] whenever
     * one is known so its specific name/handle can be stripped if the model echoed it back.
     */
    private fun sanitizePersonaOutput(text: String, bot: SocialBotEntity? = null): String {
        var t = text.trim()
        val preamblePatterns = listOf(
            Regex("^(sure|okay|ok|certainly|absolutely|got it)[,!.]?\\s*(here'?s?|here is)?[^:\\n]{0,40}:\\s*", RegexOption.IGNORE_CASE),
            Regex("^as an ai[^,.\\n]*[,.]\\s*", RegexOption.IGNORE_CASE)
        )
        for (p in preamblePatterns) t = p.replace(t, "")
        t = t.trim()

        if (bot != null) {
            val nameEsc = Regex.escape(bot.name)
            val handleEsc = Regex.escape(bot.handle.removePrefix("@"))
            val selfPatterns = listOf(
                // Transcript-style "Name: message" / "@handle: message" prefix
                Regex("^@?$nameEsc\\s*[:\\-–]\\s+", RegexOption.IGNORE_CASE),
                Regex("^@?$handleEsc\\s*[:\\-–]\\s+", RegexOption.IGNORE_CASE),
                // "Hi, I'm Name" / "Hey it's Name" self-introductions
                Regex("^(hi|hey|hello|yo)[,!]?\\s*(everyone|folks|all)?[,!]?\\s*(i'?m|i am|this is|it'?s)\\s+$nameEsc[,!.:\\-–]?\\s*", RegexOption.IGNORE_CASE)
            )
            for (p in selfPatterns) t = p.replace(t, "")
            t = t.trim()
            // "- Name" sign-off at the very end
            t = Regex("[\\n\\r]*[-–—]\\s*$nameEsc\\s*$", RegexOption.IGNORE_CASE).replace(t, "").trim()
        }

        if (t.length >= 2) {
            val first = t.first()
            val last = t.last()
            val isQuotePair = (first == '"' && last == '"') || (first == '“' && last == '”')
            if (isQuotePair) t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    /**
     * True when [candidate] is mostly just [source]'s own words — i.e. the model echoed/repeated
     * what it was supposed to be reacting to instead of writing something new. Word-overlap based
     * rather than exact-match since models often shuffle word order or drop a few words while
     * still fundamentally just restating the source.
     */
    private fun isNearDuplicate(candidate: String, source: String): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val a = words(candidate)
        val b = words(source)
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.intersect(b).size.toDouble()
        return overlap / minOf(a.size, b.size) > 0.75
    }

    private fun slugifyHandle(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_]"), "").take(20)
}
