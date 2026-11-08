# pdfcraft-export

Pure writers over `Page`/`Block`/`Image`: DOCX, HTML, RTF and PPTX. No PDF parser or UI.
`docx` keeps the existing automatic positioned-layout fallback. `docx_reflow` emits
paragraphs, inferred borderless tables and inline pictures, without legacy VML text boxes.
It uses Arial as a requested default (readers may substitute it), explicit OOXML breaks/tabs,
per-column cell widths and section page sizes. Inline pictures fit both section dimensions.
No font binary, external image link, macro or application-specific dependency is packaged.

The bounded PDF-to-DOCX caller is `pdfcraft_engine::word_export::WordExport`.
It limits text/media/page counts, verifies and normalizes encoded images, and reports pages
rendered as pictures when extraction cannot produce reliable editable content. Low-level
writers alone do not establish semantic correctness, reading order or source fidelity.

Tests: `cargo test -p pdfcraft-export --lib`. Android/reflow E2E evidence lives in
`crates/automation/tests/automation.rs::word_reflow_exports_current_unicode_and_selected_pages_without_mutating_pdf`.
