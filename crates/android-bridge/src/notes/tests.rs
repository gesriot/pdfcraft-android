use super::*;
use pdfcraft_engine::Protection;

fn revision(doc: &MobileDocument) -> u64 {
    doc.info().unwrap().revision
}
fn saved(doc: &MobileDocument) -> Arc<Vec<u8>> {
    doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap()
}
fn input(doc: &MobileDocument) -> TextNoteInput {
    TextNoteInput {
        page: 0,
        revision: revision(doc),
        bounds: TextRect { left: 40.0, top: 120.0, right: 300.0, bottom: 220.0 },
        text: "Моя заметка · My note\nЁж, № 42 — текст.".into(),
        size: 12.0,
        color: NoteColor::Blue,
    }
}

#[test]
fn russian_english_free_text_preview_undo_save_and_rotated_crop_geometry() {
    for rotation in 0..4 {
        let doc = crate::text::tests::fixture(false);
        for _ in 0..rotation {
            doc.rotate_page(0).unwrap();
        }
        let request = input(&doc);
        let before = saved(&doc);
        let pixels = doc.render_page(0, 570).unwrap();
        let preview = doc.preview_text_note(request.clone()).unwrap();
        assert_eq!(saved(&doc), before, "preview must leave bytes/history untouched");
        assert_eq!(revision(&doc), request.revision);
        assert!(preview.image.width <= 902 && preview.image.height <= 902);
        doc.apply_text_preview(preview.token).unwrap();
        assert!(doc.apply_text_preview(preview.token).is_err());
        let info = doc.info().unwrap();
        doc.with_state(|s| {
            let native = s.session.get(s.id).unwrap();
            let annotation = native.info.annotations.iter().find(|a| a.subtype == "FreeText").unwrap();
            assert_eq!(annotation.contents.as_deref(), Some(request.text.as_str()));
            let rect = TextRect::from_user(&native.info.pages[0], annotation.rect.map(f64::from)).unwrap();
            assert!((rect.left - request.bounds.left).abs() < 0.01);
            assert!((rect.top - request.bounds.top).abs() < 0.01);
            assert!((rect.right - request.bounds.right).abs() < 0.01);
            assert!((rect.bottom - request.bounds.bottom).abs() < 0.01);
            assert!(native.edit_notices.is_empty(), "no missing-character warning for Cyrillic");
            Ok(())
        })
        .unwrap();
        let actual = doc.render_tile(0, 3.0, 72, 312, preview.image.width, preview.image.height, info.revision).unwrap();
        assert_eq!(actual.rgba, preview.image.rgba, "preview must match applied PDF pixels");
        let after = doc.render_page(0, 570).unwrap();
        assert_ne!(after.rgba, pixels.rgba);
        // No white rectangle or altered neighbouring content outside the annotation.
        let scale = after.width as f32 / info.pages[0].width;
        for y in 0..after.height {
            for x in 0..after.width {
                let px = x as f32 / scale;
                let py = y as f32 / scale;
                if !(39.0..=301.0).contains(&px) || !(119.0..=221.0).contains(&py) {
                    let at = ((y * after.width + x) * 4) as usize;
                    assert_eq!(&after.rgba[at..at + 4], &pixels.rgba[at..at + 4]);
                }
            }
        }
        let reopened = MobileDocument::from_bytes("notes.pdf".into(), saved(&doc), None).unwrap();
        assert_eq!(reopened.render_page(0, 570).unwrap().rgba, after.rgba);
        doc.undo().unwrap();
        assert_eq!(doc.render_page(0, 570).unwrap().rgba, pixels.rgba);
        doc.redo().unwrap();
        assert_eq!(doc.render_page(0, 570).unwrap().rgba, after.rgba);
    }
}

#[test]
fn invalid_overflow_and_stale_notes_never_modify_working_document() {
    let doc = crate::text::tests::fixture(false);
    let before = saved(&doc);
    for text in ["".to_owned(), " ".into(), "漢".into(), "bad\0text".into(), "A".repeat(8193), "Много текста ".repeat(200)] {
        let mut request = input(&doc);
        request.text = text;
        assert!(doc.preview_text_note(request).is_err());
        assert_eq!(saved(&doc), before);
    }
    for size in [f64::NAN, f64::INFINITY, 0.0, 3.0, 145.0] {
        let mut request = input(&doc);
        request.size = size;
        assert!(doc.preview_text_note(request).is_err());
    }
    let mut bad = input(&doc);
    bad.bounds.left = -1.0;
    assert!(doc.preview_text_note(bad).is_err());
    let mut bad = input(&doc);
    bad.page = 900;
    assert!(doc.preview_text_note(bad).is_err());
    let preview = doc.preview_text_note(input(&doc)).unwrap();
    let newer = doc.preview_text_note(input(&doc)).unwrap();
    assert!(doc.apply_text_preview(preview.token).is_err());
    let old = input(&doc);
    doc.rotate_page(0).unwrap();
    assert!(doc.apply_text_preview(newer.token).is_err());
    assert!(doc.preview_text_note(old).is_err());
}

#[test]
fn opened_password_protected_pdf_allows_note_and_retains_encryption() {
    let doc = crate::text::tests::fixture(false);
    doc.with_state(|s| {
        s.session
            .apply(
                s.id,
                Edit::Protect(Protection {
                    open_password: Some("test-open".into()),
                    permissions_password: Some("test-owner".into()),
                    ..Default::default()
                }),
            )
            .map_err(engine_error)
    })
    .unwrap();
    let bytes = saved(&doc);
    assert!(matches!(MobileDocument::from_bytes("locked.pdf".into(), bytes.clone(), None), Err(MobileError::PasswordRequired)));
    let opened = MobileDocument::from_bytes("locked.pdf".into(), bytes, Some("test-open")).unwrap();
    let preview = opened.preview_text_note(input(&opened)).unwrap();
    opened.apply_text_preview(preview.token).unwrap();
    let bytes = saved(&opened);
    assert!(matches!(MobileDocument::from_bytes("saved.pdf".into(), bytes.clone(), None), Err(MobileError::PasswordRequired)));
    let reopened = MobileDocument::from_bytes("saved.pdf".into(), bytes, Some("test-open")).unwrap();
    assert!(reopened.info().unwrap().encrypted);
    assert_eq!(reopened.render_page(0, 570).unwrap().rgba, opened.render_page(0, 570).unwrap().rgba);
}
