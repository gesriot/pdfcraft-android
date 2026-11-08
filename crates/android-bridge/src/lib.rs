//! Android's synchronous binding surface. Call it on a Kotlin worker dispatcher.
//! Document changes go through Session; rendered pixels use its display renderer.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use pdfcraft_engine::{DocId, Edit, FieldConflicts, Session};
use pdfcraft_render::{RenderRequest, Tile, device_pixels};

mod word;
pub use word::WordExportJob;
mod notes;
mod objects;
mod pages;
pub use notes::{NoteColor, TextNoteInput};
pub use pages::{ExportedPageImage, PageImageFormat, create_document};
mod reading;
mod text;
pub use objects::{ArtworkBlock, ArtworkKind, ArtworkPage};
pub use reading::{ReadingGlyph, ReadingPage, SearchHit, SearchPage};
pub use text::{EditableTextBlock, TextEditPreview, TextFont, TextPage, TextRect};

uniffi::setup_scaffolding!();

/// Conservative prototype limits; raise only after device measurements.
const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_RENDER_PIXELS: f64 = 4_000_000.0;
const MAX_RENDER_SIDE: u32 = 2048;
const RENDER_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_IMPORT_PIXELS: u64 = 24_000_000;
const MAX_IMPORT_PAGES: usize = 500;
const MAX_TILE_SIDE: u32 = 1024;
const MAX_TILE_EXTENT: f32 = 16_000_000.0;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MobileError {
    #[error("Введите пароль для открытия PDF")]
    PasswordRequired,
    #[error("Неверный пароль")]
    WrongPassword,
    // `message` is reserved by Kotlin's Throwable / generated UniFFI exceptions.
    #[error("{detail}")]
    InvalidInput { detail: String },
    #[error("{detail}")]
    Engine { detail: String },
    #[error("{detail}")]
    Io { detail: String },
    #[error("the document is closed")]
    Closed,
    #[error("the document worker failed; close and reopen the document")]
    WorkerFailed,
}

#[derive(uniffi::Record)]
pub struct PageGeometry {
    /// Displayed CropBox in points, with rotation and UserUnit already applied.
    pub width: f32,
    pub height: f32,
}

#[derive(uniffi::Record)]
pub struct DocumentInfo {
    pub name: String,
    pub page_count: u32,
    pub dirty: bool,
    pub can_undo: bool,
    pub can_redo: bool,
    pub revision: u64,
    pub can_edit_text: bool,
    pub encrypted: bool,
    pub warnings: Vec<String>,
    pub pages: Vec<PageGeometry>,
}

#[derive(uniffi::Record)]
pub struct PageImage {
    pub width: u32,
    pub height: u32,
    /// Premultiplied RGBA, ready for Bitmap.copyPixelsFromBuffer on Android.
    pub rgba: Vec<u8>,
    pub revision: u64,
    pub warnings: Vec<String>,
}

struct State {
    session: Session,
    id: DocId,
    pending_text: Option<text::PendingText>,
    preview_serial: u64,
    reading_cache: reading::ReadingCache,
}

#[derive(uniffi::Object)]
pub struct MobileDocument {
    state: Mutex<Option<State>>,
}

fn engine_error(error: impl std::fmt::Display) -> MobileError {
    MobileError::Engine { detail: error.to_string() }
}

fn io_error(error: std::io::Error) -> MobileError {
    MobileError::Io { detail: error.to_string() }
}

fn invalid(message: &str) -> MobileError {
    MobileError::InvalidInput { detail: message.into() }
}

fn read_input(path: &str) -> Result<Vec<u8>, MobileError> {
    let file = File::open(path).map_err(io_error)?;
    if file.metadata().map_err(io_error)?.len() > MAX_FILE_BYTES {
        return Err(invalid("Поддерживаются файлы до 64 МиБ"));
    }
    let mut bytes = Vec::new();
    file.take(MAX_FILE_BYTES + 1).read_to_end(&mut bytes).map_err(io_error)?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err(invalid("Поддерживаются файлы до 64 МиБ"));
    }
    Ok(bytes)
}

fn render_request(state: &State, request: RenderRequest) -> Result<PageImage, MobileError> {
    let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
    doc.renderer.set_queue(vec![request]);
    let start = Instant::now();
    loop {
        if let Some(image) = doc.renderer.try_recv() {
            if image.request != request {
                continue;
            }
            if let Some(error) = image.error {
                return Err(engine_error(error));
            }
            return Ok(PageImage {
                width: image.width,
                height: image.height,
                rgba: image.rgba.to_vec(),
                revision: request.tag,
                warnings: image.warnings.iter().map(|w| format!("{w:?}")).collect(),
            });
        }
        if start.elapsed() >= RENDER_TIMEOUT {
            doc.renderer.set_queue(Vec::new());
            return Err(engine_error("rendering exceeded 30 seconds"));
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

impl MobileDocument {
    fn from_bytes(name: String, bytes: Arc<Vec<u8>>, password: Option<&str>) -> Result<Arc<Self>, MobileError> {
        if bytes.len() as u64 > MAX_FILE_BYTES {
            return Err(invalid("this prototype supports PDFs up to 64 MiB"));
        }
        let mut session = Session::new();
        let id = session.open(name, None, bytes, password).map_err(|error| match error {
            pdfcraft_render::OpenError::NeedsPassword => MobileError::PasswordRequired,
            pdfcraft_render::OpenError::WrongPassword => MobileError::WrongPassword,
            other => engine_error(other),
        })?;
        // User-requested Android policy: opening/decryption is enough to edit the PDF.
        session.set_edit_permission_enforcement(id, false).map_err(engine_error)?;
        session.set_page_export_permission_enforcement(id, false).map_err(engine_error)?;
        Ok(Arc::new(Self {
            state: Mutex::new(Some(State { session, id, pending_text: None, preview_serial: 0, reading_cache: Default::default() })),
        }))
    }

    fn with_state<T>(&self, op: impl FnOnce(&mut State) -> Result<T, MobileError>) -> Result<T, MobileError> {
        catch_unwind(AssertUnwindSafe(|| {
            let mut slot = self.state.lock().map_err(|_| MobileError::WorkerFailed)?;
            op(slot.as_mut().ok_or(MobileError::Closed)?)
        }))
        .unwrap_or(Err(MobileError::WorkerFailed))
    }
}

#[uniffi::export]
pub fn open_document(path: String, password: Option<String>) -> Result<Arc<MobileDocument>, MobileError> {
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = read_input(&path)?;
        let name = std::path::Path::new(&path).file_name().and_then(|s| s.to_str()).unwrap_or("document.pdf").to_owned();
        MobileDocument::from_bytes(name, Arc::new(bytes), password.as_deref())
    }))
    .unwrap_or(Err(MobileError::WorkerFailed))
}

#[uniffi::export]
impl MobileDocument {
    pub fn info(&self) -> Result<DocumentInfo, MobileError> {
        self.with_state(|state| {
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            Ok(DocumentInfo {
                name: doc.name.clone(),
                page_count: u32::try_from(doc.info.pages.len()).map_err(|_| invalid("too many pages"))?,
                dirty: doc.dirty,
                can_undo: doc.can_undo().is_some(),
                can_redo: doc.can_redo().is_some(),
                revision: doc.edit_generation(),
                can_edit_text: doc.allows_modification(),
                encrypted: doc.info.encrypted,
                warnings: doc.info.warnings.clone(),
                pages: doc.info.pages.iter().map(|p| PageGeometry { width: p.width, height: p.height }).collect(),
            })
        })
    }

    pub fn render_page(&self, page: u32, width: u32) -> Result<PageImage, MobileError> {
        if width == 0 || width > MAX_RENDER_SIDE {
            return Err(invalid("render width must be between 1 and 2048 pixels"));
        }
        self.with_state(|state| {
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            let geometry = doc.info.pages.get(page as usize).ok_or_else(|| invalid("page is outside the document"))?;
            if !geometry.width.is_finite() || !geometry.height.is_finite() || geometry.width <= 0.0 || geometry.height <= 0.0 {
                return Err(invalid("page dimensions are invalid"));
            }
            let scale = (f64::from(width) / f64::from(geometry.width))
                .min(f64::from(MAX_RENDER_SIDE) / f64::from(geometry.height))
                .min((MAX_RENDER_PIXELS / (f64::from(geometry.width) * f64::from(geometry.height))).sqrt());
            let request = RenderRequest { page: page as usize, scale: scale as f32, tag: doc.edit_generation(), ..Default::default() };
            render_request(state, request)
        })
    }

    /// Rasterize a bounded region at actual display resolution, never upscale a preview.
    /// Coordinates and dimensions are device pixels at `scale` pixels per PDF point.
    #[allow(clippy::too_many_arguments)]
    pub fn render_tile(&self, page: u32, scale: f32, x: u32, y: u32, width: u32, height: u32, revision: u64) -> Result<PageImage, MobileError> {
        if !scale.is_finite() || !(0.01..=256.0).contains(&scale) || width == 0 || height == 0 || width > MAX_TILE_SIDE || height > MAX_TILE_SIDE {
            return Err(invalid("invalid tile scale or dimensions (maximum 1024×1024)"));
        }
        self.with_state(|state| {
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            if revision != doc.edit_generation() {
                return Err(invalid("the document changed; discard this render request"));
            }
            let geometry = doc.info.pages.get(page as usize).ok_or_else(|| invalid("page is outside the document"))?;
            let (w, h) = (geometry.width, geometry.height);
            if !w.is_finite() || !h.is_finite() || w <= 0.0 || h <= 0.0 || w * scale > MAX_TILE_EXTENT || h * scale > MAX_TILE_EXTENT {
                return Err(invalid("page dimensions exceed the tile coordinate limits"));
            }
            let right = x.checked_add(width).ok_or_else(|| invalid("tile coordinates overflow"))?;
            let bottom = y.checked_add(height).ok_or_else(|| invalid("tile coordinates overflow"))?;
            if right > device_pixels(w, scale) || bottom > device_pixels(h, scale) {
                return Err(invalid("tile is outside the page"));
            }
            render_request(
                state,
                RenderRequest { page: page as usize, scale, tile: Some(Tile { x, y, w: width, h: height }), tag: revision, ..Default::default() },
            )
        })
    }

    pub fn rotate_page(&self, page: u32) -> Result<(), MobileError> {
        self.with_state(|state| {
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            if doc.info.pages.get(page as usize).is_none() {
                return Err(invalid("page is outside the document"));
            }
            state.session.apply(state.id, Edit::RotatePages { pages: vec![page as usize], degrees: 90 }).map_err(engine_error)?;
            Ok(())
        })
    }

    /// One undoable operation. Refuse empty, invalid or all-page selections.
    pub fn delete_pages(&self, pages: Vec<u32>) -> Result<(), MobileError> {
        self.with_state(|state| {
            let count = state.session.get(state.id).ok_or(MobileError::Closed)?.info.pages.len();
            let mut pages: Vec<usize> = pages.into_iter().map(|p| p as usize).collect();
            pages.sort_unstable();
            pages.dedup();
            if pages.is_empty() || pages.iter().any(|p| *p >= count) {
                return Err(invalid("Выберите существующие страницы"));
            }
            if pages.len() == count {
                return Err(invalid("В документе должна остаться хотя бы одна страница"));
            }
            state.session.apply(state.id, Edit::DeletePages { pages }).map_err(engine_error)?;
            Ok(())
        })
    }

    /// Insert PDF pages or one PNG/JPEG page, atomically through the undo history.
    /// `at` is a zero-based gap; `pages=None` imports every source PDF page.
    pub fn insert_file(&self, path: String, name: String, at: u32, pages: Option<Vec<u32>>) -> Result<u32, MobileError> {
        self.with_state(|state| {
            let count = state.session.get(state.id).ok_or(MobileError::Closed)?.info.pages.len();
            if at as usize > count {
                return Err(invalid("Место вставки находится за пределами документа"));
            }
            let input = read_input(&path)?;
            let name: String = name.chars().take(200).collect();
            let is_image = input.starts_with(b"\x89PNG\r\n\x1a\n") || input.starts_with(&[0xff, 0xd8, 0xff]);
            let mut selected = pages.map(|p| p.into_iter().map(|v| v as usize).collect::<Vec<_>>());
            let mut bytes = if is_image {
                if selected.is_some() {
                    return Err(invalid("Выбор страниц применяется только к PDF"));
                }
                let (width, height) = image::ImageReader::new(std::io::Cursor::new(&input))
                    .with_guessed_format()
                    .map_err(io_error)?
                    .into_dimensions()
                    .map_err(engine_error)?;
                if width == 0 || height == 0 || u64::from(width) * u64::from(height) > MAX_IMPORT_PIXELS {
                    return Err(invalid("Изображение должно содержать от 1 до 24 миллионов пикселей"));
                }
                state.session.create_from_images(&[(name.clone(), input)]).map_err(engine_error)?
            } else {
                if !input.windows(5).take(1024).any(|w| w == b"%PDF-") {
                    return Err(invalid("Поддерживаются PDF, PNG и JPG"));
                }
                Arc::new(input)
            };
            if !is_image {
                // Android policy applies to an import source after authentication too.
                // The generic engine insert tool enforces source assembly permissions;
                // normalize only a restricted source through our authorized extract path.
                // The target keeps its own encryption; the original source is read-only.
                let source = Self::from_bytes(name.clone(), bytes.clone(), None)?;
                let count = source.info()?.page_count as usize;
                let imported = selected.as_ref().map_or(count, Vec::len);
                if imported == 0 || imported > MAX_IMPORT_PAGES {
                    return Err(invalid("За один раз можно вставить от 1 до 500 страниц"));
                }
                if selected.as_ref().is_some_and(|p| p.iter().any(|v| *v >= count)) {
                    return Err(invalid("Выбранной страницы нет в исходном PDF"));
                }
                let normalized = source.with_state(|source_state| {
                    let restricted = source_state
                        .session
                        .get(source_state.id)
                        .ok_or(MobileError::Closed)?
                        .permissions()
                        .is_some_and(|permissions| !permissions.assemble());
                    if !restricted {
                        return Ok(None);
                    }
                    let chosen = selected.clone().unwrap_or_else(|| (0..count).collect());
                    source_state.session.extract(source_state.id, &chosen).map(Some).map_err(engine_error)
                })?;
                if let Some(normalized) = normalized {
                    bytes = normalized;
                    selected = None; // Only the requested pages remain in the normalized source.
                }
            }
            let source_count = state.session.page_count_of(&name, &bytes).map_err(engine_error)?;
            let inserted = selected.as_ref().map_or(source_count, Vec::len);
            if inserted == 0 || inserted > MAX_IMPORT_PAGES {
                return Err(invalid("За один раз можно вставить от 1 до 500 страниц"));
            }
            if selected.as_ref().is_some_and(|p| p.iter().any(|v| *v >= source_count)) {
                return Err(invalid("Выбранной страницы нет в исходном PDF"));
            }
            state
                .session
                .apply(state.id, Edit::InsertPagesFrom { name, bytes, pages: selected, at: at as usize, field_conflicts: FieldConflicts::Rename })
                .map_err(engine_error)?;
            Ok(inserted as u32)
        })
    }

    pub fn undo(&self) -> Result<(), MobileError> {
        self.with_state(|state| state.session.undo(state.id).map(|_| ()).map_err(engine_error))
    }

    pub fn redo(&self) -> Result<(), MobileError> {
        self.with_state(|state| state.session.redo(state.id).map(|_| ()).map_err(engine_error))
    }

    /// Write only to a NEW private staging file. Kotlin exports it through SAF.
    /// An export does not mark the source saved: its URI has not been replaced.
    pub fn export_copy(&self, path: String) -> Result<(), MobileError> {
        self.with_state(|state| {
            let bytes = state.session.save_bytes(state.id).map_err(engine_error)?;
            let mut file = OpenOptions::new().write(true).create_new(true).open(&path).map_err(io_error)?;
            let written = file.write_all(&bytes).and_then(|_| file.sync_all());
            drop(file);
            if let Err(error) = written {
                let _ = std::fs::remove_file(&path);
                return Err(io_error(error));
            }
            Ok(())
        })
    }

    pub fn release(&self) {
        // Releasing a poisoned session is still allowed; it cannot be used again.
        // Named release because UniFFI reserves Kotlin's AutoCloseable.close().
        let mut slot = self.state.lock().unwrap_or_else(|e| e.into_inner());
        *slot = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blank() -> Arc<MobileDocument> {
        let bytes = Session::new().create_blank(200.0, 300.0, 2).unwrap();
        MobileDocument::from_bytes("Русский документ.pdf".into(), bytes, None).unwrap()
    }

    #[test]
    fn rotation_undo_redo_and_render_geometry() {
        let doc = blank();
        let info = doc.info().unwrap();
        assert_eq!(info.name, "Русский документ.pdf");
        assert_eq!(info.page_count, 2);
        let before = doc.render_page(0, 200).unwrap();
        assert_eq!((before.width, before.height), (200, 300));
        doc.rotate_page(0).unwrap();
        let after = doc.render_page(0, 300).unwrap();
        assert_eq!((after.width, after.height), (300, 200));
        assert_eq!(after.rgba.len(), 300 * 200 * 4);
        assert!(after.revision > before.revision);
        assert!(doc.info().unwrap().can_undo);
        doc.undo().unwrap();
        assert_eq!(doc.render_page(0, 200).unwrap().height, 300);
        assert!(doc.info().unwrap().can_redo);
        doc.redo().unwrap();
        assert_eq!(doc.render_page(0, 300).unwrap().height, 200);
    }

    #[test]
    fn invalid_requests_and_close_are_errors() {
        let doc = blank();
        assert!(doc.render_page(0, 0).is_err());
        assert!(doc.render_page(0, 4096).is_err());
        assert!(doc.render_page(u32::MAX, 200).is_err());
        assert!(doc.rotate_page(u32::MAX).is_err());
        doc.release();
        doc.release();
        assert!(matches!(doc.info(), Err(MobileError::Closed)));
        assert!(matches!(doc.render_page(0, 200), Err(MobileError::Closed)));
    }

    #[test]
    fn export_preserves_source_and_reopens_rotated() {
        let doc = blank();
        doc.rotate_page(0).unwrap();
        let dir = std::env::temp_dir().join(format!("pdfcraft-android-export-{}", std::process::id()));
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join("результат.pdf").to_str().unwrap().to_owned();
        doc.export_copy(path.clone()).unwrap();
        assert!(doc.export_copy(path.clone()).is_err(), "must never overwrite");
        let reopened = open_document(path, None).unwrap();
        assert_eq!(reopened.info().unwrap().page_count, 2);
        assert_eq!(reopened.render_page(0, 300).unwrap().height, 200);
        reopened.release();
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn malformed_input_is_an_error() {
        assert!(MobileDocument::from_bytes("bad.pdf".into(), Arc::new(b"not a PDF".to_vec()), None).is_err());
    }

    fn input_file(bytes: &[u8]) -> tempfile::NamedTempFile {
        let mut file = tempfile::NamedTempFile::new().unwrap();
        file.write_all(bytes).unwrap();
        file
    }

    #[test]
    fn delete_selection_undo_redo_and_reopen() {
        let doc = blank();
        doc.rotate_page(1).unwrap();
        doc.delete_pages(vec![0, 0]).unwrap();
        assert_eq!(doc.info().unwrap().page_count, 1);
        assert_eq!(doc.render_page(0, 300).unwrap().height, 200);
        doc.undo().unwrap();
        assert_eq!(doc.info().unwrap().page_count, 2);
        doc.redo().unwrap();
        let bytes = doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
        let reopened = MobileDocument::from_bytes("deleted.pdf".into(), bytes, None).unwrap();
        assert_eq!(reopened.info().unwrap().page_count, 1);
        assert_eq!(reopened.render_page(0, 300).unwrap().height, 200);
        let revision = doc.info().unwrap().revision;
        assert!(doc.delete_pages(vec![0]).is_err());
        assert!(doc.delete_pages(vec![]).is_err());
        assert!(doc.delete_pages(vec![9]).is_err());
        assert_eq!(doc.info().unwrap().revision, revision);
    }

    #[test]
    fn insert_pdf_selection_keeps_order_and_is_undoable() {
        let source = blank();
        source.rotate_page(0).unwrap();
        let bytes = source.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
        let file = input_file(&bytes);
        let path = file.path().to_str().unwrap().to_owned();
        let doc = blank();
        assert_eq!(doc.insert_file(path.clone(), "Источник.pdf".into(), 1, Some(vec![1, 0])).unwrap(), 2);
        assert_eq!(doc.info().unwrap().page_count, 4);
        assert_eq!(doc.render_page(1, 200).unwrap().height, 300);
        assert_eq!(doc.render_page(2, 300).unwrap().height, 200);
        doc.undo().unwrap();
        assert_eq!(doc.info().unwrap().page_count, 2);
        doc.redo().unwrap();
        let saved = doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
        let reopened = MobileDocument::from_bytes("inserted.pdf".into(), saved, None).unwrap();
        assert_eq!(reopened.info().unwrap().page_count, 4);
        assert_eq!(reopened.render_page(2, 300).unwrap().height, 200);
        let revision = doc.info().unwrap().revision;
        assert!(doc.insert_file(path.clone(), "bad.pdf".into(), 99, None).is_err());
        assert!(doc.insert_file(path, "bad.pdf".into(), 0, Some(vec![99])).is_err());
        assert_eq!(doc.info().unwrap().revision, revision);
    }

    #[test]
    fn png_and_jpeg_become_visible_pages_and_survive_save() {
        for format in [image::ImageFormat::Png, image::ImageFormat::Jpeg] {
            let image = image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(40, 20, image::Rgb([20, 220, 30])));
            let mut encoded = std::io::Cursor::new(Vec::new());
            image.write_to(&mut encoded, format).unwrap();
            let file = input_file(encoded.get_ref());
            let doc = blank();
            // Import detects content, not a provider's extension or MIME declaration.
            doc.insert_file(file.path().to_str().unwrap().into(), "Фото".into(), 2, None).unwrap();
            assert_eq!(doc.info().unwrap().page_count, 3);
            let saved = doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
            let reopened = MobileDocument::from_bytes("image.pdf".into(), saved, None).unwrap();
            let raster = reopened.render_page(2, 400).unwrap();
            assert_eq!((raster.width, raster.height), (400, 200));
            let center = (100 * 400 + 200) * 4;
            assert!(raster.rgba[center + 1] > 180, "green must be rendered");
            assert!(raster.rgba[center] < 80, "red must stay low");
            doc.undo().unwrap();
            assert_eq!(doc.info().unwrap().page_count, 2);
        }
    }

    #[test]
    fn malformed_import_preserves_existing_changes() {
        let doc = blank();
        doc.rotate_page(0).unwrap();
        let revision = doc.info().unwrap().revision;
        for bytes in [b"not PDF".as_slice(), b"%PDF-broken", b"\x89PNG\r\n\x1a\ntruncated"] {
            let file = input_file(bytes);
            assert!(doc.insert_file(file.path().to_str().unwrap().into(), "bad.pdf".into(), 0, None).is_err());
            assert_eq!(doc.info().unwrap().revision, revision);
            assert_eq!(doc.info().unwrap().page_count, 2);
            assert!(doc.info().unwrap().can_undo);
        }
    }

    #[test]
    fn bundled_inter_covers_russian_english_and_punctuation() {
        let font = skrifa::FontRef::new(include_bytes!("../../../assets/fonts/Inter-Regular.ttf")).unwrap();
        use skrifa::MetadataProvider;
        let alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789№₽«»—–";
        for ch in alphabet.chars() {
            assert!(font.charmap().map(ch).is_some_and(|g| g.to_u32() > 0), "missing glyph: {ch}");
        }
    }

    fn vector_document() -> Arc<MobileDocument> {
        // Contributor-original sub-point vector bars; correct xref, offset CropBox.
        let mut content = String::from("0 0 0 rg\n");
        for bar in 0..128 {
            content.push_str(&format!("{} 230 0.25 40 re f\n", 20.0 + f64::from(bar) * 0.5));
        }
        let objects = [
            "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_owned(),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /CropBox [10 10 190 290] /Resources << >> /Contents 4 0 R >>".to_owned(),
            format!("<< /Length {} >>\nstream\n{content}endstream", content.len()),
        ];
        let mut pdf = String::from("%PDF-1.7\n");
        let mut offsets = Vec::new();
        for (i, object) in objects.iter().enumerate() {
            offsets.push(pdf.len());
            pdf.push_str(&format!("{} 0 obj\n{object}\nendobj\n", i + 1));
        }
        let xref = pdf.len();
        pdf.push_str("xref\n0 5\n0000000000 65535 f \n");
        for offset in offsets {
            pdf.push_str(&format!("{offset:010} 00000 n \n"));
        }
        pdf.push_str(&format!("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"));
        MobileDocument::from_bytes("vector.pdf".into(), Arc::new(pdf.into_bytes()), None).unwrap()
    }

    #[test]
    fn zoom_tiles_resolve_subpoint_vectors_without_full_page_allocation() {
        let doc = vector_document();
        let info = doc.info().unwrap();
        assert_eq!((info.pages[0].width, info.pages[0].height), (180.0, 280.0));
        // The virtual page is 4320×6720, beyond the old 2048-pixel preview cap.
        let tile = doc.render_tile(0, 24.0, 240, 480, 768, 128, info.revision).unwrap();
        assert_eq!((tile.width, tile.height, tile.rgba.len()), (768, 128, 768 * 128 * 4));
        for bar in 0..64 {
            let black = (64 * 768 + bar * 12 + 2) * 4;
            let white = (64 * 768 + bar * 12 + 8) * 4;
            assert!(tile.rgba[black] < 8, "each fine vector bar must be distinct");
            assert!(tile.rgba[white] > 247, "each fine gap must remain white");
        }
    }

    #[test]
    fn tiles_match_rendered_text_and_rotated_crop_boxes() {
        let text =
            Session::new().create_from_text("Scientific article", "Vector text 0123456789\nScientific article: small text and equations").unwrap();
        let text_doc = MobileDocument::from_bytes("text.pdf".into(), text, None).unwrap();
        for (document_index, doc) in [text_doc, vector_document()].into_iter().enumerate() {
            for rotation in 0..4 {
                let info = doc.info().unwrap();
                let scale = 900.0 / info.pages[0].width;
                let full = doc.render_page(0, 900).unwrap();
                let tile = doc.render_tile(0, scale, 30, 40, 400, 400, info.revision).unwrap();
                let mut sum = 0u64;
                let mut max = 0u8;
                let mut different = 0usize;
                for row in 0..400usize {
                    let start = ((row + 40) * full.width as usize + 30) * 4;
                    for (a, b) in tile.rgba[row * 1600..(row + 1) * 1600].iter().zip(&full.rgba[start..start + 1600]) {
                        let delta = a.abs_diff(*b);
                        sum += u64::from(delta);
                        max = max.max(delta);
                        different += usize::from(delta > 0);
                    }
                }
                // Integer tile translation can round a few anti-aliased text channels by 1/255.
                // A shifted glyph or rescaled preview differs much more than this tolerance.
                assert!(max <= 1 && sum < 640, "doc={document_index}, rotation={rotation}, max={max}, sum={sum}, changed={different}");
                doc.rotate_page(0).unwrap();
                let rotated = doc.info().unwrap();
                assert_eq!(rotated.pages[0].width, info.pages[0].height);
            }
        }
    }

    #[test]
    fn tiles_reject_unbounded_stale_and_invalid_requests() {
        let doc = blank();
        let rev = doc.info().unwrap().revision;
        for scale in [f32::NAN, f32::INFINITY, -1.0, 0.0, 0.001, 257.0] {
            assert!(doc.render_tile(0, scale, 0, 0, 16, 16, rev).is_err());
        }
        for (page, x, y, w, h) in [(9, 0, 0, 16, 16), (0, u32::MAX, 0, 16, 16), (0, 0, 0, 1025, 16), (0, 0, 0, 16, 0), (0, 190, 0, 16, 16)] {
            assert!(doc.render_tile(page, 1.0, x, y, w, h, rev).is_err());
        }
        doc.rotate_page(0).unwrap();
        assert!(doc.render_tile(0, 1.0, 0, 0, 16, 16, rev).is_err());
        let current = doc.info().unwrap().revision;
        assert_eq!(doc.render_tile(0, 1.0, 0, 0, 16, 16, current).unwrap().revision, current);
    }
}
