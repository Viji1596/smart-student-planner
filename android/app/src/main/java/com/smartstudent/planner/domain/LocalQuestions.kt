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
    private val focus = Regex("""\b(what should i (do|focus on|work on|start|tackle|study|begin)( first| now| next| today| with)?|where (do|should|can) i (start|begin)|what('?s| is) (the )?(most )?(important|urgent|pressing)( thing)?|what to do first|prioriti[sz]e|help me (prioriti[sz]e|decide|start|focus|choose)|what next|what now|what do i do first)\b""")
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
        if (l.isEmpty() || command.containsMatchIn(l)) return null
        if (ReportQuestion.matches(l)) return LocalAction("show_report", days = ReportQuestion.days(l))
        if (focus.containsMatchIn(l)) return LocalAction("show_focus")
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
