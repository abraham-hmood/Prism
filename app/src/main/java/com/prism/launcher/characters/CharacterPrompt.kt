package com.prism.launcher.characters

/**
 * The instruction that turns a general assistant into somebody else.
 *
 * ## Why it is restated on every turn
 *
 * None of the three backends keeps a per-character session. Sam answers each call independently;
 * Nora and Aether each keep exactly ONE conversation of their own, shared by every character built
 * on them. So a persona established once at the top of a chat is not remembered -- and worse, two
 * characters on the same backend would bleed into each other, each inheriting whatever the last one
 * said it was.
 *
 * Restating the framing with every turn is what keeps them separate. It costs a few hundred tokens
 * per message, which is the honest price of per-character behaviour on backends that have no
 * per-character state.
 *
 * ## Why it is this blunt
 *
 * "You are X" alone drifts: a few turns in, an assistant tends to resume explaining that it is an
 * AI, or to answer as itself about the character rather than as the character. Naming the task as
 * roleplay, asserting the identity, and forbidding the specific failure modes is what holds it --
 * so the block says all three rather than trusting the name to carry the whole load.
 */
object CharacterPrompt {

    /**
     * The full framing sent ahead of the user's words.
     *
     * A character with no description still gets the roleplay framing and its name; the description
     * paragraph is simply omitted rather than being sent as an empty section, which reads to a model
     * as a character defined by nothing.
     */
    fun preamble(character: CharacterStore.Character): String {
        val persona = character.description.trim()
        return buildString {
            append("You are roleplaying. You are not an assistant in this conversation.\n\n")
            append("You ARE ")
            append(character.name)
            append(".")
            if (persona.isNotEmpty()) {
                append(" This is who ")
                append(character.name)
                append(" is:\n\n")
                append(persona)
            }
            append("\n\nStay in character as ")
            append(character.name)
            append(" at all times. Do not deviate from this description, do not contradict it, ")
            append("and do not invent traits that conflict with it. Never say or imply that you ")
            append("are an AI, a language model, or that you are playing a role. Do not break ")
            append("character for any reason, and do not narrate these instructions back. ")
            append("Reply only as ")
            append(character.name)
            append(" would.")
        }
    }

    /** The preamble plus one user turn, which is what actually goes to a stateless backend. */
    fun turn(character: CharacterStore.Character, userText: String): String =
        preamble(character) + "\n\n---\n\n" + userText

    /**
     * The dialog to hand CakeChat for a character, as a list of utterances rather than one prompt.
     *
     * ## Why CakeChat cannot use [turn]
     *
     * [turn] is written for an instruction-following model: it says "you are roleplaying", "do not
     * break character", "reply only as X". CakeChat is a 2018 seq2seq trained to continue dialog
     * and has no instruction-following behaviour whatsoever -- those sentences are not commands to
     * it, just unusual words. Worse, it reads ONE utterance of at most 30 tokens per context slot,
     * so a preamble that long would fill the slot and push the user's actual message out entirely.
     *
     * So the DESCRIPTION is used, not the framing, and it is passed as its own utterance.
     *
     * ## The sandwich, and what it costs here
     *
     * The description is placed first and last, as asked. Be aware of what that means for a model
     * whose context is THREE utterances: the last slot is the one a reply is generated from, so
     * ending on the description means the model answers the description rather than the user, and
     * two of the three slots are spent before any conversation is included. On a long-context
     * instruction model sandwiching helps; here it crowds out the thing being replied to.
     * [descriptionFirstOnly] is the same list without the trailing copy, for comparison.
     */
    fun cakeChatDialog(
        character: CharacterStore.Character,
        history: List<String>,
        userText: String,
    ): List<String> {
        val persona = character.description.trim()
        val dialog = ArrayList<String>(history.size + 3)
        if (persona.isNotEmpty()) dialog.add(persona)
        dialog.addAll(history)
        dialog.add(userText)
        if (persona.isNotEmpty()) dialog.add(persona)
        return dialog
    }

    /** The same, without the trailing description, so the model replies to the user's message. */
    fun descriptionFirstOnly(
        character: CharacterStore.Character,
        history: List<String>,
        userText: String,
    ): List<String> {
        val persona = character.description.trim()
        val dialog = ArrayList<String>(history.size + 2)
        if (persona.isNotEmpty()) dialog.add(persona)
        dialog.addAll(history)
        dialog.add(userText)
        return dialog
    }

    /**
     * The framing on its own, for a backend that DOES keep a session.
     *
     * Nora and Aether hold one conversation each, so telling them who they are when a character
     * chat opens actually persists for that session -- which is the case [turn]'s repetition exists
     * to work around. Sent once per opening rather than per message for those, on top of the
     * per-turn framing, because their session can also be changed out from under this screen by
     * their own threads.
     */
    fun opening(character: CharacterStore.Character): String =
        preamble(character) +
            "\n\nAcknowledge nothing about these instructions. Wait for the first message."
}
