//! New visible text fields. FreeText /AP contains the attributed Inter face, never UI pixels.
use super::*;
use pdfcraft_engine::{NewAnnotation, Shape, Style};

#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum NoteColor {
    Black,
    Blue,
    Red,
    Green,
}
impl NoteColor {
    fn rgb(self) -> [f64; 3] {
        match self {
            Self::Black => [0.0, 0.0, 0.0],
            Self::Blue => [0.08, 0.28, 0.78],
            Self::Red => [0.78, 0.08, 0.08],
            Self::Green => [0.0, 0.42, 0.20],
        }
    }
}
#[derive(Clone, uniffi::Record)]
pub struct TextNoteInput {
    pub page: u32,
    pub revision: u64,
    pub bounds: TextRect,
    pub text: String,
    pub size: f64,
    pub color: NoteColor,
}

#[uniffi::export]
impl MobileDocument {
    pub fn preview_text_note(&self, input: TextNoteInput) -> Result<TextEditPreview, MobileError> {
        self.with_state(|state| {
            state.pending_text = None;
            let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
            if doc.edit_generation() != input.revision {
                return Err(invalid("Документ изменился. Выберите место заново"));
            }
            let page = doc.info.pages.get(input.page as usize).ok_or_else(|| invalid("Страница не существует"))?;
            let r = &input.bounds;
            if !input.size.is_finite()
                || !(4.0..=144.0).contains(&input.size)
                || [r.left, r.top, r.right, r.bottom].into_iter().any(|x| !x.is_finite())
                || r.left < 0.0
                || r.top < 0.0
                || r.right > page.width
                || r.bottom > page.height
                || r.right - r.left < 4.0
                || r.bottom - r.top < 4.0
            {
                return Err(invalid("Поле должно находиться на странице; размер шрифта — 4–144 пт"));
            }
            let a = page.view_to_user(r.left, r.top);
            let b = page.view_to_user(r.right, r.bottom);
            let crop_width = if matches!(page.rotation, 90 | 270) { page.crop[3] - page.crop[1] } else { page.crop[2] - page.crop[0] };
            let units = f64::from(crop_width) / f64::from(page.width);
            if !units.is_finite() || units <= 0.0 {
                return Err(invalid("Некорректные единицы страницы"));
            }
            let shape = Shape::TextBox {
                rect: [f64::from(a[0].min(b[0])), f64::from(a[1].min(b[1])), f64::from(a[0].max(b[0])), f64::from(a[1].max(b[1]))],
                font_size: input.size * units,
            };
            let mut style = Style::default_for(&shape);
            style.color = input.color.rgb();
            let annotation = NewAnnotation { page: input.page as usize, shape, style, contents: input.text, author: "PdfCraft".into() };
            let (edit, preview) = doc
                .prepare_text_annotation(annotation, Arc::new(include_bytes!("../../../assets/fonts/Inter-Regular.ttf").to_vec()))
                .map_err(engine_error)?;
            let (left, top, right, bottom) =
                ((r.left - 16.0).max(0.0), (r.top - 16.0).max(0.0), (r.right + 16.0).min(page.width), (r.bottom + 16.0).min(page.height));
            let scale = (900.0 / (right - left)).min(900.0 / (bottom - top)).min(3.0);
            if !scale.is_finite() || scale < 0.01 || page.width * scale > MAX_TILE_EXTENT || page.height * scale > MAX_TILE_EXTENT {
                return Err(invalid("Страница слишком велика для предпросмотра"));
            }
            let x = (left * scale).floor() as u32;
            let y = (top * scale).floor() as u32;
            let w = ((right * scale).ceil() as u32).min(device_pixels(page.width, scale)).saturating_sub(x);
            let h = ((bottom * scale).ceil() as u32).min(device_pixels(page.height, scale)).saturating_sub(y);
            let image = preview.render_region(scale, Tile { x, y, w, h }).map_err(engine_error)?;
            state.preview_serial = state.preview_serial.checked_add(1).ok_or_else(|| invalid("Откройте документ заново"))?;
            let token = state.preview_serial;
            state.pending_text = Some(text::PendingText { token, revision: input.revision, edit });
            Ok(TextEditPreview {
                token,
                image: PageImage {
                    width: image.width,
                    height: image.height,
                    rgba: image.rgba.to_vec(),
                    revision: input.revision,
                    warnings: image.warnings.iter().map(|w| format!("{w:?}")).collect(),
                },
                overflow: false,
                outside_page: false,
                substituted_font: Some("Inter Regular".into()),
            })
        })
    }
}

#[cfg(test)]
mod tests;
