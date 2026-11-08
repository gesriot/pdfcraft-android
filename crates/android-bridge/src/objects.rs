//! Delete the selected placement, including grouped Form artwork, through Session history.
use super::*;
use pdfcraft_engine::{ImageEdit, ObjectKind};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ArtworkKind {
    Image,
    Added,
}

#[derive(Clone, uniffi::Record)]
pub struct ArtworkBlock {
    pub index: u32,
    pub kind: ArtworkKind,
    pub bounds: TextRect,
    pub grouped: bool,
}

#[derive(uniffi::Record)]
pub struct ArtworkPage {
    pub page: u32,
    pub revision: u64,
    pub blocks: Vec<ArtworkBlock>,
}

fn artwork(state: &State, page: u32, revision: u64) -> Result<ArtworkPage, MobileError> {
    let doc = state.session.get(state.id).ok_or(MobileError::Closed)?;
    if doc.edit_generation() != revision {
        return Err(invalid("Документ изменился. Выберите объект заново"));
    }
    let info = doc.info.pages.get(page as usize).ok_or_else(|| invalid("Страница не существует"))?;
    let mut blocks = Vec::new();
    for object in doc.editable_objects(page as usize).map_err(engine_error)? {
        if !matches!(object.content_type, "image" | "form") {
            continue;
        }
        let kind = match object.target.kind {
            ObjectKind::Image => ArtworkKind::Image,
            ObjectKind::Added => ArtworkKind::Added,
            ObjectKind::Text => continue,
        };
        let bounds = TextRect::from_user(info, object.rect)?;
        if bounds.right <= 0.0 || bounds.bottom <= 0.0 || bounds.left >= info.width || bounds.top >= info.height {
            continue;
        }
        blocks.push(ArtworkBlock {
            index: u32::try_from(object.target.index).map_err(|_| invalid("Слишком большой индекс объекта"))?,
            kind,
            bounds,
            grouped: object.content_type == "form",
        });
        if blocks.len() > 2000 {
            return Err(invalid("На странице больше 2000 изображений или групп"));
        }
    }
    Ok(ArtworkPage { page, revision, blocks })
}

#[uniffi::export]
impl MobileDocument {
    pub fn artwork_blocks(&self, page: u32, revision: u64) -> Result<ArtworkPage, MobileError> {
        self.with_state(|state| artwork(state, page, revision))
    }

    pub fn delete_artwork(&self, page: u32, revision: u64, kind: ArtworkKind, index: u32) -> Result<(), MobileError> {
        self.with_state(|state| {
            if !artwork(state, page, revision)?.blocks.iter().any(|b| b.kind == kind && b.index == index) {
                return Err(invalid("Выбранное изображение больше не существует"));
            }
            let edit = match kind {
                ArtworkKind::Image => Edit::EditPageImage { page: page as usize, index: index as usize, change: ImageEdit::Delete },
                ArtworkKind::Added => Edit::DeleteContent { page: page as usize, index: index as usize },
            };
            state.session.apply(state.id, edit).map_err(engine_error)?;
            state.pending_text = None;
            Ok(())
        })
    }
}

#[cfg(test)]
mod tests;
