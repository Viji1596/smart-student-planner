@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.reminders

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.reminders.Notifier
import com.smartstudent.planner.ui.common.MinutesField
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.WhiteCard

@Composable
fun RemindersScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: RemindersViewModel = viewModel(factory = RemindersViewModel.factory(container))
    val prefs by viewModel.prefs.collectAsState()
    val diag by viewModel.diagnostics.collectAsState()
    val scheme = MaterialTheme.colorScheme

    var permissionDenied by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var alarmTestMessage by remember { mutableStateOf<String?>(null) }
    var summaryMessage by remember { mutableStateOf<String?>(null) }

    // Re-read the system state when coming back from the system settings screens.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationsAllowed = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAlarmsAllowed = remember(refresh) { canScheduleExact(context) }
    val batteryUnrestricted = remember(refresh) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionDenied = false
            viewModel.setEnabled(true)
        } else {
            permissionDenied = true
        }
    }

    fun turnOn() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.setEnabled(true)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Reminders", onBack = onBack)

        // What is really going on, so a missing reminder can be explained instead of guessed at.
        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                StatusLine("Reminders are switched on", prefs.enabled, "Turn on \"Remind me\" below.")
                StatusLine("Notifications are allowed", notificationsAllowed, "Blocked: use \"Open notification settings\".")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    StatusLine("Exact timing is allowed", exactAlarmsAllowed, "Reminders may arrive a few minutes late.")
                }
                StatusLine("Battery use is unrestricted", batteryUnrestricted, "The phone may stop reminders to save power.")
                Text(
                    "Last plan check: " + (diag.lastRunMillis?.let {
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
                    } ?: "not yet"),
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                diag.lastError?.let { Text("Problem: $it", fontSize = 13.sp, color = scheme.error) }
                Text(
                    "Alarms set: ${diag.alarmCount}",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                if (diag.upcoming.isEmpty()) {
                    Text(
                        if (prefs.enabled) "No reminders are scheduled right now (nothing planned ahead, or all are past)."
                        else "No reminders are scheduled because they are switched off.",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                } else {
                    Text("Next reminders:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    diag.upcoming.forEach { Text("• $it", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
                }
                OutlinedButton(onClick = { viewModel.recheck() }) { Text("Check again") }
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow(
                    title = "Remind me",
                    subtitle = "A notification before each study session on this phone.",
                    checked = prefs.enabled,
                    onChange = { if (it) turnOn() else viewModel.setEnabled(false) },
                )
                if (permissionDenied || (prefs.enabled && !notificationsAllowed)) {
                    Text(
                        "Notifications are blocked for this app, so reminders can't appear.",
                        color = scheme.error, fontSize = 13.sp,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }) { Text("Open notification settings") }
                }
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Study sessions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

                SwitchRow(
                    "Before it starts", "A reminder ahead of each session.",
                    prefs.startReminder, { viewModel.setStartReminder(it) },
                )
                MinutesField(
                    "Minutes before start (0 = at the start)", prefs.leadMin,
                    { viewModel.setLead(it) }, Modifier.fillMaxWidth(),
                )

                SwitchRow(
                    "Before it ends", "A heads-up shortly before the time is up.",
                    prefs.endingSoon, { viewModel.setEndingSoon(it) },
                )
                MinutesField(
                    "Minutes before end (0 = off)", prefs.endLeadMin,
                    { viewModel.setEndLead(it) }, Modifier.fillMaxWidth(),
                )

                SwitchRow(
                    "When it ends", "\"Time's up\", with Done and Later buttons so you can answer without opening the app.",
                    prefs.atEnd, { viewModel.setAtEnd(it) },
                )
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Other reminders", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The \"minutes before start\" above is also used for these.",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                SwitchRow(
                    "Meals and rest", "Before breakfast, lunch, dinner and your rest block.",
                    prefs.mealsAndRest, { viewModel.setMealsAndRest(it) },
                )
                SwitchRow(
                    "Morning summary", "At your wake-up time: how many sessions are planned.",
                    prefs.morningSummary, { viewModel.setMorningSummary(it) },
                )
                SwitchRow(
                    "Bedtime summary", "At bedtime, every day: how many tasks you finished out of those scheduled.",
                    prefs.daySummary, { viewModel.setDaySummary(it) },
                )
                SwitchRow(
                    "Weekly analysis", "On Sundays at bedtime: your week, your grade and what I notice about how you work.",
                    prefs.weekSummary, { viewModel.setWeekSummary(it) },
                )
                SwitchRow(
                    "Monthly analysis", "On the last day of the month at bedtime: your month in numbers and trends.",
                    prefs.monthSummary, { viewModel.setMonthSummary(it) },
                )
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Making sure they arrive on time", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Missed sessions are noticed even without reminders: the plan moves them to later and " +
                        "tells you where. Reminders are only the notifications.",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmsAllowed) {
                    Text(
                        "Without \"Alarms & reminders\" permission, reminders can arrive a few minutes late.",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("Allow exact timing") }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Text("Exact timing is allowed.", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                }
                Text(
                    "On Samsung and some other phones, the system stops apps in the background. For reliable " +
                        "reminders set this app's battery usage to Unrestricted (Battery → Background usage limits).",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                if (!batteryUnrestricted) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("Don't restrict this app's battery use") }
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Open app settings") }
            }
        }

        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Try it", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = {
                    val shown = Notifier.show(
                        context, 4242, "Test reminder", "If you can see this, reminders can reach you.",
                    )
                    testMessage = if (shown) "Sent. Check your notifications." else "Blocked: notifications are off for this app."
                }) { Text("Send a test notification") }
                testMessage?.let { Text(it, fontSize = 13.sp, color = scheme.onSurfaceVariant) }

                Text(
                    "The button above shows a notification at once. This one sets a real alarm, exactly like your " +
                        "reminders use, for one minute from now. Close the app and wait.",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    val exact = viewModel.scheduleTestAlarm()
                    alarmTestMessage = "Alarm set for 1 minute from now" +
                        if (exact) "." else " (exact timing is off, so it may be a few minutes late)."
                }) { Text("Send a test alarm in 1 minute") }
                alarmTestMessage?.let { Text(it, fontSize = 13.sp, color = scheme.onSurfaceVariant) }

                Text(
                    "To see what the bedtime summary looks like, show today's now. It also appears under Progress → Summaries.",
                    fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    viewModel.showSummaryNow()
                    summaryMessage = "Done. Check your notifications."
                }) { Text("Show today's summary now") }
                summaryMessage?.let { Text(it, fontSize = 13.sp, color = scheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
}

@Composable
private fun StatusLine(label: String, ok: Boolean, hintWhenNotOk: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✓" else "✗", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (ok) scheme.primary else scheme.error)
            Spacer(Modifier.padding(start = 8.dp))
            Text(label, fontSize = 14.sp)
        }
        if (!ok) Text(hintWhenNotOk, fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp))
    }
}
