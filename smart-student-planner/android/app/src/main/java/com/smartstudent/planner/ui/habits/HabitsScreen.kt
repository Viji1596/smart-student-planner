@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.habits

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.domain.HabitKind
import com.smartstudent.planner.domain.HabitRules
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.ui.common.DayChips
import com.smartstudent.planner.ui.common.MinutesField
import com.smartstudent.planner.ui.common.RoundIconButton
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.WhiteCard

private sealed interface HabitDialogState {
    data object Closed : HabitDialogState
    data object Adding : HabitDialogState
    data class Editing(val habit: HabitEntity) : HabitDialogState
}

@Composable
fun HabitsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    addRequested: Boolean,
    onAddConsumed: () -> Unit,
) {
    val viewModel: HabitsViewModel = viewModel(factory = HabitsViewModel.factory(container))
    val rows by viewModel.rows.collectAsState()
    var dialog by remember { mutableStateOf<HabitDialogState>(HabitDialogState.Closed) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(addRequested) {
        if (addRequested) {
            dialog = HabitDialogState.Adding
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
                title = "Habits",
                onBack = onBack,
                trailing = { RoundIconButton(Icons.Default.Add, "Add habit", { dialog = HabitDialogState.Adding }) },
            )
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    "No habits yet. Add things you repeat, like \"Coding practice\" every day " +
                        "or \"Aptitude practice\" twice a week. The planner fits them into your free time.",
                    fontSize = 15.sp,
                    color = scheme.onBackground.copy(alpha = 0.8f),
                )
            }
        }
        items(rows, key = { it.entity.id }) { row ->
            WhiteCard(Modifier.fillMaxWidth(), onClick = { dialog = HabitDialogState.Editing(row.entity) }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = row.doneToday,
                        onCheckedChange = { viewModel.setDoneToday(row.entity.id, it) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(row.entity.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        val progress =
                            if (row.info.kind == HabitKind.WEEKLY) " • ${row.doneThisWeek}/${row.info.timesPerWeek} this week"
                            else ""
                        Text(
                            "${HabitRules.describe(row.info)} • ${TimeUtil.formatDuration(row.entity.minutes)}" +
                                progress + if (row.entity.dirty) " • not synced" else "",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.delete(row.entity) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete habit")
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        HabitDialogState.Closed -> Unit
        HabitDialogState.Adding -> HabitDialog(
            initial = null,
            onDismiss = { dialog = HabitDialogState.Closed },
            onSave = { title, minutes, priority, kind, days, perWeek ->
                viewModel.add(title, minutes, priority, kind, days, perWeek)
                dialog = HabitDialogState.Closed
            },
        )
        is HabitDialogState.Editing -> HabitDialog(
            initial = d.habit,
            onDismiss = { dialog = HabitDialogState.Closed },
            onSave = { title, minutes, priority, kind, days, perWeek ->
                viewModel.update(d.habit, title, minutes, priority, kind, days, perWeek)
                dialog = HabitDialogState.Closed
            },
        )
    }
}

@Composable
private fun HabitDialog(
    initial: HabitEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Int, String, HabitKind, Set<Int>, Int) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var minutes by remember { mutableStateOf(initial?.minutes ?: 45) }
    var priority by remember { mutableStateOf(initial?.priority ?: "Medium") }
    var kind by remember {
        mutableStateOf(runCatching { HabitKind.valueOf(initial?.kind ?: "DAILY") }.getOrDefault(HabitKind.DAILY))
    }
    var days by remember {
        mutableStateOf(initial?.days?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1, 3, 5))
    }
    var perWeek by remember { mutableStateOf(initial?.timesPerWeek?.takeIf { it in 1..7 } ?: 2) }

    val valid = title.isNotBlank() && minutes in 5..480 &&
        (kind != HabitKind.DAYS || days.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New habit" else "Edit habit") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("Name (e.g. Aptitude practice)") },
                    singleLine = true,
                )
                MinutesField("How long (minutes)", minutes, { minutes = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach { level ->
                        FilterChip(selected = priority == level, onClick = { priority = level }, label = { Text(level) })
                    }
                }

                Text("How often", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(kind == HabitKind.DAILY, { kind = HabitKind.DAILY }, { Text("Every day") })
                    FilterChip(kind == HabitKind.WEEKLY, { kind = HabitKind.WEEKLY }, { Text("Times a week") })
                    FilterChip(kind == HabitKind.DAYS, { kind = HabitKind.DAYS }, { Text("Fixed days") })
                }
                when (kind) {
                    HabitKind.DAYS -> DayChips(
                        selected = days,
                        onToggle = { d -> days = if (d in days) days - d else days + d },
                    )
                    HabitKind.WEEKLY -> {
                        Text("Times per week", style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            (1..7).forEach { n ->
                                FilterChip(perWeek == n, { perWeek = n }, { Text("$n") })
                            }
                        }
                        Text(
                            "The planner picks the days for you, spread through the week, and catches up if you miss one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HabitKind.DAILY -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(title, minutes, priority, kind, days, perWeek) },
            ) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
