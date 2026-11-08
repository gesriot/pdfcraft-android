# PdfCraft Android bridge

Layer L7 UniFFI adapter over `pdfcraft-engine`, called on one Android worker.
Inputs, raster dimensions and imported images are bounded; errors never cross
FFI as Rust panics. Source files are read only; export writes a new staging file.

Existing text editing uses `text_blocks(page, revision)`,
`preview_text_edit(page, block, revision, text, font, size)` and
`apply_text_preview(token)`. The preview is a real rewritten PDF fragment and
leaves dirty state/history untouched. Its opaque token is single-use and bound
to the source revision. Font registration and replacement form one atomic undo
step. Inter Regular is the existing attributed OFL font, embedded on explicit
selection; the original-font option preserves source styling where supported.

At the user's request this Android frontend ignores advisory edit restrictions
once a PDF is opened, without removing encryption. Required and incorrect opening
passwords have distinct errors for the Kotlin password dialog. Test coverage
includes editing/reopening protected copies with the original opening password.

The headless upstream tools for this workflow remain `text_paragraphs`,
`text_edit`, `text_edit_layout`, `text_fonts`, `text_font_import`, `page_render`
and document save/undo/redo. Android-specific geometry/token/permission tests
are in `src/text/tests.rs`; run `sh android/check-local.sh` and
`sh android/check-native-local.sh` from the repository root.

Limits: 64 MiB input; 32 KiB replacement text; 2000 exposed blocks / 1 MiB text per
page; at most 902-pixel preview dimensions; original-font fidelity inherits the
upstream editor. No OCR, arbitrary outlined text editing, hard native-call
cancellation, or process-death draft recovery is implemented.

Android 0.5 exposes `artwork_blocks` and revision-checked `delete_artwork` using
existing `EditPageImage::Delete` / `DeleteContent` commands. Inventory indexes are
validated again before mutation; added images appear once and Form artwork is
explicitly grouped. Removing a placement is ordinary editing, not secure redaction.
Existing automation equivalents are `page_images`, `image_edit`, `object_list`.

`reading_page` exposes Unicode glyphs, displayed coordinates, reading-order lines
and inserted spaces from the existing text layer. `search_page` uses the renderer's
case/word-aware search, returns glyph-index ranges and line rectangles, and proves
truncation with one extra match. Each call checks the document revision; the three-page
native text cache is invalidated by edits/undo. Limits: 50,000 glyphs / 1 MiB per page,
1024 UTF-8 query bytes, 1000 hits per call; Kotlin also caps the whole search at 1000.
Android permits access to an already-opened text layer without an additional advisory
permission gate. Encryption and opening-password requirements remain intact.
Existing headless tools are `text_find` and `text_extract`. Native adapter tests live
in `src/reading/tests.rs` and `src/objects/tests.rs`; Kotlin tests additionally exercise
selection/zoom geometry, stale job cancellation, partial failures, retry after page errors and bounded caches.

## Page organizer and page export (Android 0.6)

`create_document` starts with one A4 page. `move_pages`, `remove_pages`,
`insert_blank_page`, `extract_pages` and `export_page_image` require the current
revision. Move `to` is a zero-based index in the RESULT after removing the selection;
non-adjacent selected pages keep document order, and a no-op adds no history entry.
Selection is bounded to 500, unique in-range pages; delete retains one page.
Extract is non-mutating and retains the requested order in a new unencrypted PDF.
Image export renders current displayed page geometry at 72–300 dpi, bounded to
12 MP / 8192 pixels per side, reports reduced dpi and uses PNG / JPEG quality 92.
Both exporters create a new file only and remove partial private files on IO errors.

The Android frontend separately opts out of advisory page-export permissions at
the user's explicit request. Password authentication is unchanged. Regression tests
check that strict engine defaults and ordinary text-copy checks remain enforced,
source encryption survives, exports work and undo/source history are preserved.

Android's complete user-facing policy is: once a PDF is successfully opened, its
advisory permission flags do not restrict available operations. Reading/search and
clipboard payloads already use an ungated glyph path. Restricted import sources
are opened normally, then only their requested pages are normalized through the
Android extract policy before the generic engine insertion tool. Opening passwords
are still required, source files remain untouched, and target encryption is retained.
`opened_restricted_sources_and_targets_support_normal_android_workflows` exercises
reading/search, text edit/undo, rotate/move/insert/delete, extract/image export,
protected-source insertion and encrypted save/reopen in one regression.

## Visible text fields (Android 0.7)

`preview_text_note(TextNoteInput)` places a FreeText annotation in displayed-page
coordinates, using the bundled Inter Regular for Russian/English. Input includes
page/revision, rectangle, text, 4–144 pt size and a color enum. The embedded font
and /AP are PDF data; no Android text bitmap is exported. The actual PDF preview
must succeed before `apply_text_preview(token)`, sharing the revision-bound,
single-use token path with existing-text edits. Add is one undoable edit, and
preview/errors do not change source bytes/history. Text is limited to 8 KiB,
font coverage is checked, and overflow is rejected instead of silently clipped.
CropBox/Rotate/UserUnit, neighboring pixels, password/encryption, save/reopen and
stale inputs are covered in `notes/tests.rs`. Android annotation re-editing and
comment threads are separate work; physical placement/IME acceptance is pending.

## Word export (0.8)

`begin_word_export(pages, revision)` creates an independent `WordExportJob` over the current
PDF snapshot. `advance()` handles one page, enabling frontend progress/cancellation between
calls. `finish(new_private_path)` writes a complete DOCX without overwriting an existing file
and returns zero-based original page indexes preserved as images. `release()` discards the job.
Calls have the same panic/error guard as the document. Source edits after capture cannot alter
the job. Rust tests cover stale/out-of-range/duplicate selections, early finish, cancellation,
write conflicts, snapshots, raster fallback, source/history preservation, opening-password
requirements and the Android permission opt-out. See engine README for conversion limits.
