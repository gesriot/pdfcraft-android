use super::*;
use pdfcraft_engine::{AddedText, Protection};

fn numbered() -> Arc<MobileDocument> {
    let mut session = Session::new().with_clock(|| 1_800_000_000);
    let bytes = session.create_blank(200.0, 300.0, 4).unwrap();
    let id = session.open("numbered.pdf", None, bytes, None).unwrap();
    for page in 0..4 {
        session
            .apply(
                id,
                Edit::AddText { page, text: AddedText { rect: [20.0, 40.0, 180.0, 80.0], text: format!("Page {page}"), ..Default::default() } },
            )
            .unwrap();
    }
    let bytes = session.save_bytes(id).unwrap();
    MobileDocument::from_bytes("numbered.pdf".into(), bytes, None).unwrap()
}
fn revision(doc: &MobileDocument) -> u64 {
    doc.info().unwrap().revision
}
fn contents(doc: &MobileDocument) -> Vec<String> {
    (0..doc.info().unwrap().page_count).map(|p| doc.reading_page(p, revision(doc)).unwrap().glyphs.into_iter().map(|g| g.text).collect()).collect()
}
fn save(doc: &MobileDocument) -> Arc<Vec<u8>> {
    doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap()
}
fn path(dir: &tempfile::TempDir, name: &str) -> String {
    dir.path().join(name).to_string_lossy().into_owned()
}

#[test]
fn new_blank_document_insert_remove_undo_and_save() {
    let doc = create_document("Новый документ.pdf".into(), false).unwrap();
    let info = doc.info().unwrap();
    assert_eq!(info.page_count, 1);
    assert!(!info.encrypted);
    assert!((info.pages[0].width - A4.0 as f32).abs() < 0.01);
    assert!(doc.remove_pages(vec![0], info.revision).is_err());
    doc.insert_blank_page(0, true, info.revision).unwrap();
    assert!(doc.info().unwrap().pages[0].width > doc.info().unwrap().pages[0].height);
    doc.remove_pages(vec![1], revision(&doc)).unwrap();
    doc.undo().unwrap();
    assert_eq!(doc.info().unwrap().page_count, 2);
    doc.undo().unwrap();
    assert_eq!(doc.info().unwrap().page_count, 1);
    doc.redo().unwrap();
    let reopened = MobileDocument::from_bytes("new.pdf".into(), save(&doc), None).unwrap();
    assert_eq!(reopened.info().unwrap().page_count, 2);
    assert!(create_document("\0".into(), false).is_err());
}

#[test]
fn moving_nonadjacent_pages_is_one_undo_step_and_survives_reopen() {
    let doc = numbered();
    let original = contents(&doc);
    let rev = revision(&doc);
    doc.move_pages(vec![2, 0], 2, rev).unwrap();
    assert_eq!(contents(&doc), vec![original[1].clone(), original[3].clone(), original[0].clone(), original[2].clone()]);
    assert!(doc.move_pages(vec![1], 0, rev).is_err());
    doc.undo().unwrap();
    assert_eq!(contents(&doc), original);
    assert!(!doc.info().unwrap().can_undo);
    doc.redo().unwrap();
    let reopened = MobileDocument::from_bytes("moved.pdf".into(), save(&doc), None).unwrap();
    assert_eq!(contents(&reopened), contents(&doc));
}

#[test]
fn invalid_and_noop_organizer_operations_leave_history_unchanged() {
    let doc = numbered();
    let rev = revision(&doc);
    for selected in [vec![], vec![4], vec![0, 0], vec![0; 501]] {
        assert!(doc.move_pages(selected, 0, rev).is_err());
    }
    assert!(doc.move_pages(vec![0, 1], 3, rev).is_err());
    assert!(doc.insert_blank_page(5, false, rev).is_err());
    assert!(doc.remove_pages(vec![0, 1, 2, 3], rev).is_err());
    doc.move_pages(vec![1, 2], 1, rev).unwrap();
    doc.move_pages(vec![0, 1, 2, 3], 0, rev).unwrap();
    assert_eq!(revision(&doc), rev);
    assert!(!doc.info().unwrap().can_undo);
}

#[test]
fn extraction_keeps_requested_order_text_and_source_history() {
    let doc = numbered();
    let original = contents(&doc);
    let rev = revision(&doc);
    let dir = tempfile::tempdir().unwrap();
    let out = path(&dir, "pages.pdf");
    doc.extract_pages(vec![3, 1], rev, out.clone()).unwrap();
    let extracted = open_document(out.clone(), None).unwrap();
    assert_eq!(contents(&extracted), vec![original[3].clone(), original[1].clone()]);
    assert_eq!(contents(&doc), original);
    assert_eq!(revision(&doc), rev);
    assert!(!doc.info().unwrap().can_undo);
    assert!(!extracted.info().unwrap().encrypted);
    let before = std::fs::read(&out).unwrap();
    assert!(doc.extract_pages(vec![0], rev, out.clone()).is_err());
    assert_eq!(std::fs::read(&out).unwrap(), before);
    assert!(doc.extract_pages(vec![], rev, path(&dir, "empty.pdf")).is_err());
    doc.rotate_page(0).unwrap();
    assert!(doc.extract_pages(vec![0], rev, path(&dir, "stale.pdf")).is_err());
    assert!(!dir.path().join("stale.pdf").exists());
}

#[test]
fn png_and_jpeg_export_current_full_page_at_requested_resolution() {
    let doc = numbered();
    doc.rotate_page(0).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let rev = revision(&doc);
    for (format, ext) in [(PageImageFormat::Png, "png"), (PageImageFormat::Jpeg, "jpg")] {
        let out = path(&dir, &format!("page.{ext}"));
        let result = doc.export_page_image(0, rev, out.clone(), format, 150).unwrap();
        assert_eq!((result.width, result.height), (625, 417));
        assert!(!result.reduced);
        let bytes = std::fs::read(&out).unwrap();
        assert_eq!(image::guess_format(&bytes).unwrap(), if ext == "png" { image::ImageFormat::Png } else { image::ImageFormat::Jpeg });
        let decoded = image::load_from_memory(&bytes).unwrap().to_rgba8();
        assert_eq!((decoded.width(), decoded.height()), (625, 417));
        let rendered = doc.render_page(0, 625).unwrap();
        if ext == "png" {
            assert_eq!(decoded.as_raw(), &rendered.rgba);
        } else {
            let mean = decoded.as_raw().iter().zip(&rendered.rgba).map(|(a, b)| f64::from(a.abs_diff(*b))).sum::<f64>() / rendered.rgba.len() as f64;
            assert!(mean < 2.0, "JPEG pixels differ too much: {mean}");
        }
        assert!(doc.export_page_image(0, rev, out.clone(), format, 150).is_err());
        assert_eq!(std::fs::read(out).unwrap(), bytes);
    }
    assert_eq!(revision(&doc), rev);
    assert!(doc.export_page_image(0, rev, path(&dir, "bad.png"), PageImageFormat::Png, 0).is_err());
    assert!(doc.export_page_image(9, rev, path(&dir, "bad.png"), PageImageFormat::Png, 150).is_err());
}

#[test]
fn oversized_image_export_reduces_resolution_and_reports_it() {
    let bytes = Session::new().create_blank(10000.0, 20.0, 1).unwrap();
    let doc = MobileDocument::from_bytes("wide.pdf".into(), bytes, None).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let result = doc.export_page_image(0, revision(&doc), path(&dir, "wide.png"), PageImageFormat::Png, 300).unwrap();
    assert!(result.reduced && result.dpi_used < 72.0);
    assert!(result.width <= 8192 && u64::from(result.width) * u64::from(result.height) <= 12_000_000);
}

#[test]
fn authorized_android_page_exports_preserve_source_encryption_and_default_engine_policy() {
    let doc = numbered();
    doc.with_state(|s| {
        s.session
            .apply(s.id, Edit::Protect(Protection { permissions_password: Some("test-owner".into()), ..Default::default() }))
            .map_err(engine_error)
    })
    .unwrap();
    let bytes = save(&doc);
    let locked = MobileDocument::from_bytes("restricted.pdf".into(), bytes.clone(), None).unwrap();
    let dir = tempfile::tempdir().unwrap();
    assert!(locked.info().unwrap().can_edit_text);
    locked.move_pages(vec![0], 3, revision(&locked)).unwrap();
    locked.extract_pages(vec![0], revision(&locked), path(&dir, "android.pdf")).unwrap();
    locked.export_page_image(0, revision(&locked), path(&dir, "android.png"), PageImageFormat::Png, 72).unwrap();
    assert!(locked.info().unwrap().encrypted);
    let mut strict = Session::new();
    let id = strict.open("strict.pdf", None, bytes.clone(), None).unwrap();
    assert!(strict.extract(id, &[0]).is_err());
    assert!(strict.get(id).unwrap().check_page_image_export().is_err());
    assert!(strict.get(id).unwrap().check_copying().is_err());
    strict.set_page_export_permission_enforcement(id, false).unwrap();
    assert!(strict.extract(id, &[0]).is_ok());
    assert!(strict.get(id).unwrap().check_page_image_export().is_ok());
    assert!(strict.get(id).unwrap().check_copying().is_err());
    strict.set_page_export_permission_enforcement(id, true).unwrap();
    assert!(strict.extract(id, &[0]).is_err());
    let owner = MobileDocument::from_bytes("owner.pdf".into(), bytes, Some("test-owner")).unwrap();
    owner.extract_pages(vec![0], revision(&owner), path(&dir, "allowed.pdf")).unwrap();
    assert!(!open_document(path(&dir, "allowed.pdf"), None).unwrap().info().unwrap().encrypted);
    owner.export_page_image(0, revision(&owner), path(&dir, "allowed.png"), PageImageFormat::Png, 72).unwrap();
    assert!(owner.info().unwrap().encrypted);
}

#[test]
fn opened_restricted_sources_and_targets_support_normal_android_workflows() {
    let original = numbered();
    let unrestricted = save(&original);
    original
        .with_state(|s| {
            s.session
                .apply(s.id, Edit::Protect(Protection { permissions_password: Some("synthetic-owner".into()), ..Default::default() }))
                .map_err(engine_error)
        })
        .unwrap();
    let bytes = save(&original);
    let dir = tempfile::tempdir().unwrap();
    let source = path(&dir, "restricted-source.pdf");
    std::fs::write(&source, bytes.as_slice()).unwrap();
    let target = create_document("target.pdf".into(), false).unwrap();
    assert_eq!(target.insert_file(source.clone(), "restricted-source.pdf".into(), 1, Some(vec![3, 1])).unwrap(), 2);
    assert!(contents(&target)[1].contains("Page 3"));
    assert!(contents(&target)[2].contains("Page 1"));
    target.undo().unwrap();
    assert_eq!(target.info().unwrap().page_count, 1);
    target.redo().unwrap();
    let all = create_document("all.pdf".into(), false).unwrap();
    assert_eq!(all.insert_file(source.clone(), "restricted-source.pdf".into(), 1, None).unwrap(), 4);
    assert_eq!(std::fs::read(source).unwrap(), *bytes, "import must never rewrite source bytes");

    let opened = MobileDocument::from_bytes("restricted.pdf".into(), bytes, None).unwrap();
    assert!(opened.info().unwrap().can_edit_text);
    // Android's copy command uses this same glyph payload, with no permissions gate.
    let page = opened.reading_page(0, revision(&opened)).unwrap();
    assert!(page.glyphs.iter().map(|g| g.text.as_str()).collect::<String>().contains("Page 0"));
    assert!(!opened.search_page(0, revision(&opened), "Page".into(), false, false, 10).unwrap().hits.is_empty());
    let blocks = opened.text_blocks(0, revision(&opened)).unwrap();
    let preview = opened.preview_text_edit(0, blocks.blocks[0].index, blocks.revision, "Текст Text".into(), TextFont::Inter, None).unwrap();
    opened.apply_text_preview(preview.token).unwrap();
    assert!(contents(&opened)[0].contains("Текст Text"));
    opened.undo().unwrap();
    opened.redo().unwrap();
    opened.rotate_page(0).unwrap();
    opened.insert_blank_page(1, false, revision(&opened)).unwrap();
    opened.remove_pages(vec![1], revision(&opened)).unwrap();
    opened.move_pages(vec![0], 3, revision(&opened)).unwrap();
    let input = path(&dir, "ordinary.pdf");
    std::fs::write(&input, unrestricted.as_slice()).unwrap();
    assert_eq!(opened.insert_file(input, "ordinary.pdf".into(), 4, Some(vec![0])).unwrap(), 1);
    opened.extract_pages(vec![3], revision(&opened), path(&dir, "extract.pdf")).unwrap();
    opened.export_page_image(3, revision(&opened), path(&dir, "page.jpg"), PageImageFormat::Jpeg, 72).unwrap();
    opened.export_copy(path(&dir, "saved.pdf")).unwrap();
    let reopened = open_document(path(&dir, "saved.pdf"), None).unwrap();
    assert!(reopened.info().unwrap().encrypted);
    assert!(contents(&reopened)[3].contains("Текст Text"));
}
