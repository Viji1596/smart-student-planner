package com.smartstudent.planner.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserDto(val id: String, val email: String, val name: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
)

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val priority: String,
    val estimatedMinutes: Int,
    val spentMinutes: Int = 0,
    val dueDate: String? = null,
    val completed: Boolean = false,
    val deleted: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class TaskUpsertRequest(
    val title: String,
    val priority: String,
    val estimatedMinutes: Int,
    val spentMinutes: Int = 0,
    val dueDate: String? = null,
    val completed: Boolean = false,
)

@Serializable
data class TasksResponse(val tasks: List<TaskDto>, val serverTime: String)

@Serializable
data class DeleteResponse(val deleted: Int = 0)

@Serializable
data class TimetableEntryDto(
    val id: String,
    val dayOfWeek: Int,
    val title: String,
    val startTime: String,
    val endTime: String,
    val deleted: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class TimetableUpsertRequest(
    val dayOfWeek: Int,
    val title: String,
    val startTime: String,
    val endTime: String,
)

@Serializable
data class TimetableResponse(val entries: List<TimetableEntryDto>, val serverTime: String)

@Serializable
data class SettingsDto(
    val wakeTime: String,
    val sleepTime: String,
    val morningRoutineMinutes: Int,
    val breakfastStart: String,
    val breakfastMinutes: Int,
    val lunchStart: String,
    val lunchMinutes: Int,
    val dinnerStart: String,
    val dinnerMinutes: Int,
    val timezone: String,
    val commuteToMinutes: Int = 0,
    val commuteFromMinutes: Int = 0,
    val arriveEarlyMinutes: Int = 10,
    val restStart: String = "16:00",
    val restMinutes: Int = 0,
    val breakMinutes: Int = 10,
    val longBreakAfterMinutes: Int = 120,
    val longBreakMinutes: Int = 20,
    val updatedAt: String? = null,
)

@Serializable
data class SettingsResponse(val settings: SettingsDto? = null, val serverTime: String)

@Serializable
data class HabitDto(
    val id: String,
    val title: String,
    val minutes: Int,
    val priority: String,
    val kind: String,
    val days: String = "",
    val timesPerWeek: Int = 0,
    val deleted: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class HabitUpsertRequest(
    val title: String,
    val minutes: Int,
    val priority: String,
    val kind: String,
    val days: String = "",
    val timesPerWeek: Int = 0,
)

@Serializable
data class HabitsResponse(val habits: List<HabitDto>, val serverTime: String)

@Serializable
data class CompletionDto(
    val id: String,
    val habitId: String,
    val date: String,
    val deleted: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class CompletionUpsertRequest(val habitId: String, val date: String)

@Serializable
data class CompletionsResponse(val completions: List<CompletionDto>, val serverTime: String)

@Serializable
data class ImportRequest(val filename: String, val data: String)

@Serializable
data class ImportedEntryDto(
    val dayOfWeek: Int,
    val title: String,
    val startTime: String,
    val endTime: String,
)

@Serializable
data class ImportResponse(
    val entries: List<ImportedEntryDto>,
    val warnings: List<String> = emptyList(),
    val method: String = "",
)

/** The error body the server sends: {"message": "..."} */
@Serializable
data class ApiMessage(val message: String? = null, val code: String? = null)

/** A word the phone recognised, with its position in the picture. */
@Serializable
data class BoxDto(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

@Serializable
data class PageDto(val boxes: List<BoxDto>)

/** Everything the phone recognised in a photo or a scanned PDF: one entry per page. */
@Serializable
data class ImportPagesRequest(val pages: List<PageDto>)

@Serializable
data class ImportStatus(val ai: Boolean = false)
