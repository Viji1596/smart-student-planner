@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartstudent.planner.data.local.ReportEntity
import com.smartstudent.planner.domain.DayStat
import com.smartstudent.planner.domain.Grading
import com.smartstudent.planner.domain.PerformanceReport
import com.smartstudent.planner.ui.common.WhiteCard
import java.time.format.TextStyle
import java.util.Locale

/** The grade, how many scheduled tasks were finished, how many were on time, and what the numbers say. */
@Composable
fun PerformanceCard(report: PerformanceReport?, period: Period, onPeriod: (Period) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How you're doing", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.values().forEach { p ->
                    FilterChip(selected = p == period, onClick = { onPeriod(p) }, label = { Text(p.label) })
                }
            }

            if (report == null) {
                Text("Working it out…", color = scheme.onSurfaceVariant)
                return@Column
            }

            // ---- the grade
            Row(verticalAlignment = Alignment.CenterVertically) {
                val gradeColor = gradeColor(report.score)
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(gradeColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        report.grade.ifEmpty { "–" },
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = gradeColor,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        report.score?.let { "$it / 100  ·  ${Grading.words(it)}" } ?: "No grade yet",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (report.score == null) "It appears once tasks have been scheduled or finished."
                        else "Half from finishing what was scheduled, half from meeting deadlines.",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                }
            }

            // ---- finished against scheduled
            val finishedFraction = if (report.scheduledTasks > 0) report.finishedScheduled.toFloat() / report.scheduledTasks else 0f
            LabelledBar(
                title = if (period == Period.TODAY) "Tasks finished today" else "Scheduled tasks finished",
                value = if (report.scheduledTasks > 0) "${report.finishedScheduled} of ${report.scheduledTasks}" else "none scheduled",
                fraction = finishedFraction,
                color = scheme.primary,
            )

            // ---- deadlines
            val judged = report.finishedOnTime + report.finishedLate + report.missedInPeriod
            LabelledBar(
                title = "Finished within the deadline",
                value = if (judged > 0) "${report.finishedOnTime} on time · ${report.finishedLate} late · ${report.missedInPeriod} missed" else "no deadlines yet",
                fraction = if (judged > 0) report.finishedOnTime.toFloat() / judged else 0f,
                color = scheme.secondary,
            )
            Text(
                "Up to 30 minutes after the end of the due day still counts as on time.",
                fontSize = 12.sp, color = scheme.onSurfaceVariant,
            )

            // ---- each day
            if (report.dayStats.size > 1) DayBars(report.dayStats)

            if (report.insights.isNotEmpty()) {
                Text("What I notice", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                report.insights.forEach { Text("• $it", fontSize = 14.sp, color = scheme.onSurface) }
            }
        }
    }
}

@Composable
private fun gradeColor(score: Int?): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        score == null -> scheme.onSurfaceVariant
        score >= 80 -> Color(0xFF2E9E5B)
        score >= 60 -> scheme.primary
        score >= 50 -> Color(0xFFD9A400)
        else -> scheme.error
    }
}

@Composable
private fun LabelledBar(title: String, value: String, fraction: Float, color: Color) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(value, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}

/** One thin bar per day: how much of what was scheduled that day was finished. */
@Composable
private fun DayBars(days: List<DayStat>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Day by day", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { d ->
                val fraction = if (d.scheduled > 0) d.finished.toFloat() / d.scheduled else 0f
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(if (d.scheduled > 0) 1f else 0.08f).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceVariant),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (d.scheduled > 0) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceIn(0.06f, 1f)).background(scheme.primary))
                        }
                    }
                }
            }
        }
        if (days.size <= 8) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                days.forEach { d ->
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(1) + "  ${d.finished}/${d.scheduled}",
                        fontSize = 10.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The published daily, weekly and monthly summaries, newest first; tap one to read it all. */
@Composable
fun PublishedReports(reports: List<ReportEntity>) {
    val scheme = MaterialTheme.colorScheme
    var openId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Summaries", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (reports.isEmpty()) {
            Text(
                "Your bedtime summary appears here every day, with a weekly analysis on Sundays and a monthly one on the last " +
                    "day of the month. Turn them on under Profile → Reminders.",
                fontSize = 14.sp, color = scheme.onSurfaceVariant,
            )
        }
        reports.forEach { report ->
            val open = openId == report.id
            WhiteCard(Modifier.fillMaxWidth(), onClick = { openId = if (open) null else report.id }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(report.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when (report.kind) {
                            "DAY" -> report.periodEnd
                            else -> "${report.periodStart} to ${report.periodEnd}"
                        } + if (open) "" else "  ·  tap to read",
                        fontSize = 12.sp, color = scheme.onSurfaceVariant,
                    )
                    if (open) Text(report.body, fontSize = 14.sp)
                }
            }
        }
    }
}
