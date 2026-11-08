//! FreeText with an explicitly supplied, openly licensed Unicode font and a portable /AP.
use super::*;
use crate::create::{normalize, num_array};
use crate::lookup::annot_ref;
use pdfcraft_fonts::{embedding, pdf::Metrics};

/// Add a transparent, borderless text box with an embedded font. Coordinates/font size are
/// user space; the appearance counterrotates with the page. Text must fit: never silently clip.
/// Callers apply this to a snapshot so a font/layout failure leaves their working PDF intact.
pub fn add(doc: &mut Document, new: &NewAnnotation, font: &[u8], meta: &Meta) -> Result<usize, AnnotError> {
    let Shape::TextBox { rect, font_size } = new.shape else {
        return Err(AnnotError::Invalid("an embedded text field must be a TextBox".into()));
    };
    if new.contents.trim().is_empty() || new.contents.len() > 8192 || new.contents.chars().any(|c| c.is_control() && c != '\n') {
        return Err(AnnotError::Invalid("Введите текст до 8 КиБ; поддерживаются переносы строк".into()));
    }
    if !font_size.is_finite() || font_size <= 0.0 || rect.iter().any(|v| !v.is_finite()) {
        return Err(AnnotError::Invalid("Некорректный размер текстового поля".into()));
    }
    if new.style.color.iter().any(|v| !v.is_finite() || !(0.0..=1.0).contains(v)) {
        return Err(AnnotError::Invalid("Некорректный цвет текста".into()));
    }
    if new.style.width != 0.0 || new.style.fill.is_some() || new.style.opacity != 1.0 {
        return Err(AnnotError::Invalid("embedded text uses no border/background and full opacity".into()));
    }
    embedding::validate_document_input(font).map_err(AnnotError::Invalid)?;
    if !embedding::covers(font, &new.contents) {
        return Err(AnnotError::Invalid("Шрифт не содержит один из введённых символов".into()));
    }
    let page = pdfcraft_model::pages(doc).get(new.page).cloned().ok_or(AnnotError::NoSuchPage(new.page))?;
    let rotation = page.rotation(doc);
    let rect = normalize(rect);
    let (w, h) = if matches!(rotation, 90 | 270) { (rect[3] - rect[1], rect[2] - rect[0]) } else { (rect[2] - rect[0], rect[3] - rect[1]) };
    let pad = font_size * 0.12;
    if !w.is_finite() || !h.is_finite() || w <= pad * 2.0 || h <= pad * 2.0 {
        return Err(AnnotError::Invalid("Увеличьте текстовое поле или уменьшите шрифт".into()));
    }
    let mut resources = page.dict.get(b"Resources").and_then(|o| doc.dict(o)).unwrap_or_default();
    let mut fonts = resources.get(b"Font").and_then(|o| doc.dict(o)).unwrap_or_default();
    let (_, name) = embedding::names(font).map_err(AnnotError::Invalid)?;
    let resource = embedding::embed(doc, &mut fonts, font, &name).map_err(AnnotError::Invalid)?;
    let dict = fonts.get(resource.as_bytes()).and_then(|o| doc.dict(o)).ok_or_else(|| AnnotError::Invalid("font was not registered".into()))?;
    let metrics = Metrics::from_dict(doc, &dict);
    let measure = |text: &str, size: f64| -> f64 {
        metrics.encode(text).map_or(f64::INFINITY, |encoded| metrics.codes(&encoded).into_iter().map(|(c, _)| metrics.width(c) * size).sum())
    };
    let lines = pdfcraft_fonts::wrap_with(&new.contents, font_size, w - pad * 2.0, measure);
    let leading = (metrics.ascent - metrics.descent).max(1.2) * font_size;
    let needed = pad * 2.0 + (metrics.ascent - metrics.descent) * font_size + lines.len().saturating_sub(1) as f64 * leading;
    if lines.len() > 256 || needed > h + 0.001 || lines.iter().any(|s| measure(s, font_size) > w - 2.0 * pad + 0.001) {
        return Err(AnnotError::Invalid("Текст не помещается. Увеличьте поле, уменьшите шрифт или сократите текст.".into()));
    }
    resources.set(b"Font".to_vec(), Object::Dict(fonts.clone()));
    doc.update_dict(page.obj, |d| d.set(b"Resources".to_vec(), Object::Dict(resources)))?;
    let index = add_annotation(doc, new, meta)?;
    let (_, reference) = annot_ref(doc, new.page, index)?;
    let [r, g, b] = new.style.color;
    let mut content = format!("q 0 0 {} {} re W n\nBT /{} {} Tf {} {} {} rg\n", n(w), n(h), resource, n(font_size), n(r), n(g), n(b)).into_bytes();
    for (i, line) in lines.iter().enumerate() {
        let y = h - pad - metrics.ascent * font_size - i as f64 * leading;
        content.extend(format!("1 0 0 1 {} {} Tm ", n(pad), n(y)).bytes());
        let encoded = metrics.encode(line).ok_or_else(|| AnnotError::Invalid("Не удалось закодировать текст".into()))?;
        content.extend(pdfcraft_fonts::literal(&encoded));
        content.extend_from_slice(b" Tj\n");
    }
    content.extend_from_slice(b"ET Q\n");
    let mut appearance = Dict::new();
    appearance.set(b"Type".to_vec(), Object::name("XObject"));
    appearance.set(b"Subtype".to_vec(), Object::name("Form"));
    appearance.set(b"BBox".to_vec(), num_array(&[0.0, 0.0, w, h]));
    appearance.set(b"Matrix".to_vec(), num_array(&pdfcraft_model::view_matrix_for(rotation, rect)));
    let mut ap_resources = Dict::new();
    ap_resources.set(b"Font".to_vec(), Object::Dict(fonts));
    appearance.set(b"Resources".to_vec(), Object::Dict(ap_resources.clone()));
    let ap = doc.add(Object::Stream(Stream::flate(appearance, &content)));
    let mut ap_dict = Dict::new();
    ap_dict.set(b"N".to_vec(), Object::Ref(ap));
    doc.update_dict(reference, |d| {
        d.set(b"AP".to_vec(), Object::Dict(ap_dict));
        d.set(b"DA".to_vec(), PdfString::literal(format!("/{resource} {} Tf {} {} {} rg", n(font_size), n(r), n(g), n(b)).into_bytes()));
        d.set(b"DR".to_vec(), Object::Dict(ap_resources));
        d.remove(b"RC");
        d.remove(b"DS");
        d.remove(b"C");
        pdfcraft_fonts::set_undrawn_mark(d, None);
    })?;
    Ok(index)
}
