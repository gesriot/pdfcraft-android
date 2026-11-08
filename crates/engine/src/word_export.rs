//! Bounded, incremental DOCX conversion for small-memory frontends. No OCR or FreeText.
use crate::export::{ExportSource, Exporter, ImageFormat};
use pdfcraft_cos::Document as CosDocument;
use pdfcraft_export::{Block, Image, Page};
use pdfcraft_render::PageInfo;

const MAX_PAGES: usize = 500;
const MAX_TEXT: usize = 8 << 20;
const MAX_MEDIA: usize = 48 << 20;
const MAX_IMAGES: usize = 900;
const MAX_BLOCKS: usize = 20_000;

/// An immutable source snapshot; dropping it cancels between pages without touching the PDF.
pub struct WordExport {
    cos: CosDocument,
    source: ExportSource,
    info: Vec<PageInfo>,
    selected: Vec<usize>,
    pages: Vec<Page>,
    title: String,
    text_bytes: usize,
    image_bytes: usize,
    image_count: usize,
    block_count: usize,
    /// Original zero-based page numbers rasterized instead of silently losing their content.
    pub image_pages: Vec<usize>,
}

impl crate::Document {
    /// Uses the frontend's page-export policy (strict by default, Android opts out after open).
    pub fn prepare_word_export(&self, pages: &[usize]) -> Result<WordExport, String> {
        self.check_page_image_export().map_err(|e| e.to_string())?;
        if pages.is_empty() || pages.len() > MAX_PAGES {
            return Err("Select between 1 and 500 pages for Word export".into());
        }
        let mut seen = std::collections::BTreeSet::new();
        if pages.iter().any(|&p| p >= self.info.pages.len() || !seen.insert(p)) {
            return Err("Invalid or repeated page number".into());
        }
        let cos = self.editor.as_ref().ok_or("Document content is unavailable")?.cos.clone();
        Ok(WordExport {
            cos,
            source: self.export_source(),
            info: self.info.pages.clone(),
            selected: pages.to_vec(),
            pages: Vec::new(),
            title: self.info.title.as_deref().unwrap_or(&self.name).chars().take(1024).collect(),
            text_bytes: 0,
            image_bytes: 0,
            image_count: 0,
            block_count: 0,
            image_pages: Vec::new(),
        })
    }
}

impl WordExport {
    pub fn completed(&self) -> usize {
        self.pages.len()
    }
    pub fn total(&self) -> usize {
        self.selected.len()
    }

    /// Process exactly one page. A failed call leaves the job unadvanced and can be discarded.
    pub fn advance(&mut self) -> Result<usize, String> {
        let page = *self.selected.get(self.pages.len()).ok_or("All selected pages are already converted")?;
        let info = self.info.get(page).ok_or("Page is unavailable")?;
        if !info.width.is_finite() || !info.height.is_finite() || info.width <= 0.0 || info.height <= 0.0 {
            return Err(format!("Page {} has invalid dimensions", page + 1));
        }
        let (converted, rasterized) = match self.editable_page(page, info) {
            Ok(p) if !p.blocks.is_empty() => (p, false),
            _ => (self.raster_page(page, info)?, true),
        };
        let text = converted.blocks.iter().map(|b| b.text.len()).fold(self.text_bytes, usize::saturating_add);
        let media = converted.images.iter().map(|i| i.bytes.len()).fold(self.image_bytes, usize::saturating_add);
        let images = self.image_count.saturating_add(converted.images.len());
        let blocks = self.block_count.saturating_add(converted.blocks.len());
        if text > MAX_TEXT || media > MAX_MEDIA || images > MAX_IMAGES || blocks > MAX_BLOCKS {
            return Err("Word export is too large; select fewer pages (8 MiB text / 48 MiB pictures / 900 pictures / 20000 blocks)".into());
        }
        self.text_bytes = text;
        self.image_bytes = media;
        self.image_count = images;
        self.block_count = blocks;
        if rasterized {
            self.image_pages.push(page);
        }
        self.pages.push(converted);
        Ok(self.pages.len())
    }

    pub fn finish(&self) -> Result<Vec<u8>, String> {
        if self.pages.len() != self.selected.len() {
            return Err("Convert all selected pages before saving Word".into());
        }
        Ok(pdfcraft_export::docx_reflow(&self.pages, &self.title))
    }

    fn editable_page(&self, page: usize, info: &PageInfo) -> Result<Page, String> {
        let crop_width = f64::from(info.crop[2] - info.crop[0]);
        let displayed_width = f64::from(if info.rotation.is_multiple_of(180) { info.width } else { info.height });
        let unit = displayed_width / crop_width;
        if !unit.is_finite() || unit <= 0.0 {
            return Err("Invalid page scale".into());
        }
        let rect = |r: [f64; 4]| -> Result<[f64; 4], String> {
            if r.iter().any(|x| !x.is_finite() || x.abs() > 1e8) {
                return Err("Invalid object geometry".into());
            }
            let a = info.user_to_view(r[0] as f32, r[1] as f32);
            let b = info.user_to_view(r[2] as f32, r[3] as f32);
            let bounds = [
                f64::from(a[0].min(b[0])),
                f64::from(info.height - a[1].max(b[1])),
                f64::from(a[0].max(b[0])),
                f64::from(info.height - a[1].min(b[1])),
            ];
            if bounds.iter().any(|n| !n.is_finite()) {
                return Err("Invalid displayed object geometry".into());
            }
            Ok(bounds)
        };
        let mut out = Page { width: f64::from(info.width), height: f64::from(info.height), ..Default::default() };
        let mut text_bytes = self.text_bytes;
        for b in pdfcraft_edit::reading_blocks(&self.cos, page).map_err(|e| e.to_string())? {
            if b.text.trim().is_empty() {
                continue;
            }
            if b.text.contains('\u{fffd}')
                || !b.size.is_finite()
                || b.size <= 0.0
                || !b.rotation.is_finite()
                || (b.rotation - f64::from(info.rotation)).rem_euclid(360.0).min((f64::from(info.rotation) - b.rotation).rem_euclid(360.0)) > 0.1
            {
                return Err("Text cannot be safely reflowed".into());
            }
            text_bytes = text_bytes.saturating_add(b.text.len());
            if text_bytes > MAX_TEXT || out.blocks.len() >= MAX_BLOCKS {
                return Err("Too much page text".into());
            }
            let font = b.base_font.to_ascii_lowercase();
            out.blocks.push(Block {
                text: b.text,
                rect: rect(b.rect)?,
                size: (b.size * unit).clamp(4.0, 144.0),
                bold: font.contains("bold") || font.contains("black") || font.contains("heavy") || b.bold,
                italic: font.contains("italic") || font.contains("oblique") || b.italic,
                color: b.color,
                rotation: 0.0,
                space_around: [false, false],
            });
        }
        let mut media_bytes = self.image_bytes;
        for im in pdfcraft_edit::reading_images(&self.cos, page).map_err(|e| e.to_string())? {
            let object = im.object.ok_or("Inline image needs raster fallback")?;
            let raw = self.cos.get(object);
            let pdfcraft_cos::Object::Stream(stream) = &*raw else {
                return Err("Image is unavailable".into());
            };
            let w = stream.dict.int(b"Width").unwrap_or(0);
            let h = stream.dict.int(b"Height").unwrap_or(0);
            if w <= 0 || h <= 0 || w.saturating_mul(h) > 12_000_000 {
                return Err("Oversize image".into());
            }
            // Complex transforms/cropping are preserved by rendering the whole page.
            if info.rotation != 0 || im.matrix[0] <= 0.0 || im.matrix[3] <= 0.0 || im.matrix[1].abs() > 0.01 || im.matrix[2].abs() > 0.01 {
                return Err("Transformed image".into());
            }
            let bounds = rect(im.rect)?;
            if bounds[0] < -1.0 || bounds[1] < -1.0 || bounds[2] > out.width + 1.0 || bounds[3] > out.height + 1.0 {
                return Err("Cropped image".into());
            }
            let (ext, encoded) = pdfcraft_create::image_file(&self.cos, object)?;
            if encoded.len() > MAX_MEDIA {
                return Err("Image is too large".into());
            }
            // Decode and re-encode so corrupt JPEG bytes never enter an otherwise valid DOCX.
            let mut reader = image::ImageReader::new(std::io::Cursor::new(encoded)).with_guessed_format().map_err(|e| e.to_string())?;
            let mut limits = image::Limits::default();
            limits.max_alloc = Some(64 << 20);
            limits.max_image_width = Some(12000);
            limits.max_image_height = Some(12000);
            reader.limits(limits);
            let decoded = reader.decode().map_err(|e| e.to_string())?;
            let mut bytes = std::io::Cursor::new(Vec::new());
            let format = if ext == "jpg" { image::ImageFormat::Jpeg } else { image::ImageFormat::Png };
            decoded.write_to(&mut bytes, format).map_err(|e| e.to_string())?;
            let bytes = bytes.into_inner();
            media_bytes = media_bytes.saturating_add(bytes.len());
            if media_bytes > MAX_MEDIA || self.image_count + out.images.len() >= MAX_IMAGES {
                return Err("Too many picture bytes".into());
            }
            out.images.push(Image {
                ext: if ext == "jpg" { "jpg" } else { "png" },
                bytes: std::sync::Arc::<[u8]>::from(bytes),
                rect: bounds,
                size: [bounds[2] - bounds[0], bounds[3] - bounds[1]],
                rotation: 0.0,
            });
        }
        Ok(out)
    }

    fn raster_page(&self, page: usize, info: &PageInfo) -> Result<Page, String> {
        let source = self.source.clone();
        // Render at <= 150 dpi / 4 megapixels, preserving the complete visible page.
        let scale = (150.0_f64 / 72.0)
            .min((4_000_000.0 / (f64::from(info.width) * f64::from(info.height))).sqrt())
            .min(8190.0 / f64::from(info.width.max(info.height)));
        if !scale.is_finite() || scale < 0.25 {
            return Err("Page is too large for a Word image; export a smaller PDF page".into());
        }
        let bytes = Exporter::from_source(source).image(page, scale * 72.0, ImageFormat::Jpeg { quality: 92 })?;
        let (w, h) = (f64::from(info.width), f64::from(info.height));
        Ok(Page {
            width: w,
            height: h,
            blocks: Vec::new(),
            images: vec![Image { ext: "jpg", bytes: std::sync::Arc::<[u8]>::from(bytes), rect: [0.0, 0.0, w, h], size: [w, h], rotation: 0.0 }],
            ..Default::default()
        })
    }
}
