//! The page beneath an inline text editor, with the source glyphs removed on a COS clone; and,
//! the same way, the page beneath a dragged added item or the marks an Update replaces.

use std::sync::Arc;

use pdfcraft_render::{PageRenderer, RenderConfig, RenderPool, RenderRequest, RenderedPage, Tile};

use crate::{BlockStyle, Document, Edit, guard};

/// A read-only replacement draft and the single undoable edit producing exactly that draft.
pub struct PreparedTextEdit {
    pub edit: Edit,
    pub preview: TextEditPreview,
    pub layout: pdfcraft_edit::text::TextEditLayout,
    pub substituted: Option<String>,
}

pub struct TextEditPreview {
    page: usize,
    bytes: Arc<Vec<u8>>,
    config: RenderConfig,
}

impl TextEditPreview {
    /// Bounded preview region. The caller supplies device-pixel bounds at `scale`.
    pub fn render_region(&self, scale: f32, tile: Tile) -> Result<RenderedPage, String> {
        if !scale.is_finite() || scale <= 0.0 || tile.w == 0 || tile.h == 0 || tile.w > 2048 || tile.h > 2048 {
            return Err("invalid text preview dimensions".into());
        }
        guard(|| {
            let mut renderer = PageRenderer::new(self.bytes.clone(), self.config.clone());
            let out = renderer.render(RenderRequest { page: self.page, scale, tile: Some(tile), ..Default::default() });
            if let Some(e) = &out.error {
                return Err(e.clone());
            }
            Ok(out)
        })?
    }

    pub fn renderer(&self) -> RenderPool {
        RenderPool::new(self.bytes.clone(), 1, self.config.clone())
    }

    pub fn render_background(&self, scale: f32) -> Result<RenderedPage, String> {
        render_one(&self.bytes, &self.config, self.page, scale)
    }
}

/// A document's pages without its marks of one kind ([`Document::without_marks`]).
pub struct UnmarkedPages {
    bytes: Arc<Vec<u8>>,
    config: RenderConfig,
    /// Parsed on the first [`Self::render_page`], once (a copy that can't be parsed isn't tried
    /// again), and kept: parsing costs about as much as reading the document.
    parsed: Option<PageRenderer>,
    /// How many times it was parsed.
    #[cfg(test)]
    builds: u64,
}

impl UnmarkedPages {
    pub fn renderer(&self) -> RenderPool {
        RenderPool::new(self.bytes.clone(), 1, self.config.clone())
    }

    /// `page` at `scale`, on this thread.
    pub fn render_page(&mut self, page: usize, scale: f32) -> Result<RenderedPage, String> {
        if !scale.is_finite() || scale <= 0.0 {
            return Err("preview scale must be finite and positive".into());
        }
        if self.parsed.is_none() {
            self.parsed = Some(PageRenderer::new(self.bytes.clone(), self.config.clone()));
            #[cfg(test)]
            {
                self.builds += 1;
            }
        }
        let Some(renderer) = self.parsed.as_mut() else { return Err("the document could not be parsed".into()) };
        let out = guard(|| renderer.render(RenderRequest { page, scale, ..Default::default() }));
        let out = match out {
            Ok(out) => out,
            Err(e) => {
                // A panic may have left it half-way: parse afresh next time.
                self.parsed = None;
                return Err(e);
            }
        };
        if let Some(e) = &out.error {
            return Err(e.clone());
        }
        Ok(out)
    }

    /// How many times [`Self::render_page`] has parsed the document.
    #[cfg(test)]
    pub(crate) fn parser_builds(&self) -> u64 {
        self.builds
    }
}

/// `page` of `bytes` at `scale`, on this thread.
fn render_one(bytes: &Arc<Vec<u8>>, config: &RenderConfig, page: usize, scale: f32) -> Result<RenderedPage, String> {
    if !scale.is_finite() || scale <= 0.0 {
        return Err("preview scale must be finite and positive".into());
    }
    guard(|| {
        let mut renderer = PageRenderer::new(bytes.clone(), config.clone());
        let out = renderer.render(RenderRequest { page, scale, ..Default::default() });
        if let Some(e) = &out.error {
            return Err(e.clone());
        }
        Ok(out)
    })?
}

impl Document {
    /// A portable FreeText appearance on a copy; Apply uses the exact same font and annotation.
    pub fn prepare_text_annotation(&self, annotation: crate::NewAnnotation, font: Arc<Vec<u8>>) -> Result<(Edit, TextEditPreview), String> {
        guard(|| {
            if !self.allows_annotation() {
                return Err("Adding text is not permitted".into());
            }
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            pdfcraft_annot::text_box::add(&mut cos, &annotation, &font, &pdfcraft_annot::Meta::default()).map_err(|e| e.to_string())?;
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            let preview = TextEditPreview { page: annotation.page, bytes, config: self.config.clone() };
            Ok((Edit::AddTextAnnotation { annotation, font }, preview))
        })?
    }

    /// Prepare a real PDF replacement without changing history. An optional licensed font
    /// and the replacement are one Batch, so a failed replacement cannot leave a font edit.
    /// Frontends must check the document's edit_generation before applying the returned edit.
    pub fn prepare_text_edit(
        &self,
        page: usize,
        block: usize,
        text: &str,
        mut style: BlockStyle,
        font: Option<Arc<Vec<u8>>>,
    ) -> Result<PreparedTextEdit, String> {
        guard(|| {
            if !self.allows_modification() {
                return Err("Редактирование этого документа запрещено".into());
            }
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            let mut edits = Vec::new();
            if let Some(bytes) = font {
                style.resource = Some(pdfcraft_edit::text::register_text_font(&mut cos, page, &bytes).map_err(|e| e.to_string())?);
                edits.push(Edit::RegisterTextFont { page, bytes });
            }
            let layout = pdfcraft_edit::text::text_edit_layout(&cos, page, block, text, &style).map_err(|e| e.to_string())?;
            let result = pdfcraft_edit::text::rewrite_block(&mut cos, page, block, Some(text), &style).map_err(|e| e.to_string())?;
            edits.push(Edit::EditTextBlock { page, block, text: text.into(), style });
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(PreparedTextEdit {
                edit: Edit::Batch { label: "Edit text".into(), edits },
                preview: TextEditPreview { page, bytes, config: self.config.clone() },
                layout,
                substituted: result.substituted,
            })
        })?
    }

    /// Background for retyping an added text item, without changing its saved content.
    pub fn added_text_preview(&self, page: usize, index: usize) -> Result<TextEditPreview, String> {
        guard(|| {
            if !self.added().iter().filter(|a| a.page == page).nth(index).is_some_and(|a| matches!(a.content, crate::AddedContent::Text(_))) {
                return Err("the selected added item is not text".into());
            }
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            pdfcraft_edit::delete_content(&mut cos, page, index).map_err(|e| e.to_string())?;
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(TextEditPreview { page, bytes, config: self.config.clone() })
        })?
    }

    /// Measure a draft without changing bytes, dirty state, or undo history.
    pub fn text_edit_layout(
        &self,
        page: usize,
        block: usize,
        text: &str,
        style: &pdfcraft_edit::BlockStyle,
    ) -> Result<pdfcraft_edit::text::TextEditLayout, String> {
        guard(|| {
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            pdfcraft_edit::text::text_edit_layout(&editor.cos, page, block, text, style).map_err(|e| e.to_string())
        })?
    }

    /// Embedded source faces for a text editor, bounded and validated as sfnt programs.
    /// This is read-only; no source font is installed or exported.
    pub fn text_editor_fonts(&self, page: usize) -> Vec<pdfcraft_edit::text::TextEditorFont> {
        self.editor.as_ref().and_then(|e| pdfcraft_edit::text::text_editor_fonts(&e.cos, page).ok()).unwrap_or_default()
    }

    /// Preparing the background never changes working bytes, dirty state or undo history.
    pub fn text_edit_preview(&self, page: usize, block: usize) -> Result<TextEditPreview, String> {
        guard(|| {
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            pdfcraft_edit::clear_block(&mut cos, page, block).map_err(|e| e.to_string())?;
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(TextEditPreview { page, bytes, config: self.config.clone() })
        })?
    }

    /// The page beneath an added item while it is dragged: `index` counts the page's added
    /// items, as `Edit::DeleteContent` does. Read-only, like [`Self::text_edit_preview`].
    pub fn added_content_preview(&self, page: usize, index: usize) -> Result<TextEditPreview, String> {
        guard(|| {
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            pdfcraft_edit::delete_content(&mut cos, page, index).map_err(|e| e.to_string())?;
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(TextEditPreview { page, bytes, config: self.config.clone() })
        })?
    }

    /// The document without its marks of `kind`, on every page: what Update replaces them on, so
    /// its preview doesn't draw the new marks over the old ones (or tint the old background).
    /// Made once for all the pages a dialog steps through, as cloning and writing the document
    /// costs about a save. `None` when the document has none, and the thumbnails already are
    /// those pages. Read-only, like [`Self::text_edit_preview`].
    pub fn without_marks(&self, kind: crate::MarkKind) -> Result<Option<UnmarkedPages>, String> {
        if !self.marks().contains(&kind) {
            return Ok(None);
        }
        guard(|| {
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            let n = pdfcraft_model::pages(&cos).len();
            if pdfcraft_edit::remove_marks(&mut cos, &(0..n).collect::<Vec<_>>(), kind).map_err(|e| e.to_string())? == 0 {
                return Ok(None);
            }
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(Some(UnmarkedPages {
                bytes,
                config: self.config.clone(),
                parsed: None,
                #[cfg(test)]
                builds: 0,
            }))
        })?
    }

    /// An added item alone, on a transparent page: what follows the pointer while it is dragged,
    /// so the page under its first place doesn't come along. Read-only, like
    /// [`Self::added_content_preview`].
    pub fn added_content_alone(&self, page: usize, index: usize) -> Result<TextEditPreview, String> {
        guard(|| {
            let editor = self.editor.as_ref().ok_or("the document can't be read")?;
            let mut cos = editor.cos.clone();
            pdfcraft_edit::isolate_content(&mut cos, page, index).map_err(|e| e.to_string())?;
            let bytes = Arc::new(pdfcraft_cos::write_incremental(&cos, &pdfcraft_cos::SaveOptions::default()).map_err(|e| e.to_string())?);
            Ok(TextEditPreview { page, bytes, config: RenderConfig { transparent: true, ..self.config.clone() } })
        })?
    }
}
