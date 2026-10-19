@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.domain.TimeUtil
import com.smartstudent.planner.ui.common.MinutesField
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.TimeField
import com.smartstudent.planner.ui.common.WhiteCard
import java.util.TimeZone

/** Returns a message describing the first problem, or null if the settings are fine. */
private fun validate(s: SettingsEntity): String? {
    if (s.wakeTime == s.sleepTime) return "Wake-up and bedtime can't be the same."
    if (s.morningRoutineMinutes !in 0..240) return "Morning routine must be 0 to 240 minutes."
    val meals = listOf(s.breakfastMinutes, s.lunchMinutes, s.dinnerMinutes)
    if (meals.any { it !in 5..180 }) return "Each meal must take 5 to 180 minutes."
    if (s.commuteToMinutes !in 0..240 || s.commuteFromMinutes !in 0..240) return "Travel time can be 0 to 240 minutes."
    if (s.arriveEarlyMinutes !in 0..60) return "Arriving early can be 0 to 60 minutes."
    if (s.restMinutes !in 0..180) return "Rest can be 0 to 180 minutes."
    if (s.breakMinutes !in 0..60) return "A break between sessions can be 0 to 60 minutes."
    if (s.longBreakAfterMinutes !in 0..480) return "Long break timing can be 0 to 480 minutes."
    if (s.longBreakMinutes !in 0..120) return "A long break can be 0 to 120 minutes."
    return null
}

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val saved by viewModel.settings.collectAsState()

    val current = saved
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        SettingsForm(saved = current, onSave = viewModel::save, onBack = onBack)
    }
}

@Composable
private fun SettingsForm(saved: SettingsEntity, onSave: (SettingsEntity) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    // Editing happens on a draft; it resets whenever the saved settings change (e.g. after saving).
    var draft by remember(saved) { mutableStateOf(saved) }
    var justSaved by remember(saved) { mutableStateOf(false) }

    val problem = validate(draft)
    val changed = draft != saved

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Daily routine", onBack = onBack)

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your day", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Wake up", draft.wakeTime, { draft = draft.copy(wakeTime = it) }, Modifier.weight(1f))
                    TimeField("Bedtime", draft.sleepTime, { draft = draft.copy(sleepTime = it) }, Modifier.weight(1f))
                }
                val wake = TimeUtil.parse(draft.wakeTime)
                val sleep = TimeUtil.parse(draft.sleepTime)
                if (wake != null && sleep != null && wake != sleep) {
                    val awake = if (sleep > wake) sleep - wake else sleep + 24 * 60 - wake
                    Text(
                        "Awake for ${TimeUtil.formatDuration(awake)}, sleep ${TimeUtil.formatDuration(24 * 60 - awake)}.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                MinutesField(
                    "Morning routine (minutes)", draft.morningRoutineMinutes,
                    { draft = draft.copy(morningRoutineMinutes = it) }, Modifier.fillMaxWidth(),
                )
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Meals", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                MealRow(
                    "Breakfast", draft.breakfastStart, draft.breakfastMinutes,
                    { draft = draft.copy(breakfastStart = it) }, { draft = draft.copy(breakfastMinutes = it) },
                )
                MealRow(
                    "Lunch", draft.lunchStart, draft.lunchMinutes,
                    { draft = draft.copy(lunchStart = it) }, { draft = draft.copy(lunchMinutes = it) },
                )
                MealRow(
                    "Dinner", draft.dinnerStart, draft.dinnerMinutes,
                    { draft = draft.copy(dinnerStart = it) }, { draft = draft.copy(dinnerMinutes = it) },
                )
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Travel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Added on days you have classes: before the first one and after the last one. " +
                        "Walking to the hostel, a bus ride, a train... set the way there and the way back " +
                        "separately. Leave both at 0 if you don't travel.",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MinutesField(
                        "To college (min)", draft.commuteToMinutes,
                        { draft = draft.copy(commuteToMinutes = it) }, Modifier.weight(1f),
                    )
                    MinutesField(
                        "Back home (min)", draft.commuteFromMinutes,
                        { draft = draft.copy(commuteFromMinutes = it) }, Modifier.weight(1f),
                    )
                }
                MinutesField(
                    "Arrive early by (min)", draft.arriveEarlyMinutes,
                    { draft = draft.copy(arriveEarlyMinutes = it) }, Modifier.fillMaxWidth(),
                )
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rest and breaks", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Rest in the middle of the day is kept free of study. Set the minutes to 0 to turn it off.",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimeField("Rest at", draft.restStart, { draft = draft.copy(restStart = it) }, Modifier.weight(1f))
                    MinutesField(
                        "Minutes", draft.restMinutes,
                        { draft = draft.copy(restMinutes = it) }, Modifier.weight(0.7f),
                    )
                }
                Text(
                    "Between study sessions the planner leaves a short break, and a longer one after " +
                        "a stretch of work. Use 0 to turn either off.",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                )
                MinutesField(
                    "Short break between sessions (min)", draft.breakMinutes,
                    { draft = draft.copy(breakMinutes = it) }, Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MinutesField(
                        "Long break after (min)", draft.longBreakAfterMinutes,
                        { draft = draft.copy(longBreakAfterMinutes = it) }, Modifier.weight(1f),
                    )
                    MinutesField(
                        "Long break (min)", draft.longBreakMinutes,
                        { draft = draft.copy(longBreakMinutes = it) }, Modifier.weight(1f),
                    )
                }
            }
        }

        problem?.let { Text(it, color = scheme.error) }

        Button(
            enabled = problem == null && changed,
            onClick = {
                onSave(draft.copy(timezone = TimeZone.getDefault().id))
                justSaved = true
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.inverseSurface,
                contentColor = scheme.inverseOnSurface,
            ),
        ) { Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        if (justSaved) {
            Text(
                "Saved. It will sync to your other devices.",
                fontSize = 13.sp,
                color = scheme.onBackground.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MealRow(
    name: String,
    start: String,
    minutes: Int,
    onStartChange: (String) -> Unit,
    onMinutesChange: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TimeField(name, start, onStartChange, Modifier.weight(1f))
        MinutesField("Minutes", minutes, onMinutesChange, Modifier.weight(0.7f))
    }
}
