package com.smartstudent.planner.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One line of the conversation, sent to the server so it understands follow-ups ("friday" after "which day?"). */
@Serializable
data class ChatMessageDto(val role: String, val text: String)

@Serializable
data class ChatTaskDto(
    val id: String,
    val title: String,
    val minutes: Int,
    val spent: Int,
    val priority: String,
    val due: String? = null,
    val completed: Boolean,
)

@Serializable
data class ChatHabitDto(val id: String, val title: String, val minutes: Int, val kind: String, val doneToday: Boolean)

@Serializable
data class ChatSessionDto(
    val id: String,
    val title: String,
    val kind: String,
    val refId: String,
    val start: String,
    val end: String,
    val status: String,
)

/** A stretch of a day with a name: a class, a meal, a block of free time. */
@Serializable
data class ChatSpanDto(val label: String, val start: String, val end: String)

@Serializable
data class ChatDayDto(
    val sessions: List<ChatSessionDto>,
    val freeMinutes: Int,
    val busy: List<ChatSpanDto>,
    val freeBlocks: List<ChatSpanDto>,
)

/** A snapshot of what the assistant needs to know. Built fresh for every message; nothing is stored on the server. */
@Serializable
data class ChatContextDto(
    val today: String,
    val nowMin: Int,
    val tasks: List<ChatTaskDto>,
    val habits: List<ChatHabitDto>,
    val plan: ChatDayDto,
    val tomorrow: ChatDayDto,
)

@Serializable
data class ChatRequest(val messages: List<ChatMessageDto>, val context: ChatContextDto)

/** Something the assistant wants done. The app checks it again and carries it out on the phone. */
@Serializable
data class ChatActionDto(val type: String, val args: JsonObject = JsonObject(emptyMap()), val confirm: Boolean = false)

@Serializable
data class ClarifyOptionDto(val label: String, val text: String)

@Serializable
data class ClarifyDto(val question: String, val options: List<ClarifyOptionDto> = emptyList())

@Serializable
data class ChatResponse(
    val reply: String = "",
    val actions: List<ChatActionDto> = emptyList(),
    val clarify: ClarifyDto? = null,
    val source: String = "",
    val notice: String? = null,
)
