@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smartstudent.planner.ui.timetable

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.BuildConfig
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.domain.ImportedClass
import com.smartstudent.planner.domain.TimetableImport
import com.smartstudent.planner.ui.common.ScreenTitle
import com.smartstudent.planner.ui.common.WhiteCard
import com.smartstudent.planner.ui.common.dayFullName

private val FILE_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/pdf",
    "image/*",
    "application/vnd.ms-excel",
    "application/octet-stream",
)

@Composable
fun TimetableImportScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TimetableImportViewModel = viewModel(factory = TimetableImportViewModel.factory(container))
    val ui by viewModel.ui.collectAsState()
    val check by viewModel.check.collectAsState()
    val scheme = MaterialTheme.colorScheme

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readFile(context.applicationContext, uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Import timetable", onBack = onBack)

        when (val state = ui) {
            ImportUi.Idle -> {
                WhiteCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Choose your timetable", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The best way is the original file: Excel (.xlsx), CSV, or the PDF itself. Those are read " +
                                "exactly. Photos and scanned PDFs are read on your phone (free), which is less exact. " +
                                "You'll see what was found and can fix it before anything is saved.",
                            fontSize = 14.sp, color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "Use the PDF rather than a screenshot of it: a screenshot of a whole page is too small " +
                                "to read. For a photo, take it straight on, in good light, with just the table in the " +
                                "frame. Handwriting usually can't be read.",
                            fontSize = 13.sp, color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "Old Excel files (.xls): open them and use Save As > Excel Workbook (.xlsx) first.",
                            fontSize = 13.sp, color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "Server: ${BuildConfig.BASE_URL}",
                            fontSize = 12.sp, color = scheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { viewModel.checkConnection() }) { Text("Check connection") }
                        check?.let { Text(it, fontSize = 13.sp) }
                        Button(
                            onClick = { picker.launch(FILE_TYPES) },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = scheme.inverseSurface, contentColor = scheme.inverseOnSurface,
                            ),
                        ) { Text("Choose a file", fontSize = 16.sp) }
                    }
                }
            }

            is ImportUi.Reading -> WhiteCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator()
                    Text(state.message, fontWeight = FontWeight.Medium)
                    Text(
                        "Nothing is saved until you check it.",
                        fontSize = 13.sp, color = scheme.onSurfaceVariant,
                    )
                }
            }

            is ImportUi.Failed -> WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't import", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.error)
                    Text(state.message, fontSize = 15.sp)
                    state.details?.let {
                        Text("Details: $it", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                    Text("Server: ${BuildConfig.BASE_URL}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    OutlinedButton(onClick = { viewModel.reset() }) { Text("Try another file") }
                }
            }

            is ImportUi.Review -> ReviewContent(state, viewModel)

            is ImportUi.Done -> WhiteCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (state.added == 1) "Added 1 class" else "Added ${state.added} classes",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text("Your plan now works around them. Check the Home tab.", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                    Button(
                        onClick = onBack,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.inverseSurface, contentColor = scheme.inverseOnSurface,
                        ),
                    ) { Text("Back to timetable", fontSize = 16.sp) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReviewContent(state: ImportUi.Review, viewModel: TimetableImportViewModel) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Draft?>(null) }

    val chosen = state.drafts.filter { it.included }
    val overlaps = TimetableImport.overlapCount(chosen.map { it.cls })

    WhiteCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Found ${state.drafts.size} classes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (state.method) {
                    "ai" -> "Read with AI. AI can make mistakes: please check every line before saving."
                    "ocr" -> "Read from the picture with text recognition on your phone. It can misread " +
                        "times and names: please check every line before saving."
                    else -> "Read from your spreadsheet. Check that it looks right."
                },
                fontSize = 14.sp, color = scheme.onSurfaceVariant,
            )
            state.warnings.forEach { Text("• $it", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
            if (overlaps > 0) {
                Text(
                    "$overlaps ${if (overlaps == 1) "pair of classes overlaps" else "pairs of classes overlap"}. " +
                        "That often means a line was misread: tap it to fix, or untick it.",
                    fontSize = 13.sp, color = scheme.error,
                )
            }
        }
    }

    if (state.existingCount > 0) {
        WhiteCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You already have ${state.existingCount} classes", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.replace, onClick = { viewModel.setReplace(false) },
                        label = { Text("Add to them") },
                    )
                    FilterChip(
                        selected = state.replace, onClick = { viewModel.setReplace(true) },
                        label = { Text("Replace them") },
                    )
                }
                Text(
                    if (state.replace) "Your current ${state.existingCount} classes will be removed."
                    else "Classes you already have are skipped, not doubled.",
                    fontSize = 13.sp, color = if (state.replace) scheme.error else scheme.onSurfaceVariant,
                )
            }
        }
    }

    val byDay = state.drafts.groupBy { it.cls.day }.toSortedMap()
    byDay.forEach { (day, drafts) ->
        Text(
            dayFullName(day), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = scheme.onBackground, modifier = Modifier.padding(top = 4.dp),
        )
        drafts.sortedBy { it.cls.start }.forEach { draft ->
            WhiteCard(Modifier.fillMaxWidth(), onClick = { editing = draft }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.included, onCheckedChange = { viewModel.setIncluded(draft.id, it) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            draft.cls.title, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            color = if (draft.included) scheme.onSurface else scheme.onSurfaceVariant,
                        )
                        Text("${draft.cls.start} – ${draft.cls.end}", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    Button(
        enabled = chosen.isNotEmpty(),
        onClick = { viewModel.save() },
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.inverseSurface, contentColor = scheme.inverseOnSurface,
        ),
    ) { Text("Save ${chosen.size} ${if (chosen.size == 1) "class" else "classes"}", fontSize = 16.sp) }
    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }

    editing?.let { draft ->
        // Reuse the timetable's edit dialog. The day can't be changed here (it is part of the line).
        EntryDialog(
            initial = TimetableEntryEntity(
                id = "draft-${draft.id}",
                dayOfWeek = draft.cls.day,
                title = draft.cls.title,
                startTime = draft.cls.start,
                endTime = draft.cls.end,
                deleted = false,
                dirty = false,
                updatedAtLocal = 0L,
            ),
            defaultDay = draft.cls.day,
            onDismiss = { editing = null },
            onSave = { title, start, end, _ ->
                viewModel.update(draft.id, ImportedClass(draft.cls.day, title.trim(), start, end))
                editing = null
            },
        )
    }
}
