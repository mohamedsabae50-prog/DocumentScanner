package com.nahla.docscanner

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Every working file lives under `cacheDir/scan_<id>/`, so the OS can reclaim
 * it when the app is evicted and nothing leaks into shared storage except the
 * finished PDF.
 */
object ScanStore {

    private const val DIR_PREFIX = "scan_"
    private const val SOURCE_NAME = "source.img"
    private const val PAGE_NAME = "page.png"
    private const val SIGNATURE_NAME = "signature.png"
    private const val SIGNATURE_POSITION_NAME = "signature.position"
    private const val SIGNATURES_DIR = "page_signatures"
    private const val SIGNATURE_POSITIONS_NAME = "signature.positions"
    private const val SHAPES_NAME = "page.shapes"
    private const val SAVED_SIGNATURES_DIR = "signatures"

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun pageDir(context: Context, id: String): File =
        File(context.applicationContext.cacheDir, "$DIR_PREFIX$id").apply { mkdirs() }

    /** Pre-created destination for ACTION_IMAGE_CAPTURE. */
    fun sourceFile(context: Context, id: String): File = File(pageDir(context, id), SOURCE_NAME)

    /** Cropped, unfiltered, lossless page - the source of truth for the PDF. */
    fun pageFile(context: Context, id: String): File = File(pageDir(context, id), PAGE_NAME)

    fun pageSignatureFile(context: Context, id: String): File =
        File(pageDir(context, id), SIGNATURE_NAME)

    fun savedSignaturesDir(context: Context): File =
        File(context.applicationContext.filesDir, SAVED_SIGNATURES_DIR).apply { mkdirs() }

    fun newSavedSignatureFile(context: Context): File =
        File(savedSignaturesDir(context), "sig_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.png")

    fun listSavedSignatures(context: Context): List<File> =
        savedSignaturesDir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun pageSignaturesDir(context: Context, id: String): File =
        File(pageDir(context, id), SIGNATURES_DIR)

    fun readPageSignatures(context: Context, id: String): List<Pair<File, SignaturePlacement>> {
        val directory = pageSignaturesDir(context, id)
        val positions = File(pageDir(context, id), SIGNATURE_POSITIONS_NAME)
        if (!directory.exists() || !positions.exists()) return emptyList()
        return try {
            DataInputStream(positions.inputStream()).use { input ->
                val count = input.readInt().coerceIn(0, 100)
                (0 until count).mapNotNull { index ->
                    val placement = SignaturePlacement(
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()
                    )
                    val file = File(directory, "sig_$index.png")
                    if (file.isFile) file to placement else null
                }
            }
        } catch (e: IOException) {
            Log.w("ScanStore", "Could not read signature placements for page $id", e)
            emptyList()
        }
    }

    fun readPageShapes(context: Context, id: String): List<ShapeLayer> {
        val file = File(pageDir(context, id), SHAPES_NAME)
        if (!file.exists()) return emptyList()
        return try {
            DataInputStream(file.inputStream()).use { input ->
                val count = input.readInt().coerceIn(0, 100)
                (0 until count).map {
                    val type = PageShapeType.values().getOrNull(input.readInt())
                        ?: throw IOException("Unsupported saved shape")
                    val placement = ShapePlacement(
                        centerX = input.readFloat(),
                        centerY = input.readFloat(),
                        width = input.readFloat(),
                        height = input.readFloat(),
                        rotationDegrees = input.readFloat(),
                        color = input.readInt(),
                        strokeWidth = input.readFloat()
                    )
                    ShapeLayer(type, placement)
                }
            }
        } catch (e: IOException) {
            Log.w("ScanStore", "Could not read shapes for page $id", e)
            emptyList()
        }
    }

    fun writePageShapes(context: Context, id: String, layers: List<ShapeLayer>) {
        val file = File(pageDir(context, id), SHAPES_NAME)
        if (layers.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove saved shapes")
            return
        }
        DataOutputStream(file.outputStream()).use { output ->
            output.writeInt(layers.size)
            layers.forEach { layer ->
                val placement = layer.placement
                output.writeInt(layer.type.ordinal)
                output.writeFloat(placement.centerX)
                output.writeFloat(placement.centerY)
                output.writeFloat(placement.width)
                output.writeFloat(placement.height)
                output.writeFloat(placement.rotationDegrees)
                output.writeInt(placement.color)
                output.writeFloat(placement.strokeWidth)
            }
        }
    }

    fun writePageSignatures(context: Context, id: String, layers: List<SignatureLayer>) {
        val directory = pageSignaturesDir(context, id)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not replace page signatures")
        }
        val positions = File(pageDir(context, id), SIGNATURE_POSITIONS_NAME)
        if (layers.isEmpty()) {
            listOf(
                positions,
                pageSignatureFile(context, id),
                File(pageDir(context, id), SIGNATURE_POSITION_NAME)
            ).filter { it.exists() && !it.delete() }
                .firstOrNull()
                ?.let { throw IOException("Could not remove saved signatures") }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create page signature directory")
        }
        DataOutputStream(positions.outputStream()).use { output ->
            output.writeInt(layers.size)
            layers.forEachIndexed { index, layer ->
                ImageProcessor.writePng(layer.bitmap, File(directory, "sig_$index.png"))
                output.writeFloat(layer.placement.centerX)
                output.writeFloat(layer.placement.centerY)
                output.writeFloat(layer.placement.width)
                output.writeFloat(layer.placement.rotationDegrees)
            }
        }
        // Keep the original single-signature files readable by older builds.
        if (layers.size == 1) {
            ImageProcessor.writePng(layers[0].bitmap, pageSignatureFile(context, id))
            DataOutputStream(File(pageDir(context, id), SIGNATURE_POSITION_NAME).outputStream()).use {
                it.writeFloat(layers[0].placement.centerX)
                it.writeFloat(layers[0].placement.centerY)
                it.writeFloat(layers[0].placement.width)
            }
        } else {
            listOf(pageSignatureFile(context, id), File(pageDir(context, id), SIGNATURE_POSITION_NAME))
                .filter { it.exists() && !it.delete() }
                .firstOrNull()
                ?.let { throw IOException("Could not replace legacy signature file") }
        }
    }

    fun readSignaturePlacement(context: Context, id: String): SignaturePlacement? {
        val file = File(pageDir(context, id), SIGNATURE_POSITION_NAME)
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream()).use {
                SignaturePlacement(it.readFloat(), it.readFloat(), it.readFloat())
            }
        } catch (e: IOException) {
            Log.w("ScanStore", "Could not read signature placement for page $id", e)
            null
        }
    }

    fun writeSignaturePlacement(
        context: Context,
        id: String,
        placement: SignaturePlacement?,
        signatureBitmap: android.graphics.Bitmap?
    ) {
        val directory = pageDir(context, id)
        val signature = File(directory, SIGNATURE_NAME)
        val position = File(directory, SIGNATURE_POSITION_NAME)
        if (placement == null) {
            if (signature.exists() && !signature.delete()) {
                throw IOException("Could not remove the page signature")
            }
            if (position.exists() && !position.delete()) {
                throw IOException("Could not remove signature placement")
            }
            return
        }
        val bitmap = signatureBitmap
            ?: throw IOException("A signature image is required for its placement")
        ImageProcessor.writePng(bitmap, signature)
        DataOutputStream(position.outputStream()).use {
            it.writeFloat(placement.centerX)
            it.writeFloat(placement.centerY)
            it.writeFloat(placement.width)
        }
    }

    fun copyToCache(context: Context, source: Uri, id: String): File {
        val target = sourceFile(context, id)
        val resolver = context.applicationContext.contentResolver
        val input = resolver.openInputStream(source)
            ?: throw IOException("Cannot read $source")
        input.use { stream -> target.outputStream().use { out -> stream.copyTo(out) } }
        return target
    }

    fun delete(context: Context, id: String) {
        pageDir(context, id).deleteRecursively()
    }

    fun tempPdf(context: Context, name: String): File =
        File(context.applicationContext.cacheDir, name)

    /** Clears page-session data without touching exports or the persistent signature library. */
    fun clearScanPages(context: Context) {
        val cache = context.applicationContext.cacheDir
        val directories = cache.listFiles()
            ?: throw IOException("Cannot list scan cache: ${cache.absolutePath}")
        directories.filter { it.isDirectory && it.name.startsWith(DIR_PREFIX) }
            .firstOrNull { !it.deleteRecursively() }
            ?.let { throw IOException("Could not clear scan session: ${it.absolutePath}") }
    }

    /** Kept for older callers; never removes PDF exports or shareable cache files. */
    fun clearCache(context: Context) = clearScanPages(context)
}