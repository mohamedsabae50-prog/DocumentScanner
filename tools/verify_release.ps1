$ErrorActionPreference = "Stop"

$project = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $project
$gradleRoot = Join-Path $HOME ".gradle\wrapper\dists\gradle-9.1.0-all"
$gradle = Get-ChildItem -LiteralPath $gradleRoot -Filter "gradle.bat" -Recurse |
    Select-Object -First 1
if ($null -eq $gradle) {
    throw "Gradle 9.1 wrapper distribution was not found under $gradleRoot"
}

Push-Location $project
try {
    & $gradle.FullName --no-daemon :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle release build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$pdfSource = Get-Content -LiteralPath (Join-Path $project "app\src\main\java\com\nahla\docscanner\PdfGenerator.kt") -Raw
$mainSource = Get-Content -LiteralPath (Join-Path $project "app\src\main\java\com\nahla\docscanner\MainActivity.kt") -Raw
$storeSource = Get-Content -LiteralPath (Join-Path $project "app\src\main\java\com\nahla\docscanner\ScanStore.kt") -Raw
$signatureSource = Get-Content -LiteralPath (Join-Path $project "app\src\main\java\com\nahla\docscanner\SignaturePhotoTools.kt") -Raw
$editSource = Get-Content -LiteralPath (Join-Path $project "app\src\main\java\com\nahla\docscanner\EditActivity.kt") -Raw
$pathsXml = [xml](Get-Content -LiteralPath (Join-Path $project "app\src\main\res\xml\file_paths.xml") -Raw)

function Assert-Contains([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw "Regression check failed: $Message"
    }
}

Assert-Contains $pdfSource 'File\(context\.cacheDir,\s*"exports"\)' "PDF is written under cacheDir/exports"
Assert-Contains $pdfSource 'build\(pages,\s*cachePdfFile,\s*onProgress\)' "PDF is generated directly to its share cache file"
Assert-Contains $pdfSource 'cachePdfFile\.length\(\)\s*<=\s*0L' "empty generated PDFs are rejected"
Assert-Contains $pdfSource 'Downloads\.getContentUri\(MediaStore\.VOLUME_EXTERNAL_PRIMARY\)' "primary Downloads volume is used"
Assert-Contains $pdfSource 'runCatching\s*\{\s*saveWithMediaStore' "MediaStore has a fallback path"
Assert-Contains $pdfSource 'ClipData\.newRawUri' "share intents set a raw URI ClipData"
Assert-Contains $pdfSource 'Intent\.EXTRA_STREAM' "share intents attach the PDF stream"

Assert-Contains $mainSource 'PdfGenerator\.export\(this,\s*snapshot\.map\s*\{\s*it\.pageFile\s*\},\s*fileName\)' "MainActivity uses the complete export path"
Assert-Contains $mainSource '(?s)result = PdfGenerator\.export\(this,.*?if\s*\(result\?\.downloadError\s*==\s*null\)\s*\{\s*try\s*\{\s*ScanStore\.clearScanPages\(this\)' "page files clear only after successful publication"
Assert-Contains $mainSource 'setOnClickListener\s*\{\s*val name = sanitizePdfName\(input\.text\.toString\(\), defaultName\)\s*dialog\.dismiss\(\)\s*beginExport\(name,\s*true\)' "Save & Send button invokes export"
Assert-Contains $mainSource 'setOnClickListener\s*\{\s*val name = sanitizePdfName\(input\.text\.toString\(\), defaultName\)\s*dialog\.dismiss\(\)\s*beginExport\(name,\s*false\)' "Save Only button invokes export"
Assert-Contains $mainSource 'startActivity\(Intent\.createChooser\(intent,\s*getString\(R\.string\.send_pdf\)\)\)' "sharing chooser is launched"

foreach ($entry in @(
    @{ Name = "cache"; Path = "." },
    @{ Name = "files"; Path = "." },
    @{ Name = "external"; Path = "." }
)) {
    $matchingPath = $pathsXml.paths.ChildNodes | Where-Object {
        $_.LocalName -eq "cache-path" -or $_.LocalName -eq "files-path" -or $_.LocalName -eq "external-path"
    } | Where-Object { $_.GetAttribute("name") -eq $entry.Name -and $_.GetAttribute("path") -eq $entry.Path }
    if ($null -eq $matchingPath) {
        throw "Regression check failed: FileProvider $($entry.Name) path is missing"
    }
}

Assert-Contains $storeSource '(?s)fun clearScanPages\(context: Context\).*?name\.startsWith\(DIR_PREFIX\)' "cleanup targets scan session folders only"
Assert-Contains $signatureSource 'BACKGROUND_TILE_SIZE = 96' "paper estimation uses broad regional tiles"
Assert-Contains $signatureSource 'sampleCount \* 0\.8f' "paper level uses an 80th percentile"
Assert-Contains $signatureSource '0\.55f \* 255f' "original ink RGB is darkened"
Assert-Contains $signatureSource '0xFF002FA7' "blue ink uses deep vivid blue"
Assert-Contains $signatureSource '0xFF050505' "black ink uses jet black"
Assert-Contains $signatureSource 'alphaValues\[ny \* width \+ nx\] \* 0\.85f' "signature strokes receive alpha dilation"
Assert-Contains $signatureSource 'SignatureInkMode\.NATURAL' "natural crop mode exists"
Assert-Contains $editSource 'Color\.rgb\(248,\s*249,\s*250\)' "signature preview uses warm paper"

$apk = Join-Path $project "app\build\outputs\apk\release\app-release.apk"
if (!(Test-Path -LiteralPath $apk) -or (Get-Item -LiteralPath $apk).Length -le 0) {
    throw "Release APK is missing or empty: $apk"
}
$apksigner = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\35.0.0\apksigner.bat"
if (!(Test-Path -LiteralPath $apksigner)) {
    throw "Android apksigner was not found: $apksigner"
}
$certificate = & $apksigner verify --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed: $certificate"
}
$fingerprint = ($certificate | Select-String "Signer #1 certificate SHA-256 digest:").ToString().Split(":")[-1].Trim()
$expected = "625ff9e2151afce3c2974ba4aa49a19b381b22855180cb2e6f2b15101a509662"
if ($fingerprint -ne $expected) {
    throw "Unexpected APK signing certificate: $fingerprint"
}

Write-Output "PASS: release build, export/share regression checks, signature extraction checks, and APK certificate."
