package com.smartstudent.planner.reminders

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.HabitCompletionEntity
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.local.PlannedSessionDao
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.SettingsRepository
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.TimetableRepository
import com.smartstudent.planner.data.repo.toDomainOrNull
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toEntity
import com.smartstudent.planner.data.repo.toFixedEntry
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.data.repo.toRules
import com.smartstudent.planner.domain.BlockKind
import com.smartstudent.planner.domain.DayBuilder
import com.smartstudent.planner.domain.DayPlanner
import com.smartstudent.planner.domain.DaySummary
import com.smartstudent.planner.domain.MissedInfo
import com.smartstudent.planner.domain.PlanReconciler
import com.smartstudent.planner.domain.PlannedSession
import com.smartstudent.planner.domain.Priority
import com.smartstudent.planner.domain.Reminder
import com.smartstudent.planner.domain.ReminderPlanner
import com.smartstudent.planner.domain.MoveResult
import com.smartstudent.planner.domain.Scheduler
import com.smartstudent.planner.domain.SessionMover
import com.smartstudent.planner.domain.SessionStatus
import com.smartstudent.planner.domain.SummarySchedule
import com.smartstudent.planner.domain.TimeBlock
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.domain.WorkKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps today's committed plan, the reminder alarms and the "missed session" handling in step.
 *
 * [reconcile] is safe to call at any time and from anywhere (the app, an alarm, a background job):
 *  1. if there's no plan for today yet, or something changed, or a session was missed, it re-plans;
 *  2. it then sets the alarms to match the plan;
 *  3. if sessions were newly missed, it tells you where their work was moved.
 */
/** The result of asking to move a session to another time today. */
sealed interface MoveOutcome {
    data class Moved(val startMin: Int) : MoveOutcome
    data class Refused(val reason: String) : MoveOutcome
}

class PlanManager(
    private val appContext: Context,
    private val tokenStore: TokenStore,
    private val settings: SettingsRepository,
    private val timetable: TimetableRepository,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val sessionDao: PlannedSessionDao,
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** While the app process is alive, re-plan whenever the data or the reminder choices change. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            // If the watcher ever fails it starts again, instead of leaving the plan frozen until the app is reopened.
            while (true) {
                try {
                    combine(
                        settings.settings,
                        timetable.entries,
                        tasks.tasks,
                        habits.habits,
                        habits.completionsSince(LocalDate.now().minusDays(14)),
                    ) { s, e, t, h, c -> fingerprint(s, e, t, h, c) }
                        .distinctUntilChanged()
                        .debounce(800)
                        .collect { reconcile() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Plan watcher stopped (${e.message}); restarting", e)
                    store.setError(System.currentTimeMillis(), "Plan watcher: ${e.message ?: e.javaClass.simpleName}")
                    delay(2_000)
                }
            }
        }
        scope.launch {
            store.settings.distinctUntilChanged().collect { reconcile() }
        }
    }

    /**
     * Brings today's plan up to date right now (it only re-plans if something relevant changed), and says what went
     * wrong instead of only recording it. Used after the assistant changes tasks or habits, so the Home screen
     * never has to wait for the background watcher.
     */
    suspend fun refresh(): String? = mutex.withLock {
        try {
            doReconcile(userInitiated = false, force = false, skipId = null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}", e)
            val message = e.message ?: e.javaClass.simpleName
            store.setError(System.currentTimeMillis(), message)
            message
        }
    }

    suspend fun reconcile(userInitiated: Boolean = false, force: Boolean = false, skipId: String? = null) {
        mutex.withLock {
            try {
                doReconcile(userInitiated, force, skipId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reconcile failed: ${e.message}", e)
                store.setError(System.currentTimeMillis(), e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * A cheap check that Home runs while it is open (and when the app is opened): re-plan only if a
     * session has run past its grace period or the day has changed. This means the screen never
     * depends on an alarm having arrived.
     */
    suspend fun reconcileIfNeeded() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        val (planDate, _) = store.planState()
        val sessions = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
        if (planDate != today.toString() || PlanReconciler.needsMissedHandling(sessions, nowMin)) {
            reconcile()
        }
    }

    /**
     * Work that was done without ticking a planned session on Home (told to the assistant, or finished on the Tasks
     * tab) is written into today's history, so it shows under "Earlier today" and counts in the performance report.
     * See [PlanReconciler.recordDone].
     */
    suspend fun recordDone(kind: WorkKind, refId: String, title: String, priority: Priority, minutes: Int) {
        mutex.withLock {
            try {
                val now = LocalDateTime.now()
                val today = now.toLocalDate()
                val existing = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
                val updated = PlanReconciler.recordDone(existing, today, kind, refId, title, priority, minutes, now.hour * 60 + now.minute)
                sessionDao.replaceForDate(today.toString(), updated.map { it.toEntity() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Recording done work failed: ${e.message}", e)
            }
        }
    }

    /** "Re-plan" button: rebuild the rest of today from now. */
    suspend fun replanNow() = reconcile(userInitiated = true, force = true)

    /**
     * "Do it at 9pm" for a task that has no session today (for example one that was just added): a session is created
     * at that time (or the nearest free slot, if [flexible]) and kept there when the plan is rebuilt. If the task
     * already has a session today, that one is moved instead. The session is at most 90 minutes.
     */
    suspend fun placeTask(taskId: String, timeMin: Int, flexible: Boolean): MoveOutcome = mutex.withLock {
        try {
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val nowMin = now.hour * 60 + now.minute

            val task = tasks.get(taskId) ?: return@withLock MoveOutcome.Refused("I couldn't find that task.")
            val remaining = task.estimatedMinutes - task.spentMinutes
            if (task.completed || remaining <= 0) return@withLock MoveOutcome.Refused("“${task.title}” is already done.")

            val rules = settings.settings.first().toRules()
            val todayEntries = timetable.entries.first()
                .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
                .filter { it.first == today.dayOfWeek.value }
                .map { it.second }
            val plan = DayPlanner.plan(rules, todayEntries)

            val sessions = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
            val existing = sessions.filter { it.kind == WorkKind.TASK && it.refId == taskId && it.status == SessionStatus.PLANNED }
                .minByOrNull { it.startMin }
            val duration = existing?.duration ?: minOf(remaining, Scheduler.MAX_SESSION)
            val blockers = sessions
                .filter { it.id != existing?.id && it.status == SessionStatus.PLANNED && (it.pinned || it.startMin <= nowMin) }
                .map { TimeBlock(it.startMin, it.endMin, it.title, BlockKind.CLASS) }

            when (val result = SessionMover.place(plan, blockers, duration, timeMin, nowMin, flexible)) {
                is MoveResult.Refused -> MoveOutcome.Refused(result.reason)
                is MoveResult.Placed -> {
                    val placed = PlannedSession(
                        id = PlanReconciler.sessionId(today, WorkKind.TASK, taskId, result.startMin),
                        date = today,
                        startMin = result.startMin,
                        endMin = result.startMin + duration,
                        kind = WorkKind.TASK,
                        refId = taskId,
                        title = existing?.title ?: task.title,
                        priority = existing?.priority ?: Priority.parse(task.priority),
                        status = SessionStatus.PLANNED,
                        pinned = true,
                    )
                    if (existing != null) sessionDao.replaceOne(existing.id, placed.toEntity()) else sessionDao.insertAll(listOf(placed.toEntity()))
                    doReconcile(userInitiated = true, force = true, skipId = null)
                    MoveOutcome.Moved(result.startMin)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Placing a task failed: ${e.message}", e)
            MoveOutcome.Refused("Something went wrong while scheduling it.")
        }
    }

    /**
     * Swaps two of today's planned sessions: each takes the other's start time, and both stay there when
     * the plan is rebuilt. Refused if either would not fit in its new place (they may differ in length).
     */
    suspend fun swapSessions(aId: String, bId: String): MoveOutcome = mutex.withLock {
        try {
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val nowMin = now.hour * 60 + now.minute

            val rules = settings.settings.first().toRules()
            val todayEntries = timetable.entries.first()
                .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
                .filter { it.first == today.dayOfWeek.value }
                .map { it.second }
            val plan = DayPlanner.plan(rules, todayEntries)

            val sessions = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
            val a = sessions.firstOrNull { it.id == aId && it.status == SessionStatus.PLANNED }
            val b = sessions.firstOrNull { it.id == bId && it.status == SessionStatus.PLANNED }
            if (a == null || b == null) return@withLock MoveOutcome.Refused("One of those sessions isn't planned any more.")

            val newAStart = b.startMin
            val newBStart = a.startMin
            val newAEnd = newAStart + a.duration
            val newBEnd = newBStart + b.duration
            if (newAStart < newBEnd && newBStart < newAEnd) {
                return@withLock MoveOutcome.Refused("They would overlap after the swap, because they're different lengths. Try moving one of them at a time.")
            }

            val blockers = sessions
                .filter { it.id != a.id && it.id != b.id && it.status == SessionStatus.PLANNED && (it.pinned || it.startMin <= nowMin) }
                .map { TimeBlock(it.startMin, it.endMin, it.title, BlockKind.CLASS) }
            val aSpot = SessionMover.place(plan, blockers + TimeBlock(newBStart, newBEnd, b.title, BlockKind.CLASS), a.duration, newAStart, nowMin, false)
            val bSpot = SessionMover.place(plan, blockers + TimeBlock(newAStart, newAEnd, a.title, BlockKind.CLASS), b.duration, newBStart, nowMin, false)
            if (aSpot is MoveResult.Refused) return@withLock MoveOutcome.Refused("“${a.title}” can't go there. ${aSpot.reason}")
            if (bSpot is MoveResult.Refused) return@withLock MoveOutcome.Refused("“${b.title}” can't go there. ${bSpot.reason}")

            val movedA = a.copy(id = PlanReconciler.sessionId(today, a.kind, a.refId, newAStart), startMin = newAStart, endMin = newAEnd, pinned = true)
            val movedB = b.copy(id = PlanReconciler.sessionId(today, b.kind, b.refId, newBStart), startMin = newBStart, endMin = newBEnd, pinned = true)
            sessionDao.replaceOne(a.id, movedA.toEntity())
            sessionDao.replaceOne(b.id, movedB.toEntity())
            doReconcile(userInitiated = true, force = true, skipId = null)
            MoveOutcome.Moved(newAStart)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Swap failed: ${e.message}", e)
            MoveOutcome.Refused("Something went wrong while swapping them.")
        }
    }

    /**
     * Moves today's planned session to [timeMin] (or the nearest free slot, if [flexible]). The session is
     * then "pinned": re-planning works around it instead of moving it again. Refused when the time is taken
     * by a class, a meal, travel or rest, by another session placed by hand, or has already passed.
     */
    suspend fun moveSession(sessionId: String, timeMin: Int, flexible: Boolean): MoveOutcome = mutex.withLock {
        try {
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val nowMin = now.hour * 60 + now.minute

            val rules = settings.settings.first().toRules()
            val todayEntries = timetable.entries.first()
                .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
                .filter { it.first == today.dayOfWeek.value }
                .map { it.second }
            val plan = DayPlanner.plan(rules, todayEntries)

            val sessions = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
            val target = sessions.firstOrNull { it.id == sessionId && it.status == SessionStatus.PLANNED }
                ?: return@withLock MoveOutcome.Refused("That session isn't planned any more.")
            val blockers = sessions
                .filter { it.id != target.id && it.status == SessionStatus.PLANNED && (it.pinned || it.startMin <= nowMin) }
                .map { TimeBlock(it.startMin, it.endMin, it.title, BlockKind.CLASS) }

            when (val result = SessionMover.place(plan, blockers, target.duration, timeMin, nowMin, flexible)) {
                is MoveResult.Refused -> MoveOutcome.Refused(result.reason)
                is MoveResult.Placed -> {
                    val moved = target.copy(
                        id = PlanReconciler.sessionId(today, target.kind, target.refId, result.startMin),
                        startMin = result.startMin,
                        endMin = result.startMin + target.duration,
                        pinned = true,
                    )
                    sessionDao.replaceOne(target.id, moved.toEntity())
                    doReconcile(userInitiated = true, force = true, skipId = null)
                    MoveOutcome.Moved(result.startMin)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Move failed: ${e.message}", e)
            MoveOutcome.Refused("Something went wrong while moving it.")
        }
    }

    /** "Later" button: this session is skipped and its work is planned again. */
    suspend fun skip(sessionId: String) = reconcile(userInitiated = true, skipId = sessionId)

    /** "Done" on the Time's up notification: same as ticking the session off in the app. */
    suspend fun completeFromNotification(sessionId: String) {
        val today = LocalDate.now()
        val session = sessionDao.getForDate(today.toString())
            .firstOrNull { it.id == sessionId }?.toDomainOrNull() ?: return
        if (session.status == SessionStatus.DONE) return
        markDone(sessionId)
        when (session.kind) {
            WorkKind.TASK -> tasks.logProgress(session.refId, session.duration)
            WorkKind.HABIT -> habits.setDone(session.refId, today, true)
        }
        reconcile(userInitiated = true) // re-plan and reset the alarms before this process may be stopped
    }

    /**
     * A session was finished. Its older "missed" records for the same task are dropped: the work is
     * done, so there is nothing left to press "I did it" on.
     */
    suspend fun markDone(sessionId: String) {
        val row = sessionDao.getById(sessionId)
        sessionDao.updateStatus(sessionId, SessionStatus.DONE.name)
        if (row != null) sessionDao.deleteMissedFor(row.date, row.kind, row.refId)
    }

    /**
     * Takes back ONE tick ("Undo" right after ticking a session): the session is planned again and the work it recorded
     * is taken off the task (or the habit is un-ticked). If its time has already passed, the next re-plan treats it as
     * missed, which is what happened.
     */
    suspend fun undoSession(sessionId: String) {
        mutex.withLock {
            try {
                val row = sessionDao.getById(sessionId)?.toDomainOrNull() ?: return@withLock
                if (row.status != SessionStatus.DONE) return@withLock
                sessionDao.updateStatus(sessionId, SessionStatus.PLANNED.name)
                when (row.kind) {
                    WorkKind.TASK -> tasks.undoProgress(row.refId, row.duration)
                    WorkKind.HABIT -> habits.setDone(row.refId, row.date, false)
                }
                doReconcile(userInitiated = true, force = true, skipId = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Undo of a tick failed: ${e.message}", e)
            }
        }
    }

    /**
     * Takes back "done" for a task or habit: today's finished sessions of it are removed, the work they recorded is
     * taken off its progress (so the task is open again), and the plan is rebuilt. With [adjustProgress] false only
     * the records go (used when the caller changes the task itself, like un-ticking it on the Tasks tab).
     * Returns the minutes taken back.
     */
    suspend fun undoDone(kind: WorkKind, refId: String, adjustProgress: Boolean = true): Int = mutex.withLock {
        try {
            val today = LocalDate.now()
            val existing = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
            val (after, minutes) = PlanReconciler.undoDone(existing, kind, refId)
            sessionDao.replaceForDate(today.toString(), after.map { it.toEntity() })
            if (adjustProgress) {
                when (kind) {
                    WorkKind.TASK -> tasks.undoProgress(refId, minutes)
                    WorkKind.HABIT -> habits.setDone(refId, today, false)
                }
            }
            doReconcile(userInitiated = true, force = true, skipId = null)
            minutes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Undo failed: ${e.message}", e)
            0
        }
    }

    /**
     * "I did it" on a line that says "Missed N times": all of those records become one finished
     * session. Returns the minutes to record as done (one session's worth, not N), or 0 if none.
     */
    suspend fun completeMissed(kind: WorkKind, refId: String): Int {
        val today = LocalDate.now().toString()
        val missed = sessionDao.getForDate(today).mapNotNull { it.toDomainOrNull() }
            .filter { it.status == SessionStatus.MISSED && it.kind == kind && it.refId == refId }
        if (missed.isEmpty()) return 0
        val latest = missed.maxByOrNull { it.startMin }!!
        sessionDao.resolveMissed(today, kind.name, refId, latest.id)
        return missed.maxOf { it.duration }
    }

    private suspend fun doReconcile(userInitiated: Boolean, force: Boolean, skipId: String?) {
        if (tokenStore.accessTokenNow() == null) {
            scheduler.cancelAll() // signed out: no reminders
            return
        }

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute

        val settingsEntity = settings.settings.first()
        val entries = timetable.entries.first()
        val taskList = tasks.tasks.first()
        val habitList = habits.habits.first()
        val completions = habits.completionsSince(today.minusDays(14)).first()

        val rules = settingsEntity.toRules()
        val entriesByWeekday = entries
            .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
            .groupBy({ it.first }, { it.second })
        val todayEntries = entriesByWeekday[today.dayOfWeek.value] ?: emptyList()
        val openTasks = taskList.filter { !it.completed }.map { it.toInfo() }
        val habitInfos = habitList.map { it.toInfo() }
        val doneDates = completions.toDoneDates()

        val existing = sessionDao.getForDate(today.toString()).mapNotNull { it.toDomainOrNull() }
        val (planDate, planFingerprint) = store.planState()
        val fingerprint = fingerprint(settingsEntity, entries, taskList, habitList, completions)

        val somethingMissed = PlanReconciler.needsMissedHandling(existing, nowMin)
        val needsReplan = force || skipId != null || somethingMissed ||
            planDate != today.toString() || planFingerprint != fingerprint

        var sessions = existing
        var newlyMissed: List<MissedInfo> = emptyList()
        if (needsReplan) {
            val result = PlanReconciler.replanToday(
                rules = rules,
                entries = todayEntries,
                tasks = openTasks,
                habits = habitInfos,
                completions = doneDates,
                existing = existing,
                date = today,
                nowMin = nowMin,
                skipIds = setOfNotNull(skipId),
            )
            sessionDao.replaceForDate(today.toString(), result.sessions.map { it.toEntity() })
            store.setPlanState(today.toString(), fingerprint)
            sessions = result.sessions
            newlyMissed = result.newlyMissed
        }

        // ---- alarms that match the plan
        val prefs = store.settingsNow()
        val todayPlan = DayPlanner.plan(rules, todayEntries)
        val tomorrowView = DayBuilder.buildRange(
            rules, entriesByWeekday, openTasks, habitInfos, doneDates, today, 2, nowMin,
        ).last()
        val upcoming = sessions.filter { it.status == SessionStatus.PLANNED }
        val summaries = listOf(
            DaySummary(today, todayPlan.windowStart, upcoming.size, upcoming.sumOf { it.duration }),
            DaySummary(
                today.plusDays(1), tomorrowView.plan.windowStart,
                tomorrowView.schedule.sessions.size, tomorrowView.schedule.plannedMinutes,
            ),
        )
        val reminders: List<Pair<Long, Reminder>> =
            ReminderPlanner.build(prefs, today, nowMin, todayPlan, sessions, summaries)
                .map { toMillis(it.date, it.minute, zone) to it }

        val checks = ReminderPlanner.missedChecks(sessions, nowMin).map { toMillis(today, it, zone) } +
            // Just after midnight, plan the new day so its reminders (e.g. "time to leave") get set.
            toMillis(today.plusDays(1), 10, zone)
        scheduler.replaceAll(reminders, checks)
        // the bedtime summary: one alarm, at the next bedtime, only when the student wants any of the summaries
        scheduler.scheduleSummary(
            if (prefs.enabled && (prefs.daySummary || prefs.weekSummary || prefs.monthSummary)) {
                SummarySchedule.nextBedtime(now, rules.sleepMin).atZone(zone).toInstant().toEpochMilli()
            } else null
        )
        Log.i(TAG, "Alarms set: ${reminders.size} reminders, ${checks.size} checks (exact=${scheduler.canScheduleExact()})")

        val dayTime = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")
        val timeOnly = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        store.setDiagnostics(
            runMillis = System.currentTimeMillis(),
            upcoming = reminders.take(8).map { (at, r) ->
                val local = java.time.Instant.ofEpochMilli(at).atZone(zone)
                (if (local.toLocalDate() == today) local.format(timeOnly) else local.format(dayTime)) + " · " + r.title
            },
            alarmCount = reminders.size + checks.size,
        )

        // ---- tell the user about anything that was missed (not when they skipped it themselves)
        if (!userInitiated && prefs.enabled) {
            for (m in newlyMissed) {
                // Don't nag: after the same thing was missed twice today, re-plan quietly.
                val timesMissed = sessions.count {
                    it.status == SessionStatus.MISSED && it.kind == m.session.kind && it.refId == m.session.refId
                }
                if (timesMissed > MAX_MISSED_NOTIFICATIONS) continue
                Notifier.show(
                    appContext,
                    id = ("missed|" + m.session.id).hashCode(),
                    title = "Missed: ${m.session.title}",
                    text = m.movedToMin?.let { "Moved to ${TimeUtil.format(it)} today." }
                        ?: "It didn't fit today, so it continues tomorrow.",
                    channel = Notifier.CHANNEL_PLAN,
                )
            }
        }

        // keep two months of history: the performance report is built from it
        sessionDao.deleteBefore(today.minusDays(60).toString())
    }

    private fun toMillis(date: LocalDate, minute: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    /** Changes when anything that affects the plan changes (and not when only status flags change). */
    private fun fingerprint(
        s: SettingsEntity,
        entries: List<TimetableEntryEntity>,
        tasks: List<TaskEntity>,
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>,
    ): String {
        val sb = StringBuilder()
        sb.append(s.copy(dirty = false, updatedAtLocal = 0L)).append('#')
        entries.sortedBy { it.id }.forEach {
            sb.append(it.id).append(it.dayOfWeek).append(it.startTime).append(it.endTime).append(it.title).append(';')
        }
        sb.append('#')
        tasks.filter { !it.completed }.sortedBy { it.id }.forEach {
            sb.append(it.id).append('|').append(it.estimatedMinutes).append('|').append(it.spentMinutes)
                .append('|').append(it.dueDate).append('|').append(it.priority).append('|').append(it.title)
                .append('|').append(it.deferUntil).append(';')
        }
        sb.append('#')
        habits.sortedBy { it.id }.forEach {
            sb.append(it.id).append('|').append(it.minutes).append('|').append(it.priority).append('|')
                .append(it.kind).append('|').append(it.days).append('|').append(it.timesPerWeek).append('|')
                .append(it.title).append(';')
        }
        sb.append('#')
        completions.map { it.id }.sorted().forEach { sb.append(it).append(';') }
        return sb.toString().hashCode().toString()
    }

    private companion object {
        const val TAG = "PlanManager"
        const val MAX_MISSED_NOTIFICATIONS = 2
    }
}
