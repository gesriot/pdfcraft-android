use super::*;
use pdfcraft_engine::{AddedText, Protection};

fn document() -> Arc<MobileDocument> {
    let mut session = Session::new();
    let bytes = session.create_blank(300.0, 400.0, 3).unwrap();
    let id = session.open("word.pdf", None, bytes, None).unwrap();
    for page in 0..2 {
        session
            .apply(
                id,
                Edit::AddText { page, text: AddedText { rect: [20.0, 30.0, 280.0, 60.0], text: format!("Page {page}"), ..Default::default() } },
            )
            .unwrap();
    }
    MobileDocument::from_bytes("word.pdf".into(), session.save_bytes(id).unwrap(), None).unwrap()
}

#[test]
fn word_export_snapshot_selection_cancel_and_new_file_only() {
    let doc = document();
    let rev = doc.info().unwrap().revision;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("out.docx").to_string_lossy().into_owned();
    for pages in [vec![], vec![3], vec![1, 1], vec![0; 501]] {
        assert!(doc.begin_word_export(pages, rev).is_err());
    }
    let job = doc.begin_word_export(vec![1, 0], rev).unwrap();
    assert!(job.finish(path.clone()).is_err());
    assert!(!std::path::Path::new(&path).exists());
    assert_eq!(job.advance().unwrap(), 1);
    // Later source edits cannot alter the already-captured export.
    doc.rotate_page(0).unwrap();
    assert!(doc.begin_word_export(vec![0], rev).is_err());
    assert_eq!(job.advance().unwrap(), 2);
    std::fs::write(&path, b"existing").unwrap();
    assert!(job.finish(path.clone()).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), b"existing");
    std::fs::remove_file(&path).unwrap();
    assert!(job.finish(path.clone()).unwrap().is_empty());
    assert!(std::fs::read(&path).unwrap().starts_with(b"PK"));
    assert!(job.advance().is_err());
    let cancelled = doc.begin_word_export(vec![0], doc.info().unwrap().revision).unwrap();
    cancelled.release().unwrap();
    assert!(cancelled.advance().is_err());
    assert!(cancelled.finish(dir.path().join("cancelled.docx").to_string_lossy().into_owned()).is_err());
    assert!(!dir.path().join("cancelled.docx").exists());
}

#[test]
fn word_export_blank_and_rotated_pages_fall_back_without_changing_pdf() {
    let doc = document();
    doc.rotate_page(0).unwrap();
    let before = doc.with_state(|s| Ok(s.session.get(s.id).unwrap().bytes.clone())).unwrap();
    let rev = doc.info().unwrap().revision;
    let job = doc.begin_word_export(vec![2, 0, 1], rev).unwrap();
    for n in 1..=3 {
        assert_eq!(job.advance().unwrap(), n);
    }
    let dir = tempfile::tempdir().unwrap();
    let images = job.finish(dir.path().join("out.docx").to_string_lossy().into_owned()).unwrap();
    assert_eq!(images, vec![2, 0]);
    assert_eq!(doc.info().unwrap().revision, rev);
    assert_eq!(doc.with_state(|s| Ok(s.session.get(s.id).unwrap().bytes.clone())).unwrap(), before);
}

#[test]
fn word_export_after_password_open_ignores_advisory_flags_on_android() {
    let original = document();
    original
        .with_state(|s| {
            s.session
                .apply(
                    s.id,
                    Edit::Protect(Protection {
                        open_password: Some("read".into()),
                        permissions_password: Some("owner".into()),
                        ..Default::default()
                    }),
                )
                .map_err(engine_error)
        })
        .unwrap();
    let bytes = original.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
    assert!(MobileDocument::from_bytes("locked.pdf".into(), bytes.clone(), None).is_err());
    let doc = MobileDocument::from_bytes("locked.pdf".into(), bytes.clone(), Some("read")).unwrap();
    let job = doc.begin_word_export(vec![0], doc.info().unwrap().revision).unwrap();
    job.advance().unwrap();
    let dir = tempfile::tempdir().unwrap();
    job.finish(dir.path().join("out.docx").to_string_lossy().into_owned()).unwrap();
    assert!(doc.info().unwrap().encrypted);
    let mut strict = Session::new();
    let id = strict.open("strict.pdf", None, bytes, Some("read")).unwrap();
    assert!(strict.get(id).unwrap().prepare_word_export(&[0]).is_err());
}
