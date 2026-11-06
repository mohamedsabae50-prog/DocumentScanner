Nahla Document Scanner Desktop
==============================

This is a self-contained Windows document scanner. It uses the installed
Microsoft Edge app window when available, with the system browser as a
fallback. No network access, installer, account, or runtime dependency is
required; optional Google Fonts use local system fallbacks when offline. The
complete interface is available in Arabic (default, right-to-left) and English;
use the bright language button in the header to switch languages.

Start the app by double-clicking `Nahla-Desktop.exe`. Keep the executable,
`index.html`, `nahla.ico`, and the `vendor` folder together when copying the
standalone NahlaDesktop folder to another Windows PC. The launcher opens a
standalone Edge, Chrome, or Brave app window when available, with the system
browser as a fallback. Run `Nahla-Desktop.exe --create-shortcut` to create a
Desktop shortcut, or use `--shortcut` to create it if missing and launch.

Features
--------
- Import multiple JPG, PNG, WEBP, BMP, and PDF pages; drag files onto the app.
- Open local PDFs with bundled PDF.js. Page 1 renders at 2x immediately;
  remaining pages render at 2x with selectable text on demand, while lightweight
  thumbnails fill in asynchronously. Print and PDF export load all pages first.
  Selectable PDF text lines can be replaced, deleted, or edited in place.
- Click selectable text to edit it directly on the page, or drag over scanned
  text to replace it. The on-page cursor follows the original text position;
  font family, integer size, bold, alignment, and paper-color/texture erasure
  controls update the text live. Click outside, press Enter/Escape, or delete
  to finish.
- Zoom from 50% to 400%, fit the page or width, and pan with Space-drag, the
  middle mouse button, or the hand toggle. Ctrl+wheel zooms around the pointer.
- Reorder, rotate, select, and delete pages.
- Adjust four crop corners, use the automatic edge estimate, and perspective
  rectify a page with natural camera colors.
- Extract black transparent signatures from photos with an ink-boldness
  control, draw signatures with adjustable pen thickness, save them in a local
  browser library, and insert them onto pages. Selected signatures can be
  thickened or thinned live without adding a background.
- Add outline, whiteout, oval, and arrow annotations. Drag marks smoothly with
  screen-sized handles; signature corner resizing preserves its aspect ratio.
- Export a named, JPEG-compressed, full-bleed PDF at approximately 180 DPI, or
  print the composited pages directly with the native Windows print dialog.
  The browser prompts for an export save location where supported.
- Clear the current document to start a new one.

Signatures are stored in the browser's local storage on this Windows account.
Pages remain in memory until the app is closed or the document is cleared.
PDF.js and its worker are bundled in `vendor/`; PDF processing runs in the main
thread so the app works from `file://` without network access or worker CORS.
Imported and exported pages are rasterized; edited text is visually embedded
in the exported page image and is not retained as selectable PDF text.
