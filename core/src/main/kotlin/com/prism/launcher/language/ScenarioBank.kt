package com.prism.launcher.language

/**
 * The situations a conversation lesson happens inside.
 *
 * ## Why a scenario and not just "talk about food"
 *
 * An open conversation with a language tutor collapses into an interview: the tutor asks, the
 * learner answers, and nobody has to do anything. A scenario gives both sides a role and the
 * learner a GOAL — get the refund, order for four people, explain why you were late — and a goal is
 * what forces the language out. It is also the difference between a lesson that can be marked and
 * one that cannot: either the learner got the thing or they did not.
 *
 * ## Why they are written and not generated
 *
 * A model asked for a role-play situation produces one, and it produces a different one every time,
 * and about one in ten is unusable — set in a place the learner will never go, or requiring
 * vocabulary three levels above them. These are the scaffold; the model improvises inside them.
 * Sixty hand-written situations cover A0 to C2 with room to spare, and each one is guaranteed to be
 * possible with the words the learner has.
 *
 * ## Matching
 *
 * [forLesson] picks on level first and interest second. Level is a hard filter — a B2 negotiation
 * handed to an A1 learner is not a stretch, it is a wall — while interest is a preference, because
 * a learner who picked "cooking" and "gaming" still has to be able to buy a train ticket.
 */
object ScenarioBank {

    /**
     * @param setting where it happens, in one phrase.
     * @param tutorRole who the tutor plays. Never "a teacher" — the point is that they are not one.
     * @param learnerGoal what the learner has to achieve. This is what gets marked.
     * @param tags interests this suits, matched against the learner's own answers.
     */
    data class Scenario(
        val id: String,
        val minLevel: Cefr.Level,
        val setting: String,
        val tutorRole: String,
        val learnerGoal: String,
        val tags: Set<String> = emptySet(),
    )

    private val ALL: List<Scenario> = listOf(
        // A1 — one transaction, one outcome, no small talk required.
        Scenario("cafe_order", Cefr.Level.A1, "a small café", "the person behind the counter",
            "Order a drink and something to eat, and pay", setting("food", "traveling")),
        Scenario("meet_someone", Cefr.Level.A1, "a friend's party", "somebody you have just met",
            "Introduce yourself and find one thing you have in common", setting("relationships", "chatting")),
        Scenario("ask_directions", Cefr.Level.A1, "a street corner", "a passer-by",
            "Find out how to get to the station", setting("traveling")),
        Scenario("shop_buy", Cefr.Level.A1, "a clothes shop", "a shop assistant",
            "Buy something in your size and ask the price", setting("shopping", "fashion")),
        Scenario("phone_home", Cefr.Level.A1, "a phone call", "a family member",
            "Say where you are and when you will be back", setting("parenting", "relationships")),
        Scenario("market_fruit", Cefr.Level.A1, "a street market", "a stallholder",
            "Buy fruit for the week and ask what is fresh", setting("food", "cooking")),

        // A2 — something goes slightly wrong, and has to be explained.
        Scenario("late_apology", Cefr.Level.A2, "a meeting you are late for", "the person waiting",
            "Apologise, explain why, and suggest what to do now", setting("interview", "management")),
        Scenario("doctor_visit", Cefr.Level.A2, "a doctor's surgery", "a doctor",
            "Describe what hurts, since when, and answer their questions", setting("fitness")),
        Scenario("restaurant_problem", Cefr.Level.A2, "a restaurant", "a waiter",
            "The order is wrong. Get it fixed without being rude", setting("food")),
        Scenario("hotel_checkin", Cefr.Level.A2, "a hotel reception", "the receptionist",
            "Check in, ask about breakfast and the wifi", setting("traveling")),
        Scenario("plan_weekend", Cefr.Level.A2, "a message to a friend", "a friend",
            "Agree on what to do at the weekend, and when", setting("chatting", "movies", "gaming")),
        Scenario("lost_property", Cefr.Level.A2, "a lost property office", "a clerk",
            "Describe what you lost, where and when", setting("traveling")),
        Scenario("cooking_recipe", Cefr.Level.A2, "a kitchen", "somebody cooking with you",
            "Explain how to make something you know, step by step", setting("cooking", "food")),

        // B1 — an opinion has to be held and defended.
        Scenario("flat_viewing", Cefr.Level.B1, "a flat you might rent", "the landlord",
            "Find out what you need to know and raise one concern", setting("management")),
        Scenario("job_small_talk", Cefr.Level.B1, "the first ten minutes of a new job", "a colleague",
            "Explain what you do and ask about how things work here", setting("interview", "business", "tech")),
        Scenario("film_argument", Cefr.Level.B1, "after a film", "a friend who disagrees with you",
            "Argue for your view of the film and respond to theirs", setting("movies", "cinema", "tv")),
        Scenario("complaint_shop", Cefr.Level.B1, "a shop", "a manager",
            "Return a faulty item you no longer have the receipt for", setting("shopping")),
        Scenario("travel_story", Cefr.Level.B1, "a dinner table", "somebody who asked about your trip",
            "Tell the story of something that went wrong on a journey", setting("traveling")),
        Scenario("explain_hobby", Cefr.Level.B1, "a conversation with a stranger", "somebody curious",
            "Explain what you do for fun well enough that they understand why", setting("music", "art", "gaming", "sports")),
        Scenario("doctor_second", Cefr.Level.B1, "a follow-up appointment", "a doctor",
            "Describe whether the treatment worked and push for what you want", setting("fitness")),

        // B2 — two positions, and a compromise to be reached.
        Scenario("salary_talk", Cefr.Level.B2, "a review meeting", "your manager",
            "Make the case for a raise, and handle the pushback", setting("business", "management", "finance")),
        Scenario("interview_full", Cefr.Level.B2, "a job interview", "the interviewer",
            "Answer why you, handle one difficult question, and ask two of your own", setting("interview", "business")),
        Scenario("debate_policy", Cefr.Level.B2, "a conversation about the news", "somebody who disagrees",
            "Argue a position, concede one point, and keep the rest", setting("news", "history")),
        Scenario("present_idea", Cefr.Level.B2, "a small meeting", "a sceptical colleague",
            "Present an idea in two minutes and defend it under questions", setting("startups", "business", "tech", "ai")),
        Scenario("misunderstanding", Cefr.Level.B2, "an awkward conversation", "a friend you upset",
            "Work out what went wrong and repair it", setting("relationships")),
        Scenario("negotiate_deal", Cefr.Level.B2, "a negotiation", "the other side",
            "Get most of what you want without losing the relationship", setting("business", "finance")),

        // C1 and C2 — the difficulty is in the delivery, not the content.
        Scenario("bad_news", Cefr.Level.C1, "a difficult conversation", "somebody it affects",
            "Deliver news they will not like, clearly and without cruelty", setting("management")),
        Scenario("expert_explain", Cefr.Level.C1, "a conversation with a specialist", "somebody in your field",
            "Discuss your own field at the level a colleague would expect", setting("tech", "ai", "finance", "history")),
        Scenario("hostile_question", Cefr.Level.C1, "the end of a talk you gave", "a hostile questioner",
            "Hold your ground without conceding or escalating", setting("business", "news")),
        Scenario("irony_reading", Cefr.Level.C1, "a conversation with somebody being indirect", "somebody hinting",
            "Read what is meant rather than what is said, and answer that", setting("chatting", "comedy")),
        Scenario("mediate", Cefr.Level.C2, "a disagreement between two other people", "one of the two",
            "Mediate: represent both positions fairly and find the shared ground", setting("management")),
        Scenario("nuance_choice", Cefr.Level.C2, "an editorial discussion", "an editor",
            "Defend one word over another, and explain the difference", setting("literature", "content")),
    )

    private fun setting(vararg tags: String): Set<String> = tags.toSet()

    /**
     * The best scenario for this lesson.
     *
     * Deterministic — the lesson id seeds the choice — because a learner who closes a lesson and
     * reopens it should get the same situation back, not a different one. Being sent to a different
     * café halfway through is the kind of thing that makes an app feel broken even when nothing
     * failed.
     */
    fun forLesson(lessonId: String, level: Cefr.Level, interests: List<String>): Scenario? {
        val eligible = ALL.filter { it.minLevel.ordinal <= level.ordinal }
            .ifEmpty { return null }

        // Prefer the highest level the learner has reached: an A1 scenario at B2 is not practice.
        val ceiling = eligible.maxOf { it.minLevel.ordinal }
        val atLevel = eligible.filter { it.minLevel.ordinal >= ceiling - 1 }

        val wanted = interests.toSet()
        val matching = atLevel.filter { it.tags.any { tag -> tag in wanted } }
        val pool = matching.ifEmpty { atLevel }

        return pool[Math.floorMod(lessonId.hashCode(), pool.size)]
    }

    fun byId(id: String): Scenario? = ALL.firstOrNull { it.id == id }

    fun count(): Int = ALL.size
}
