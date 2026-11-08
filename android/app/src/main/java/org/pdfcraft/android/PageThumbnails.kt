package org.pdfcraft.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import org.pdfcraft.android.engine.MobileDocument

class PageThumbnails(private val scope: CoroutineScope, private val worker: CoroutineDispatcher,
    private val document: () -> MobileDocument?, private val snapshot: () -> PageSnapshot?,
    private val enabled: () -> Boolean, private val dimensions: (Int) -> PageDimensions?) {
    var version by mutableStateOf(0)
        private set
    var errors: Map<Int, String> by mutableStateOf(emptyMap())
        private set
    private val cache = RasterCache<Int, ImageBitmap>(12L * 1024 * 1024)
    private var demand = emptyList<Int>()
    private var job: Job? = null
    private var epoch = 0
    fun get(page: Int): ImageBitmap? = cache[page]
    fun clear() { epoch++; demand = emptyList(); cache.clear(); errors = emptyMap(); version++ }
    fun pause() { epoch++; demand = emptyList() }
    fun request(pages: List<Int>) {
        if (!enabled()) return
        demand = pages.distinct().take(24)
        errors = errors.filterKeys { it in demand }
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                while (enabled()) {
                    val page = demand.firstOrNull { !cache.contains(it) && it !in errors } ?: break
                    val expected = snapshot() ?: break
                    val generation = epoch
                    val size = dimensions(page)
                    val width = if (size != null) (256f * size.width / size.height).toInt().coerceIn(1, 192) else 192
                    try {
                        val bitmap = withContext(worker) {
                            val result = requireNotNull(document()).renderPage(page.toUInt(), width.toUInt())
                            check(result.revision == expected.revision) { "Документ изменился" }
                            Bitmap.createBitmap(result.width.toInt(), result.height.toInt(), Bitmap.Config.ARGB_8888).apply {
                                copyPixelsFromBuffer(ByteBuffer.wrap(result.rgba))
                            }
                        }
                        if (generation == epoch && expected == snapshot() && page in demand) {
                            cache.put(page, bitmap.asImageBitmap(), bitmap.allocationByteCount.toLong()); version++
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (generation == epoch) errors = errors + (page to (error.message ?: "Ошибка миниатюры"))
                    } catch (_: OutOfMemoryError) {
                        cache.clear(); version++
                        if (generation == epoch) errors = errors + (page to "Недостаточно памяти")
                    } catch (error: LinkageError) {
                        if (generation == epoch) errors = errors + (page to "Ошибка движка")
                    }
                }
            } finally { job = null }
        }
    }
}
