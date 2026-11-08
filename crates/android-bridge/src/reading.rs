//! Bounded access to the existing text layer; no OCR or raster conversion.
use super::*;
use pdfcraft_render::{PageRenderer, PageText, RenderConfig};
use std::collections::VecDeque;

const MAX_GLYPHS: usize = 50_000;
const MAX_BYTES: usize = 1_048_576;

#[derive(Default)]
pub(super) struct ReadingCache {
    revision: Option<u64>,
    pages: VecDeque<(u32, Arc<PageText>)>,
}

#[derive(uniffi::Record)]
pub struct ReadingGlyph {
    pub text: String,
    pub bounds: TextRect,
    pub line: u32,
    pub space_before: bool,
}

#[derive(uniffi::Record)]
pub struct ReadingPage {
    pub page: u32,
    pub revision: u64,
    pub glyphs: Vec<ReadingGlyph>,
}

#[derive(uniffi::Record)]
pub struct SearchHit {
    pub start: u32,
    /// Exclusive glyph index, never a UTF-8 or UTF-16 offset.
    pub end: u32,
    pub bounds: Vec<TextRect>,
}

#[derive(uniffi::Record)]
pub struct SearchPage {
    pub page: u32,
    pub revision: u64,
    pub hits: Vec<SearchHit>,
    pub truncated: bool,
}

fn rect(r: [f32; 4]) -> TextRect {
    TextRect { left: r[0], top: r[1], right: r[2], bottom: r[3] }
}

fn page_text(state: &mut State, page: u32, revision: u64) -> Result<Arc<PageText>, MobileError> {
    let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
    if doc.edit_generation() != revision {
        return Err(invalid("Документ изменился. Повторите поиск или выделение"));
    }
    if page as usize >= doc.info.pages.len() {
        return Err(invalid("Страница не существует"));
    }
    let cache = &mut state.reading_cache;
    if cache.revision != Some(revision) {
        cache.pages.clear();
        cache.revision = Some(revision);
    }
    if let Some((_, text)) = cache.pages.iter().find(|(p, _)| *p == page) {
        return Ok(text.clone());
    }
    let mut renderer = PageRenderer::new(doc.bytes.clone(), RenderConfig { password: doc.password.as_deref().map(Arc::from), ..Default::default() });
    let text = renderer.filtered_text(page as usize, Default::default()).map_err(engine_error)?;
    if text.line_of.len() != text.glyphs.len() || text.space_before.len() != text.glyphs.len() {
        return Err(invalid("Некорректная структура текстового слоя"));
    }
    if text.glyphs.len() > MAX_GLYPHS || text.glyphs.iter().map(|g| g.text.len()).sum::<usize>() > MAX_BYTES {
        return Err(invalid("Текстовый слой страницы превышает лимит: 50 000 знаков или 1 МиБ"));
    }
    if text.glyphs.iter().any(|g| g.rect.iter().any(|n| !n.is_finite() || n.abs() > MAX_TILE_EXTENT)) {
        return Err(invalid("Некорректные координаты текстового слоя"));
    }
    let text = Arc::new(text);
    while cache.pages.len() >= 3 {
        cache.pages.pop_front();
    }
    cache.pages.push_back((page, text.clone()));
    Ok(text)
}

#[uniffi::export]
impl MobileDocument {
    pub fn reading_page(&self, page: u32, revision: u64) -> Result<ReadingPage, MobileError> {
        self.with_state(|state| {
            let text = page_text(state, page, revision)?;
            Ok(ReadingPage {
                page,
                revision,
                glyphs: text
                    .glyphs
                    .iter()
                    .zip(&text.line_of)
                    .zip(&text.space_before)
                    .map(|((g, line), space)| ReadingGlyph { text: g.text.clone(), bounds: rect(g.rect), line: *line, space_before: *space })
                    .collect(),
            })
        })
    }

    /// Search a single page so the UI can cancel or yield between pages. A caller-selected
    /// limit plus one proves truncation without allocating an unbounded result set.
    pub fn search_page(
        &self,
        page: u32,
        revision: u64,
        query: String,
        case_sensitive: bool,
        whole_words: bool,
        limit: u32,
    ) -> Result<SearchPage, MobileError> {
        if query.trim().is_empty() || query.len() > 1024 || !(1..=1000).contains(&limit) {
            return Err(invalid("Введите запрос до 1024 байт; лимит результатов от 1 до 1000"));
        }
        self.with_state(|state| {
            let text = page_text(state, page, revision)?;
            let ranges = text.find_opts_limited(&query, case_sensitive, whole_words, limit as usize + 1);
            let truncated = ranges.len() > limit as usize;
            let hits = ranges
                .into_iter()
                .take(limit as usize)
                .map(|range| SearchHit {
                    start: range.start as u32,
                    end: range.end as u32,
                    bounds: text.line_rects(range).into_iter().map(rect).collect(),
                })
                .collect();
            Ok(SearchPage { page, revision, hits, truncated })
        })
    }
}

#[cfg(test)]
mod tests;
