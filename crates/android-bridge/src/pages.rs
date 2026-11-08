//! Visual organizer and exports. Every index is revision-bound and zero-based.
use super::*;
use pdfcraft_engine::export::{ImageFormat, encode_image};

const A4: (f64, f64) = (595.2756, 841.8898);
const EXPORT_PIXELS: f64 = 12_000_000.0;
const EXPORT_SIDE: f64 = 8192.0;

#[derive(Clone, Copy, uniffi::Enum)]
pub enum PageImageFormat {
    Png,
    Jpeg,
}

#[derive(uniffi::Record)]
pub struct ExportedPageImage {
    pub width: u32,
    pub height: u32,
    pub dpi_used: f64,
    pub reduced: bool,
}

fn dimensions(landscape: bool) -> (f64, f64) {
    if landscape { (A4.1, A4.0) } else { A4 }
}

fn checked_count(state: &State, revision: u64) -> Result<usize, MobileError> {
    let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
    if doc.edit_generation() != revision {
        return Err(invalid("Документ изменился. Выберите страницы заново"));
    }
    Ok(doc.info.pages.len())
}

fn selection(pages: &[u32], count: usize) -> Result<Vec<usize>, MobileError> {
    if pages.is_empty() || pages.len() > MAX_IMPORT_PAGES {
        return Err(invalid("Выберите от 1 до 500 страниц"));
    }
    let mut unique = std::collections::BTreeSet::new();
    for &page in pages {
        if page as usize >= count || !unique.insert(page as usize) {
            return Err(invalid("Неверные или повторяющиеся номера страниц"));
        }
    }
    Ok(pages.iter().map(|p| *p as usize).collect())
}

pub(super) fn write_new(path: &str, bytes: &[u8]) -> Result<(), MobileError> {
    let mut file = OpenOptions::new().write(true).create_new(true).open(path).map_err(io_error)?;
    let result = file.write_all(bytes).and_then(|_| file.sync_all());
    drop(file);
    if let Err(error) = result {
        let _ = std::fs::remove_file(path);
        return Err(io_error(error));
    }
    Ok(())
}

#[uniffi::export]
pub fn create_document(name: String, landscape: bool) -> Result<Arc<MobileDocument>, MobileError> {
    catch_unwind(AssertUnwindSafe(|| {
        let name = name.trim();
        if name.is_empty() || name.len() > 512 || name.chars().any(char::is_control) {
            return Err(invalid("Укажите название документа до 512 байт"));
        }
        let (width, height) = dimensions(landscape);
        let bytes = Session::new().create_blank(width, height, 1).map_err(engine_error)?;
        MobileDocument::from_bytes(name.to_owned(), bytes, None)
    }))
    .unwrap_or(Err(MobileError::WorkerFailed))
}

#[uniffi::export]
impl MobileDocument {
    /// `to` is the FIRST moved page's index in the RESULT, after removing the selection.
    /// Non-adjacent pages retain document order. A no-op does not create an undo entry.
    pub fn move_pages(&self, pages: Vec<u32>, to: u32, revision: u64) -> Result<(), MobileError> {
        self.with_state(|state| {
            let count = checked_count(state, revision)?;
            let mut selected = selection(&pages, count)?;
            selected.sort_unstable();
            if to as usize > count - selected.len() {
                return Err(invalid("Неверное место переноса страниц"));
            }
            if selected.iter().enumerate().all(|(offset, page)| *page == to as usize + offset) {
                return Ok(());
            }
            state.session.apply(state.id, Edit::MovePages { pages: selected, to: to as usize }).map_err(engine_error)?;
            state.pending_text = None;
            Ok(())
        })
    }

    pub fn remove_pages(&self, pages: Vec<u32>, revision: u64) -> Result<(), MobileError> {
        self.with_state(|state| {
            let count = checked_count(state, revision)?;
            let selected = selection(&pages, count)?;
            if selected.len() == count {
                return Err(invalid("В документе должна остаться хотя бы одна страница"));
            }
            state.session.apply(state.id, Edit::DeletePages { pages: selected }).map_err(engine_error)?;
            state.pending_text = None;
            Ok(())
        })
    }

    pub fn insert_blank_page(&self, at: u32, landscape: bool, revision: u64) -> Result<(), MobileError> {
        self.with_state(|state| {
            if at as usize > checked_count(state, revision)? {
                return Err(invalid("Неверное место вставки"));
            }
            let (width, height) = dimensions(landscape);
            state.session.apply(state.id, Edit::InsertBlankPage { at: at as usize, width, height }).map_err(engine_error)?;
            state.pending_text = None;
            Ok(())
        })
    }

    /// A new, unencrypted PDF containing only the requested pages, in the requested order.
    /// Uses the frontend's page export policy. Source history stays intact.
    pub fn extract_pages(&self, pages: Vec<u32>, revision: u64, path: String) -> Result<(), MobileError> {
        self.with_state(|state| {
            let selected = selection(&pages, checked_count(state, revision)?)?;
            let bytes = state.session.extract(state.id, &selected).map_err(engine_error)?;
            write_new(&path, &bytes)
        })
    }

    /// Render one whole page from current PDF content, never from a screen thumbnail.
    /// Bounded to 12 MP / 8192 per side. Uses the frontend's page export policy.
    pub fn export_page_image(
        &self,
        page: u32,
        revision: u64,
        path: String,
        format: PageImageFormat,
        dpi: u32,
    ) -> Result<ExportedPageImage, MobileError> {
        if !(72..=300).contains(&dpi) {
            return Err(invalid("Разрешение экспорта: от 72 до 300 dpi"));
        }
        self.with_state(|state| {
            checked_count(state, revision)?;
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            doc.check_page_image_export().map_err(engine_error)?;
            let p = doc.info.pages.get(page as usize).ok_or_else(|| invalid("Страница не существует"))?;
            let (w, h) = (f64::from(p.width), f64::from(p.height));
            if !w.is_finite() || !h.is_finite() || w <= 0.0 || h <= 0.0 {
                return Err(invalid("Некорректный размер страницы"));
            }
            // Leave a two-pixel rounding margin when clamping the area and side budgets.
            let scale =
                (f64::from(dpi) / 72.0).min(((EXPORT_PIXELS - 40_000.0) / (w * h)).sqrt()).min((EXPORT_SIDE - 2.0) / w).min((EXPORT_SIDE - 2.0) / h);
            if !scale.is_finite() || scale < 0.01 {
                return Err(invalid("Страница слишком велика для экспорта изображения"));
            }
            let width = device_pixels(p.width, scale as f32);
            let height = device_pixels(p.height, scale as f32);
            if u64::from(width) * u64::from(height) > EXPORT_PIXELS as u64 || f64::from(width.max(height)) > EXPORT_SIDE {
                return Err(invalid("Превышен размер экспортируемого изображения"));
            }
            let image = render_request(state, RenderRequest { page: page as usize, scale: scale as f32, tag: revision, ..Default::default() })?;
            let encoded = encode_image(
                image.width,
                image.height,
                &image.rgba,
                match format {
                    PageImageFormat::Png => ImageFormat::Png,
                    PageImageFormat::Jpeg => ImageFormat::Jpeg { quality: 92 },
                },
                scale * 72.0,
            )
            .map_err(engine_error)?;
            write_new(&path, &encoded)?;
            let dpi_used = scale * 72.0;
            Ok(ExportedPageImage { width: image.width, height: image.height, dpi_used, reduced: dpi_used < f64::from(dpi) - 0.01 })
        })
    }
}

#[cfg(test)]
mod tests;
