package org.pdfcraft.android

import java.util.concurrent.CancellationException

data class PageSnapshot(val documentKey: Int, val revision: ULong, val count: Int) {
    fun requireCurrent(current: PageSnapshot?) {
        require(this == current) { "Документ изменился. Выберите страницы заново." }
    }
}
data class PageSelection(val snapshot: PageSnapshot, val pages: List<Int>)

/** A gap in the original order -> insertion position after removing the moved pages. */
fun moveDestination(pages: List<Int>, gap: Int, count: Int): Int {
    require(pages.isNotEmpty() && pages.size <= 500 && pages.distinct().size == pages.size)
    require(pages.all { it in 0 until count } && gap in 0..count)
    return gap - pages.count { it < gap }
}

data class PageCell(val index: Int, val x: Float, val y: Float, val width: Float, val height: Float)
fun pageAt(cells: List<PageCell>, x: Float, y: Float): PageCell? =
    cells.firstOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
fun dropGap(cells: List<PageCell>, x: Float, y: Float): Int? {
    // Closest cell also handles grid spacing and a finger just outside the viewport.
    val cell = cells.minByOrNull {
        val dx = (it.x - x).coerceAtLeast(0f) + (x - it.x - it.width).coerceAtLeast(0f)
        val dy = (it.y - y).coerceAtLeast(0f) + (y - it.y - it.height).coerceAtLeast(0f)
        dx * dx + dy * dy
    } ?: return null
    return cell.index + if (y > cell.y + cell.height || (y >= cell.y && x > cell.x + cell.width / 2)) 1 else 0
}
fun dragScrollSpeed(y: Float, height: Float, edge: Float): Float = when {
    height <= 0 || edge <= 0 -> 0f
    y < edge -> -((edge - y) / edge).coerceIn(0f, 1f)
    y > height - edge -> ((y - height + edge) / edge).coerceIn(0f, 1f)
    else -> 0f
}

enum class PageExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"), JPG("jpg", "image/jpeg"), PNG("png", "image/png")
}
data class PageExportRequest(val snapshot: PageSnapshot, val pages: List<Int>?, val format: PageExportFormat,
    val dpi: Int, val title: String) {
    val total: Int get() = pages?.size ?: snapshot.count
}
fun exportBaseName(title: String): String = title.substringBeforeLast('.', title).map {
    if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_'
}.joinToString("").trim().take(64).ifBlank { "PdfCraft" }
fun pageImageName(base: String, page: Int, count: Int, format: PageExportFormat): String =
    "$base-${(page + 1).toString().padStart(count.toString().length.coerceAtLeast(3), '0')}.${format.extension}"

class ExportStopped : Exception()
data class ImageBatchResult(val completed: Int, val reduced: Int, val stopped: Boolean, val error: String?)
/** Sequential export: completed files survive stop/error. No allocation proportional to all page count. */
fun runImageBatch(request: PageExportRequest, stopped: () -> Boolean,
    progress: (Int, Int) -> Unit, export: (Int) -> Boolean): ImageBatchResult {
    var completed = 0
    var reduced = 0
    try {
        for (ordinal in 0 until request.total) {
            if (stopped()) throw ExportStopped()
            val page = request.pages?.get(ordinal) ?: ordinal
            progress(completed, page)
            if (export(page)) reduced++
            completed++
        }
    } catch (_: ExportStopped) {
        return ImageBatchResult(completed, reduced, true, null)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        return ImageBatchResult(completed, reduced, false, error.message ?: "Ошибка экспорта")
    } catch (_: OutOfMemoryError) {
        return ImageBatchResult(completed, reduced, false, "Недостаточно памяти для экспорта страницы")
    } catch (error: LinkageError) {
        return ImageBatchResult(completed, reduced, false, "Не удалось загрузить движок: ${error.message}")
    }
    return ImageBatchResult(completed, reduced, false, null)
}
