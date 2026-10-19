package com.smartstudent.planner

import android.content.Context
import androidx.room.Room
import com.smartstudent.planner.data.local.AppDatabase
import com.smartstudent.planner.data.local.MIGRATION_1_2
import com.smartstudent.planner.data.local.MIGRATION_2_3
import com.smartstudent.planner.data.local.MIGRATION_3_4
import com.smartstudent.planner.data.remote.AuthInterceptor
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.TokenAuthenticator
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.data.repo.AuthRepository
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.SettingsRepository
import com.smartstudent.planner.data.repo.SyncManager
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.TimetableRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container (simple enough that a DI framework isn't needed yet). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val tokenStore = TokenStore(appContext)

    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "planner.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun buildClient(builder: OkHttpClient.Builder) = builder
        .connectTimeout(20, TimeUnit.SECONDS)
        // Free hosting sleeps when idle: the server can take about a minute to wake, and then
        // the database a few seconds more.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun buildApi(client: OkHttpClient): PlannerApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PlannerApi::class.java)

    /** No auth headers: used for login and token refresh. */
    private val plainApi: PlannerApi = buildApi(buildClient(OkHttpClient.Builder()))

    /** Adds the access token and refreshes it automatically on 401. */
    private val authedApi: PlannerApi = buildApi(
        buildClient(
            OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenStore))
                .authenticator(TokenAuthenticator(tokenStore, lazyOf(plainApi)))
        )
    )

    val authRepository = AuthRepository(plainApi, tokenStore, database, appContext)
    val taskRepository = TaskRepository(authedApi, database.taskDao(), tokenStore, appContext)
    val timetableRepository = TimetableRepository(authedApi, database.timetableDao(), tokenStore, appContext)
    val settingsRepository = SettingsRepository(authedApi, database.settingsDao(), appContext)

    val habitRepository = HabitRepository(
        authedApi, database.habitDao(), database.habitCompletionDao(), tokenStore, appContext,
    )

    val syncManager = SyncManager(
        tokenStore, taskRepository, timetableRepository, settingsRepository, habitRepository,
    )
}
