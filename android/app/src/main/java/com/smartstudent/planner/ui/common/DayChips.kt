package com.smartstudent.planner.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

fun dayShortName(day: Int): String =
    DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun dayFullName(day: Int): String =
    DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())

/** A scrollable row of Mon..Sun chips. Days are 1 = Monday ... 7 = Sunday. */
@Composable
fun DayChips(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..7).forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(dayShortName(day)) },
            )
        }
    }
}
