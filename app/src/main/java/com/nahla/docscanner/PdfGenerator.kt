package com.nahla.docscanner

import android.content.ContentValues
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * A4 PDF writer built on the framework's `android.graphics.pdf.PdfDocument`.
 * No third-party PDF engine, so the APK stays small.
 *
 * Memory model: pages are streamed one at a time. Each source JPEG is decoded,
 * drawn, and recycled before the next one is touched, so peak usage is a single
 * page bitmap (~2.6 MB for a 1600 px page) regardless of document length.
 */
object PdfGenerator {

    /** A4 in PostScript points at 72 dpi. */
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    private const val MARGIN = 20f
    private const val MAX_PAGES = 60
    private const val FOLDER_NAME = "DocScanner"
    private const val MIME_PDF = "application/pdf"

    class ExportResult(
        val uri: Uri?,
        val file: File,
        val shareUri: Uri,
        val displayName: String,
        val bytes: Long,
        val downloadError: String? = null
    )

    /** Writes the natural-color [pages] into [target] as a multi-page PDF. */
    fun build(
        pages: List<File>,
        target: File,
        onProgress: (Int, Int) -> Unit
    ): Long {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        require(pages.size <= MAX_PAGES) { "Too many pages (${pages.size})" }

        target.parentFile?.mkdirs()
        val document = PdfDocument()
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = Color.parseColor("#1F000000")
        }

        try {
            pages.forEachIndexed { index, file ->
                onProgress(index, pages.size)
                val bitmap = ImageProcessor.decode(file, ImageProcessor.PAGE_EDGE)
                try {
                    val info = PdfDocument.PageInfo
                        .Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1)
                        .create()
                    val page = document.startPage(info)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val dest = fitRect(bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, null, dest, bitmapPaint)
                    canvas.drawRect(dest, borderPaint)

                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            onProgress(pages.size, pages.size)

            FileOutputStream(target).use { out -> document.writeTo(out) }
        } finally {
            document.close()
        }
        return target.length()
    }

    private fun fitRect(bitmapWidth: Int, bitmapHeight: Int): RectF {
        val availableWidth = PAGE_WIDTH - MARGIN * 2
        val availableHeight = PAGE_HEIGHT - MARGIN * 2
        val scale = min(availableWidth / bitmapWidth, availableHeight / bitmapHeight)
        val width = bitmapWidth * scale
        val height = bitmapHeight * scale
        val left = (PAGE_WIDTH - width) / 2f
        val top = (PAGE_HEIGHT - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    /**
     * Publishes the finished file into Downloads/DocScanner.
     * API 29+ goes through MediaStore (scoped storage, no permission needed);
     * older devices write the legacy public directory.
     */
    fun saveToDownloads(context: Context, pdf: File, fileName: String): ExportResult =
        exportToDownloads(context, pdf, fileName)

    /**
     * Builds the final, shareable PDF in cache first, then publishes a copy to Downloads.
     * A Downloads failure is reported in the result while the verified share copy remains usable.
     */
    fun export(
        context: Context,
        pages: List<File>,
        fileName: String,
        onProgress: (Int, Int) -> Unit
    ): ExportResult {
        val safeName = safePdfName(fileName)
        val exportDirectory = File(context.cacheDir, "exports")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IOException("Cannot create PDF export directory: ${exportDirectory.absolutePath}")
        }
        val cachePdfFile = File(exportDirectory, safeName)
        build(pages, cachePdfFile, onProgress)
        if (!cachePdfFile.exists() || cachePdfFile.length() <= 0L) {
            throw IOException("PDF generation produced an empty file: ${cachePdfFile.absolutePath}")
        }
        val shareUri = try {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cachePdfFile
            )
        } catch (e: IllegalArgumentException) {
            throw IOException("Cannot create a share URI for ${cachePdfFile.absolutePath}", e)
        }

        val download = publishToDownloads(context, cachePdfFile, safeName)
        return ExportResult(
            uri = download.uri,
            file = cachePdfFile,
            shareUri = shareUri,
            displayName = download.fileName ?: safeName,
            bytes = cachePdfFile.length(),
            downloadError = download.error
        ).also {
            // Resolve the provider URI here too, so provider configuration errors are not deferred
            // until a recipient opens the share sheet.
            if (shareUri.scheme != "content") {
                throw IOException("PDF sharing did not produce a content URI")
            }
        }
    }

    private data class DownloadResult(
        val uri: Uri?,
        val fileName: String?,
        val error: String?
    )

    private fun exportToDownloads(context: Context, pdf: File, fileName: String): ExportResult {
        if (!pdf.isFile || pdf.length() <= 0L) {
            throw IOException("PDF file is missing or empty: ${pdf.absolutePath}")
        }
        val name = safePdfName(fileName)
        val download = publishToDownloads(context, pdf, name)
        val directory = File(context.cacheDir, "exports")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create PDF export directory: ${directory.absolutePath}")
        }
        val shareFile = File(directory, name)
        if (pdf.absoluteFile != shareFile.absoluteFile) {
            FileOutputStream(shareFile).use { output ->
                pdf.inputStream().use { input -> input.copyTo(output) }
            }
        }
        if (!shareFile.isFile || shareFile.length() <= 0L) {
            throw IOException("PDF sharing copy is missing or empty: ${shareFile.absolutePath}")
        }
        val shareUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", shareFile
        )
        return ExportResult(download.uri, shareFile, shareUri,
            download.fileName ?: name, shareFile.length(), download.error)
    }

    private fun publishToDownloads(context: Context, pdf: File, fileName: String): DownloadResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreResult = runCatching { saveWithMediaStore(context, pdf, fileName) }
            mediaStoreResult.getOrNull()?.let { return it }
            val mediaError = mediaStoreResult.exceptionOrNull()?.message
                ?: "MediaStore could not save $fileName"
            val fallback = runCatching { saveLegacy(context, pdf, fileName) }
            fallback.getOrNull()?.let {
                Log.w("PdfGenerator", "MediaStore failed; saved through Downloads fallback: $mediaError")
                return it
            }
            return DownloadResult(
                uri = null,
                fileName = null,
                error = "MediaStore failed: $mediaError; Downloads fallback failed: " +
                    (fallback.exceptionOrNull()?.message ?: "unknown error")
            )
        }
        return runCatching { saveLegacy(context, pdf, fileName) }.getOrElse { error ->
            DownloadResult(null, null, "Downloads save failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun saveWithMediaStore(context: Context, pdf: File, requestedName: String): DownloadResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME + "/"
        var lastError: Throwable? = null
        for (attempt in 0 until 100) {
            val candidate = uniqueCandidate(requestedName, attempt)
            if (mediaStoreNameExists(context, collection, candidate, relativePath)) continue
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                put(MediaStore.Downloads.MIME_TYPE, MIME_PDF)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (t: Throwable) {
                lastError = t
                break
            }
            if (uri == null) {
                lastError = IOException("MediaStore insert returned null for $candidate")
                continue
            }
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IOException("Cannot open Downloads output stream for $candidate")
                output.use { out -> pdf.inputStream().use { input -> input.copyTo(out) } }
                if (resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    ) <= 0
                ) throw IOException("MediaStore could not publish $candidate")
                return DownloadResult(uri, candidate, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                lastError = t
                break
            }
        }
        throw IOException(lastError?.message ?: "Could not reserve a unique MediaStore name for $requestedName",
            lastError)
    }

    private fun mediaStoreNameExists(
        context: Context,
        collection: Uri,
        name: String,
        relativePath: String
    ): Boolean {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        return context.contentResolver.query(collection, projection, selection,
            arrayOf(name, relativePath), null)?.use { cursor ->
            cursor.moveToFirst()
        } ?: throw IOException("MediaStore returned no cursor while checking $name")
    }

    private fun saveLegacy(context: Context, pdf: File, requestedName: String): DownloadResult {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Cannot create ${dir.absolutePath}")
        }
        for (attempt in 0 until 100) {
            val candidate = uniqueCandidate(requestedName, attempt)
            val target = File(dir, candidate)
            if (target.exists()) continue
            try {
                FileOutputStream(target).use { out -> pdf.inputStream().use { input -> input.copyTo(out) } }
                if (target.length() <= 0L) {
                    target.delete()
                    throw IOException("Downloads file is empty: ${target.absolutePath}")
                }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", target
                )
                return DownloadResult(uri, candidate, null)
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
        }
        throw IOException("Could not find an unused Downloads filename for $requestedName")
    }

    private fun uniqueCandidate(fileName: String, attempt: Int): String {
        if (attempt == 0) return fileName
        val base = fileName.removeSuffix(".pdf")
        val suffix = if (attempt == 1) System.currentTimeMillis().toString()
            else "${System.currentTimeMillis()}_$attempt"
        return "${base}_$suffix.pdf"
    }

    private fun safePdfName(fileName: String): String {
        val cleaned = fileName.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
            .trim()
            .trimEnd('.')
            .trim()
            .ifBlank { "Nahla_Scan" }
        val base = if (cleaned.endsWith(".pdf", true)) cleaned.dropLast(4).trimEnd('.').trim()
            else cleaned
        return "${base.ifBlank { "Nahla_Scan" }}.pdf"
    }

    /** Intent for an external PDF viewer. */
    fun openIntent(context: Context, result: ExportResult): Intent {
        val uri = result.uri ?: result.shareUri
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_PDF)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(result: ExportResult): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_PDF
        putExtra(Intent.EXTRA_STREAM, result.shareUri)
        clipData = ClipData.newRawUri(result.displayName, result.shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun folderName(): String = FOLDER_NAME
}