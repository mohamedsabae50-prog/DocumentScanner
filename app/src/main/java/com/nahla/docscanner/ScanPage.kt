package com.nahla.docscanner

import android.graphics.Bitmap
import java.io.File

/**
 * One captured document.
 *
 * [thumb] is a small natural-color preview (~256 px); the full-size lossless
 * PNG is the source used for PDF export.
 */
data class ScanPage(
    val id: String,
    val pageFile: File,
    val thumb: Bitmap
)