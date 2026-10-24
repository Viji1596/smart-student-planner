package com.smartstudent.planner.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.chat.ChatService
import com.smartstudent.planner.data.remote.ChatActionDto
import com.smartstudent.planner.data.remote.ChatContextDto
import com.smartstudent.planner.data.remote.ChatMessageDto
import com.smartstudent.planner.data.remote.ClarifyOptionDto
import com.smartstudent.planner.domain.ReportQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException

/** Something that needs the student's "yes" before it happens (deleting). */
data class PendingAction(val action: ChatActionDto, val context: ChatContextDto)

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Tap-to-answer choices, when the assistant asked "which one?". */
    val options: List<ClarifyOptionDto> = emptyList(),
    val pending: PendingAction? = null,
    /** True once a confirm card has been answered. */
    val resolved: Boolean = false,
    val isError: Boolean = false,
)

class ChatViewModel(private val service: ChatService) : ViewModel() {
    private var nextId = 1L

    private val _messages = MutableStateFlow(listOf(ChatMessage(nextId++, false, GREETING)))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** "ai" or "rules": which brain answered last. Shown under the title so it is clear what to expect. */
    private val _mode = MutableStateFlow<String?>(null)
    val mode: StateFlow<String?> = _mode.asStateFlow()

    /** The backend version that answered last: null before the first answer, "" from an old backend that doesn't say. */
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val _typing = MutableStateFlow(false)
    val typing: StateFlow<Boolean> = _typing.asStateFlow()

    private fun add(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    private fun say(text: String, options: List<ClarifyOptionDto> = emptyList(), error: Boolean = false) =
        add(ChatMessage(nextId++, false, text, options = options, isError = error))

    fun send(text: String) {
        val clean = text.trim().take(1000)
        if (clean.isEmpty() || _typing.value) return
        // choices from an earlier question are no longer needed
        _messages.value = _messages.value.map { if (it.options.isNotEmpty()) it.copy(options = emptyList()) else it }
        add(ChatMessage(nextId++, true, clean))
        _typing.value = true

        viewModelScope.launch {
            try {
                val context = service.buildContext()
                val history = _messages.value
                    .filter { it.pending == null && !it.isError }
                    .takeLast(12)
                    .map { ChatMessageDto(if (it.fromUser) "user" else "assistant", it.text) }
                val response = try {
                    service.ask(history, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the report is built from data on this phone, so it doesn't need the server
                    if (ReportQuestion.matches(clean)) {
                        showReport(clean, context, "I couldn't reach the server, so I read this on your phone.")
                        return@launch
                    }
                    throw e
                }
                _mode.value = response.source.takeIf { it.isNotBlank() }
                _serverVersion.value = response.version

                // the server didn't understand a question about how you're doing: the phone still can
                if (response.actions.isEmpty() && response.clarify == null && ReportQuestion.matches(clean)) {
                    showReport(clean, context, null)
                    return@launch
                }

                val results = mutableListOf<String>()
                val needConfirm = mutableListOf<ChatActionDto>()
                for (action in response.actions.take(5)) {
                    if (service.needsConfirmation(action, context)) needConfirm += action
                    else service.execute(action, context).takeIf { it.isNotBlank() }?.let { results += it }
                }

                val text = (listOfNotNull(response.reply.takeIf { it.isNotBlank() }, response.notice) + results).joinToString("\n")
                if (text.isNotBlank() || needConfirm.isEmpty()) {
                    say(text.ifBlank { "Okay." }, options = response.clarify?.options.orEmpty())
                }
                for (action in needConfirm) {
                    add(ChatMessage(nextId++, false, service.describe(action, context), pending = PendingAction(action, context)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(friendly(e), error = true)
            } finally {
                _typing.value = false
            }
        }
    }

    private suspend fun showReport(text: String, context: ChatContextDto, note: String?) {
        val args = JsonObject(mapOf("days" to JsonPrimitive(ReportQuestion.days(text))))
        val report = service.execute(ChatActionDto("show_report", args), context)
        say(listOfNotNull(note, report).joinToString("\n"))
    }

    fun confirm(messageId: Long) {
        val message = _messages.value.firstOrNull { it.id == messageId }
        val pending = message?.pending ?: return
        if (message.resolved) return
        markResolved(messageId)
        viewModelScope.launch {
            val result = service.execute(pending.action, pending.context)
            say(result.ifBlank { "Done." })
        }
    }

    fun cancel(messageId: Long) {
        if (_messages.value.firstOrNull { it.id == messageId }?.resolved != false) return
        markResolved(messageId)
        say("Okay, I won't do that.")
    }

    private fun markResolved(id: Long) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(resolved = true) else it }
    }

    private fun friendly(e: Exception): String = when (e) {
        is HttpException -> when (e.code()) {
            401 -> "You're signed out. Sign in again and try once more."
            404 -> "The server doesn't have the assistant yet. The backend needs the latest update."
            429 -> "That's a lot of messages in a short time. Try again in a little while."
            400 -> "I couldn't understand that message. Try putting it another way."
            else -> "The server had a problem (HTTP ${e.code()}). Try again in a moment."
        }
        is IOException -> "I can't reach the server right now. Check your connection (the first message can take up to a minute while the server wakes up)."
        else -> "Something went wrong. Try again."
    }

    companion object {
        private const val GREETING =
            "Hi! I'm your planner assistant. Ask about your plan, tell me what to add or change, ask how you're doing, " +
                "or just tell me if the day feels like too much. For example: “move physics to 6pm”, " +
                "“swap maths and reading”, “how am I doing this week?”."

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(container.chatService) }
        }
    }
}
