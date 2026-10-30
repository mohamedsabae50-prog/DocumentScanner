# Nahla — Document Scanner

A lightweight, ad-free document scanner for Android, engineered for **API 24+ / 2 GB RAM**
devices. No OpenCV, no ML models, no ad SDKs, no analytics — only the Android framework.

## Why it stays small and safe

| Concern | Approach | Cost |
| --- | --- | --- |
| APK size | Framework graphics + 5 AndroidX libs | ~1.7 MB |
| Resolution | Long edge lands on exactly **2560 px** (~300 DPI across A4) | — |
| Colour depth | ARGB_8888 everywhere | 4 bytes/px |
| Perspective warp | `Paint(ANTI_ALIAS \| FILTER_BITMAP \| DITHER)` | sharp skewed text |
| Page storage | Lossless PNG preserves camera colors and keeps placed signatures sharp | — |
| PDF export | Pages streamed one at a time: decode → draw → `recycle()` | one page-sized bitmap at peak |
| Threads | Single background executor; no coroutines, no Rx | smaller runtime surface |

### Natural-color document pages

Cropping only applies the high-resolution perspective warp. It does not change
brightness, exposure, contrast, or color, and does not divide by a background
estimate, apply a gamma curve, or clip whites. Cropped pages are saved as
lossless `ARGB_8888` PNGs; thumbnails and PDFs use those same natural pixels.

### Decoding without the `inSampleSize` trap

`inSampleSize` can only halve, so decoding straight to a strict bound is
disastrous for detail: a 3250 px photo lands on sample 4 → **812 px**. So
`ImageProcessor.decode` works in two steps: pick the largest power-of-two sample
that still leaves **≥ 2560 px** of real detail, then rescale the result to
**exactly 2560 px** with one bilinear `createScaledBitmap`, recycling the
intermediate immediately. Images already at or below the cap are never upscaled.

### Auto-crop v2

Detection runs on a 360 px grid: luminance → 5×5 Gaussian → morphological
closing (so text strokes stop punching holes in the sheet) → Sobel gradient →
a centre-seeded colour mask built from the mean/std of the middle quarter →
largest connected component → hole filling → contour → Ramer-Douglas-Peucker →
convex hull → largest inscribed quadrilateral → each corner snapped onto the
strongest local gradient. Anything that fails validation (area, convexity,
bounds) falls back to a safe 8 % inset. While dragging a corner, a 2.5×
loupe appears in the top corner so the handle can be placed on the pixel.

Perspective correction uses the native `Matrix.setPolyToPoly` — a real
quadrilateral rectification with zero extra dependencies. If a drag produces a
degenerate quad, the code falls back to an axis-aligned bounding-box crop rather
than emitting a black page.

## Features

- Capture with the system camera, or pick an existing photo (EXIF rotation is honoured).
- 4-point crop overlay: drag corners individually, drag the body to reposition, clamped to the
  photo, with rule-of-thirds guides and dimmed surroundings.
- Rotate 90° clockwise — the crop quad is rotated with the image, not reset.
- Natural camera colors and lighting retained in crops, page thumbnails, and PDF export.
- Two-stage editing: correct the four crop corners, then preview the real warped page
  with live signatures and editable vector shapes before saving.
- Keep a reusable signature library extracted automatically from camera/gallery photos
  or drawn in blue, black, or red ink. Signatures are placed directly on the live page;
  no placement popup is used. The optional manual crop is available from the extraction
  preview.
- Add outlined or filled rectangles, ovals, and arrows with color and stroke controls.
  Tap empty page space to deselect and lock marks; tap a mark to edit it again. The
  selected mark has a fine size slider, delete/lock controls, and touch handles.
- Auto document-edge detection snaps the corners to the paper the moment the crop screen
  opens and insets the detected boundary by 1.5 % to trim table edges and outer shadows.
- A 2.5x loupe follows the corner being dragged so the handle can land on the pixel.
- Multi-page list with thumbnails; tap a page to re-crop it, or delete it.
- A4 multi-page PDF via `android.graphics.pdf.PdfDocument`, with a custom filename, saved to
  `Downloads/DocScanner`. Choose Save Only or Save & Send; sharing uses a named
  `FileProvider` cache copy and the Android share sheet.
- Signatures and shapes are composited into each full-resolution `page.png`, so page
  thumbnails and PDF exports match the live editor. Reopening a page retains its editable
  signature and shape placements.
- A successful PDF export clears the current scanned-page session while preserving the
  saved signature library and the cached share copy.

## Permissions

| Permission | Why |
| --- | --- |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) | Writing the PDF to the public Downloads folder on Android 9 and older. Not requested on API 29+, where MediaStore needs no permission. |

`CAMERA` is deliberately **not** declared. Because the app writes the capture straight into its
own cache file through a `FileProvider` URI, no camera permission is needed — and declaring it
would force one.

## Build

Open the folder in Android Studio (Hedgehog or newer), or:

```bash
# point the build at your SDK
echo "sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk" > local.properties

gradle :app:assembleDebug
gradle :app:assembleRelease   # minified, resource shrunk, signed -> outputs/apk/release/app-release.apk
```

On Windows, `.\tools\verify_release.ps1` assembles the release and checks the export/share
and signature-extraction regression conditions plus the APK signing certificate.

`release` is signed with the local `~/.android/debug.keystore`, so the output installs on
any device out of the box. That key is a throwaway: replace `signingConfigs.release` with
your own keystore (kept out of version control) before publishing.

Toolchain: AGP 8.13.2, Gradle 9.1, Kotlin 2.3.0, JDK 17, `compileSdk 35`, `minSdk 24`,
`targetSdk 34`.

## Source map

| File | Responsibility |
| --- | --- |
| `ImageProcessor.kt` | Sampled decode, EXIF rotation, natural-color perspective crop, PNG encode |
| `CropOverlayView.kt` | 4-corner touch crop overlay |
| `PdfGenerator.kt` | `PdfDocument` page painting + `MediaStore` publish |
| `MainActivity.kt` | Capture / pick / gallery flow, page list, PDF export |
| `EditActivity.kt` | Two-stage crop/editor workflow, signature library, and page saving |
| `PageAnnotationView.kt` | Live signature/shape canvas, editing handles, and annotation rendering |
| `ScanStore.kt` | Cache layout (`cacheDir/scan_<id>/source.img`, `page.png`, page signatures) and persistent signature library |
| `ScanAdapter.kt`, `ScanPage.kt` | Page list, page model and natural-color thumbnail |

## Notes and limits

- Working files live in `cacheDir` and are cleared after a successful export. Pages are not
  restored after the process is killed — the cache is intentionally disposable.
- `PdfGenerator` refuses documents over 60 pages, because native page objects are retained until
  the document is closed.
- The A4 page (595 × 842 pt) leaves a 20 pt margin; each scan is fitted and centred.