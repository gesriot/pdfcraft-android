package org.pdfcraft.android

import kotlin.math.*

data class PageDimensions(val width: Float, val height: Float)
data class DocumentPosition(val zoom: Float = 1f, val panX: Float = 0f, val scrollY: Double = 0.0)
data class LayoutPage(val index: Int, val size: PageDimensions, val left: Float, val top: Double, val width: Float, val height: Double) {
    val fit: Float get() = width / size.width
    val bottom: Double get() = top + height
}

/** A virtual strip of pages; long-document offsets stay Double, never giant Android views. */
class DocumentLayout(sizes: List<PageDimensions>, val width: Float, val gap: Float) {
    val pageWidth = (width - 2f * gap).coerceAtLeast(1f)
    val pages: List<LayoutPage>
    val height: Double
    init {
        require(width.isFinite() && width > 0f && gap.isFinite() && gap >= 0f)
        var top = gap.toDouble()
        pages = sizes.mapIndexed { index, size ->
            require(size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f)
            val h = pageWidth.toDouble() * size.height / size.width
            LayoutPage(index, size, (width - pageWidth) / 2f, top, pageWidth, h).also { top += h + gap }
        }
        height = top
    }

    fun pageAt(y: Double): Int {
        if (pages.isEmpty()) return 0
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pages[mid].top <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visible(position: DocumentPosition, viewportHeight: Float): List<LayoutPage> {
        if (pages.isEmpty()) return emptyList()
        val top = position.scrollY / position.zoom
        val bottom = (position.scrollY + viewportHeight) / position.zoom
        val result = ArrayList<LayoutPage>()
        var index = pageAt(top)
        while (index < pages.size && pages[index].top < bottom && result.size < 64) {
            if (pages[index].bottom > top) result.add(pages[index])
            index++
        }
        return result
    }

    fun constrain(value: DocumentPosition, viewportHeight: Float): DocumentPosition {
        val zoom = if (value.zoom.isFinite()) value.zoom.coerceIn(1f, 8f) else 1f
        val maxX = max(0f, (pageWidth * zoom - width) / 2f)
        val x = if (value.panX.isFinite()) value.panX.coerceIn(-maxX, maxX) else 0f
        val maxY = max(0.0, height * zoom - viewportHeight)
        val y = if (value.scrollY.isFinite()) value.scrollY.coerceIn(0.0, maxY) else 0.0
        return DocumentPosition(zoom, x, y)
    }

    fun transform(old: DocumentPosition, zoom: Float, dx: Float, dy: Float, focusX: Float, focusY: Float, viewportHeight: Float): DocumentPosition {
        if (!zoom.isFinite() || zoom <= 0f || !dx.isFinite() || !dy.isFinite() || !focusX.isFinite() || !focusY.isFinite()) return old
        val nextZoom = (old.zoom * zoom).coerceIn(1f, 8f)
        val ratio = nextZoom / old.zoom
        return constrain(DocumentPosition(nextZoom,
            old.panX * ratio + (focusX - width / 2f) * (1f - ratio) + dx,
            (old.scrollY + focusY) * ratio - focusY - dy), viewportHeight)
    }

    fun jump(old: DocumentPosition, page: Int, viewportHeight: Float): DocumentPosition =
        constrain(old.copy(scrollY = (pages.getOrNull(page)?.top ?: 0.0) * old.zoom), viewportHeight)

    fun reflow(old: DocumentPosition, previous: DocumentLayout, viewportHeight: Float): DocumentPosition {
        val anchor = previous.pages.getOrNull(previous.pageAt(old.scrollY / old.zoom)) ?: return constrain(old, viewportHeight)
        val next = pages.getOrNull(anchor.index) ?: return constrain(old, viewportHeight)
        val fraction = (old.scrollY / old.zoom - anchor.top) / anchor.height
        return constrain(old.copy(panX = old.panX * width / previous.width,
            scrollY = (next.top + fraction * next.height) * old.zoom), viewportHeight)
    }
}

const val PREVIEW_LEVEL = Int.MIN_VALUE
const val TILE_PIXELS = 512
const val RASTER_CACHE_BYTES = 48L * 1024 * 1024
private const val DEMAND_BYTES = 40L * 1024 * 1024

data class RasterKey(val page: Int, val level: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
    val preview: Boolean get() = level == PREVIEW_LEVEL
    val scale: Float get() = 2.0.pow(level / 4.0).toFloat()
    val bytes: Long get() = width.toLong() * height * 4
}

// Match the renderer's rounding, including its 0.01 pixel float-noise tolerance.
private fun rasterPixels(points: Float, scale: Float) = ceil((points * scale - 0.01f).toDouble()).toInt().coerceAtLeast(1)

fun previewKey(page: LayoutPage): RasterKey {
    val scale = min(384f / page.size.width, min(2048f / page.size.height,
        sqrt(4_000_000.0 / (page.size.width.toDouble() * page.size.height)).toFloat()))
    return RasterKey(page.index, PREVIEW_LEVEL, 0, 0, rasterPixels(page.size.width, scale), rasterPixels(page.size.height, scale))
}

/** Device-pixel scale is rounded UP, so a final tile is never an enlarged low-res bitmap. */
fun tileLevel(scale: Float): Int = ceil(log2(scale.toDouble()) * 4.0).toInt().coerceIn(-26, 32)

fun renderDemand(layout: DocumentLayout, position: DocumentPosition, viewportHeight: Float): List<RasterKey> {
    val visible = layout.visible(position, viewportHeight)
    val previews = visible.map(::previewKey)
    val tiles = ArrayList<Pair<RasterKey, Double>>()
    for (page in visible) {
        val displayScale = page.fit * position.zoom
        val level = tileLevel(displayScale)
        val rasterScale = 2.0.pow(level / 4.0).toFloat()
        val fullW = rasterPixels(page.size.width, rasterScale)
        val fullH = rasterPixels(page.size.height, rasterScale)
        if (fullW > 16_000_000 || fullH > 16_000_000) continue
        val ratio = displayScale / rasterScale
        val left = layout.width / 2f + (page.left - layout.width / 2f) * position.zoom + position.panX
        val top = page.top * position.zoom - position.scrollY
        val x0 = (max(0.0, -left.toDouble()) / ratio / TILE_PIXELS).toInt()
        val y0 = (max(0.0, -top) / ratio / TILE_PIXELS).toInt()
        val x1 = (min(fullW - 1.0, (layout.width - left) / ratio.toDouble()) / TILE_PIXELS).toInt()
        val y1 = (min(fullH - 1.0, (viewportHeight - top) / ratio) / TILE_PIXELS).toInt()
        var emitted = 0
        loop@ for (row in y0..y1) for (col in x0..x1) {
            if (emitted++ >= 256) break@loop
            // One pixel of overlap prevents filtering seams at fractional zoom levels.
            val x = (col * TILE_PIXELS - 1).coerceAtLeast(0)
            val y = (row * TILE_PIXELS - 1).coerceAtLeast(0)
            val w = min(fullW, (col + 1) * TILE_PIXELS + 1) - x
            val h = min(fullH, (row + 1) * TILE_PIXELS + 1) - y
            if (w <= 0 || h <= 0) continue
            val key = RasterKey(page.index, level, x, y, w, h)
            val dx = left + (x + w / 2.0) * ratio - layout.width / 2.0
            val dy = top + (y + h / 2.0) * ratio - viewportHeight / 2.0
            tiles.add(key to dx * dx + dy * dy)
        }
    }
    val wanted = previews + tiles.sortedBy { it.second }.map { it.first }
    // Preview the next/previous page only after filling the current viewport.
    val neighbours = if (visible.isEmpty()) emptyList() else listOfNotNull(
        layout.pages.getOrNull(visible.last().index + 1), layout.pages.getOrNull(visible.first().index - 1)
    ).map(::previewKey)
    var bytes = 0L
    return (wanted + neighbours).distinct().filter { key ->
        if (bytes + key.bytes <= DEMAND_BYTES) { bytes += key.bytes; true } else false
    }
}

/** Main-thread cache. Eviction releases references, not bitmaps still used by a GPU frame. */
class RasterCache<K, V>(private val limit: Long) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    var bytes: Long = 0
        private set
    operator fun get(key: K): V? = entries[key]?.value
    fun contains(key: K): Boolean = entries.containsKey(key)
    fun put(key: K, value: V, weight: Long) {
        require(weight in 0..limit)
        entries.remove(key)?.let { bytes -= it.bytes }
        entries[key] = Entry(value, weight)
        bytes += weight
        while (bytes > limit) {
            val iterator = entries.entries.iterator()
            val oldest = iterator.next()
            bytes -= oldest.value.bytes
            iterator.remove()
        }
    }
    fun clear() { entries.clear(); bytes = 0 }
}
