package com.nahla.docscanner

import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.nahla.docscanner.databinding.ActivityEditBinding
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class EditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditBinding

    private var pageId = ""
    private var source: Bitmap? = null
    private var pendingQuad: FloatArray? = null
    private val pageSignatures = mutableListOf<SignatureLayer>()
    private val pageShapes = mutableListOf<ShapeLayer>()
    private var pageBitmap: Bitmap? = null
    private var pageEditor: PageAnnotationView? = null
    private var editorStage = false

    /** Automatically detected corners, retained when rotating the image. */
    private var autoQuad: FloatArray? = null

    /** Keeps auto-detection from replacing a crop the user already adjusted. */
    private var userAdjusted = false

    private var busy = false

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pickSignaturePhoto =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadSignaturePhoto(uri)
        }
    private val takeSignaturePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val file = signaturePhotoFile()
            if (saved && file.exists() && file.length() > 0L) {
                loadSignaturePhoto(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
            } else if (saved) {
                toast(getString(R.string.error_signature_camera))
            }
            if (!saved && file.exists()) file.delete()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pageId = intent.getStringExtra(EXTRA_PAGE_ID).orEmpty().ifBlank { ScanStore.newId() }
        savedInstanceState?.getFloatArray(STATE_QUAD)?.let { pendingQuad = it }

        binding.cropOverlay.onCropChanged = { userAdjusted = true }

        binding.rotate90.setOnClickListener { rotate(90) }
        binding.next.setOnClickListener { enterEditorStage() }
        binding.backToCrop.setOnClickListener { returnToCropStage() }
        binding.sign.setOnClickListener { chooseSignature() }
        binding.addShape.setOnClickListener { chooseShape() }
        binding.savePage.setOnClickListener { savePage() }

        loadSource()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Same bitmap, new fit-center rect: re-map the quad instead of re-decoding.
        binding.root.post { applyImageRect() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val quad = binding.cropOverlay.normalizedPoints()
        if (quad.any { it != 0f }) outState.putFloatArray(STATE_QUAD, quad)
    }

    // ------------------------------------------------------------------ source

    private fun loadSource() {
        val file = ScanStore.sourceFile(this, pageId)
        if (!file.exists() || file.length() == 0L) {
            toast(getString(R.string.error_generic))
            finish()
            return
        }

        setBusy(true, getString(R.string.loading))
        io.execute {
            var decoded: Bitmap? = null
            val decodedSignatures = mutableListOf<SignatureLayer>()
            val decodedShapes = mutableListOf<ShapeLayer>()
            try {
                decoded = ImageProcessor.decode(file, ImageProcessor.MAX_EDGE)
                val stored = ScanStore.readPageSignatures(this, pageId)
                if (stored.isNotEmpty()) {
                    stored.forEach { (signatureFile, placement) ->
                        val bitmap = BitmapFactory.decodeFile(
                            signatureFile.absolutePath,
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        ) ?: throw IOException("Could not decode page signature")
                        decodedSignatures.add(SignatureLayer(bitmap, placement))
                    }
                } else {
                    val signatureFile = ScanStore.pageSignatureFile(this, pageId)
                    if (signatureFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(
                            signatureFile.absolutePath,
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        ) ?: throw IOException("Could not decode page signature")
                        decodedSignatures.add(
                            SignatureLayer(bitmap, ScanStore.readSignaturePlacement(this, pageId)
                                ?: SignaturePlacement())
                        )
                    }
                }
                decodedShapes.addAll(ScanStore.readPageShapes(this, pageId))
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed", t)
                decoded?.recycle()
                decoded = null
                decodedSignatures.forEach { it.bitmap.recycle() }
                decodedSignatures.clear()
                decodedShapes.clear()
            }
            val loadedSignatures = decodedSignatures
            main.post {
                setBusy(false)
                val full = decoded
                if (isFinishing || isDestroyed) {
                    full?.recycle()
                    loadedSignatures.forEach { it.bitmap.recycle() }
                    return@post
                }
                if (full == null) {
                    full?.recycle()
                    loadedSignatures.forEach { it.bitmap.recycle() }
                    toast(getString(R.string.error_generic))
                    finish()
                    return@post
                }
                recycleAll()
                pageSignatures.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                pageSignatures.clear()
                pageShapes.clear()
                source = full
                pageSignatures.addAll(loadedSignatures)
                pageShapes.addAll(decodedShapes)
                binding.photo.setImageBitmap(full)
                binding.cropOverlay.sourceBitmap = full
                binding.root.post { applyImageRect() }
                detectDocument(full)
            }
        }
    }

    private fun applyImageRect(reset: Boolean = false) {
        val drawable = binding.photo.drawable ?: return
        val viewWidth = binding.photo.width.toFloat()
        val viewHeight = binding.photo.height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return

        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f

        val pending = pendingQuad
        binding.cropOverlay.setImageRect(
            RectF(left, top, left + width, top + height),
            resetQuad = reset || pending == null
        )
        if (pending != null) {
            binding.cropOverlay.setNormalizedPoints(pending)
            pendingQuad = null
        }
    }

    // --------------------------------------------------------------- transform

    /**
     * Runs the edge detector on a background thread and snaps the overlay onto the
     * detected paper corners.
     */
    private fun detectDocument(bitmap: Bitmap) {
        io.execute {
            val detected = try {
                DocumentEdgeDetector.detect(bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "edge detection failed", t)
                null
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                autoQuad = detected
                if (!userAdjusted && detected != null) {
                    pendingQuad = detected
                    binding.root.post { applyImageRect() }
                }
            }
        }
    }

    private fun rotate(degrees: Int) {
        val full = source ?: return
        if (busy) return

        val points = binding.cropOverlay.normalizedPoints()
        setBusy(true, getString(R.string.working))
        io.execute {
            var rotatedFull: Bitmap? = null
            try {
                rotatedFull = ImageProcessor.rotate(full, degrees)
            } catch (t: Throwable) {
                Log.w(TAG, "rotate failed", t)
            }
            val nextQuad = ImageProcessor.rotateNormalizedQuad(points, degrees)
            main.post {
                setBusy(false)
                val newFull = rotatedFull
                if (isFinishing || isDestroyed || newFull == null) {
                    newFull?.recycle()
                    return@post
                }
                recycleAll()
                source = newFull
                // Keep the detected corners glued to the page across a rotation.
                autoQuad = autoQuad?.let { ImageProcessor.rotateNormalizedQuad(it, degrees) }
                binding.photo.setImageBitmap(newFull)
                binding.cropOverlay.sourceBitmap = newFull
                pendingQuad = nextQuad
                binding.root.post { applyImageRect() }
            }
        }
    }

    private fun enterEditorStage() {
        val full = source ?: return
        if (busy || editorStage) return
        val points = binding.cropOverlay.normalizedPoints()
        if (points.all { it == 0f }) {
            toast(getString(R.string.error_crop))
            return
        }
        setBusy(true, getString(R.string.working))
        io.execute {
            val cropped = try {
                ImageProcessor.crop(full, points)
            } catch (t: Throwable) {
                Log.e(TAG, "page crop failed", t)
                null
            }
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) {
                    cropped?.recycle()
                    return@post
                }
                if (cropped == null) {
                    toast(getString(R.string.error_crop))
                    return@post
                }
                pageBitmap?.let { if (!it.isRecycled) it.recycle() }
                pageBitmap = cropped
                pageEditor = PageAnnotationView(this, cropped, pageSignatures, pageShapes)
                binding.annotationContainer.removeAllViews()
                binding.annotationContainer.addView(
                    pageEditor,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                editorStage = true
                binding.cropContent.visibility = View.GONE
                binding.cropActions.visibility = View.GONE
                binding.annotationContainer.visibility = View.VISIBLE
                binding.editorActions.visibility = View.VISIBLE
                binding.backToCrop.visibility = View.VISIBLE
                binding.stageTitle.setText(R.string.editor_stage)
            }
        }
    }

    private fun returnToCropStage() {
        if (!editorStage || busy) return
        editorStage = false
        binding.annotationContainer.removeAllViews()
        pageEditor = null
        pageBitmap?.let { if (!it.isRecycled) it.recycle() }
        pageBitmap = null
        binding.annotationContainer.visibility = View.GONE
        binding.editorActions.visibility = View.GONE
        binding.cropContent.visibility = View.VISIBLE
        binding.cropActions.visibility = View.VISIBLE
        binding.backToCrop.visibility = View.GONE
        binding.stageTitle.setText(R.string.crop_stage)
    }

    // -------------------------------------------------------------- signatures

    private fun chooseSignature() {
        if (busy || !editorStage || pageEditor == null) return
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.signature_title)
            textSize = 20f
            setTextColor(Color.rgb(25, 35, 40))
            setPadding(dp(2), 0, 0, dp(12))
        })
        val scan = brightButton(getString(R.string.scan_signature_photo), true)
        scan.setOnClickListener {
            sheet.dismiss()
            showSignaturePhotoSources()
        }
        content.addView(scan, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        val draw = brightButton(getString(R.string.draw_signature), false)
        draw.setOnClickListener {
            sheet.dismiss()
            showSignaturePad()
        }
        content.addView(draw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = getString(R.string.signature_saved_choice)
            textSize = 15f
            setTextColor(Color.rgb(25, 35, 40))
            setPadding(dp(2), dp(18), 0, dp(8))
        })
        val tray = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val signatures = ScanStore.listSavedSignatures(this)
        if (signatures.isEmpty()) {
            tray.addView(TextView(this).apply {
                text = getString(R.string.signature_library_empty)
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        }
        signatures.forEach { file ->
            val card = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(210, 225, 225))
                }
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.WHITE)
                contentDescription = getString(R.string.signature_saved_choice)
                setImageBitmap(decodeSignatureThumbnail(file))
                setOnClickListener {
                    sheet.dismiss()
                    loadSignature(file)
                }
            }
            card.addView(image, FrameLayout.LayoutParams(dp(108), dp(84)))
            val delete = TextView(this).apply {
                text = "×"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(220, 53, 69))
                }
                contentDescription = getString(R.string.delete)
                setOnClickListener {
                    if (!file.delete()) {
                        toast(getString(R.string.signature_error))
                    } else {
                        tray.removeView(card)
                        if (tray.childCount == 0) {
                            tray.addView(TextView(this@EditActivity).apply {
                                text = getString(R.string.signature_library_empty)
                                setTextColor(Color.DKGRAY)
                                setPadding(dp(4), dp(8), dp(4), dp(8))
                            })
                        }
                    }
                }
            }
            card.addView(delete, FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.END or android.view.Gravity.TOP))
            tray.addView(card, LinearLayout.LayoutParams(dp(110), dp(88)).apply {
                marginEnd = dp(10)
            })
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tray)
        }
        content.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(94)
        ))
        sheet.setContentView(content)
        sheet.show()
    }

    private fun chooseShape() {
        if (busy || !editorStage || pageEditor == null) return
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.shapes_title)
            textSize = 20f
            setTextColor(Color.rgb(25, 35, 40))
        })
        val colors = listOf(
            ShapePlacement.BLUE to "Blue",
            ShapePlacement.RED to "Red",
            ShapePlacement.BLACK to "Black",
            ShapePlacement.GREEN to "Green",
            ShapePlacement.YELLOW to "Highlight",
            ShapePlacement.WHITE to "White"
        )
        var selectedColor = ShapePlacement.BLUE
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        colors.forEach { (color, name) ->
            val chip = MaterialButton(this).apply {
                text = name
                textSize = 9f
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                setTextColor(if (color == ShapePlacement.YELLOW || color == ShapePlacement.WHITE)
                    Color.BLACK else Color.WHITE)
                setOnClickListener { selectedColor = color }
            }
            colorRow.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(3)
            })
        }
        content.addView(colorRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(12) })
        val stroke = SeekBar(this).apply {
            max = 13
            progress = 2
            contentDescription = getString(R.string.shape_stroke)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.shape_stroke)
            setTextColor(Color.DKGRAY)
        })
        content.addView(stroke)
        val types = listOf(
            PageShapeType.RECTANGLE to R.string.shape_rectangle,
            PageShapeType.FILLED_BOX to R.string.shape_filled,
            PageShapeType.OVAL to R.string.shape_oval,
            PageShapeType.ARROW to R.string.shape_arrow
        )
        types.forEach { (type, label) ->
            val button = brightButton(getString(label), true)
            button.setOnClickListener {
                pageEditor?.addShape(type, selectedColor, (stroke.progress + 1).toFloat())
                sheet.dismiss()
            }
            content.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(6) })
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun brightButton(label: String, primary: Boolean): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) Color.rgb(0, 230, 118) else Color.rgb(224, 242, 241)
            )
            setTextColor(if (primary) Color.rgb(0, 37, 26) else Color.rgb(0, 77, 64))
            elevation = dp(4).toFloat()
        }

    private fun decodeSignatureThumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) + 219) / 220)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun loadSignature(file: File) {
        setBusy(true, getString(R.string.working))
        io.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                ) ?: throw IOException("Could not decode signature")
            } catch (t: Throwable) {
                Log.e(TAG, "signature decode failed", t)
            }
            val result = bitmap
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) {
                    result?.recycle()
                    return@post
                }
                if (result == null) {
                    toast(getString(R.string.signature_error))
                } else {
                    preparePlacement(result)
                }
            }
        }
    }

    private fun showNewSignatureOptions() {
        val options = arrayOf(
            getString(R.string.extract_signature_photo),
            getString(R.string.draw_signature)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_signature_arabic)
            .setItems(options) { _, which ->
                if (which == 0) showSignaturePhotoSources() else showSignaturePad()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSignaturePhotoSources() {
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val camera = brightButton(getString(R.string.signature_take_photo), false)
        camera.setOnClickListener {
            photoSourcesDialog?.dismiss()
            captureSignaturePhoto()
        }
        choices.addView(camera, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        val gallery = brightButton(getString(R.string.signature_pick_gallery), false)
        gallery.setOnClickListener {
            photoSourcesDialog?.dismiss()
            pickSignaturePhoto.launch(arrayOf("image/*"))
        }
        choices.addView(gallery, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(8) })
        photoSourcesDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.extract_signature_photo)
            .setView(choices)
            .setNegativeButton(R.string.cancel, null)
            .create()
        photoSourcesDialog?.setOnShowListener {
            photoSourcesDialog?.let(::styleDialogButtons)
        }
        photoSourcesDialog?.show()
    }

    private var photoSourcesDialog: androidx.appcompat.app.AlertDialog? = null

    private fun captureSignaturePhoto() {
        val file = signaturePhotoFile()
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && !file.delete()) throw IOException("Could not replace capture file")
            file.createNewFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            takeSignaturePhoto.launch(uri)
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "no camera app available for signature photo", t)
            toast(getString(R.string.error_no_camera))
        } catch (t: Exception) {
            Log.e(TAG, "could not start signature photo capture", t)
            toast(getString(R.string.error_signature_camera))
        }
    }

    private fun signaturePhotoFile(): File =
        File(cacheDir, "signature_capture_$pageId.jpg")

    private fun loadSignaturePhoto(uri: Uri) {
        if (busy) return
        setBusy(true, getString(R.string.loading_signature_photo))
        io.execute {
            var bitmap: Bitmap? = null
            try {
                val raw = SignatureExtractionEngine.decode(this, uri)
                bitmap = try {
                    SignatureExtractionEngine.autoCrop(raw)
                } finally {
                    if (!raw.isRecycled) raw.recycle()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "signature photo decode failed", t)
            } finally {
                File(cacheDir, "sig_raw_import.tmp").delete()
            }
            val loaded = bitmap
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) {
                    loaded?.recycle()
                    return@post
                }
                signaturePhotoFile().delete()
                if (loaded == null) {
                    toast(getString(R.string.error_signature_photo))
                    return@post
                }
                showSignatureExtractionPreview(loaded)
            }
        }
    }

    private fun showSignatureCrop(photo: Bitmap) {
        val cropView = SignatureCropView(this, photo)
        var result: Bitmap? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crop_signature_photo)
            .setView(cropView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_signature, null)
            .create()
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    try {
                        result = cropView.croppedBitmap()
                        dialog.dismiss()
                    } catch (t: Throwable) {
                        Log.e(TAG, "signature photo crop failed", t)
                        toast(getString(R.string.error_signature_photo))
                    }
                }
        }
        dialog.setOnDismissListener {
            if (!photo.isRecycled) photo.recycle()
            result?.let { showSignatureExtractionPreview(it) }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            dp(420)
        )
    }

    private fun showSignatureExtractionPreview(cropped: Bitmap) {
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(248, 249, 250))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(205, 214, 220))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.signature_extraction_preview)
        }
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val modeButtons = listOf(
            SignatureInkMode.BLUE to R.string.signature_color_blue,
            SignatureInkMode.BLACK to R.string.signature_color_black,
            SignatureInkMode.ORIGINAL to R.string.signature_ink_original,
            SignatureInkMode.NATURAL to R.string.signature_ink_natural
        )
        modeButtons.forEachIndexed { index, (mode, label) ->
            modes.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(label)
                textSize = 9f
                tag = mode
                isChecked = mode == SignatureInkMode.ORIGINAL
            }, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val threshold = SeekBar(this).apply {
            max = 100
            progress = 50
            contentDescription = getString(R.string.signature_threshold)
        }
        val thresholdCaption = android.widget.TextView(this).apply {
            text = getString(R.string.signature_threshold)
            textSize = 13f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(4))
            addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
            ))
            addView(modes)
            addView(thresholdCaption)
            addView(threshold)
        }
        var currentMode = SignatureInkMode.ORIGINAL
        var previewBitmap: Bitmap? = null
        var resultSignature: Bitmap? = null
        val previewGeneration = AtomicInteger()
        var previewRunnable: Runnable? = null
        var saved = false
        var libraryFile: File? = null
        var dismissed = false
        var manualCropRequested = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.signature_extraction_preview)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.manual_crop, null)
            .setPositiveButton(R.string.signature_done_save, null)
            .create()
        fun updatePreview(immediate: Boolean = false) {
            val request = previewGeneration.incrementAndGet()
            val adjustment = threshold.progress
            val mode = currentMode
            previewRunnable?.let { main.removeCallbacks(it) }
            previewRunnable = Runnable {
                io.execute {
                    if (previewGeneration.get() != request || dismissed) return@execute
                    var generated: Bitmap? = null
                    try {
                        generated = SignatureExtractionEngine.extract(cropped, mode, adjustment)
                    } catch (t: Throwable) {
                        Log.e(TAG, "signature extraction preview failed", t)
                    }
                    val next = generated
                    main.post {
                        if (dismissed || previewGeneration.get() != request || isFinishing || isDestroyed) {
                            next?.recycle()
                            return@post
                        }
                        if (next == null) {
                            toast(getString(R.string.error_signature_photo))
                            return@post
                        }
                        val old = previewBitmap
                        previewBitmap = next
                        preview.setImageBitmap(next)
                        old?.recycle()
                    }
                }
            }
            main.postDelayed(previewRunnable!!, if (immediate) 0L else 120L)
        }
        modes.setOnCheckedChangeListener { group, checkedId ->
            currentMode = group.findViewById<RadioButton>(checkedId)?.tag as? SignatureInkMode
                ?: SignatureInkMode.ORIGINAL
            updatePreview()
        }
        threshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    manualCropRequested = true
                    dialog.dismiss()
                }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val current = previewBitmap
                    if (current == null) {
                        toast(getString(R.string.signature_preview_loading))
                        return@setOnClickListener
                    }
                    try {
                        resultSignature = SignatureExtractionEngine.trimTransparent(current)
                        libraryFile = ScanStore.newSavedSignatureFile(this)
                        ImageProcessor.writePng(resultSignature!!, libraryFile!!)
                        saved = true
                        dialog.dismiss()
                    } catch (t: Throwable) {
                        libraryFile?.delete()
                        libraryFile = null
                        Log.e(TAG, "extracted signature could not be saved", t)
                        resultSignature?.recycle()
                        resultSignature = null
                        toast(getString(R.string.error_signature_extraction))
                    }
                }
            updatePreview(true)
        }
        dialog.setOnDismissListener {
            dismissed = true
            previewGeneration.incrementAndGet()
            previewRunnable?.let { main.removeCallbacks(it) }
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            previewBitmap = null
            if (manualCropRequested) {
                showSignatureCrop(cropped)
                return@setOnDismissListener
            }
            if (!cropped.isRecycled) cropped.recycle()
            val signature = resultSignature
            if (saved && signature != null) {
                toast(getString(R.string.signature_saved))
                preparePlacement(signature)
            } else {
                signature?.let { if (!it.isRecycled) it.recycle() }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showSignaturePad() {
        val pad = SignaturePadView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colors = listOf(
            getString(R.string.signature_color_blue) to SignaturePadView.BLUE,
            getString(R.string.signature_color_black) to SignaturePadView.BLACK,
            getString(R.string.signature_color_red) to SignaturePadView.RED
        )
        colors.forEach { (label, color) ->
            val button = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                setOnClickListener { pad.selectColor(color) }
            }
            colorRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        container.addView(colorRow)
        container.addView(
            pad,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
                topMargin = dp(8)
            }
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.signature_pad_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.signature_clear, null)
            .setPositiveButton(R.string.signature_done_save, null)
            .create()
        dialog.setOnShowListener {
            styleDialogButtons(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { pad.clear() }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val signature = pad.trimmedBitmap()
                    if (signature == null) {
                        toast(getString(R.string.signature_empty))
                        return@setOnClickListener
                    }
                    val savedFile = ScanStore.newSavedSignatureFile(this)
                    try {
                        ImageProcessor.writePng(signature, savedFile)
                    } catch (t: Throwable) {
                        savedFile.delete()
                        Log.e(TAG, "signature save failed", t)
                        signature.recycle()
                        toast(getString(R.string.signature_error))
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    toast(getString(R.string.signature_saved))
                    preparePlacement(signature)
                }
        }
        dialog.show()
    }

    private fun preparePlacement(signature: Bitmap) {
        val editor = pageEditor
        if (!editorStage || editor == null || busy) {
            signature.recycle()
            return
        }
        editor.addSignature(signature)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun styleDialogButtons(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 230, 118))
            setTextColor(Color.rgb(0, 37, 26))
        }
        listOf(
            androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
            androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL
        ).forEach { which ->
            dialog.getButton(which)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(30, 58, 58))
                setTextColor(Color.rgb(100, 255, 218))
            }
        }
    }

    // -------------------------------------------------------------------- save

    private fun savePage() {
        val page = pageBitmap ?: return
        val editor = pageEditor ?: return
        if (busy || !editorStage) return
        val composited = try {
            page.copy(Bitmap.Config.ARGB_8888, true).also { copy ->
                editor.drawAnnotations(Canvas(copy), copy)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not render page annotations", t)
            toast(getString(R.string.error_generic))
            return
        }
        val signatures = editor.signatureLayers
        val shapes = editor.shapeLayers
        setBusy(true, getString(R.string.saving))
        io.execute {
            var ok = false
            try {
                ImageProcessor.writePng(composited, ScanStore.pageFile(this@EditActivity, pageId))
                ScanStore.writePageSignatures(this@EditActivity, pageId, signatures)
                ScanStore.writePageShapes(this@EditActivity, pageId, shapes)
                ok = true
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
            } finally {
                composited.recycle()
            }
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) return@post
                if (!ok) {
                    toast(getString(R.string.error_crop))
                    return@post
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PAGE_ID, pageId))
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun setBusy(value: Boolean, label: String? = null) {
        busy = value
        binding.busyOverlay.visibility = if (value) View.VISIBLE else View.GONE
        binding.rotate90.isEnabled = !value
        binding.next.isEnabled = !value
        binding.backToCrop.isEnabled = !value
        binding.sign.isEnabled = !value
        binding.addShape.isEnabled = !value
        binding.savePage.isEnabled = !value
        if (label != null) binding.busyLabel.text = label
    }

    private fun recycleAll() {
        binding.cropOverlay.sourceBitmap = null
        source?.let { if (!it.isRecycled) it.recycle() }
        source = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        io.shutdownNow()
        recycleAll()
        pageBitmap?.let { if (!it.isRecycled) it.recycle() }
        pageBitmap = null
        pageSignatures.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        pageSignatures.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE_ID = "extra_page_id"
        private const val TAG = "EditActivity"
        private const val STATE_QUAD = "state_quad"
    }
}