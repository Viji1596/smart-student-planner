@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.domain.BlockKind
import com.smartstudent.planner.domain.BreakItem
import com.smartstudent.planner.domain.BusyItem
import com.smartstudent.planner.domain.FreeItem
import com.smartstudent.planner.domain.HistoryEntry
import com.smartstudent.planner.domain.SessionStatus
import com.smartstudent.planner.domain.Session
import com.smartstudent.planner.domain.SessionItem
import com.smartstudent.planner.domain.TimeBlock
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.domain.Unscheduled
import com.smartstudent.planner.domain.UnscheduledReason
import com.smartstudent.planner.domain.WorkItem
import com.smartstudent.planner.domain.WorkKind
import com.smartstudent.planner.ui.common.DayPill
import com.smartstudent.planner.ui.common.Pill
import com.smartstudent.planner.ui.common.RoundIconButton
import com.smartstudent.planner.ui.common.StatTile
import com.smartstudent.planner.ui.common.WhiteCard
import com.smartstudent.planner.ui.common.dayFullName
import com.smartstudent.planner.ui.common.dayShortName
import java.time.LocalDate

@Composable
fun HomeScreen(
    container: AppContainer,
    userName: String?,
    onSync: () -> Unit,
    onOpenProfile: () -> Unit,
    onViewAllTasks: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.state.collectAsState()
    val remindersOn by viewModel.remindersOn.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    // Home may come back after the assistant (or another screen) changed things: bring the plan up to date.
    LaunchedEffect(Unit) { viewModel.onResume() }

    val current = state
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        HomeContent(
            state = current,
            userName = userName,
            onSelectOffset = viewModel::selectOffset,
            onCompleteSession = viewModel::completeSession,
            onSkipSession = viewModel::skipSession,
            onMarkMissedDone = viewModel::markMissedDone,
            onReplan = viewModel::replan,
            onUndoHabit = viewModel::undoHabit,
            onSync = onSync,
            onOpenProfile = onOpenProfile,
            onViewAllTasks = onViewAllTasks,
            remindersOff = remindersOn == false,
            onOpenReminders = onOpenReminders,
            onOpenChat = onOpenChat,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeState,
    userName: String?,
    onSelectOffset: (Int) -> Unit,
    onCompleteSession: (Session) -> Unit,
    onSkipSession: (Session) -> Unit,
    onMarkMissedDone: (HistoryEntry) -> Unit,
    onReplan: () -> Unit,
    onUndoHabit: (String) -> Unit,
    onSync: () -> Unit,
    onOpenProfile: () -> Unit,
    onViewAllTasks: () -> Unit,
    remindersOff: Boolean,
    onOpenReminders: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val view = state.view
    val schedule = view.schedule
    val isToday = state.offset == 0
    val leftover = schedule.leftoverFree.sumOf { it.duration }
    val habitSessions = schedule.sessions.count { it.item.kind == WorkKind.HABIT }
    val habitsDone = view.doneHabits.size
    val scheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- greeting row
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (userName?.firstOrNull() ?: 'S').uppercaseChar().toString(),
                        color = scheme.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Hey, ${userName?.substringBefore(' ') ?: "there"}",
                    fontSize = 22.sp, fontWeight = FontWeight.Medium, color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                RoundIconButton(Icons.Default.Face, "Assistant", onOpenChat)
                Spacer(Modifier.width(10.dp))
                RoundIconButton(Icons.Default.Settings, "Profile and settings", onOpenProfile)
                Spacer(Modifier.width(10.dp))
                RoundIconButton(Icons.Default.Refresh, "Sync", onSync)
            }
        }

        // ---- big heading
        item {
          Column {
            val heading = if (isToday) "today's tasks." else "${dayFullName(state.date.dayOfWeek.value)}."
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.onBackground.copy(alpha = 0.55f))) {
                        append(if (isToday) "Start " else "Plan for ")
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(heading) }
                },
                fontSize = 38.sp,
                lineHeight = 44.sp,
                color = scheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                TimeUtil.formatDuration(schedule.plannedMinutes) + " planned · " +
                    TimeUtil.formatDuration(leftover) + " free" + if (isToday) " from now" else "",
                fontSize = 15.sp,
                color = scheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp),
            )
          }
        }

        // ---- week strip
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                (0..6).forEach { offset ->
                    val date = LocalDate.now().plusDays(offset.toLong())
                    DayPill(
                        weekday = dayShortName(date.dayOfWeek.value),
                        dayNumber = date.dayOfMonth,
                        selected = state.offset == offset,
                        onClick = { onSelectOffset(offset) },
                    )
                }
            }
        }

        // ---- 2x2 tiles
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    Icons.Default.DateRange, "Planned", schedule.sessions.size.toString(), "Sessions",
                    Modifier.weight(1f),
                )
                StatTile(
                    Icons.Default.Star, "Habits",
                    if (isToday) "$habitsDone/${habitsDone + habitSessions}" else habitSessions.toString(),
                    "Habits", Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    Icons.Default.Check, "Open", state.openTasks.toString(), "Task",
                    Modifier.weight(1f), onClick = onViewAllTasks,
                )
                StatTile(
                    Icons.Default.Warning, "Past due", state.overdueTasks.toString(), "Task",
                    Modifier.weight(1f), onClick = onViewAllTasks,
                )
            }
        }

        // ---- reminders are off: say so (they are off until switched on)
        if (remindersOff) {
            item {
                WhiteCard(Modifier.fillMaxWidth(), onClick = onOpenReminders) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reminders are off", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Turn them on to get a notification before each session.",
                                fontSize = 13.sp, color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenReminders) { Text("Turn on") }
                    }
                }
            }
        }

        // ---- warnings
        if (schedule.unscheduled.isNotEmpty()) item { UnscheduledCard(schedule.unscheduled) }
        if (view.plan.conflicts.isNotEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Overlapping items", fontWeight = FontWeight.SemiBold, color = scheme.onErrorContainer)
                        view.plan.conflicts.forEach {
                            Text("${it.first} overlaps ${it.second}", color = scheme.onErrorContainer)
                        }
                    }
                }
            }
        }
        if (!state.hasTimetable) {
            item {
                Text(
                    "Tip: add your classes (Profile → Timetable) so the plan avoids them.",
                    fontSize = 14.sp, color = scheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }

        // ---- timeline
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    if (isToday) "Today's plan" else "Plan",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (isToday) TextButton(onClick = onReplan) { Text("Re-plan", color = scheme.onBackground) }
                TextButton(onClick = onViewAllTasks) { Text("View All", color = scheme.onBackground) }
            }
        }
        // Sessions whose time is over: shown (not hidden) until they are ticked off or counted as missed.
        items(state.justEnded) { ended ->
            SessionCard(
                session = ended.toSession(),
                dayLabel = "Time is up",
                canComplete = true,
                onDone = { onCompleteSession(ended.toSession()) },
                onSkip = { onSkipSession(ended.toSession()) },
            )
        }
        if (view.timeline.isEmpty() && state.justEnded.isEmpty()) {
            item { Text("Nothing left for today.", color = scheme.onBackground.copy(alpha = 0.7f)) }
        }
        items(view.timeline) { entry ->
            when (entry) {
                is BusyItem -> BusyRow(entry.block)
                is SessionItem -> SessionCard(
                    session = entry.session,
                    dayLabel = if (isToday) "Today" else dayShortName(state.date.dayOfWeek.value),
                    canComplete = isToday && entry.session.plannedId != null,
                    onDone = { onCompleteSession(entry.session) },
                    onSkip = { onSkipSession(entry.session) },
                )
                is BreakItem -> Text(
                    "${TimeUtil.format(entry.startMin)} – ${TimeUtil.format(entry.endMin)}  ·  " +
                        "${TimeUtil.formatDuration(entry.block.duration)} break",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                is FreeItem -> Text(
                    "${TimeUtil.format(entry.startMin)} – ${TimeUtil.format(entry.endMin)}  ·  " +
                        "${TimeUtil.formatDuration(entry.block.duration)} free",
                    fontSize = 13.sp,
                    color = scheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        // ---- earlier today: finished and missed sessions
        if (state.earlier.isNotEmpty()) {
            item {
                Text(
                    "Earlier today", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground, modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.earlier) { past ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(past.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val label = if (past.status == SessionStatus.DONE) {
                                if (past.count > 1) "Done · ${past.count} sessions" else "Done"
                            } else {
                                if (past.count > 1) "Missed ${past.count} times" else "Missed"
                            }
                            Text(
                                "${TimeUtil.format(past.startMin)} – ${TimeUtil.format(past.endMin)}  ·  $label",
                                fontSize = 13.sp,
                                color = if (past.status == SessionStatus.DONE) scheme.onSurfaceVariant else scheme.error,
                            )
                        }
                        if (past.status == SessionStatus.MISSED) {
                            TextButton(onClick = { onMarkMissedDone(past) }) { Text("I did it") }
                        }
                    }
                }
            }
        }

        // ---- done today
        if (isToday && view.doneHabits.isNotEmpty()) {
            item {
                Text(
                    "Done today", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground, modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(view.doneHabits) { habit ->
                WhiteCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = { onUndoHabit(habit.id) })
                        Text(habit.title, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    dayLabel: String,
    canComplete: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val item = session.item
    val scheme = MaterialTheme.colorScheme
    WhiteCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${TimeUtil.format(session.startMin)} – ${TimeUtil.format(session.endMin)}",
                    fontSize = 14.sp, color = scheme.onSurfaceVariant,
                )
                Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
                Text(
                    (if (item.kind == WorkKind.HABIT) "Habit" else "Task") + " · " +
                        item.priority.name.lowercase().replaceFirstChar { it.uppercase() } + " priority" +
                        if (session.pinned) " · Moved by you" else "",
                    fontSize = 14.sp, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(dayLabel, dark = true)
                    Pill(TimeUtil.formatDuration(session.duration))
                    if (canComplete) TextButton(onClick = onSkip) { Text("Later") }
                }
            }
            if (canComplete) {
                Surface(
                    onClick = onDone,
                    shape = CircleShape,
                    color = scheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, "Mark done", tint = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

/** Classes, meals and the morning routine: quieter than the work sessions. */
@Composable
private fun BusyRow(block: TimeBlock) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (block.kind) {
        BlockKind.CLASS -> scheme.secondaryContainer
        BlockKind.MEAL -> scheme.tertiaryContainer
        BlockKind.TRAVEL -> scheme.primaryContainer
        BlockKind.REST -> scheme.tertiaryContainer
        else -> scheme.surface.copy(alpha = 0.55f)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = tint) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${TimeUtil.format(block.startMin)} – ${TimeUtil.format(block.endMin)}",
                fontSize = 13.sp, modifier = Modifier.width(112.dp), color = scheme.onBackground.copy(alpha = 0.8f),
            )
            Text(block.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), color = scheme.onBackground)
            Text(TimeUtil.formatDuration(block.duration), fontSize = 13.sp, color = scheme.onBackground.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun UnscheduledCard(items: List<Unscheduled>) {
    val scheme = MaterialTheme.colorScheme
    // Only things due today (or already overdue) that don't fit are a real problem;
    // later deadlines simply continue on the following days.
    val atRisk = items.any { it.item.tier <= 1 }
    val container = if (atRisk) scheme.errorContainer else scheme.surface.copy(alpha = 0.7f)
    val textColor = if (atRisk) scheme.onErrorContainer else scheme.onBackground
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (atRisk) "Not enough time for everything" else "Continues on later days",
                fontWeight = FontWeight.SemiBold, color = textColor,
            )
            items.forEach {
                val why = when (it.reason) {
                    UnscheduledReason.NO_ROOM -> "no free slot big enough"
                    UnscheduledReason.DAILY_LIMIT -> "daily work limit reached"
                }
                Text("${it.item.title}: ${TimeUtil.formatDuration(it.minutes)} ($why)", fontSize = 14.sp, color = textColor)
            }
        }
    }
}
