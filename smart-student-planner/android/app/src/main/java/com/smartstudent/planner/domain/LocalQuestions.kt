package com.smartstudent.planner.domain

/** One read-only thing the phone can show by itself (the same names the assistant's actions use). */
data class LocalAction(
    val type: String,
    val day: String? = null,
    val filter: String? = null,
    val days: Int? = null,
)

/**
 * Questions about the student's own data ("how am I doing", "what should I focus on", "what's my plan today")
 * are answered from data on this phone. The server understands them too, but the app doesn't depend on it:
 * with an out-of-date or unreachable server these still work.
 */
object LocalQuestions {
    private val command = Regex("""^(add|create|new|remind me|delete|remove|mark|move|swap|rename|change|set|postpone|skip|todo|task|start|bring back)\b""")
    /** "What should I focus on?" in all the ways people ask it (the server understands the same ones). */
    private val focusAsk = Regex(
        listOf(
            """\bfocus(ing)?\b""",
            """\bwhat (should|can|could|shall|do|will) i (be )?(do|doing|work on|working on|study|studying|start|tackle|begin|handle|take up)\b""",
            """\bwhat (do|should) i need to (do|work on|study|finish|tackle|start)\b""",
            """\bwhat to (do|work on|study|start|begin|tackle|handle)\b""",
            """\bwhere (to|do i|should i|can i|shall i) (start|begin)\b""",
            """\bwhich (task|one|thing|subject|assignment|work)\b.*\b(first|start|begin|tackle|work on|do)\b""",
            """\b(my|the|top|what'?s|what are|what is) (priority|priorities)\b""",
            """^(priority|priorities)$""",
            """\bprioriti[sz]e\b""",
            """\b(most|more) (important|urgent|pressing)\b""",
            """^(the )?(urgent|important|pressing)( task| thing| one)?s?$""",
            """\bwhat('?s| is| are) (the )?(important|urgent|pressing|first)\b""",
            """^what first$""",
            """^what (next|now)$""",
            """\bstart with what\b""",
            """\b(don'?t|do not|dont) know (what to do|where to start|what to start|what to work on|what first)\b""",
            """\b(tell|suggest|give|show|recommend) me (something|what|where|a task|a thing)\b.*\b(do|start|work|study|first)\b""",
            """\bsomething to do\b""",
            """\bwhat needs my attention\b""",
            """\bwhat'?s on my plate\b""",
        ).joinToString("|")
    )
    private val commandAnywhere = Regex("""\b(add|create|insert|remind me|delete|remove|mark|move|swap|rename|change|set|postpone|skip|reschedule|push|bring back)\b|^(todo|task|new)\b""")
    private val aboutSelf = Regex("""^(i|i'm|im|i am|i can't|i cant|my)\b""")
    private val dontKnow = Regex("""\b(don'?t|do not|dont) know (what|where)\b""")
    private val otherDay = Regex("""\b(tomorrow|yesterday|next week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")

    private fun isFocusQuestion(l: String): Boolean {
        if (commandAnywhere.containsMatchIn(l)) return false
        if (aboutSelf.containsMatchIn(l) && !dontKnow.containsMatchIn(l)) return false // "I can't focus" is a statement
        if (otherDay.containsMatchIn(l)) return false // about another day: that's a question about the plan
        return focusAsk.containsMatchIn(l)
    }

    private val freeTime = Regex("""\b(free (time|hours?|slots?)|spare time|how (much|many)\b.*\b(free|time|hours)|am i free|when am i free)\b""")
    private val overdue = Regex("""\b(overdue|past due|late tasks?)\b""")
    private val dueSoon = Regex("""\b(what|which|show|list)\b.*\b(due|deadlines?)\b""")
    private val missed = Regex("""\b(what|which|did i)\b.*\bmiss(ed)?\b|\bmissed\b.*\btoday\b""")
    private val plan = Regex("""\b(what('?s| is| do i have| have i got)|show|tell me|give me|see|check|open)\b.*\b(plan|schedule|agenda|timeline)\b|^(my )?(plan|schedule|agenda)( for)?( today| tomorrow)?$""")
    private val openTasks = Regex("""\b(show|list|what are|what('?s| is)|see)\b.*\b(tasks?|to-?dos?)\b""")

    private fun clean(text: String) = text.trim().lowercase().replace(Regex("""[!?.]+$"""), "").replace(Regex("""\s+"""), " ")

    /** What to show for [text], or null when it isn't one of these questions. */
    fun detect(text: String): LocalAction? {
        val l = clean(text)
        if (l.isEmpty()) return null
        if (isFocusQuestion(l)) return LocalAction("show_focus")
        if (command.containsMatchIn(l)) return null
        if (ReportQuestion.matches(l)) return LocalAction("show_report", days = ReportQuestion.days(l))
        if (freeTime.containsMatchIn(l)) return LocalAction("show_free_time", day = if ("tomorrow" in l) "tomorrow" else "today")
        if (overdue.containsMatchIn(l)) return LocalAction("show_tasks", filter = "overdue")
        if (dueSoon.containsMatchIn(l)) return LocalAction("show_tasks", filter = "due_soon")
        if (missed.containsMatchIn(l)) return LocalAction("show_missed")
        if (plan.containsMatchIn(l)) return LocalAction("show_plan", day = if ("tomorrow" in l) "tomorrow" else "today")
        if (openTasks.containsMatchIn(l)) return LocalAction("show_tasks", filter = "open")
        return null
    }
}

/** Compares backend versions like "6.4" and "6.10". An empty version (an old backend that doesn't say) is older than anything. */
object ServerVersion {
    fun isOlder(actual: String, expected: String): Boolean {
        if (actual.isBlank()) return true
        val a = parts(actual)
        val e = parts(expected)
        for (i in 0 until maxOf(a.size, e.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = e.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    private fun parts(v: String) = v.trim().split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
}
