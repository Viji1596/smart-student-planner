@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.progress

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.ui.common.DayPill
import com.smartstudent.planner.ui.common.PillBarChart
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.StatTile
import com.smartstudent.planner.ui.common.dayShortName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val headerFormat = DateTimeFormatter.ofPattern("d MMMM yyyy")

@Composable
fun ProgressScreen(container: AppContainer) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.factory(container))
    val state by viewModel.state.collectAsState()

    val current = state
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val today = LocalDate.now()
    val todayIndex = current.weekDates.indexOf(today).coerceAtLeast(0)
    var selected by remember { mutableIntStateOf(todayIndex) }
    val scheme = MaterialTheme.colorScheme

    val habitsThisWeek = current.habitsDonePerDay.sum()
    val daysSoFar = (todayIndex + 1).coerceAtLeast(1)
    val average = habitsThisWeek.toFloat() / daysSoFar

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                ScreenTitle("Your Progress")
                Text(
                    "Today, ${today.format(headerFormat)}",
                    fontSize = 16.sp,
                    color = scheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---- this week's strip
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                current.weekDates.forEachIndexed { index, date ->
                    DayPill(
                        weekday = dayShortName(date.dayOfWeek.value),
                        dayNumber = date.dayOfMonth,
                        selected = index == selected,
                        onClick = { selected = index },
                    )
                }
            }
        }

        // ---- habits chart
        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.primaryContainer) {
                Column(Modifier.padding(20.dp)) {
                    Text("Habits done this week", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = scheme.onPrimaryContainer)
                    Text(
                        "avg " + String.format(java.util.Locale.US, "%.1f", average) + " per day · " +
                            "${current.habitsDonePerDay[selected]} on " +
                            dayShortName(current.weekDates[selected].dayOfWeek.value),
                        fontSize = 13.sp,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(18.dp))
                    PillBarChart(
                        values = current.habitsDonePerDay.map { it.toFloat() },
                        labels = current.weekDates.map { dayShortName(it.dayOfWeek.value) },
                        highlight = selected,
                    )
                }
            }
        }

        // ---- tiles
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    Icons.Default.Check, "Tasks done", current.tasksDone.toString(), "Task",
                    Modifier.weight(1f), container = scheme.secondaryContainer,
                )
                StatTile(
                    Icons.Default.Warning, "Still open", current.tasksOpen.toString(), "Task",
                    Modifier.weight(1f), container = scheme.tertiaryContainer,
                )
            }
        }

        // ---- planned workload
        item {
            val total = current.plannedNext7.sum()
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text("Week ahead", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(
                        TimeUtil.formatDuration(total) + " of work planned in the next 7 days",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    PillBarChart(
                        values = current.plannedNext7.map { it.toFloat() },
                        labels = current.nextDates.map { dayShortName(it.dayOfWeek.value) },
                        highlight = 0,
                        chartHeight = 130.dp,
                    )
                }
            }
        }
    }
}
