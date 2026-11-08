use super::*;
use crate::text::tests::fixture;

#[test]
fn search_english_case_words_limits_and_invalid_queries() {
    let doc = fixture(false);
    let rev = doc.info().unwrap().revision;
    let find = |q: &str, case, words, limit| doc.search_page(0, rev, q.into(), case, words, limit).unwrap();
    assert_eq!(find("ENGLISH", false, true, 100).hits.len(), 1);
    assert!(find("ENGLISH", true, false, 100).hits.is_empty());
    assert!(find("Eng", false, true, 100).hits.is_empty());
    assert_eq!(find("Eng", false, false, 100).hits.len(), 1);
    assert!(find("e", false, false, 1).truncated);
    for query in [String::new(), " ".into(), "x".repeat(1025)] {
        assert!(doc.search_page(0, rev, query, false, false, 10).is_err());
    }
    assert!(doc.search_page(0, rev, "e".into(), false, false, 0).is_err());
    assert!(doc.reading_page(99, rev).is_err());
}

#[test]
fn russian_unicode_search_after_edit_undo_and_reopen() {
    let doc = fixture(false);
    let old = doc.info().unwrap().revision;
    doc.reading_page(0, old).unwrap(); // populate cache before the change
    let draft = doc.preview_text_edit(0, 0, old, "Привет мир! English text".into(), TextFont::Inter, None).unwrap();
    doc.apply_text_preview(draft.token).unwrap();
    assert!(doc.reading_page(0, old).is_err());
    let rev = doc.info().unwrap().revision;
    let hit = doc.search_page(0, rev, "ПРИВЕТ".into(), false, true, 10).unwrap();
    assert_eq!(hit.hits.len(), 1);
    let page = doc.reading_page(0, rev).unwrap();
    let h = &hit.hits[0];
    let selected: String = page.glyphs[h.start as usize..h.end as usize].iter().map(|g| g.text.as_str()).collect();
    assert_eq!(selected, "Привет");
    let bytes = doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
    let reopened = MobileDocument::from_bytes("copy.pdf".into(), bytes, None).unwrap();
    assert_eq!(reopened.search_page(0, reopened.info().unwrap().revision, "мир".into(), false, true, 10).unwrap().hits.len(), 1);
    doc.undo().unwrap();
    assert!(doc.search_page(0, doc.info().unwrap().revision, "Привет".into(), false, false, 10).unwrap().hits.is_empty());
    doc.redo().unwrap();
    assert_eq!(doc.search_page(0, doc.info().unwrap().revision, "Привет".into(), false, false, 10).unwrap().hits.len(), 1);
}

#[test]
fn geometry_is_display_space_at_every_rotation_and_existing_ocr_is_readable() {
    let doc = fixture(true);
    for _ in 0..4 {
        let info = doc.info().unwrap();
        let p = doc.reading_page(0, info.revision).unwrap();
        assert!(!p.glyphs.is_empty());
        let result = doc.search_page(0, info.revision, "Original English".into(), false, false, 10).unwrap();
        assert_eq!(result.hits.len(), 1);
        for b in &result.hits[0].bounds {
            assert!(b.left >= 0.0 && b.top >= 0.0 && b.right <= info.pages[0].width && b.bottom <= info.pages[0].height);
        }
        doc.rotate_page(0).unwrap();
    }
}
