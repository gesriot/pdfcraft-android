package org.pdfcraft.android

import org.pdfcraft.android.engine.NoteColor
import org.pdfcraft.android.engine.TextNoteInput
import org.pdfcraft.android.engine.TextRect

data class TextNoteDraft(val snapshot: PageSnapshot, val page: Int, val left: Float, val top: Float,
    val width: String, val height: String, val value: String = "", val size: String = "16", val color: NoteColor = NoteColor.BLACK)

fun placeTextNote(snapshot: PageSnapshot, point: PagePoint, dimensions: PageDimensions, previous: TextNoteDraft? = null): TextNoteDraft {
    require(point.x.isFinite() && point.y.isFinite() && dimensions.width >= 4f && dimensions.height >= 4f)
    val width = previous?.width?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(4f, dimensions.width)
        ?: 240f.coerceAtMost(dimensions.width)
    val height = previous?.height?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(4f, dimensions.height)
        ?: 96f.coerceAtMost(dimensions.height)
    return TextNoteDraft(snapshot, point.page, point.x.coerceIn(0f, dimensions.width - width), point.y.coerceIn(0f, dimensions.height - height),
        width.toString(), height.toString(), previous?.value.orEmpty(), previous?.size ?: "16", previous?.color ?: NoteColor.BLACK)
}
fun noteBounds(draft: TextNoteDraft): DisplayRect? {
    val width = draft.width.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it >= 4f } ?: return null
    val height = draft.height.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it >= 4f } ?: return null
    return DisplayRect(draft.left, draft.top, draft.left + width, draft.top + height)
}
/** Only valid page geometry may reach Android's native Canvas, even before Preview. */
fun noteFrame(draft: TextNoteDraft, dimensions: PageDimensions): DisplayRect? = noteBounds(draft)?.takeIf {
    it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
        it.left >= 0f && it.top >= 0f && it.right <= dimensions.width && it.bottom <= dimensions.height
}
fun textNoteInput(draft: TextNoteDraft, dimensions: PageDimensions): TextNoteInput {
    require(draft.value.isNotBlank() && draft.value.toByteArray(Charsets.UTF_8).size <= 8192) { "Введите текст до 8 КиБ" }
    require(draft.value.none { it.isISOControl() && it != '\n' }) { "Поддерживаются переносы строк; управляющие символы недопустимы" }
    val size = draft.size.replace(',', '.').toDoubleOrNull()
    require(size != null && size.isFinite() && size in 4.0..144.0) { "Размер шрифта: 4–144 пт" }
    val rect = requireNotNull(noteBounds(draft)) { "Ширина и высота поля – не менее 4 пт" }
    require(rect.right.isFinite() && rect.bottom.isFinite() && rect.left >= 0 && rect.top >= 0 && rect.right <= dimensions.width && rect.bottom <= dimensions.height) {
        "Поле выходит за страницу. Уменьшите его или выберите другое место."
    }
    return TextNoteInput(draft.page.toUInt(), draft.snapshot.revision, TextRect(rect.left, rect.top, rect.right, rect.bottom), draft.value, size, draft.color)
}
