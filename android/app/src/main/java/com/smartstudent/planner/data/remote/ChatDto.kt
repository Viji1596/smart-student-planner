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
    /** Put off until this day (it will not be planned before it). */
    val deferUntil: String? = null,
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
data class ChatSpanDto(val label: String, val start: String, val end: String, val kind: String = "")

@Serializable
data class ChatDayDto(
    val sessions: List<ChatSessionDto>,
    val freeMinutes: Int,
    val busy: List<ChatSpanDto>,
    val freeBlocks: List<ChatSpanDto>,
)

@Serializable
data class ChatHabitReportDto(val title: String, val done: Int, val expected: Int, val streak: Int)

/** The last week in numbers, so the assistant can talk about how things are going. */
@Serializable
data class ChatReportDto(
    val days: Int,
    val trackedDays: Int,
    val minutesDone: Int,
    val minutesDonePrevious: Int? = null,
    val sessionsDone: Int,
    val sessionsMissed: Int,
    val completionPercent: Int? = null,
    val tasksCompleted: Int,
    val tasksOpen: Int,
    val overdue: Int,
    val dueSoon: Int,
    val onTimePercent: Int? = null,
    val bestDay: String? = null,
    val mostMissed: String? = null,
    val habits: List<ChatHabitReportDto> = emptyList(),
)

/** The task the student was just working with: "it" in "move it to 9pm" means this one. */
@Serializable
data class ChatFocusDto(val taskId: String, val title: String)

/** A snapshot of what the assistant needs to know. Built fresh for every message; nothing is stored on the server. */
@Serializable
data class ChatContextDto(
    val today: String,
    val nowMin: Int,
    val tasks: List<ChatTaskDto>,
    val habits: List<ChatHabitDto>,
    val plan: ChatDayDto,
    val tomorrow: ChatDayDto,
    val report: ChatReportDto? = null,
    val focus: ChatFocusDto? = null,
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
    /** Which backend version answered. Empty from an older backend, which the chat then points out. */
    val version: String = "",
)
