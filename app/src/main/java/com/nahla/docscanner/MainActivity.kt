package com.nahla.docscanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nahla.docscanner.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pages = mutableListOf<ScanPage>()
    private val adapter = ScanAdapter(onOpen = ::openPage, onDelete = ::deletePage)

    private var pendingCameraId: String? = null
    private var pendingExportName: String? = null
    private var pendingExportShare = false

    private val io = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ingest(it) }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val id = pendingCameraId
        pendingCameraId = null
        if (saved && id != null) {
            openEditor(id)
        } else if (id != null) {
            ScanStore.delete(this, id)
        }
    }

    private val requestLegacyWrite =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val name = pendingExportName
            pendingExportName = null
            if (granted && name != null) {
                exportPdfNow(name, pendingExportShare)
            } else if (!granted) {
                toast(getString(R.string.error_permission))
            }
        }

    private val editPage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val id = result.data?.getStringExtra(EditActivity.EXTRA_PAGE_ID)
                ?: return@registerForActivityResult
            onPageSaved(id)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pageList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pageList.adapter = adapter
        binding.pageList.setHasFixedSize(true)

        binding.takePhoto.setOnClickListener { capturePhoto() }
        binding.pickImage.setOnClickListener { pickFromGallery() }
        binding.exportPdf.setOnClickListener { startExport() }

        updateChrome()
    }

    // ---------------------------------------------------------------- capture

    private fun capturePhoto() {
        val id = ScanStore.newId()
        val target = ScanStore.sourceFile(this, id)
        try {
            target.createNewFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
            pendingCameraId = id
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            ScanStore.delete(this, id)
            toast(getString(R.string.error_no_camera))
        } catch (_: IllegalArgumentException) {
            ScanStore.delete(this, id)
            toast(getString(R.string.error_generic))
        }
    }

    private fun pickFromGallery() {
        try {
            pickImage.launch(arrayOf("image/*"))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.error_no_gallery))
        }
    }

    private fun ingest(uri: android.net.Uri) {
        val id = ScanStore.newId()
        setBusy(true, getString(R.string.importing))
        io.execute {
            var ok = false
            try {
                ScanStore.copyToCache(this, uri, id)
                ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "import failed", t)
            }
            main.post {
                setBusy(false)
                if (ok) openEditor(id) else {
                    ScanStore.delete(this, id)
                    toast(getString(R.string.error_generic))
                }
            }
        }
    }

    private fun openEditor(id: String) {
        val intent = Intent(this, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_PAGE_ID, id)
        editPage.launch(intent)
    }

    private fun openPage(page: ScanPage) = openEditor(page.id)

    private fun onPageSaved(id: String) {
        val pageFile = ScanStore.pageFile(this, id)
        if (!pageFile.exists()) return

        val index = pages.indexOfFirst { it.id == id }
        setBusy(true, getString(R.string.working))
        io.execute {
            val thumb = try {
                ImageProcessor.decode(pageFile, ImageProcessor.THUMB_EDGE)
            } catch (t: Throwable) {
                Log.w(TAG, "thumbnail failed", t)
                null
            }
            main.post {
                setBusy(false)
                if (thumb == null) {
                    toast(getString(R.string.error_generic))
                    return@post
                }
                if (index >= 0) {
                    pages[index].thumb.recycle()
                    adapter.replace(ScanPage(id, pageFile, thumb))
                } else {
                    val page = ScanPage(id, pageFile, thumb)
                    pages.add(page)
                    adapter.add(page)
                    binding.pageList.scrollToPosition(pages.size - 1)
                }
                updateChrome()
            }
        }
    }

    private fun deletePage(page: ScanPage) {
        val index = pages.indexOfFirst { it.id == page.id }
        if (index < 0) return
        pages.removeAt(index).thumb.recycle()
        adapter.removeAt(index)
        ScanStore.delete(this, page.id)
        updateChrome()
    }

    // ----------------------------------------------------------------- export

    private fun startExport() {
        if (pages.isEmpty()) {
            toast(getString(R.string.no_pages))
            return
        }
        showPdfNameDialog()
    }

    private fun showPdfNameDialog() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val defaultName = "Nahla_Scan_$stamp"
        val input = EditText(this).apply {
            hint = getString(R.string.pdf_name_hint)
            setSingleLine(true)
            setText(defaultName)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            selectAll()
        }
        val clear = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.DKGRAY)
            contentDescription = getString(R.string.signature_clear)
            setPadding(0, 0, 0, 0)
            setOnClickListener { input.setText("") }
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(clear, LinearLayout.LayoutParams(dp(44), dp(54)))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_name_title)
            .setView(nameRow)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.save_and_send, null)
            .setPositiveButton(R.string.save_only, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00C853.toInt())
                setTextColor(0xFF00351A.toInt())
                setOnClickListener {
                    val name = sanitizePdfName(input.text.toString(), defaultName)
                    dialog.dismiss()
                    beginExport(name, true)
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF80CBC4.toInt())
                setTextColor(0xFF004D40.toInt())
                setOnClickListener {
                    val name = sanitizePdfName(input.text.toString(), defaultName)
                    dialog.dismiss()
                    beginExport(name, false)
                }
            }
        }
        dialog.show()
        input.post { input.selectAll() }
    }

    private fun sanitizePdfName(value: String, fallback: String): String {
        val clean = value.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
            .trim()
            .trimEnd('.')
            .trim()
        val base = if (clean.endsWith(".pdf", ignoreCase = true)) {
            clean.dropLast(4).trimEnd('.').trim()
        } else clean
        return "${base.ifBlank { fallback }}.pdf"
    }

    private fun beginExport(fileName: String, share: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportName = fileName
            pendingExportShare = share
            requestLegacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exportPdfNow(fileName, share)
    }

    private fun exportPdfNow(fileName: String, share: Boolean) {
        val snapshot = pages.toList()

        setBusy(true, getString(R.string.exporting, 0, snapshot.size))
        io.execute {
            var result: PdfGenerator.ExportResult? = null
            var failure: String? = null
            var cleanupFailure: String? = null
            try {
                result = PdfGenerator.export(this, snapshot.map { it.pageFile }, fileName) { done, total ->
                    main.post { binding.busyLabel.text = getString(R.string.exporting, done, total) }
                }
                if (result?.downloadError == null) {
                    try {
                        ScanStore.clearScanPages(this)
                    } catch (t: Throwable) {
                        Log.e(TAG, "PDF saved but scan session cleanup failed", t)
                        cleanupFailure = t.message ?: t.javaClass.simpleName
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
                failure = t.message ?: t.javaClass.simpleName
            }
            main.post {
                setBusy(false)
                val saved = result
                when {
                    saved != null -> {
                        if (saved.downloadError == null) {
                            clearPageSession()
                            cleanupFailure?.let {
                                toast(getString(R.string.error_session_cleanup, it))
                            }
                            if (share) sharePdf(saved) else showExported(saved)
                        } else {
                            toast(saved.downloadError)
                            if (share) sharePdf(saved) else showShareAvailable(saved)
                        }
                    }
                    else -> toast(getString(R.string.error_export, failure ?: ""))
                }
            }
        }
    }

    private fun showExported(result: PdfGenerator.ExportResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_done)
            .setMessage(
                getString(
                    R.string.export_done_message,
                    result.displayName,
                    result.bytes / 1024,
                    PdfGenerator.folderName()
                )
            )
            .setPositiveButton(R.string.send_pdf) { _, _ -> sharePdf(result) }
            .setNeutralButton(R.string.open) { _, _ -> openPdf(result) }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    private fun showShareAvailable(result: PdfGenerator.ExportResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_done)
            .setMessage(result.downloadError ?: getString(R.string.error_export, "Unknown error"))
            .setPositiveButton(R.string.send_pdf) { _, _ -> sharePdf(result) }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    private fun sharePdf(result: PdfGenerator.ExportResult) {
        try {
            val intent = PdfGenerator.shareIntent(result)
            startActivity(Intent.createChooser(intent, getString(R.string.send_pdf)))
        } catch (t: Exception) {
            Log.w(TAG, "no app available to share PDF", t)
            toast(getString(R.string.error_share_pdf, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun openPdf(result: PdfGenerator.ExportResult) {
        try {
            startActivity(PdfGenerator.openIntent(this, result))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.error_no_viewer))
        }
    }

    private fun clearPageSession() {
        pages.forEach { if (!it.thumb.isRecycled) it.thumb.recycle() }
        pages.clear()
        adapter.clear()
        updateChrome()
    }

    // ------------------------------------------------------------------- chrome

    private fun updateChrome() {
        val count = pages.size
        binding.emptyState.visibility = if (count == 0) View.VISIBLE else View.GONE
        binding.pageList.visibility = if (count == 0) View.GONE else View.VISIBLE
        binding.exportPdf.isEnabled = count > 0
        binding.toolbar.subtitle = resources.getQuantityString(R.plurals.page_count, count, count)
    }

    private fun setBusy(busy: Boolean, label: String? = null) {
        binding.busyOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        binding.takePhoto.isEnabled = !busy
        binding.pickImage.isEnabled = !busy
        binding.exportPdf.isEnabled = !busy && pages.isNotEmpty()
        if (label != null) binding.busyLabel.text = label
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdownNow()
        for (page in pages) {
            if (!page.thumb.isRecycled) page.thumb.recycle()
        }
        pages.clear()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}