Nahla Document Scanner Desktop
==============================

This is a self-contained, offline Windows document scanner. It uses the
installed Microsoft Edge app window when available, with the system browser
as a fallback. No network access, installer, account, or runtime dependency is
required. The complete interface is available in Arabic (default, right-to-left)
and English; use the bright language button in the header to switch languages.

Start the app by double-clicking ..\Nahla-Desktop-Launcher.bat or opening
index.html in a modern browser.

Features
--------
- Import multiple JPG, PNG, WEBP, BMP, and PDF pages; drag files onto the app.
- Open local PDFs with the bundled offline PDF.js renderer. Pages are rendered
  at 2x resolution, and selectable PDF text lines can be replaced, deleted, or
  edited in place. Text on scanned pages can also be selected by dragging.
- Replacements use ink-only paper-texture inpainting rather than a solid cover
  box. Add new text with adjustable size and bold styling.
- Reorder, rotate, select, and delete pages.
- Adjust four crop corners, use the automatic edge estimate, and perspective
  rectify a page with natural camera colors.
- Extract black transparent signatures from photos with an ink-boldness
  control, draw signatures, save them in a local browser library, and insert
  them onto pages.
- Add outline, whiteout, oval, and arrow annotations. Click empty page space
  to deselect and lock; select a mark to move or resize it with handles or the
  bottom size and line-thickness sliders.
- Export a named, JPEG-compressed, full-bleed PDF at approximately 180 DPI.
  The browser prompts for a save location where supported.
- Clear the current document to start a new one.

Signatures are stored in the browser's local storage on this Windows account.
Pages remain in memory until the app is closed or the document is cleared.
PDF.js and its worker are bundled in `vendor/`; PDF processing runs in the main
thread so the app works from `file://` without network access or worker CORS.
Imported and exported pages are rasterized; edited text is visually embedded
in the exported page image and is not retained as selectable PDF text.
