package com.smartstudent.planner.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The wording of what the assistant shows for "what should I focus on?" and "lighten my day". Plain text, no Android. */
object SupportText {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun dueWords(due: LocalDate?, today: LocalDate): String = when {
        due == null -> "no due date"
        due < today -> "overdue"
        due == today -> "due today"
        due == today.plusDays(1) -> "due tomorrow"
        else -> "due ${due.format(DAY)}"
    }

    /** The three things to start with, most pressing first, and one tiny first step. */
    fun focus(tasks: List<TriageTask>, today: LocalDate): String {
        val ranked = Triage.rank(tasks.filter { it.minutes > 0 }, today).take(3)
        if (ranked.isEmpty()) return "You have nothing open right now. Enjoy the free time, or add something you'd like to get ahead on."
        val lines = ranked.mapIndexed { i, t -> "${i + 1}. ${t.title} (${dueWords(t.due, today)}, ${TimeUtil.formatDuration(t.minutes)} left)" }
        return "Start with “${ranked.first().title}”.\n" + lines.joinToString("\n") +
            "\nGive the first one just 10 minutes; you can stop after that if you want. The rest can wait."
    }

    /** The question on the confirm card, naming exactly what would move. */
    fun lightenQuestion(plan: LightenPlan): String {
        val names = plan.defer.joinToString(", ") { "“${it.title}”" }
        val n = plan.defer.size
        return "Today has about ${TimeUtil.formatDuration(plan.totalMinutes)} of work planned. I'll keep the most urgent " +
            "${TimeUtil.formatDuration(plan.keptMinutes)} and put off $n task${if (n == 1) "" else "s"} until tomorrow: $names. " +
            "Nothing is deleted, and you can bring any back by saying “start <task> today”. Go ahead?"
    }

    fun lightenAlreadyLight(plan: LightenPlan): String =
        "Today's plan is already light (about ${TimeUtil.formatDuration(plan.totalMinutes)}), so I left it as it is."

    fun lightenDone(plan: LightenPlan): String {
        val names = plan.defer.joinToString(", ") { "“${it.title}”" }
        val n = plan.defer.size
        return "✓ I moved $n task${if (n == 1) "" else "s"} to tomorrow ($names). Today now has about " +
            "${TimeUtil.formatDuration(plan.keptMinutes)} of work. Say “start <task> today” to bring one back."
    }
}
