@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.ui.common.FilterPill
import com.smartstudent.planner.ui.common.Pill
import com.smartstudent.planner.ui.common.RoundIconButton
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.WhiteCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dueFormat = DateTimeFormatter.ofPattern("d MMM")

private fun dueDay(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatDue(millis: Long): String = dueDay(millis).format(dueFormat)

/** "Today", "Tomorrow", "20 Oct" or "No date". */
private fun dueLabel(millis: Long?): String {
    if (millis == null) return "No date"
    val day = dueDay(millis)
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(dueFormat)
    }
}

/** The date picker returns UTC midnight; convert to 23:59 on that date in the user's timezone. */
private fun endOfDayLocal(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private enum class TaskFilter(val label: String) {
    ALL("All Tasks"),
    OPEN("Open"),
    COMPLETED("Completed"),
    OVERDUE("Overdue"),
}

@Composable
fun TasksScreen(
    container: AppContainer,
    addRequested: Boolean,
    onAddConsumed: () -> Unit,
    onSync: () -> Unit,
) {
    val viewModel: TasksViewModel = viewModel(factory = TasksViewModel.factory(container))
    val tasks by viewModel.tasks.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var filter by remember { mutableStateOf(TaskFilter.ALL) }

    // The "+" button in the bottom bar asks this screen to open the add dialog.
    LaunchedEffect(addRequested) {
        if (addRequested) {
            showAddDialog = true
            onAddConsumed()
        }
    }

    val now = System.currentTimeMillis()
    val shown = when (filter) {
        TaskFilter.ALL -> tasks
        TaskFilter.OPEN -> tasks.filter { !it.completed }
        TaskFilter.COMPLETED -> tasks.filter { it.completed }
        TaskFilter.OVERDUE -> tasks.filter { !it.completed && it.dueDate != null && it.dueDate < now }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle(
                title = "Your tasks",
                trailing = {
                    RoundIconButton(Icons.Default.Refresh, "Sync", onSync)
                    RoundIconButton(Icons.Default.Add, "Add task", { showAddDialog = true })
                },
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                TaskFilter.entries.forEach { f ->
                    FilterPill(f.label, selected = filter == f, onClick = { filter = f })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (tasks.isEmpty()) "No tasks yet. Tap + to add one." else "Nothing here.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        items(shown, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onEdit = { editing = task },
                onToggle = { viewModel.setCompleted(task, !task.completed) },
                onDelete = { viewModel.delete(task) },
            )
        }
    }

    if (showAddDialog) {
        TaskDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { title, priority, minutes, due ->
                viewModel.add(title, priority, minutes, due)
                showAddDialog = false
            },
        )
    }

    editing?.let { task ->
        TaskDialog(
            initial = task,
            onDismiss = { editing = null },
            onSave = { title, priority, minutes, due ->
                viewModel.update(task, title, priority, minutes, due)
                editing = null
            },
        )
    }
}

@Composable
private fun TaskCard(task: TaskEntity, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val overdue = !task.completed && task.dueDate != null && task.dueDate < System.currentTimeMillis()
    var menuOpen by remember { mutableStateOf(false) }

    val topLine = when {
        task.dueDate == null -> "No due date"
        overdue -> "Overdue · ${formatDue(task.dueDate)}"
        else -> "Due ${formatDue(task.dueDate)}"
    }
    val subtitle = buildString {
        append(task.priority)
        append(" priority")
        if (task.spentMinutes > 0 && !task.completed) {
            append(" · ${task.spentMinutes}/${task.estimatedMinutes} min done")
        }
        if (task.dirty) append(" · not synced")
        // put off by "do it tomorrow" or "lighten my day": say why it isn't in today's plan
        val startsOn = task.deferUntil?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (!task.completed && startsOn != null && startsOn.isAfter(java.time.LocalDate.now())) {
            append(" · starts ${startsOn.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))}")
        }
    }

    WhiteCard(Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(topLine, fontSize = 14.sp, color = if (overdue) scheme.error else scheme.onSurfaceVariant)
                Text(
                    task.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 28.sp,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                )
                Text(subtitle, fontSize = 14.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(dueLabel(task.dueDate), dark = true)
                    Pill(TimeUtil.formatDuration(task.estimatedMinutes))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onToggle,
                    shape = CircleShape,
                    color = if (task.completed) scheme.primary else scheme.surfaceVariant,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = if (task.completed) "Mark as not done" else "Mark done",
                            tint = if (task.completed) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Adds a task ([initial] = null) or edits an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDialog(
    initial: TaskEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Long?) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var priority by remember { mutableStateOf(initial?.priority ?: "Medium") }
    var minutes by remember { mutableStateOf((initial?.estimatedMinutes ?: 30).toString()) }
    var dueMillis by remember { mutableStateOf(initial?.dueDate) }
    var showPicker by remember { mutableStateOf(false) }

    val minutesValue = minutes.toIntOrNull()
    val valid = title.isNotBlank() && minutesValue != null && minutesValue in 1..1440

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New task" else "Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    label = { Text("Title") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach { level ->
                        FilterChip(
                            selected = priority == level,
                            onClick = { priority = level },
                            label = { Text(level) },
                        )
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (initial != null && initial.spentMinutes > 0) {
                    Text(
                        "${initial.spentMinutes} min already done is kept.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPicker = true }) {
                        Text(dueMillis?.let { "Due ${formatDue(it)}" } ?: "Set due date (optional)")
                    }
                    if (dueMillis != null) TextButton(onClick = { dueMillis = null }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(title, priority, minutesValue!!, dueMillis) }) {
                Text(if (initial == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showPicker) {
        // The picker works in UTC dates; show the task's current due date as the starting point.
        val startMillis = dueMillis?.let {
            dueDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = startMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dueMillis = state.selectedDateMillis?.let(::endOfDayLocal)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
