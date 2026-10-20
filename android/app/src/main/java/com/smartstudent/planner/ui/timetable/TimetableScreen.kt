@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.ui.common.DayChips
import com.smartstudent.planner.ui.common.FilterPill
import com.smartstudent.planner.ui.common.RoundIconButton
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.TimeField
import com.smartstudent.planner.ui.common.WhiteCard
import com.smartstudent.planner.ui.common.dayFullName
import com.smartstudent.planner.ui.common.dayShortName

/** Which dialog is open: none, adding a new entry, or editing an existing one. */
private sealed interface DialogState {
    data object Closed : DialogState
    data object Adding : DialogState
    data class Editing(val entry: TimetableEntryEntity) : DialogState
}

@Composable
fun TimetableScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onImport: () -> Unit,
    addRequested: Boolean,
    onAddConsumed: () -> Unit,
) {
    val viewModel: TimetableViewModel = viewModel(factory = TimetableViewModel.factory(container))
    val day by viewModel.selectedDay.collectAsState()
    val entries by viewModel.entries.collectAsState()
    var dialog by remember { mutableStateOf<DialogState>(DialogState.Closed) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(addRequested) {
        if (addRequested) {
            dialog = DialogState.Adding
            onAddConsumed()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle(
                title = "Timetable",
                onBack = onBack,
                trailing = {
                    TextButton(onClick = onImport) { Text("Import", color = scheme.onBackground) }
                    RoundIconButton(Icons.Default.Add, "Add class", { dialog = DialogState.Adding })
                },
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                (1..7).forEach { d ->
                    FilterPill(dayShortName(d), selected = d == day, onClick = { viewModel.selectDay(d) })
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Nothing on ${dayFullName(day)}. Tap + to add a class.",
                    color = scheme.onBackground.copy(alpha = 0.8f),
                )
            }
        }
        items(entries, key = { it.id }) { entry ->
            WhiteCard(Modifier.fillMaxWidth(), onClick = { dialog = DialogState.Editing(entry) }) {
                Row(
                    Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${entry.startTime} – ${entry.endTime}" + if (entry.dirty) " • not synced" else "",
                            fontSize = 14.sp, color = scheme.onSurfaceVariant,
                        )
                        Text(entry.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { viewModel.delete(entry) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        DialogState.Closed -> Unit
        DialogState.Adding -> EntryDialog(
            initial = null,
            defaultDay = day,
            onDismiss = { dialog = DialogState.Closed },
            onSave = { title, start, end, days ->
                viewModel.add(days, title, start, end)
                dialog = DialogState.Closed
            },
        )
        is DialogState.Editing -> EntryDialog(
            initial = d.entry,
            defaultDay = d.entry.dayOfWeek,
            onDismiss = { dialog = DialogState.Closed },
            onSave = { title, start, end, _ ->
                viewModel.update(d.entry, title, start, end)
                dialog = DialogState.Closed
            },
        )
    }
}

@Composable
internal fun EntryDialog(
    initial: TimetableEntryEntity?,
    defaultDay: Int,
    onDismiss: () -> Unit,
    onSave: (title: String, start: String, end: String, days: Set<Int>) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var start by remember { mutableStateOf(initial?.startTime ?: "09:00") }
    var end by remember { mutableStateOf(initial?.endTime ?: "10:00") }
    var days by remember { mutableStateOf(setOf(defaultDay)) }

    val startMin = TimeUtil.parse(start)
    val endMin = TimeUtil.parse(end)
    val timesOk = startMin != null && endMin != null && endMin > startMin
    val valid = title.isNotBlank() && timesOk && days.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add class" else "Edit class") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("Title (e.g. Maths lecture)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Start", start, { start = it }, Modifier.weight(1f))
                    TimeField("End", end, { end = it }, Modifier.weight(1f))
                }
                if (!timesOk) {
                    Text(
                        "End time must be after the start time.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (initial == null) {
                    Text("Repeats on", style = MaterialTheme.typography.labelLarge)
                    DayChips(
                        selected = days,
                        onToggle = { d -> days = if (d in days) days - d else days + d },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(title, start, end, days) }) {
                Text(if (initial == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
