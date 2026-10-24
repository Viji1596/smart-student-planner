package com.smartstudent.planner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** A recognised word and where it is in the picture (pixels). */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Reads the words (and where they are) out of photos and scanned PDFs on the phone: free, no account.
 * The text recogniser is Google ML Kit. The server puts the words back into the rows and columns of a
 * timetable, using the same reader for every kind of file.
 */
class OcrReader(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The words in a photo (one "page"). */
    suspend fun readImage(uri: Uri): List<List<OcrBox>> {
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri, OCR_MAX_SIDE) }
        return try {
            listOf(recognize(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    /** The words on each page of a scanned PDF (the first few pages). */
    suspend fun readPdf(uri: Uri): List<List<OcrBox>> = withContext(Dispatchers.IO) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open the PDF")
        val pages = mutableListOf<List<OcrBox>>()
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (index in 0 until minOf(renderer.pageCount, MAX_PDF_PAGES)) {
                    val bitmap = renderer.openPage(index).use { page ->
                        val scale = PDF_PAGE_WIDTH.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(PDF_PAGE_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE) // a PDF page has no background of its own
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                    try {
                        pages += recognize(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        pages
    }

    /** A smaller JPEG of a photo, for the AI reader (which accepts images only up to a few MB). */
    suspend fun jpegForUpload(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(uri, UPLOAD_MAX_SIDE)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrBox> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val boxes = mutableListOf<OcrBox>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                if (line.elements.isEmpty()) {
                    line.boundingBox?.let { boxes += OcrBox(line.text, it.left, it.top, it.right, it.bottom) }
                } else {
                    for (word in line.elements) {
                        word.boundingBox?.let { boxes += OcrBox(word.text, it.left, it.top, it.right, it.bottom) }
                    }
                }
            }
        }
        return boxes
    }

    /** Loads a photo, turned the right way up (phones store rotation separately) and no bigger than [maxSide]. */
    private fun loadBitmap(uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a readable picture")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Not a readable picture")

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = maxSide.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (degrees != 0f) postRotate(degrees)
            if (scale < 1f) postScale(scale, scale)
        }
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private companion object {
        const val OCR_MAX_SIDE = 2800
        const val UPLOAD_MAX_SIDE = 1800
        const val PDF_PAGE_WIDTH = 2000
        const val MAX_PDF_PAGES = 3
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
