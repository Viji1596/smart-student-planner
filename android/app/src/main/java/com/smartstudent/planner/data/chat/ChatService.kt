package com.smartstudent.planner.data.chat

import com.smartstudent.planner.data.local.PlannedSessionDao
import com.smartstudent.planner.data.remote.ChatActionDto
import com.smartstudent.planner.data.remote.ChatContextDto
import com.smartstudent.planner.data.remote.ChatDayDto
import com.smartstudent.planner.data.remote.ChatHabitDto
import com.smartstudent.planner.data.remote.ChatMessageDto
import com.smartstudent.planner.data.remote.ChatRequest
import com.smartstudent.planner.data.remote.ChatResponse
import com.smartstudent.planner.data.remote.ChatSessionDto
import com.smartstudent.planner.data.remote.ChatSpanDto
import com.smartstudent.planner.data.remote.ChatTaskDto
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.SettingsRepository
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.TimetableRepository
import com.smartstudent.planner.data.repo.toDomainOrNull
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toFixedEntry
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.data.repo.toRules
import com.smartstudent.planner.domain.BlockKind
import com.smartstudent.planner.domain.DayBuilder
import com.smartstudent.planner.domain.DayView
import com.smartstudent.planner.domain.HabitKind
import com.smartstudent.planner.domain.SessionStatus
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.domain.buildWithCommitted
import com.smartstudent.planner.reminders.MoveOutcome
import com.smartstudent.planner.reminders.PlanManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The assistant, on the phone. The server only interprets what the student typed. Everything it asks for
 * is checked again here and done with the same repositories and planner the rest of the app uses.
 * Deleting is never done without the student's confirmation, whatever the server (or an AI) says.
 */
class ChatService(
    private val api: PlannerApi,
    private val settings: SettingsRepository,
    private val timetable: TimetableRepository,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val sessionDao: PlannedSessionDao,
    private val planManager: PlanManager,
) {
    suspend fun ask(history: List<ChatMessageDto>, context: ChatContextDto): ChatResponse =
        api.chat(ChatRequest(history, context))

    fun needsConfirmation(action: ChatActionDto): Boolean =
        action.confirm || action.type == "delete_task" || action.type == "delete_habit"

    /** The question shown on a confirm card. */
    fun describe(action: ChatActionDto, context: ChatContextDto): String = when (action.type) {
        "delete_task" -> {
            val title = context.tasks.firstOrNull { it.id == action.args.str("taskId") }?.title
            if (title != null) "Delete the task “$title”?" else "Delete this task?"
        }
        "delete_habit" -> {
            val title = context.habits.firstOrNull { it.id == action.args.str("habitId") }?.title
            if (title != null) "Delete the habit “$title”?" else "Delete this habit?"
        }
        else -> "Go ahead?"
    }

    // ------------------------------------------------------------------------------ the snapshot

    suspend fun buildContext(): ChatContextDto {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute

        val rules = settings.settings.first().toRules()
        val entriesByWeekday = timetable.entries.first()
            .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
            .groupBy({ it.first }, { it.second })
        val taskList = tasks.tasks.first()
        val habitList = habits.habits.first()
        val doneDates = habits.completionsSince(today.minusDays(14)).first().toDoneDates()
        val open = taskList.filter { !it.completed }
        val openInfos = open.map { it.toInfo() }
        val habitInfos = habitList.map { it.toInfo() }
        val committed = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
        val todayEntries = entriesByWeekday[today.dayOfWeek.value] ?: emptyList()

        val todayView = if (committed.isNotEmpty()) {
            DayBuilder.buildWithCommitted(rules, todayEntries, openInfos, habitInfos, doneDates, today, nowMin, committed)
        } else {
            DayBuilder.build(rules, todayEntries, openInfos, habitInfos, doneDates, today, nowMin)
        }
        val todaySessions = if (committed.isNotEmpty()) {
            committed.map {
                ChatSessionDto(it.id, it.title, it.kind.name, it.refId, TimeUtil.format(it.startMin), TimeUtil.format(it.endMin), it.status.name)
            }
        } else {
            todayView.schedule.sessions.mapIndexed { i, s -> sessionDto("planned-$i", s.item.title, s.item.kind.name, s.item.refId, s.startMin, s.endMin) }
        }

        val tomorrowView = DayBuilder.buildRange(rules, entriesByWeekday, openInfos, habitInfos, doneDates, today, 2, nowMin).last()
        val tomorrowSessions = tomorrowView.schedule.sessions.mapIndexed { i, s ->
            sessionDto("tomorrow-$i", s.item.title, s.item.kind.name, s.item.refId, s.startMin, s.endMin)
        }

        return ChatContextDto(
            today = today.toString(),
            nowMin = nowMin,
            tasks = open.take(60).map {
                ChatTaskDto(
                    id = it.id,
                    title = it.title.take(200),
                    minutes = it.estimatedMinutes,
                    spent = it.spentMinutes,
                    priority = it.priority,
                    due = it.dueDate?.let { ms -> java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString() },
                    completed = false,
                )
            },
            habits = habitList.take(40).map {
                ChatHabitDto(it.id, it.title.take(200), it.minutes, it.kind, doneDates[it.id]?.contains(today) == true)
            },
            plan = dayDto(todayView, todaySessions),
            tomorrow = dayDto(tomorrowView, tomorrowSessions),
        )
    }

    private fun sessionDto(id: String, title: String, kind: String, refId: String, start: Int, end: Int) =
        ChatSessionDto(id, title, kind, refId, TimeUtil.format(start), TimeUtil.format(end), "PLANNED")

    private fun dayDto(view: DayView, sessions: List<ChatSessionDto>): ChatDayDto {
        val busy = view.plan.blocks.filter { it.kind != BlockKind.FREE }
            .map { ChatSpanDto(it.label, TimeUtil.format(it.startMin), TimeUtil.format(it.endMin)) }
        val freeBlocks = view.schedule.leftoverFree
        return ChatDayDto(
            sessions = sessions,
            freeMinutes = freeBlocks.sumOf { it.duration },
            busy = busy,
            freeBlocks = freeBlocks.map { ChatSpanDto("free", TimeUtil.format(it.startMin), TimeUtil.format(it.endMin)) },
        )
    }

    // --------------------------------------------------------------------------- doing things

    /** Carries out one action and returns a short line for the chat ("✓ ..." or "✗ ..."), or "" if there's nothing to add. */
    suspend fun execute(action: ChatActionDto, context: ChatContextDto): String {
        val args = action.args
        return try {
            when (action.type) {
                "add_task" -> {
                    val title = args.str("title")?.trim().orEmpty().take(200)
                    if (title.isEmpty()) return "✗ I need a name for the task."
                    val minutes = (args.int("minutes") ?: 30).coerceIn(1, 1440)
                    val due = args.date("due")
                    tasks.add(title, args.str("priority") ?: "Medium", minutes, due?.let(::dueMillis))
                    "✓ Added “$title” (${TimeUtil.formatDuration(minutes)}${due?.let { ", due ${label(it)}" } ?: ""})."
                }
                "update_task" -> {
                    val task = args.str("taskId")?.let { tasks.get(it) } ?: return "✗ I couldn't find that task."
                    val dueArg = args.str("due")
                    val due = when (dueArg) {
                        null -> task.dueDate
                        "none" -> null
                        else -> args.date("due")?.let(::dueMillis) ?: task.dueDate
                    }
                    val title = args.str("title")?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: task.title
                    tasks.update(task, title, args.str("priority") ?: task.priority, (args.int("minutes") ?: task.estimatedMinutes).coerceIn(1, 1440), due)
                    "✓ Updated “$title”."
                }
                "complete_task" -> {
                    val task = args.str("taskId")?.let { tasks.get(it) } ?: return "✗ I couldn't find that task."
                    tasks.setCompleted(task, true)
                    "✓ Marked “${task.title}” as done."
                }
                "log_progress" -> {
                    val task = args.str("taskId")?.let { tasks.get(it) } ?: return "✗ I couldn't find that task."
                    val minutes = (args.int("minutes") ?: return "✗ How many minutes?").coerceIn(1, 1440)
                    tasks.logProgress(task.id, minutes)
                    "✓ Logged ${TimeUtil.formatDuration(minutes)} on “${task.title}”."
                }
                "delete_task" -> {
                    val task = args.str("taskId")?.let { tasks.get(it) } ?: return "✗ I couldn't find that task."
                    tasks.delete(task)
                    "✓ Deleted “${task.title}”."
                }
                "add_habit" -> {
                    val title = args.str("title")?.trim().orEmpty().take(100)
                    if (title.isEmpty()) return "✗ I need a name for the habit."
                    val kind = runCatching { HabitKind.valueOf(args.str("kind") ?: "DAILY") }.getOrDefault(HabitKind.DAILY)
                    val days = args.ints("days").filter { it in 1..7 }.toSet()
                    if (kind == HabitKind.DAYS && days.isEmpty()) return "✗ Which days of the week?"
                    habits.add(title, (args.int("minutes") ?: 30).coerceIn(5, 480), args.str("priority") ?: "Medium", kind, days, (args.int("perWeek") ?: 2).coerceIn(1, 7))
                    "✓ Added the habit “$title”."
                }
                "complete_habit" -> {
                    val habit = args.str("habitId")?.let { habits.get(it) } ?: return "✗ I couldn't find that habit."
                    habits.setDone(habit.id, LocalDate.now(), true)
                    "✓ Marked “${habit.title}” as done for today."
                }
                "delete_habit" -> {
                    val habit = args.str("habitId")?.let { habits.get(it) } ?: return "✗ I couldn't find that habit."
                    habits.delete(habit)
                    "✓ Deleted the habit “${habit.title}”."
                }
                "move_session" -> {
                    val session = plannedSessionFor(args) ?: return "✗ I couldn't find that session among today's planned ones."
                    val time = args.str("time")?.let { TimeUtil.parse(it) } ?: return "✗ I didn't understand the time."
                    when (val outcome = planManager.moveSession(session.id, time, args.bool("flexible") == true)) {
                        is MoveOutcome.Moved -> "✓ Moved “${session.title}” to ${TimeUtil.format(outcome.startMin)}. It stays there when I re-plan."
                        is MoveOutcome.Refused -> "✗ ${outcome.reason}"
                    }
                }
                "skip_session" -> {
                    val session = plannedSessionFor(args) ?: return "✗ I couldn't find that session among today's planned ones."
                    planManager.skip(session.id)
                    "✓ Skipped “${session.title}”: I'll plan it again for later today."
                }
                "replan" -> {
                    planManager.replanNow()
                    "✓ Re-planned the rest of today."
                }
                "show_plan" -> {
                    val tomorrow = args.str("day") == "tomorrow"
                    planText(if (tomorrow) context.tomorrow else context.plan, if (tomorrow) "tomorrow" else "today")
                }
                "show_free_time" -> {
                    val tomorrow = args.str("day") == "tomorrow"
                    freeText(if (tomorrow) context.tomorrow else context.plan, if (tomorrow) "tomorrow" else "today")
                }
                "show_tasks" -> tasksText(args.str("filter") ?: "open", context)
                "show_missed" -> missedText(context)
                else -> "✗ I can't do that yet."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "✗ That didn't work (${e.message ?: e.javaClass.simpleName})."
        }
    }

    /** Today's still-planned session the action points to: by session id, or the next one of that task. */
    private suspend fun plannedSessionFor(args: JsonObject): com.smartstudent.planner.domain.PlannedSession? {
        val today = LocalDate.now().toString()
        val sessions = sessionDao.getForDate(today).mapNotNull { it.toDomainOrNull() }.filter { it.status == SessionStatus.PLANNED }
        args.str("sessionId")?.let { id -> sessions.firstOrNull { it.id == id }?.let { return it } }
        return args.str("taskId")?.let { ref -> sessions.filter { it.refId == ref }.minByOrNull { it.startMin } }
    }

    // ------------------------------------------------------------------------- answers as text

    private fun planText(day: ChatDayDto, name: String): String {
        val lines = buildList {
            day.busy.forEach { add(it.start to "${it.start}–${it.end}  ${it.label}") }
            day.sessions.filter { it.status != "MISSED" }.forEach {
                add(it.start to "${it.start}–${it.end}  ${it.title}${if (it.status == "DONE") "  ✓" else ""}")
            }
        }.sortedBy { it.first }.map { it.second }
        return if (day.sessions.none { it.status != "MISSED" } && day.busy.isEmpty()) {
            "Nothing is planned $name. You have ${TimeUtil.formatDuration(day.freeMinutes)} free."
        } else {
            "Your plan for $name:\n" + lines.joinToString("\n") + "\nFree time left: ${TimeUtil.formatDuration(day.freeMinutes)}."
        }
    }

    private fun freeText(day: ChatDayDto, name: String): String {
        if (day.freeBlocks.isEmpty()) return "You have no free time left $name."
        val blocks = day.freeBlocks.joinToString("\n") { b ->
            val minutes = (TimeUtil.parse(b.end) ?: 0) - (TimeUtil.parse(b.start) ?: 0)
            "• ${b.start}–${b.end} (${TimeUtil.formatDuration(minutes)})"
        }
        return "Free time $name: ${TimeUtil.formatDuration(day.freeMinutes)} in total, after the work I've planned.\n$blocks"
    }

    private fun tasksText(filter: String, context: ChatContextDto): String {
        val today = LocalDate.parse(context.today)
        val open = context.tasks.filter { !it.completed }
        val picked = when (filter) {
            "overdue" -> open.filter { it.due != null && LocalDate.parse(it.due) < today }
            "due_soon" -> open.filter { it.due != null && LocalDate.parse(it.due) in today..today.plusDays(7) }
            else -> open
        }.sortedWith(compareBy({ it.due == null }, { it.due }))
        if (picked.isEmpty()) {
            return when (filter) {
                "overdue" -> "Nothing is overdue."
                "due_soon" -> "Nothing is due in the next week."
                else -> "You have no open tasks."
            }
        }
        val head = when (filter) {
            "overdue" -> "Overdue:"
            "due_soon" -> "Due in the next week:"
            else -> "Open tasks:"
        }
        return head + "\n" + picked.take(12).joinToString("\n") { t ->
            val due = t.due?.let { " · due ${label(LocalDate.parse(it))}" } ?: ""
            "• ${t.title}$due · ${TimeUtil.formatDuration(maxOf(0, t.minutes - t.spent))} left"
        } + if (picked.size > 12) "\n…and ${picked.size - 12} more." else ""
    }

    private fun missedText(context: ChatContextDto): String {
        val missed = context.plan.sessions.filter { it.status == "MISSED" }.groupBy { it.refId }
        if (missed.isEmpty()) return "You haven't missed anything today."
        return "Missed today:\n" + missed.values.joinToString("\n") { list ->
            "• ${list.first().title}" + if (list.size > 1) " (${list.size} times)" else ""
        }
    }

    // ----------------------------------------------------------------------------- small helpers

    private fun label(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))

    /** Noon on that day, local time: safely inside the day whatever the time zone. */
    private fun dueMillis(date: LocalDate): Long = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
    private fun JsonObject.date(key: String): LocalDate? = str(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
