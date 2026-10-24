package com.smartstudent.planner.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface PlannerApi {
    @POST("auth/google")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @GET("tasks")
    suspend fun getTasks(@Query("since") since: String?): TasksResponse

    @PUT("tasks/{id}")
    suspend fun putTask(@Path("id") id: String, @Body body: TaskUpsertRequest): TaskDto

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): DeleteResponse

    @GET("timetable")
    suspend fun getTimetable(@Query("since") since: String?): TimetableResponse

    @PUT("timetable/{id}")
    suspend fun putTimetableEntry(@Path("id") id: String, @Body body: TimetableUpsertRequest): TimetableEntryDto

    @DELETE("timetable/{id}")
    suspend fun deleteTimetableEntry(@Path("id") id: String): DeleteResponse

    @GET("settings")
    suspend fun getSettings(): SettingsResponse

    @PUT("settings")
    suspend fun putSettings(@Body body: SettingsDto): SettingsDto

    @GET("habits")
    suspend fun getHabits(@Query("since") since: String?): HabitsResponse

    @PUT("habits/{id}")
    suspend fun putHabit(@Path("id") id: String, @Body body: HabitUpsertRequest): HabitDto

    @DELETE("habits/{id}")
    suspend fun deleteHabit(@Path("id") id: String): DeleteResponse

    @GET("habit-completions")
    suspend fun getCompletions(@Query("since") since: String?): CompletionsResponse

    @PUT("habit-completions/{id}")
    suspend fun putCompletion(@Path("id") id: String, @Body body: CompletionUpsertRequest): CompletionDto

    @DELETE("habit-completions/{id}")
    suspend fun deleteCompletion(@Path("id") id: String): DeleteResponse

    @POST("timetable/import")
    suspend fun importTimetable(@Body body: ImportRequest): ImportResponse

    @POST("timetable/import")
    suspend fun importPages(@Body body: ImportPagesRequest): ImportResponse

    @GET("timetable/import/status")
    suspend fun importStatus(): ImportStatus

    @POST("chat")
    suspend fun chat(@Body body: ChatRequest): ChatResponse
}
