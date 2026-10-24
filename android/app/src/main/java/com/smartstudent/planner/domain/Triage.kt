package com.smartstudent.planner.domain

import java.time.LocalDate

/** A task, as far as deciding what matters most is concerned. */
data class TriageTask(
    val id: String,
    val title: String,
    /** Minutes of work still to do (or planned today, when used for lightening a day). */
    val minutes: Int,
    val priority: Priority,
    val due: LocalDate?,
)

data class LightenPlan(
    val keep: List<TriageTask>,
    val defer: List<TriageTask>,
    val totalMinutes: Int,
    val keptMinutes: Int,
)

/** What matters most, and what can wait. Used for "what should I focus on?" and "lighten my day". */
object Triage {
    /** 0 = overdue, 1 = due today, 2 = within two days, 3 = within a week, 4 = later or no date. */
    fun urgency(due: LocalDate?, today: LocalDate): Int = when {
        due == null -> 4
        due < today -> 0
        due == today -> 1
        due <= today.plusDays(2) -> 2
        due <= today.plusDays(7) -> 3
        else -> 4
    }

    /** Most pressing first: by urgency, then by due date, then by priority, then the quicker one. */
    fun rank(tasks: List<TriageTask>, today: LocalDate): List<TriageTask> =
        tasks.sortedWith(
            compareBy<TriageTask>({ urgency(it.due, today) }, { it.due ?: LocalDate.MAX }, { -it.priority.weight }, { it.minutes }, { it.title })
        )

    /**
     * Keeps the most pressing work until about [keepMinutes] are planned, and puts the rest off.
     * Work that is overdue or due today is never put off, and at least one task always stays.
     */
    fun lighten(tasks: List<TriageTask>, today: LocalDate, keepMinutes: Int): LightenPlan {
        val ranked = rank(tasks, today)
        val keep = mutableListOf<TriageTask>()
        val defer = mutableListOf<TriageTask>()
        var kept = 0
        for (task in ranked) {
            val mustStay = urgency(task.due, today) <= 1
            if (mustStay || keep.isEmpty() || kept < keepMinutes) {
                keep += task
                kept += task.minutes
            } else {
                defer += task
            }
        }
        return LightenPlan(keep, defer, tasks.sumOf { it.minutes }, kept)
    }
}
