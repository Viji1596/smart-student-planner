package com.smartstudent.planner.domain

/**
 * Is this message a question about how the student is doing ("how am I doing", "progress", "am I on track")?
 * The server understands these too, but the report is built from data on this phone, so the app also recognises
 * them itself: the answer never depends on the server's version, or on being online.
 */
object ReportQuestion {
    private val words = Regex("""\b(report|summary|summari[sz]e|stats?|statistics|performance|performing|progress|analysis|analy[sz]e|grades?|score|streaks?|productiv\w*|on track|doing (well|ok|okay|good|bad|fine|great))\b""")
    private val how = Regex("""\bhow\b.*\b(am i|i am|are my|have i|did i|was my|were my|is my|has my|much did i|much have i|much do i)\b""")
    private val howMuch = Regex("""\bhow much\b.*\b(done|studied|study|worked|work|completed|finished|learn\w*|revised|revise)\b""")
    private val tracking = Regex("""\b(am i|are we)\b.*\b(on track|behind|ahead|falling behind|keeping up)\b""")
    private val period = Regex("""\bhow\b.*\b(was|is|were|went|going)\b.*\b(this|the|last|my)\b.*\b(week|day|month|today|term|semester)\b""")
    private val command = Regex("""^(add|create|new|remind me|delete|remove|mark|move|swap|rename|change|set|postpone|skip|todo|task)\b""")
    private val dayCount = Regex("""\b(\d{1,4})\s*days?\b""")

    private fun clean(text: String) = text.trim().lowercase().replace(Regex("""[!?.]+$"""), "").replace(Regex("""\s+"""), " ")

    fun matches(text: String): Boolean {
        val l = clean(text)
        if (l.isEmpty() || command.containsMatchIn(l)) return false
        return words.containsMatchIn(l) || how.containsMatchIn(l) || howMuch.containsMatchIn(l) ||
            tracking.containsMatchIn(l) || period.containsMatchIn(l)
    }

    /** How many days the question is about: today, a week (the default), two weeks, a month, or "N days". */
    fun days(text: String): Int {
        val l = clean(text)
        dayCount.find(l)?.let { return it.groupValues[1].toInt().coerceIn(1, 60) }
        return when {
            Regex("""\b(today|daily|so far today|this morning|my day|the day|this day|day going|day went)\b""").containsMatchIn(l) -> 1
            Regex("""\bmonth(ly)?\b""").containsMatchIn(l) -> 30
            Regex("""\b(two weeks|2 weeks|fortnight)\b""").containsMatchIn(l) -> 14
            else -> 7
        }
    }
}
