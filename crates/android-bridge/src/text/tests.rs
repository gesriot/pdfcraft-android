use super::*;

pub(crate) fn fixture(invisible: bool) -> Arc<MobileDocument> {
    // Contributor-original text on a coloured background, with a separate neighbouring block.
    let mode = if invisible { "3 Tr" } else { "" };
    let content = format!(
        "0.94 0.96 0.8 rg 10 10 380 480 re f\nBT /F1 14 Tf 0.1 0.2 0.7 rg {mode} 35 440 Td (Original English text) Tj ET\nBT /F1 12 Tf 0 0 0 rg 35 80 Td (Keep this neighbour unchanged) Tj ET\n"
    );
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_owned(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 500] /CropBox [10 10 390 490] /UserUnit 1.5 /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>".to_owned(),
        format!("<< /Length {} >>\nstream\n{content}endstream", content.len()),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".to_owned(),
    ];
    let mut pdf = String::from("%PDF-1.7\n");
    let mut offsets = Vec::new();
    for (i, object) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.push_str(&format!("{} 0 obj\n{object}\nendobj\n", i + 1));
    }
    let xref = pdf.len();
    pdf.push_str("xref\n0 6\n0000000000 65535 f \n");
    for offset in offsets {
        pdf.push_str(&format!("{offset:010} 00000 n \n"));
    }
    pdf.push_str(&format!("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"));
    // Freeze ModDate: dirty saves legitimately use the session's current wall-clock time.
    let mut session = Session::new().with_clock(|| 1_800_000_000);
    let id = session.open("text.pdf", None, Arc::new(pdf.into_bytes()), None).unwrap();
    session.set_edit_permission_enforcement(id, false).unwrap();
    Arc::new(MobileDocument {
        state: Mutex::new(Some(State { session, id, pending_text: None, preview_serial: 0, reading_cache: Default::default() })),
    })
}

fn blocks(doc: &MobileDocument) -> TextPage {
    doc.text_blocks(0, doc.info().unwrap().revision).unwrap()
}

fn saved(doc: &MobileDocument) -> Arc<Vec<u8>> {
    doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap()
}

#[test]
fn original_text_edit_preview_history_and_reopen_at_all_page_rotations() {
    for rotation in 0..4 {
        let doc = fixture(false);
        for _ in 0..rotation {
            doc.rotate_page(0).unwrap();
        }
        let list = blocks(&doc);
        assert_eq!(list.blocks.len(), 2);
        let source = &list.blocks[0];
        let neighbour = &list.blocks[1];
        let before = saved(&doc);
        let pixels = doc.render_page(0, 570).unwrap();
        let preview = doc.preview_text_edit(0, source.index, list.revision, "Edited English text".into(), TextFont::Original, None).unwrap();
        assert_eq!(saved(&doc), before, "preview must not mutate bytes");
        assert_eq!(doc.info().unwrap().revision, list.revision);
        assert!(preview.image.width <= 902 && preview.image.height <= 902);
        assert!(preview.substituted_font.is_none());
        doc.apply_text_preview(preview.token).unwrap();
        assert!(doc.apply_text_preview(preview.token).is_err(), "preview is single-use");
        let after = blocks(&doc);
        assert!(after.blocks.iter().any(|b| b.text == "Edited English text" && b.font_name == source.font_name));
        assert!(
            after.blocks.iter().any(|b| b.text == neighbour.text && b.bounds.left == neighbour.bounds.left && b.bounds.top == neighbour.bounds.top)
        );
        let changed = doc.render_page(0, 570).unwrap();
        assert_ne!(changed.rgba, pixels.rgba);
        let scale = pixels.width as f32 / doc.info().unwrap().pages[0].width;
        let r = &source.bounds;
        for y in 0..pixels.height {
            for x in 0..pixels.width {
                let (px, py) = (x as f32 / scale, y as f32 / scale);
                if px < r.left - 4.0 || px > r.right + 4.0 || py < r.top - 4.0 || py > r.bottom + 4.0 {
                    let offset = ((y * pixels.width + x) * 4) as usize;
                    assert_eq!(&changed.rgba[offset..offset + 4], &pixels.rgba[offset..offset + 4], "untouched pixel {x},{y}, rotation {rotation}");
                }
            }
        }
        let reopened = MobileDocument::from_bytes("saved.pdf".into(), saved(&doc), None).unwrap();
        assert!(blocks(&reopened).blocks.iter().any(|b| b.text == "Edited English text"));
        assert_eq!(reopened.render_page(0, 570).unwrap().rgba, doc.render_page(0, 570).unwrap().rgba);
        doc.undo().unwrap();
        assert_eq!(saved(&doc), before);
        doc.redo().unwrap();
        assert!(blocks(&doc).blocks.iter().any(|b| b.text == "Edited English text"));
    }
}

#[test]
fn russian_english_font_embedding_is_one_edit_and_survives_reopen() {
    let doc = fixture(false);
    let before = saved(&doc);
    let list = blocks(&doc);
    let preview = doc.preview_text_edit(0, list.blocks[0].index, list.revision, "Привет, PDF! Ёж №1".into(), TextFont::Inter, Some(12.0)).unwrap();
    assert_eq!(preview.substituted_font.as_deref(), Some("Inter Regular"));
    assert!(!doc.info().unwrap().dirty);
    let before_image = doc.render_page(0, 760).unwrap();
    doc.apply_text_preview(preview.token).unwrap();
    let output = saved(&doc);
    let reopened = MobileDocument::from_bytes("русский.pdf".into(), output, None).unwrap();
    let joined = blocks(&reopened).blocks.into_iter().map(|b| b.text).collect::<Vec<_>>().join("\n");
    assert!(joined.contains("Привет, PDF! Ёж №1"), "{joined}");
    assert!(!joined.contains("Original"));
    assert!(joined.contains("Keep this neighbour unchanged"));
    assert!(reopened.render_page(0, 570).unwrap().rgba.chunks_exact(4).any(|p| p[0] < 100));
    if let Ok(path) = std::env::var("PDFCRAFT_EDITOR_ARTIFACT_DIR") {
        let path = std::path::Path::new(&path);
        std::fs::create_dir_all(path).unwrap();
        std::fs::write(path.join("edited-ru-en.pdf"), saved(&doc).as_slice()).unwrap();
        let after_image = reopened.render_page(0, 760).unwrap();
        for (name, image) in [("before", before_image), ("preview", preview.image), ("after", after_image)] {
            image::RgbaImage::from_raw(image.width, image.height, image.rgba).unwrap().save(path.join(format!("{name}.png"))).unwrap();
        }
    }
    // The saved embedded Unicode font must also support a later edit without Inter override.
    let again = blocks(&reopened);
    let russian = again.blocks.iter().find(|b| b.text.contains("Привет")).unwrap();
    let second = reopened.preview_text_edit(0, russian.index, again.revision, "Пока, PDF! Ёж №2".into(), TextFont::Original, None).unwrap();
    assert!(second.substituted_font.is_none());
    reopened.apply_text_preview(second.token).unwrap();
    assert!(blocks(&reopened).blocks.iter().any(|b| b.text.contains("Пока, PDF! Ёж №2")));
    doc.undo().unwrap();
    assert_eq!(saved(&doc), before, "font registration and replacement undo together");
    assert!(!doc.info().unwrap().can_undo);
    doc.redo().unwrap();
    assert!(blocks(&doc).blocks.iter().any(|b| b.text.contains("Привет")));
}

#[test]
fn stale_failed_and_oversized_drafts_do_not_change_document() {
    let doc = fixture(false);
    let list = blocks(&doc);
    let original = saved(&doc);
    let preview = doc.preview_text_edit(0, 0, list.revision, "Draft".into(), TextFont::Original, None).unwrap();
    for (text, size) in [
        ("a".repeat(MAX_TEXT_BYTES + 1), None),
        ("bad\0text".into(), None),
        ("bad".into(), Some(f64::NAN)),
        ("bad".into(), Some(301.0)),
        ("🦄".into(), None),
    ] {
        assert!(doc.preview_text_edit(0, 0, list.revision, text, TextFont::Inter, size).is_err());
        assert_eq!(saved(&doc), original);
        assert_eq!(doc.info().unwrap().revision, list.revision);
    }
    assert!(doc.apply_text_preview(preview.token).is_err());
    assert!(doc.preview_text_edit(0, 99, list.revision, "bad".into(), TextFont::Original, None).is_err());
    let next = doc.preview_text_edit(0, 0, list.revision, "Next".into(), TextFont::Original, None).unwrap();
    assert!(doc.apply_text_preview(next.token + 1).is_err());
    doc.rotate_page(0).unwrap();
    assert!(doc.apply_text_preview(next.token).is_err());
    assert!(doc.text_blocks(0, list.revision).is_err());
}

#[test]
fn deleting_text_and_overflow_preview_are_explicit() {
    let doc = fixture(false);
    let list = blocks(&doc);
    let long = doc.preview_text_edit(0, 0, list.revision, "A much longer paragraph of text. ".repeat(30), TextFont::Inter, Some(18.0)).unwrap();
    assert!(long.overflow);
    assert!(long.outside_page);
    assert!(!doc.info().unwrap().dirty);
    let empty = doc.preview_text_edit(0, 0, list.revision, String::new(), TextFont::Original, None).unwrap();
    doc.apply_text_preview(empty.token).unwrap();
    assert_eq!(blocks(&doc).blocks.len(), 1);
    assert!(blocks(&doc).blocks[0].text.contains("neighbour"));
    doc.undo().unwrap();
    assert_eq!(blocks(&doc).blocks.len(), 2);
}

#[test]
fn invisible_ocr_text_is_not_offered_as_editable_page_content() {
    let doc = fixture(true);
    let list = blocks(&doc);
    assert!(list.blocks.is_empty());
    assert_eq!(list.skipped, 2);
    assert!(doc.preview_text_edit(0, 0, list.revision, "replacement".into(), TextFont::Inter, None).is_err());
    assert!(!doc.info().unwrap().dirty);
}

#[test]
fn protected_document_can_be_edited_without_owner_password_and_stays_encrypted() {
    let doc = fixture(false);
    doc.with_state(|s| {
        s.session
            .apply(
                s.id,
                Edit::Protect(pdfcraft_engine::Protection { permissions_password: Some("synthetic-owner-password".into()), ..Default::default() }),
            )
            .map_err(engine_error)
    })
    .unwrap();
    let restricted = MobileDocument::from_bytes("protected.pdf".into(), saved(&doc), None).unwrap();
    let info = restricted.info().unwrap();
    assert!(info.can_edit_text);
    assert_eq!(restricted.text_blocks(0, info.revision).unwrap().blocks.len(), 2);
    let preview = restricted.preview_text_edit(0, 0, info.revision, "Разрешено".into(), TextFont::Inter, None).unwrap();
    restricted.apply_text_preview(preview.token).unwrap();
    let edited = saved(&restricted);
    let reopened = MobileDocument::from_bytes("edited.pdf".into(), edited.clone(), None).unwrap();
    assert!(blocks(&reopened).blocks.iter().any(|b| b.text.contains("Разрешено")));
    reopened
        .with_state(|s| {
            let d = s.session.get(s.id).unwrap();
            assert!(d.permissions().is_some());
            assert!(!d.permissions().unwrap().modify(), "saved encryption permissions remain intact");
            Ok(())
        })
        .unwrap();
    // Other frontends keep their default policy.
    let mut desktop = Session::new();
    let id = desktop.open("edited.pdf", None, edited, None).unwrap();
    assert!(desktop.apply(id, Edit::EditTextBlock { page: 0, block: 0, text: "x".into(), style: Default::default() }).is_err());
}

#[test]
fn opening_password_is_still_required_and_saves_remain_encrypted() {
    let doc = fixture(false);
    doc.with_state(|s| {
        s.session
            .apply(
                s.id,
                Edit::Protect(pdfcraft_engine::Protection {
                    open_password: Some("synthetic-open-password".into()),
                    permissions_password: Some("synthetic-owner-password".into()),
                    ..Default::default()
                }),
            )
            .map_err(engine_error)
    })
    .unwrap();
    let bytes = saved(&doc);
    assert!(matches!(MobileDocument::from_bytes("locked.pdf".into(), bytes.clone(), None), Err(MobileError::PasswordRequired)));
    assert!(matches!(MobileDocument::from_bytes("locked.pdf".into(), bytes.clone(), Some("wrong")), Err(MobileError::WrongPassword)));
    let opened = MobileDocument::from_bytes("locked.pdf".into(), bytes, Some("synthetic-open-password")).unwrap();
    let preview = opened.preview_text_edit(0, 0, opened.info().unwrap().revision, "Edited".into(), TextFont::Original, None).unwrap();
    opened.apply_text_preview(preview.token).unwrap();
    let bytes = saved(&opened);
    assert!(matches!(MobileDocument::from_bytes("saved.pdf".into(), bytes.clone(), None), Err(MobileError::PasswordRequired)));
    let reopened = MobileDocument::from_bytes("saved.pdf".into(), bytes, Some("synthetic-open-password")).unwrap();
    assert!(blocks(&reopened).blocks.iter().any(|b| b.text == "Edited"));
}
