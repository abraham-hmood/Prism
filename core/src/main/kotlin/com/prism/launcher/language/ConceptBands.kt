package com.prism.launcher.language

import com.prism.launcher.language.Concepts.Category
import com.prism.launcher.language.Concepts.Concept

/**
 * The vocabulary above A0 — everything that cannot be shown in a picture.
 *
 * ## Why this is a separate list from the concrete one
 *
 * A0 is constrained by depictability and stops when it runs out. Everything from A1 upward is
 * constrained by *usefulness at a level*, which is a completely different selection rule: "because"
 * is far more frequent than "butterfly" and completely unteachable with a photograph.
 *
 * Splitting them keeps [Concepts.pictureable] honest — a picture lesson can only ever draw from the
 * concrete tier — while [Concepts.ALL] concatenates the two in teaching order, so the spec builder
 * walks straight from A0's last animal into A1's function words without a special case.
 *
 * ## Where this list stops, and why that is the right answer
 *
 * It stops at B2, and it stops deliberately.
 *
 * Through A2 a vocabulary list IS the syllabus: the words everyone needs are the same words, and
 * there are about eight hundred of them. By B1 that stops being true — the vocabulary that serves
 * "argue a case giving advantages and disadvantages" is the vocabulary of whatever the learner is
 * arguing about, and a B2 engineer and a B2 nurse need almost disjoint sets. By C1 the useful
 * vocabulary is the learner's own field, which no authored list can contain.
 *
 * So above B2, Prism does not pretend to have a list. The tutor introduces words from the topic the
 * learner chose, and [LearnerModel] records whatever was actually introduced as an open term
 * ([LearnerModel.TERM_PREFIX]) — same FSRS scheduling, same word bank, no list required. That is
 * how the lexicon reaches C2 without anybody hand-authoring forty thousand entries in sixteen
 * languages and getting a third of them subtly wrong.
 *
 * The bands here are therefore a *floor*, not a ceiling: they guarantee a learner has the common
 * core, and everything past it is theirs.
 */
internal object ConceptBands {

    private fun a1(id: String, english: String, category: Category) =
        Concept(id, english, category, band = Cefr.Level.A1)

    private fun a2(id: String, english: String, category: Category) =
        Concept(id, english, category, band = Cefr.Level.A2)

    private fun b1(id: String, english: String, category: Category) =
        Concept(id, english, category, band = Cefr.Level.B1)

    private fun b2(id: String, english: String, category: Category) =
        Concept(id, english, category, band = Cefr.Level.B2)

    val ABSTRACT: List<Concept> = listOf(
        // -- A1: courtesy, questions, and the verbs a sentence cannot do without ---
        a1("hello", "hello", Category.GREETINGS),
        a1("goodbye", "goodbye", Category.GREETINGS),
        a1("please", "please", Category.GREETINGS),
        a1("thank_you", "thank you", Category.GREETINGS),
        a1("sorry", "sorry", Category.GREETINGS),
        a1("excuse_me", "excuse me", Category.GREETINGS),
        a1("yes", "yes", Category.GREETINGS),
        a1("no", "no", Category.GREETINGS),

        a1("q_what", "what", Category.QUESTIONS),
        a1("q_where", "where", Category.QUESTIONS),
        a1("q_when", "when", Category.QUESTIONS),
        a1("q_who", "who", Category.QUESTIONS),
        a1("q_why", "why", Category.QUESTIONS),
        a1("q_how", "how", Category.QUESTIONS),
        a1("q_how_much", "how much", Category.QUESTIONS),

        a1("to_be", "to be", Category.CORE_VERBS),
        a1("to_have", "to have", Category.CORE_VERBS),
        a1("to_want", "to want", Category.CORE_VERBS),
        a1("to_need", "to need", Category.CORE_VERBS),
        a1("to_go", "to go", Category.CORE_VERBS),
        a1("to_come", "to come", Category.CORE_VERBS),
        a1("to_know", "to know", Category.CORE_VERBS),
        a1("to_say", "to say", Category.CORE_VERBS),
        a1("to_give", "to give", Category.CORE_VERBS),
        a1("to_take", "to take", Category.CORE_VERBS),
        a1("to_make", "to make", Category.CORE_VERBS),
        a1("to_live", "to live", Category.CORE_VERBS),
        a1("to_work", "to work", Category.CORE_VERBS),
        a1("to_like", "to like", Category.CORE_VERBS),
        a1("to_can", "to be able to", Category.CORE_VERBS),

        a1("good", "good", Category.QUALITIES),
        a1("bad", "bad", Category.QUALITIES),
        a1("big", "big", Category.QUALITIES),
        a1("small", "small", Category.QUALITIES),
        a1("new", "new", Category.QUALITIES),
        a1("old", "old", Category.QUALITIES),
        a1("easy", "easy", Category.QUALITIES),
        a1("difficult", "difficult", Category.QUALITIES),
        a1("beautiful", "beautiful", Category.QUALITIES),
        a1("expensive", "expensive", Category.QUALITIES),

        a1("name_word", "name", Category.IDEAS),
        a1("year", "year", Category.TIME_WORDS),
        a1("month", "month", Category.TIME_WORDS),
        a1("tomorrow", "tomorrow", Category.TIME_WORDS),
        a1("yesterday", "yesterday", Category.TIME_WORDS),
        a1("now", "now", Category.TIME_WORDS),

        // -- A2: time, connection, and the verbs of everyday life -----------------
        a2("always", "always", Category.TIME_WORDS),
        a2("never", "never", Category.TIME_WORDS),
        a2("sometimes", "sometimes", Category.TIME_WORDS),
        a2("often", "often", Category.TIME_WORDS),
        a2("early", "early", Category.TIME_WORDS),
        a2("late", "late", Category.TIME_WORDS),
        a2("before", "before", Category.TIME_WORDS),
        a2("after", "after", Category.TIME_WORDS),
        a2("already", "already", Category.TIME_WORDS),
        a2("still_yet", "still", Category.TIME_WORDS),

        a2("because", "because", Category.CONNECTORS),
        a2("but", "but", Category.CONNECTORS),
        a2("so_therefore", "so", Category.CONNECTORS),
        a2("if", "if", Category.CONNECTORS),
        a2("also", "also", Category.CONNECTORS),
        a2("than", "than", Category.CONNECTORS),

        a2("to_think", "to think", Category.CORE_VERBS),
        a2("to_feel", "to feel", Category.CORE_VERBS),
        a2("to_help", "to help", Category.CORE_VERBS),
        a2("to_try", "to try", Category.CORE_VERBS),
        a2("to_start", "to start", Category.CORE_VERBS),
        a2("to_finish", "to finish", Category.CORE_VERBS),
        a2("to_meet", "to meet", Category.CORE_VERBS),
        a2("to_travel", "to travel", Category.CORE_VERBS),
        a2("to_remember", "to remember", Category.CORE_VERBS),
        a2("to_forget", "to forget", Category.CORE_VERBS),
        a2("to_understand", "to understand", Category.CORE_VERBS),
        a2("to_ask", "to ask", Category.CORE_VERBS),
        a2("to_answer", "to answer", Category.CORE_VERBS),
        a2("to_pay", "to pay", Category.CORE_VERBS),

        a2("problem", "problem", Category.IDEAS),
        a2("question", "question", Category.IDEAS),
        a2("idea", "idea", Category.IDEAS),
        a2("reason", "reason", Category.IDEAS),
        a2("time_abstract", "time", Category.IDEAS),
        a2("place", "place", Category.IDEAS),
        a2("person", "person", Category.IDEAS),
        a2("thing", "thing", Category.IDEAS),
        a2("life", "life", Category.IDEAS),
        a2("world", "world", Category.IDEAS),
        a2("story", "story", Category.IDEAS),
        a2("important", "important", Category.QUALITIES),
        a2("possible", "possible", Category.QUALITIES),
        a2("different", "different", Category.QUALITIES),
        a2("same", "same", Category.QUALITIES),

        // -- B1: opinion, consequence, and the machinery of an argument -----------
        b1("experience", "experience", Category.IDEAS),
        b1("opinion", "opinion", Category.IDEAS),
        b1("decision", "decision", Category.IDEAS),
        b1("situation", "situation", Category.IDEAS),
        b1("change_noun", "change", Category.IDEAS),
        b1("result", "result", Category.IDEAS),
        b1("choice", "choice", Category.IDEAS),
        b1("chance", "chance", Category.IDEAS),
        b1("advantage", "advantage", Category.IDEAS),
        b1("difference", "difference", Category.IDEAS),
        b1("relationship", "relationship", Category.IDEAS),
        b1("health", "health", Category.IDEAS),
        b1("society", "society", Category.IDEAS),
        b1("culture", "culture", Category.IDEAS),
        b1("environment", "environment", Category.IDEAS),
        b1("education", "education", Category.IDEAS),

        b1("to_decide", "to decide", Category.ABSTRACT_VERBS),
        b1("to_explain", "to explain", Category.ABSTRACT_VERBS),
        b1("to_suggest", "to suggest", Category.ABSTRACT_VERBS),
        b1("to_agree", "to agree", Category.ABSTRACT_VERBS),
        b1("to_disagree", "to disagree", Category.ABSTRACT_VERBS),
        b1("to_improve", "to improve", Category.ABSTRACT_VERBS),
        b1("to_avoid", "to avoid", Category.ABSTRACT_VERBS),
        b1("to_allow", "to allow", Category.ABSTRACT_VERBS),
        b1("to_achieve", "to achieve", Category.ABSTRACT_VERBS),
        b1("to_consider", "to consider", Category.ABSTRACT_VERBS),
        b1("to_expect", "to expect", Category.ABSTRACT_VERBS),
        b1("to_describe", "to describe", Category.ABSTRACT_VERBS),

        b1("however", "however", Category.CONNECTORS),
        b1("although", "although", Category.CONNECTORS),
        b1("instead", "instead", Category.CONNECTORS),
        b1("useful", "useful", Category.QUALITIES),
        b1("serious", "serious", Category.QUALITIES),
        b1("common", "common", Category.QUALITIES),

        // -- B2: the last authored band. Past here the tutor supplies the words. --
        b2("consequence", "consequence", Category.ACADEMIC),
        b2("evidence", "evidence", Category.ACADEMIC),
        b2("perspective", "perspective", Category.ACADEMIC),
        b2("tendency", "tendency", Category.ACADEMIC),
        b2("assumption", "assumption", Category.ACADEMIC),
        b2("to_acknowledge", "to acknowledge", Category.ACADEMIC),
        b2("to_emphasise", "to emphasise", Category.ACADEMIC),
        b2("to_justify", "to justify", Category.ACADEMIC),
        b2("to_assess", "to assess", Category.ACADEMIC),
        b2("to_distinguish", "to distinguish", Category.ACADEMIC),
    )
}
