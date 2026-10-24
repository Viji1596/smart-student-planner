package com.smartstudent.planner.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.ui.common.RoundIconButton

private val STARTERS = listOf("What's my plan today?", "How much free time do I have tomorrow?", "Re-plan my day", "Help")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val messages by viewModel.messages.collectAsState()
    val typing by viewModel.typing.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val version by viewModel.serverVersion.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme

    // keep the newest message in view
    LaunchedEffect(messages.size, typing) {
        val count = messages.size + if (typing) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        if (input.isNotBlank() && !typing) {
            viewModel.send(input)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Default.ArrowBack, "Back", onBack)
            Spacer(Modifier.padding(start = 12.dp))
            Column {
                Text("Assistant", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                Text(
                    listOfNotNull(
                        when (mode) {
                            "ai" -> "AI mode"
                            "rules" -> "Basic mode (no AI)"
                            else -> null
                        },
                        when {
                            version == null -> null
                            version!!.isBlank() -> "the server is out of date: update the backend"
                            else -> "server $version"
                        },
                    ).joinToString(" · ").ifEmpty { "Changes happen on your phone. Deleting always asks first." },
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                Bubble(
                    message,
                    onConfirm = { viewModel.confirm(message.id) },
                    onCancel = { viewModel.cancel(message.id) },
                    onChoose = viewModel::send,
                )
            }
            if (typing) {
                item("typing") {
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(scheme.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Thinking…", color = scheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                }
            }
        }

        if (messages.size <= 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                STARTERS.forEach { SuggestionChip(onClick = { viewModel.send(it) }, label = { Text(it) }) }
                SuggestionChip(onClick = { input = "add task " }, label = { Text("Add a task") })
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or tell me something…") },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Spacer(Modifier.padding(start = 10.dp))
            RoundIconButton(Icons.Default.Send, "Send", onClick = { submit() })
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, onConfirm: () -> Unit, onCancel: () -> Unit, onChoose: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (message.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(scheme.primary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(message.text, color = scheme.onPrimary, fontSize = 16.sp) }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(if (message.isError) scheme.errorContainer else scheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                message.text,
                color = if (message.isError) scheme.onErrorContainer else scheme.onSurface,
                fontSize = 16.sp,
            )
        }
        if (message.pending != null && !message.resolved) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConfirm) { Text("Yes, do it") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        if (message.options.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                message.options.forEach { option -> SuggestionChip(onClick = { onChoose(option.text) }, label = { Text(option.label) }) }
            }
        }
    }
}
