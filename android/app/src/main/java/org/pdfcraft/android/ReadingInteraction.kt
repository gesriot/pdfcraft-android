package org.pdfcraft.android

import kotlin.math.max
import kotlin.math.min

/** Glyph indices preserve ligatures, surrogate pairs and reconstructed word/line breaks. */
data class SelectableGlyph(val text: String, val rect: DisplayRect, val line: Int, val spaceBefore: Boolean)
data class SelectablePage(val page: Int, val revision: ULong, val glyphs: List<SelectableGlyph>)
data class TextSelection(val page: Int, val revision: ULong, val start: Int, val end: Int)

fun SelectablePage.nearest(x: Float, y: Float, tolerance: Float = Float.POSITIVE_INFINITY): Int? {
    fun distance(g: SelectableGlyph): Float {
        val r = g.rect
        return max(0f, max(r.left - x, x - r.right)) + 3f * max(0f, max(r.top - y, y - r.bottom))
    }
    if (!x.isFinite() || !y.isFinite()) return null
    return glyphs.indices.minByOrNull { distance(glyphs[it]) }?.takeIf { distance(glyphs[it]) <= tolerance }
}

fun SelectablePage.word(index: Int): TextSelection? {
    if (index !in glyphs.indices || glyphs[index].text.isBlank()) return null
    fun joined(i: Int) = i > 0 && i < glyphs.size && !glyphs[i].spaceBefore &&
        glyphs[i].line == glyphs[i - 1].line && glyphs[i].text.isNotBlank() && glyphs[i - 1].text.isNotBlank()
    var start = index
    var end = index + 1
    while (joined(start)) start--
    while (joined(end)) end++
    return TextSelection(page, revision, start, end)
}

fun SelectablePage.selected(selection: TextSelection): List<SelectableGlyph> =
    if (selection.page == page && selection.revision == revision && selection.start >= 0 &&
        selection.end <= glyphs.size && selection.start < selection.end) glyphs.subList(selection.start, selection.end)
    else emptyList()

fun SelectablePage.copyText(selection: TextSelection): String = buildString {
    val selected = selected(selection)
    selected.forEachIndexed { i, g ->
        if (i > 0) {
            if (selected[i - 1].line != g.line) append('\n') else if (g.spaceBefore) append(' ')
        }
        append(g.text)
    }
}

fun SelectablePage.selectionRects(selection: TextSelection): List<DisplayRect> {
    val out = mutableListOf<DisplayRect>()
    var previousLine: Int? = null
    for (g in selected(selection)) {
        if (previousLine == g.line && out.isNotEmpty()) {
            val r = out.removeAt(out.lastIndex)
            out += DisplayRect(min(r.left, g.rect.left), min(r.top, g.rect.top), max(r.right, g.rect.right), max(r.bottom, g.rect.bottom))
        } else out += g.rect
        previousLine = g.line
    }
    return out
}

fun moveSelectionBoundary(selection: TextSelection, index: Int, startHandle: Boolean): TextSelection =
    if (startHandle) selection.copy(start = index.coerceIn(0, selection.end - 1))
    else selection.copy(end = (index + 1).coerceAtLeast(selection.start + 1))

/** Centre the actual match while keeping zoom; a horizontal pan exposes matches off-screen. */
fun DocumentLayout.focus(position: DocumentPosition, pageIndex: Int, rect: DisplayRect, height: Float): DocumentPosition {
    val page = pages.getOrNull(pageIndex) ?: return position
    val cx = page.left + (rect.left + rect.right) / 2f * page.fit
    val cy = page.top + (rect.top + rect.bottom) / 2f * page.fit
    return constrain(position.copy(panX = -(cx - width / 2f) * position.zoom,
        scrollY = cy * position.zoom - height / 2f), height)
}
