use super::*;

fn fixture() -> Arc<MobileDocument> {
    // Original test artwork: shared one-pixel red bitmap drawn twice on page 1 and once
    // on page 2, plus a Form with text. Deletion must remove only one placement.
    let content = "q 60 0 0 40 20 100 cm /Im Do Q q 40 0 0 40 150 100 cm /Im Do Q q 1 0 0 1 30 200 cm /Group Do Q BT /F1 12 Tf 20 40 Td (Neighbour stays) Tj ET\n";
    let second = "q 80 0 0 60 30 80 cm /Im Do Q\n";
    let group = "0 0 1 rg 0 0 80 30 re f BT /F1 10 Tf 0 0 0 rg 2 10 Td (Caption) Tj ET\n";
    let objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".into(),
        "<< /Type /Pages /Kids [3 0 R 8 0 R] /Count 2 >>".into(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /CropBox [10 10 290 390] /UserUnit 1.5 /Resources << /Font << /F1 5 0 R >> /XObject << /Im 6 0 R /Group 7 0 R >> >> /Contents 4 0 R >>".into(),
        format!("<< /Length {} >>\nstream\n{content}endstream", content.len()),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".into(),
        "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /ASCIIHexDecode /Length 7 >>\nstream\nFF0000>\nendstream".into(),
        format!("<< /Type /XObject /Subtype /Form /BBox [0 0 80 30] /Resources << /Font << /F1 5 0 R >> >> /Length {} >>\nstream\n{group}endstream", group.len()),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /XObject << /Im 6 0 R >> >> /Contents 9 0 R >>".into(),
        format!("<< /Length {} >>\nstream\n{second}endstream", second.len()),
    ];
    let mut pdf = String::from("%PDF-1.7\n");
    let mut offsets = vec![];
    for (i, obj) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.push_str(&format!("{} 0 obj\n{obj}\nendobj\n", i + 1));
    }
    let start = pdf.len();
    pdf.push_str(&format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1));
    for offset in offsets {
        pdf.push_str(&format!("{offset:010} 00000 n \n"));
    }
    pdf.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n", objects.len() + 1));
    MobileDocument::from_bytes("objects.pdf".into(), Arc::new(pdf.into_bytes()), None).unwrap()
}

#[test]
fn delete_only_selected_placement_and_preserve_other_page_at_all_rotations() {
    for turns in 0..4 {
        let doc = fixture();
        for _ in 0..turns {
            doc.rotate_page(0).unwrap();
        }
        let rev = doc.info().unwrap().revision;
        let list = doc.artwork_blocks(0, rev).unwrap();
        assert_eq!(list.blocks.len(), 3);
        let target = &list.blocks[0];
        let before = doc.render_page(0, 420).unwrap();
        let other = doc.render_page(1, 420).unwrap();
        doc.delete_artwork(0, rev, target.kind, target.index).unwrap();
        assert!(doc.delete_artwork(0, rev, target.kind, target.index).is_err());
        let next = doc.info().unwrap().revision;
        assert_eq!(doc.artwork_blocks(0, next).unwrap().blocks.len(), 2);
        assert_eq!(doc.render_page(1, 420).unwrap().rgba, other.rgba);
        let after = doc.render_page(0, 420).unwrap();
        assert_ne!(after.rgba, before.rgba);
        let scale = before.width as f32 / doc.info().unwrap().pages[0].width;
        let r = &target.bounds;
        for y in 0..before.height {
            for x in 0..before.width {
                let (px, py) = (x as f32 / scale, y as f32 / scale);
                if px < r.left - 2.0 || px > r.right + 2.0 || py < r.top - 2.0 || py > r.bottom + 2.0 {
                    let i = ((y * before.width + x) * 4) as usize;
                    assert_eq!(&before.rgba[i..i + 4], &after.rgba[i..i + 4]);
                }
            }
        }
        assert_eq!(doc.search_page(0, next, "Neighbour stays".into(), false, false, 10).unwrap().hits.len(), 1);
        doc.undo().unwrap();
        assert_eq!(doc.render_page(0, 420).unwrap().rgba, before.rgba);
        doc.redo().unwrap();
        assert_eq!(doc.render_page(0, 420).unwrap().rgba, after.rgba);
        let bytes = doc.with_state(|s| s.session.save_bytes(s.id).map_err(engine_error)).unwrap();
        let reopened = MobileDocument::from_bytes("saved.pdf".into(), bytes, None).unwrap();
        assert_eq!(reopened.artwork_blocks(0, reopened.info().unwrap().revision).unwrap().blocks.len(), 2);
        assert_eq!(reopened.render_page(0, 420).unwrap().rgba, after.rgba);
    }
}

#[test]
fn form_is_explicitly_grouped_and_deletion_removes_its_caption_together() {
    let doc = fixture();
    let rev = doc.info().unwrap().revision;
    let block = doc.artwork_blocks(0, rev).unwrap().blocks.into_iter().find(|b| b.grouped).unwrap();
    assert_eq!(doc.search_page(0, rev, "Caption".into(), false, true, 10).unwrap().hits.len(), 1);
    doc.delete_artwork(0, rev, block.kind, block.index).unwrap();
    let next = doc.info().unwrap().revision;
    assert!(doc.search_page(0, next, "Caption".into(), false, true, 10).unwrap().hits.is_empty());
    assert_eq!(doc.artwork_blocks(0, next).unwrap().blocks.len(), 2);
    assert!(doc.delete_artwork(0, next, ArtworkKind::Image, 999).is_err());
}

#[test]
fn added_image_uses_added_inventory_once_and_undo_restores_it() {
    let doc = fixture();
    let mut png = std::io::Cursor::new(Vec::new());
    image::DynamicImage::new_rgb8(3, 2).write_to(&mut png, image::ImageFormat::Png).unwrap();
    doc.with_state(|s| {
        s.session
            .apply(
                s.id,
                Edit::AddImage { page: 0, rect: Some([30.0, 300.0, 90.0, 340.0]), name: "test.png".into(), bytes: Arc::new(png.into_inner()) },
            )
            .map_err(engine_error)
    })
    .unwrap();
    let rev = doc.info().unwrap().revision;
    let list = doc.artwork_blocks(0, rev).unwrap();
    assert_eq!(list.blocks.len(), 4);
    let block = list.blocks.iter().find(|b| b.kind == ArtworkKind::Added).unwrap();
    doc.delete_artwork(0, rev, block.kind, block.index).unwrap();
    assert_eq!(doc.artwork_blocks(0, doc.info().unwrap().revision).unwrap().blocks.len(), 3);
    doc.undo().unwrap();
    assert_eq!(doc.artwork_blocks(0, doc.info().unwrap().revision).unwrap().blocks.len(), 4);
}
