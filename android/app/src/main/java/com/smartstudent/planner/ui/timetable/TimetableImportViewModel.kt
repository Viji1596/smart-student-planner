package com.smartstudent.planner.ui.timetable

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.ocr.OcrReader
import com.smartstudent.planner.data.remote.ApiMessage
import com.smartstudent.planner.data.remote.ImportResponse
import com.smartstudent.planner.data.repo.TimetableRepository
import com.smartstudent.planner.domain.ImportedClass
import com.smartstudent.planner.domain.TimetableImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A class found in the file, which the user can keep, change or leave out before saving. */
data class Draft(val id: Int, val cls: ImportedClass, val included: Boolean = true)

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Reading(val message: String) : ImportUi
    data class Failed(val message: String, val details: String? = null) : ImportUi
    data class Review(
        val drafts: List<Draft>,
        val warnings: List<String>,
        /** "grid" (spreadsheet), "ocr" (text recognised on the phone) or "ai". */
        val method: String,
        val existingCount: Int,
        val replace: Boolean,
    ) : ImportUi
    data class Done(val added: Int) : ImportUi
}

class TimetableImportViewModel(
    private val repo: TimetableRepository,
    private val ocr: OcrReader,
) : ViewModel() {

    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val ui: StateFlow<ImportUi> = _ui

    private val json = Json { ignoreUnknownKeys = true }

    /** The result of "Check connection", shown under the button. */
    private val _check = MutableStateFlow<String?>(null)
    val check: StateFlow<String?> = _check

    /** Asks the server whether it has the import feature, and says what it answered. */
    fun checkConnection() {
        _check.value = "Checking…"
        viewModelScope.launch {
            _check.value = try {
                val ai = repo.aiReaderAvailable()
                "Connected. The server has the import feature." +
                    if (ai) " The AI reader is on." else " The AI reader is off (photos are read on your phone)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when (e.code()) {
                    404 -> "The server answered 404 (not found): it does NOT have the import feature. " +
                        "The backend on that address needs updating, or the app is using the wrong server."
                    401 -> "The server answered 401: you're signed out. Sign out and in again."
                    else -> "The server answered an error (HTTP ${e.code()})."
                }
            } catch (e: IOException) {
                "Can't reach the server: ${e.message ?: e.javaClass.simpleName}. Check your connection."
            } catch (e: Exception) {
                "Check failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun reset() {
        _ui.value = ImportUi.Idle
    }

    private enum class Kind { SPREADSHEET, IMAGE, PDF }

    /** Reads the chosen file and shows what was found for review. Nothing is saved yet. */
    fun readFile(context: Context, uri: Uri) {
        _ui.value = ImportUi.Reading("Reading your timetable…")
        viewModelScope.launch {
            try {
                val resolver = context.contentResolver
                val name = displayName(resolver, uri)
                val response = when (kindOf(resolver.getType(uri), name)) {
                    Kind.SPREADSHEET -> uploadFile(resolver, uri, name)
                    Kind.IMAGE -> readPhoto(uri)
                    Kind.PDF -> readPdf(resolver, uri, name)
                }
                showReview(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooLarge) {
                _ui.value = ImportUi.Failed("That file is larger than 8 MB. Try a smaller one.")
            } catch (e: NothingFound) {
                _ui.value = ImportUi.Failed(e.message ?: "No classes could be found.")
            } catch (e: HttpException) {
                _ui.value = ImportUi.Failed(serverMessage(e), details(e))
            } catch (e: IOException) {
                _ui.value = ImportUi.Failed(
                    "Couldn't read the file or reach the server. Check your connection, and that the file is saved on the phone.",
                    "${e.javaClass.simpleName}: ${e.message}",
                )
            } catch (e: Exception) {
                _ui.value = ImportUi.Failed("That file couldn't be read.", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private class NothingFound(message: String) : Exception(message)

    private fun kindOf(mime: String?, name: String): Kind {
        val lower = name.lowercase()
        return when {
            mime?.startsWith("image/") == true || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") -> Kind.IMAGE
            mime == "application/pdf" || lower.endsWith(".pdf") -> Kind.PDF
            else -> Kind.SPREADSHEET
        }
    }

    /** Excel and CSV go to the server, which reads them. */
    private suspend fun uploadFile(resolver: ContentResolver, uri: Uri, name: String): ImportResponse {
        val bytes = withContext(Dispatchers.IO) { loadBytes(resolver, uri) }
        return repo.readFile(name, encode(bytes))
    }

    /**
     * PDFs. Most have real text inside (exported from Excel, Sheets, Word), which the server reads exactly:
     * free, and no mistakes. Only a scanned PDF (a picture of a page), or one whose text isn't a timetable
     * grid, is read as a picture on the phone instead.
     */
    private suspend fun readPdf(resolver: ContentResolver, uri: Uri, name: String): ImportResponse {
        try {
            val bytes = withContext(Dispatchers.IO) { loadBytes(resolver, uri) }
            return repo.readFile(name, encode(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooLarge) {
            // too big to upload: the phone can still read the pages itself
        } catch (e: HttpException) {
            val code = errorCode(e)
            if (code != "scanned" && code != "layout") throw e
        }

        _ui.value = ImportUi.Reading("Reading the pages as pictures on your phone…")
        val pages = ocr.readPdf(uri)
        if (pages.all { it.isEmpty() }) {
            throw NothingFound("No text could be found in this PDF.")
        }
        return repo.readPages(pages)
    }

    /**
     * Photos. If the server has the AI reader, it goes first (it copes best with messy pictures); otherwise,
     * or if it can't read the picture, the phone recognises the text itself (free, works offline) and the
     * server builds the table from the words.
     */
    private suspend fun readPhoto(uri: Uri): ImportResponse {
        val aiAvailable = runCatching { repo.aiReaderAvailable() }.getOrDefault(false)
        if (aiAvailable) {
            _ui.value = ImportUi.Reading("Reading your timetable with AI… this can take up to a minute.")
            try {
                return repo.readFile("timetable.jpg", encode(ocr.jpegForUpload(uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // fall through to reading the text on the phone
            }
        }

        _ui.value = ImportUi.Reading("Reading the text on your phone…")
        val pages = ocr.readImage(uri)
        if (pages.all { it.isEmpty() }) {
            throw NothingFound("No text could be found in this picture. Try a sharper photo, taken straight on, of just the table.")
        }
        return repo.readPages(pages)
    }

    private fun showReview(response: ImportResponse) {
        val drafts = response.entries.mapIndexed { i, e ->
            Draft(i, ImportedClass(e.dayOfWeek, e.title, e.startTime, e.endTime))
        }.filter { TimetableImport.isValid(it.cls) }

        viewModelScope.launch {
            _ui.value = if (drafts.isEmpty()) {
                ImportUi.Failed("No classes could be found in this file.")
            } else {
                ImportUi.Review(
                    drafts = drafts,
                    warnings = response.warnings,
                    method = response.method,
                    existingCount = repo.currentClasses().size,
                    replace = false,
                )
            }
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private class TooLarge : Exception()

    private fun displayName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "timetable"

    private fun loadBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw TooLarge()
                out.write(chunk, 0, n)
            }
        } ?: throw IOException("Can't open the file")
        return out.toByteArray()
    }

    /** What the server answered and to which request, to make a failure easy to explain. */
    private fun details(e: HttpException): String {
        val request = e.response()?.raw()?.request
        val where = request?.let { " for ${it.method} ${it.url.encodedPath}" } ?: ""
        return "The server answered HTTP ${e.code()}$where"
    }

    private fun errorCode(e: HttpException): String? {
        val text = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        return text?.let { runCatching { json.decodeFromString<ApiMessage>(it).code }.getOrNull() }
    }

    private fun serverMessage(e: HttpException): String {
        val text = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val message = text?.let { runCatching { json.decodeFromString<ApiMessage>(it).message }.getOrNull() }
        return message ?: "The server couldn't read this file (error ${e.code()})."
    }

    private fun review(change: (ImportUi.Review) -> ImportUi.Review) {
        val current = _ui.value
        if (current is ImportUi.Review) _ui.value = change(current)
    }

    fun setIncluded(id: Int, included: Boolean) = review { r ->
        r.copy(drafts = r.drafts.map { if (it.id == id) it.copy(included = included) else it })
    }

    fun update(id: Int, cls: ImportedClass) = review { r ->
        r.copy(drafts = r.drafts.map { if (it.id == id) it.copy(cls = cls) else it })
    }

    fun setReplace(replace: Boolean) = review { it.copy(replace = replace) }

    fun save() {
        val current = _ui.value as? ImportUi.Review ?: return
        val classes = current.drafts.filter { it.included }.map { it.cls }
        viewModelScope.launch {
            val added = repo.importClasses(classes, current.replace)
            _ui.value = ImportUi.Done(added)
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TimetableImportViewModel(container.timetableRepository, container.ocrReader) }
        }
    }
}
