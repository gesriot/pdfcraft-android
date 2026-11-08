//! Revision-bound text editing. Previews never enter the undo history.
use super::*;
use pdfcraft_engine::BlockStyle;
use pdfcraft_render::PageInfo;

const MAX_TEXT_BYTES: usize = 32_768;
const MAX_TEXT_BLOCKS: usize = 2000;
const MAX_PAGE_TEXT_BYTES: usize = 1_048_576;
const INTER: &[u8] = include_bytes!("../../../assets/fonts/Inter-Regular.ttf");

#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum TextFont {
    Original,
    Inter,
}

/// Displayed PDF points, y down; includes CropBox, Rotate and UserUnit.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TextRect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl TextRect {
    pub(super) fn from_user(info: &PageInfo, r: [f64; 4]) -> Result<Self, MobileError> {
        let a = info.user_to_view(r[0] as f32, r[1] as f32);
        let b = info.user_to_view(r[2] as f32, r[3] as f32);
        if a.into_iter().chain(b).any(|n| !n.is_finite() || n.abs() > MAX_TILE_EXTENT) {
            return Err(invalid("Недопустимые координаты текстового блока"));
        }
        Ok(Self { left: a[0].min(b[0]), top: a[1].min(b[1]), right: a[0].max(b[0]), bottom: a[1].max(b[1]) })
    }
}

#[derive(Clone, uniffi::Record)]
pub struct EditableTextBlock {
    pub index: u32,
    pub bounds: TextRect,
    pub text: String,
    pub font_name: String,
    pub font_size: f64,
}

#[derive(uniffi::Record)]
pub struct TextPage {
    pub page: u32,
    pub revision: u64,
    pub blocks: Vec<EditableTextBlock>,
    pub skipped: u32,
}

#[derive(uniffi::Record)]
pub struct TextEditPreview {
    pub token: u64,
    pub image: PageImage,
    pub overflow: bool,
    pub outside_page: bool,
    pub substituted_font: Option<String>,
}

pub(super) struct PendingText {
    pub(super) token: u64,
    pub(super) revision: u64,
    pub(super) edit: Edit,
}

#[uniffi::export]
impl MobileDocument {
    pub fn text_blocks(&self, page: u32, revision: u64) -> Result<TextPage, MobileError> {
        self.with_state(|state| {
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            if doc.edit_generation() != revision {
                return Err(invalid("Документ изменился. Выберите текст заново"));
            }
            if !doc.allows_modification() {
                return Err(invalid("Редактирование этого документа запрещено"));
            }
            let info = doc.info.pages.get(page as usize).ok_or_else(|| invalid("Страница не существует"))?;
            let mut blocks = Vec::new();
            let mut skipped = 0u32;
            let mut bytes = 0usize;
            for (index, b) in doc.text_blocks(page as usize).into_iter().enumerate() {
                if b.invisible
                    || b.editor_text.is_empty()
                    || b.editor_text.len() > MAX_TEXT_BYTES
                    || blocks.len() >= MAX_TEXT_BLOCKS
                    || bytes.saturating_add(b.editor_text.len()) > MAX_PAGE_TEXT_BYTES
                {
                    skipped = skipped.saturating_add(1);
                    continue;
                }
                let Ok(bounds) = TextRect::from_user(info, b.rect) else {
                    skipped = skipped.saturating_add(1);
                    continue;
                };
                if bounds.right <= 0.0 || bounds.bottom <= 0.0 || bounds.left >= info.width || bounds.top >= info.height {
                    skipped = skipped.saturating_add(1);
                    continue;
                }
                bytes += b.editor_text.len();
                blocks.push(EditableTextBlock {
                    index: u32::try_from(index).map_err(|_| invalid("Слишком много блоков текста"))?,
                    bounds,
                    text: b.editor_text,
                    font_name: b.base_font,
                    font_size: b.size,
                });
            }
            Ok(TextPage { page, revision, blocks, skipped })
        })
    }

    /// Rasterize the actual rewritten PDF around the source and replacement, with context.
    pub fn preview_text_edit(
        &self,
        page: u32,
        block: u32,
        revision: u64,
        text: String,
        font: TextFont,
        size: Option<f64>,
    ) -> Result<TextEditPreview, MobileError> {
        self.with_state(|state| {
            state.pending_text = None;
            if text.len() > MAX_TEXT_BYTES || text.chars().any(|c| c.is_control() && c != '\n') {
                return Err(invalid("Допустимо до 32 КиБ текста без управляющих символов, кроме переноса строки"));
            }
            if size.is_some_and(|n| !n.is_finite() || !(1.0..=300.0).contains(&n)) {
                return Err(invalid("Размер шрифта должен быть от 1 до 300 пунктов"));
            }
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            if doc.edit_generation() != revision {
                return Err(invalid("Документ изменился. Выберите текст заново"));
            }
            let info = doc.info.pages.get(page as usize).ok_or_else(|| invalid("Страница не существует"))?;
            if !info.width.is_finite() || !info.height.is_finite() || info.width <= 0.0 || info.height <= 0.0 {
                return Err(invalid("Некорректный размер страницы"));
            }
            let source = doc.text_blocks(page as usize);
            let source = source.get(block as usize).ok_or_else(|| invalid("Текстовый блок не существует"))?;
            if source.invisible || source.editor_text.len() > MAX_TEXT_BYTES {
                return Err(invalid("Этот текстовый слой пока не поддерживается редактором"));
            }
            if text == source.editor_text && size.is_none() && matches!(font, TextFont::Original) {
                return Err(invalid("Текст не изменён"));
            }
            let font_bytes = matches!(font, TextFont::Inter).then(|| Arc::new(INTER.to_vec()));
            let draft = doc
                .prepare_text_edit(page as usize, block as usize, &text, BlockStyle { size, ..Default::default() }, font_bytes)
                .map_err(engine_error)?;
            let old = TextRect::from_user(info, draft.layout.source_rect)?;
            let new = TextRect::from_user(info, draft.layout.draft_rect.unwrap_or(draft.layout.source_rect))?;
            let outside_page = new.left < 0.0 || new.top < 0.0 || new.right > info.width || new.bottom > info.height;
            let left = (old.left.min(new.left) - 24.0).clamp(0.0, info.width);
            let top = (old.top.min(new.top) - 24.0).clamp(0.0, info.height);
            let right = (old.right.max(new.right) + 24.0).clamp(0.0, info.width);
            let bottom = (old.bottom.max(new.bottom) + 24.0).clamp(0.0, info.height);
            if right <= left || bottom <= top {
                return Err(invalid("Текст находится за пределами страницы"));
            }
            let scale = (900.0 / (right - left)).min(900.0 / (bottom - top)).min(3.0);
            if scale < 0.01 || info.width * scale > MAX_TILE_EXTENT || info.height * scale > MAX_TILE_EXTENT {
                return Err(invalid("Страница слишком велика для предпросмотра"));
            }
            let x = (left * scale).floor() as u32;
            let y = (top * scale).floor() as u32;
            let width = ((right * scale).ceil() as u32).min(device_pixels(info.width, scale)).saturating_sub(x);
            let height = ((bottom * scale).ceil() as u32).min(device_pixels(info.height, scale)).saturating_sub(y);
            let rendered = draft.preview.render_region(scale, Tile { x, y, w: width, h: height }).map_err(engine_error)?;
            state.preview_serial = state.preview_serial.checked_add(1).ok_or_else(|| invalid("Откройте документ заново"))?;
            let token = state.preview_serial;
            state.pending_text = Some(PendingText { token, revision, edit: draft.edit });
            Ok(TextEditPreview {
                token,
                image: PageImage {
                    width: rendered.width,
                    height: rendered.height,
                    rgba: rendered.rgba.to_vec(),
                    revision,
                    warnings: rendered.warnings.iter().map(|w| format!("{w:?}")).collect(),
                },
                overflow: draft.layout.overflow,
                outside_page,
                substituted_font: if matches!(font, TextFont::Inter) { Some("Inter Regular".into()) } else { draft.substituted },
            })
        })
    }

    /// Apply only the exact successful preview, once, against its unchanged revision.
    pub fn apply_text_preview(&self, token: u64) -> Result<(), MobileError> {
        self.with_state(|state| {
            let pending = state.pending_text.as_ref().ok_or_else(|| invalid("Сначала выполните предпросмотр"))?;
            let revision = state.session.get(state.id).ok_or(MobileError::Closed)?.edit_generation();
            if pending.token != token || pending.revision != revision {
                return Err(invalid("Предпросмотр устарел. Выполните его заново"));
            }
            state.session.apply(state.id, pending.edit.clone()).map_err(engine_error)?;
            state.pending_text = None;
            Ok(())
        })
    }
}

#[cfg(test)]
pub(crate) mod tests;
