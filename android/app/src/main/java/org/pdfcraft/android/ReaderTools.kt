package org.pdfcraft.android

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.pdfcraft.android.engine.*

data class FoundText(val page: Int, val start: Int, val end: Int, val bounds: List<DisplayRect>)
data class ArtworkSelection(val page: Int, val revision: ULong, val block: ArtworkBlock)
fun TextRect.displayRect() = DisplayRect(left, top, right, bottom)

/** Blocking native boundary, replaceable in tests to exercise cancellation and stale results. */
interface ReadingSource {
    fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int): SearchPage
    fun read(page: Int, revision: ULong): SelectablePage
    fun artwork(page: Int, revision: ULong): ArtworkPage
}
class NativeReadingSource(private val document: () -> MobileDocument?) : ReadingSource {
    override fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int) =
        requireNotNull(document()).searchPage(page.toUInt(), revision, query, case, whole, limit.toUInt())
    override fun read(page: Int, revision: ULong): SelectablePage =
        requireNotNull(document()).readingPage(page.toUInt(), revision).let { p ->
            SelectablePage(page, p.revision, p.glyphs.map { SelectableGlyph(it.text, it.bounds.displayRect(), it.line.toInt(), it.spaceBefore) })
        }
    override fun artwork(page: Int, revision: ULong) = requireNotNull(document()).artworkBlocks(page.toUInt(), revision)
}

/** Worker calls are serialized with rendering. UI state belongs to the main dispatcher. */
class ReaderTools(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val source: ReadingSource,
    private val info: () -> DocumentInfo?,
    private val busy: () -> Boolean,
    private val notify: (String) -> Unit,
    private val navigate: (Int, DisplayRect) -> Unit,
) {
    var searchOpen by mutableStateOf(false); private set
    var query by mutableStateOf(""); private set
    var caseSensitive by mutableStateOf(false); private set
    var wholeWords by mutableStateOf(false); private set
    var searching by mutableStateOf(false); private set
    var searchedPages by mutableIntStateOf(0); private set
    var searchNotice by mutableStateOf<String?>(null); private set
    var matches: List<FoundText> by mutableStateOf(emptyList()); private set
    var matchIndex by mutableIntStateOf(-1); private set
    var selection: TextSelection? by mutableStateOf(null); private set
    var pages: Map<Int, SelectablePage> by mutableStateOf(emptyMap()); private set
    var artworkMode by mutableStateOf(false); private set
    var artwork: Map<Int, ArtworkPage> by mutableStateOf(emptyMap()); private set
    var selectedArtwork: ArtworkSelection? by mutableStateOf(null); private set
    var artworkError: String? by mutableStateOf(null); private set
    var pageErrors: Map<Int, String> by mutableStateOf(emptyMap()); private set
    private var searchJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0L
    private var wanted = emptyList<Int>()
    private val failed = mutableSetOf<Int>()
    private var restartSearch = false

    fun pauseWorkers() {
        generation++
        pageJob?.cancel(); pageJob = null
        restartSearch = searching
        searchJob?.cancel(); searchJob = null; searching = false
    }

    fun invalidate(newDocument: Boolean = false) {
        pauseWorkers()
        pages = emptyMap(); artwork = emptyMap(); failed.clear(); pageErrors = emptyMap(); artworkError = null
        selection = null; selectedArtwork = null; matches = emptyList(); matchIndex = -1
        if (newDocument) { searchOpen = false; query = ""; artworkMode = false }
        restartSearch = searchOpen && query.isNotBlank()
    }

    fun resume() { if (restartSearch) { restartSearch = false; startSearch() } }

    fun openSearch() {
        changeArtworkMode(false)
        searchOpen = true
        startSearch()
    }

    fun closeSearch() {
        searchOpen = false; searchJob?.cancel(); searchJob = null; searching = false
        restartSearch = false; matches = emptyList(); matchIndex = -1; searchNotice = null
    }

    fun updateQuery(value: String) { query = value.take(256); startSearch() }
    fun searchOptions(case: Boolean, whole: Boolean) { caseSensitive = case; wholeWords = whole; startSearch() }

    private fun startSearch() {
        searchJob?.cancel(); matches = emptyList(); matchIndex = -1; searchNotice = null; searchedPages = 0
        searching = false
        if (!searchOpen || query.isBlank() || busy()) return
        val revision = info()?.revision ?: return
        val epoch = generation
        val count = info()?.pageCount?.toInt() ?: return
        val needle = query.trim()
        val case = caseSensitive; val whole = wholeWords
        searching = true
        searchJob = scope.launch {
            try {
                delay(300)
                val found = mutableListOf<FoundText>()
                var failures = 0
                var limited = false
                for (page in 0 until count) {
                    ensureActive()
                    try {
                        val result = withContext(worker) { source.search(page, revision, needle, case, whole, 1000 - found.size) }
                        if (epoch != generation || revision != info()?.revision) return@launch
                        found += result.hits.map { FoundText(page, it.start.toInt(), it.end.toInt(), it.bounds.map(TextRect::displayRect)) }
                        matches = found.toList()
                        searchedPages = page + 1
                        if (matchIndex < 0 && found.isNotEmpty()) goToMatch(0)
                        limited = result.truncated || (found.size == 1000 && page + 1 < count)
                        if (limited) break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failures++; searchedPages = page + 1 }
                    yield()
                }
                searchNotice = listOfNotNull(
                    if (limited) "Показаны первые 1000 совпадений. Уточните запрос." else null,
                    if (failures > 0) "Не удалось прочитать страниц: $failures" else null,
                    if (!limited && failures == 0 && found.isEmpty()) "Совпадений нет. Для скана нужен готовый текстовый слой." else null,
                ).joinToString(" ").ifEmpty { null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { searchNotice = "Недостаточно памяти для поиска" }
            catch (error: LinkageError) { searchNotice = "Ошибка PDF-движка: ${error.message}" }
            finally { if (isActive && epoch == generation) searching = false }
        }
    }

    fun goToMatch(index: Int) {
        if (matches.isEmpty() || busy()) return
        matchIndex = ((index % matches.size) + matches.size) % matches.size
        val hit = matches[matchIndex]
        hit.bounds.firstOrNull()?.let { navigate(hit.page, it) }
    }

    fun changeArtworkMode(enabled: Boolean) {
        if (busy()) return
        closeSearch(); clearSelection(); selectedArtwork = null
        artworkMode = enabled
        pageJob?.cancel(); pageJob = null; generation++
        failed.clear(); artwork = emptyMap(); pageErrors = emptyMap(); artworkError = null
    }

    fun requestPages(visible: List<Int>) {
        if (busy()) return
        wanted = (listOfNotNull(selection?.page) + visible).distinct().take(3)
        failed.retainAll(wanted.toSet())
        pages = pages.filterKeys { it in wanted }; artwork = artwork.filterKeys { it in wanted }; pageErrors = pageErrors.filterKeys { it in wanted }
        if (pageJob?.isActive == true) return
        val epoch = generation
        pageJob = scope.launch {
            try {
                while (!busy()) {
                    val index = wanted.firstOrNull { it !in failed && if (artworkMode) it !in artwork else it !in pages } ?: break
                    val revision = info()?.revision ?: break
                    try {
                        if (artworkMode) {
                            val result = withContext(worker) { source.artwork(index, revision) }
                            if (epoch == generation && index in wanted && artworkMode) artwork = artwork + (index to result)
                        } else {
                            val result = withContext(worker) {
                                source.read(index, revision)
                            }
                            if (epoch == generation && index in wanted && !artworkMode) pages = pages + (index to result)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (epoch == generation) pageFailure(index, error.message ?: "Не удалось прочитать страницу") }
                    catch (_: OutOfMemoryError) { if (epoch == generation) pageFailure(index, "Недостаточно памяти для текстового слоя") }
                    catch (error: LinkageError) { if (epoch == generation) pageFailure(index, "Ошибка PDF-движка: ${error.message}") }
                }
            } finally { if (epoch == generation) pageJob = null }
        }
    }

    private fun pageFailure(index: Int, detail: String) {
        if (index !in wanted) return
        failed.add(index); pageErrors = pageErrors + (index to detail); notify("Страница ${index + 1}: $detail")
    }
    fun reportArtworkError(detail: String) { artworkError = detail }

    fun selectWord(point: PagePoint, tolerance: Float): Boolean {
        val p = pages[point.page]
        if (p == null) { notify(pageErrors[point.page] ?: "Текст страницы ещё загружается"); return false }
        selection = p.nearest(point.x, point.y, tolerance)?.let(p::word)
        if (selection == null) notify("Здесь нет доступного текста. Для скана нужен готовый текстовый слой.")
        return selection != null
    }
    fun clearSelection() { selection = null }
    fun selectPage(page: Int) {
        val p = pages[page]
        if (p == null) { notify(pageErrors[page] ?: "Текст страницы ещё загружается"); return }
        if (p.glyphs.isEmpty()) { notify("На странице нет текстового слоя"); return }
        selection = TextSelection(page, p.revision, 0, p.glyphs.size)
    }
    fun dragBoundary(point: PagePoint, start: Boolean) {
        val s = selection ?: return
        if (point.page != s.page) return
        val index = pages[s.page]?.nearest(point.x, point.y) ?: return
        selection = moveSelectionBoundary(s, index, start)
    }
    fun selectedText(): String = selection?.let { pages[it.page]?.copyText(it) }.orEmpty()
    fun chooseArtwork(page: Int, block: ArtworkBlock) {
        if (!busy()) artwork[page]?.let { artworkError = null; selectedArtwork = ArtworkSelection(page, it.revision, block) }
    }
    fun hitArtwork(point: PagePoint, tolerance: Float) {
        val p = artwork[point.page]
        if (p == null) { notify(pageErrors[point.page] ?: "Объекты ещё загружаются"); return }
        val index = hitTextBlock(p.blocks.map { it.bounds.displayRect() }, point.x, point.y, tolerance)
        if (index == null) notify("Коснитесь оранжевой рамки или откройте список объектов")
        else chooseArtwork(point.page, p.blocks[index])
    }
    fun cancelArtwork() { if (!busy()) { selectedArtwork = null; artworkError = null } }
}
