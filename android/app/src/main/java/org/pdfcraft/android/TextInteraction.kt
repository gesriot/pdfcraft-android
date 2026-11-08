package org.pdfcraft.android

/** Coordinates shared by the page raster and the editable text overlay. */
data class PagePoint(val page: Int, val x: Float, val y: Float)
data class DisplayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    fun contains(x: Float, y: Float, padding: Float = 0f) =
        x >= left - padding && x <= right + padding && y >= top - padding && y <= bottom + padding
}

fun DocumentLayout.pagePoint(position: DocumentPosition, x: Float, y: Float): PagePoint? {
    if (!x.isFinite() || !y.isFinite()) return null
    val stripY = (position.scrollY + y) / position.zoom
    val page = pages.getOrNull(pageAt(stripY)) ?: return null
    val left = width / 2f + (page.left - width / 2f) * position.zoom + position.panX
    val px = (x - left) / (page.fit * position.zoom)
    val py = ((stripY - page.top) / page.fit).toFloat()
    return if (px in 0f..page.size.width && py in 0f..page.size.height) PagePoint(page.index, px, py) else null
}

fun LayoutPage.screenRect(layoutWidth: Float, position: DocumentPosition, rect: DisplayRect): DisplayRect {
    val left = layoutWidth / 2f + (this.left - layoutWidth / 2f) * position.zoom + position.panX
    val top = this.top * position.zoom - position.scrollY
    val scale = fit * position.zoom
    return DisplayRect(left + rect.left * scale, (top + rect.top * scale).toFloat(),
        left + rect.right * scale, (top + rect.bottom * scale).toFloat())
}

/** Prefer a directly touched small block over the padding of a nearby larger one. */
fun hitTextBlock(rectangles: List<DisplayRect>, x: Float, y: Float, padding: Float): Int? =
    rectangles.indices.filter { rectangles[it].contains(x, y) }.minByOrNull { rectangles[it].area }
        ?: rectangles.indices.filter { rectangles[it].contains(x, y, padding) }.minByOrNull { rectangles[it].area }

fun textSizeOverride(value: String): Double? {
    if (value.isBlank()) return null
    val size = value.trim().replace(',', '.').toDoubleOrNull()
    require(size != null && size.isFinite() && size in 1.0..300.0) { "Размер: от 1 до 300 пт или пустое поле" }
    return size
}
