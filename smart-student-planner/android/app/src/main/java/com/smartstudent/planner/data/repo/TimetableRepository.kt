package com.smartstudent.planner.data.repo

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.TimetableDao
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.ocr.OcrBox
import com.smartstudent.planner.data.remote.BoxDto
import com.smartstudent.planner.data.remote.ImportPagesRequest
import com.smartstudent.planner.data.remote.PageDto
import com.smartstudent.planner.data.remote.ImportRequest
import com.smartstudent.planner.data.remote.ImportResponse
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.TimetableEntryDto
import com.smartstudent.planner.data.remote.TimetableUpsertRequest
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.domain.ImportedClass
import com.smartstudent.planner.domain.TimetableImport
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.util.UUID

class TimetableRepository(
    private val api: PlannerApi,
    private val dao: TimetableDao,
    private val tokenStore: TokenStore,
    private val appContext: Context,
) {
    val entries: Flow<List<TimetableEntryEntity>> = dao.observeActive()

    /** One entry is created per selected weekday (1 = Monday ... 7 = Sunday). */
    suspend fun add(days: Set<Int>, title: String, startTime: String, endTime: String) {
        val now = System.currentTimeMillis()
        dao.upsertAll(
            days.sorted().map { day ->
                TimetableEntryEntity(
                    id = UUID.randomUUID().toString(),
                    dayOfWeek = day,
                    title = title.trim(),
                    startTime = startTime,
                    endTime = endTime,
                    deleted = false,
                    dirty = true,
                    updatedAtLocal = now,
                )
            }
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun update(entry: TimetableEntryEntity, title: String, startTime: String, endTime: String) {
        dao.upsert(
            entry.copy(
                title = title.trim(),
                startTime = startTime,
                endTime = endTime,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    /** Sends a file to the server and gets back a preview of the classes it contains. Nothing is saved. */
    suspend fun readFile(filename: String, base64: String): ImportResponse =
        api.importTimetable(ImportRequest(filename, base64))

    /** Sends the words the phone recognised in a photo or scanned PDF; the server builds the table from them. */
    suspend fun readPages(pages: List<List<OcrBox>>): ImportResponse =
        api.importPages(
            ImportPagesRequest(
                pages.map { page ->
                    PageDto(
                        page.filter { it.text.isNotBlank() }.take(4000).map {
                            BoxDto(it.text.take(200), it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                        }
                    )
                }
            )
        )

    /** Is the server's AI reader set up? (The app only uses it for photos and PDFs.) */
    suspend fun aiReaderAvailable(): Boolean = api.importStatus().ai

    suspend fun currentClasses(): List<ImportedClass> =
        entries.first().map { ImportedClass(it.dayOfWeek, it.title, it.startTime, it.endTime) }

    /**
     * Saves imported classes. [replace] first removes the whole current timetable; otherwise only
     * classes that aren't already there are added. Returns how many classes were added.
     */
    suspend fun importClasses(classes: List<ImportedClass>, replace: Boolean): Int {
        val now = System.currentTimeMillis()
        val current = entries.first()
        if (replace) {
            current.forEach { dao.upsert(it.copy(deleted = true, dirty = true, updatedAtLocal = now)) }
        }
        val existing = if (replace) emptyList()
        else current.map { ImportedClass(it.dayOfWeek, it.title, it.startTime, it.endTime) }
        val toAdd = TimetableImport.newClasses(existing, classes.filter(TimetableImport::isValid))
        dao.upsertAll(
            toAdd.map {
                TimetableEntryEntity(
                    id = UUID.randomUUID().toString(),
                    dayOfWeek = it.day,
                    title = it.title.trim().take(100),
                    startTime = it.start,
                    endTime = it.end,
                    deleted = false,
                    dirty = true,
                    updatedAtLocal = now,
                )
            }
        )
        SyncScheduler.syncSoon(appContext)
        return toAdd.size
    }

    suspend fun delete(entry: TimetableEntryEntity) {
        dao.upsert(entry.copy(deleted = true, dirty = true, updatedAtLocal = System.currentTimeMillis()))
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun push() {
        for (entry in dao.getDirty()) {
            try {
                if (entry.deleted) {
                    api.deleteTimetableEntry(entry.id)
                    dao.removeDeletedIfUnchanged(entry.id, entry.updatedAtLocal)
                } else {
                    api.putTimetableEntry(
                        entry.id,
                        TimetableUpsertRequest(entry.dayOfWeek, entry.title, entry.startTime, entry.endTime),
                    )
                    dao.markCleanIfUnchanged(entry.id, entry.updatedAtLocal)
                }
            } catch (e: HttpException) {
                if (isRetryable(e)) throw e
                Log.w(TAG, "Server rejected timetable entry ${entry.id} (${e.code()}); skipping")
            }
        }
    }

    suspend fun pull() {
        val response = api.getTimetable(tokenStore.lastSyncTimetable())
        for (remote in response.entries) {
            val local = dao.get(remote.id)
            if (local?.dirty == true) continue
            if (remote.deleted) dao.remove(remote.id) else dao.upsert(remote.toEntity())
        }
        tokenStore.setLastSyncTimetable(response.serverTime)
    }

    private fun TimetableEntryDto.toEntity() = TimetableEntryEntity(
        id = id,
        dayOfWeek = dayOfWeek,
        title = title,
        startTime = startTime,
        endTime = endTime,
        deleted = false,
        dirty = false,
        updatedAtLocal = 0L,
    )

    private companion object {
        const val TAG = "TimetableRepository"
    }
}
