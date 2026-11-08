package org.pdfcraft.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.pdfcraft.android.engine.*

class ReaderToolsTest {
    private open class Source : ReadingSource {
        override fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int) =
            SearchPage(page.toUInt(), revision, listOf(hit(page)), false)
        override fun read(page: Int, revision: ULong) = SelectablePage(page, revision,
            listOf(SelectableGlyph("text", DisplayRect(0f, 0f, 50f, 12f), 0, false)))
        override fun artwork(page: Int, revision: ULong) = ArtworkPage(page.toUInt(), revision, emptyList())
    }
    private companion object {
        fun hit(index: Int) = SearchHit(index.toUInt(), (index + 1).toUInt(), listOf(TextRect(10f, 10f, 30f, 22f)))
        fun info() = DocumentInfo("test.pdf", 4u, false, false, false, 7u, true, false, emptyList(), emptyList())
        suspend fun until(check: () -> Boolean) { withTimeout(8000) { while (!check()) delay(10) } }
    }

    @Test fun searchSkipsFailedPagesReportsPartialResultAndWrapsNavigation() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val source = object : Source() {
                override fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int): SearchPage {
                    if (page == 1) error("bad page")
                    return super.search(page, revision, query, case, whole, limit)
                }
            }
            val navigated = mutableListOf<Int>()
            val tools = ReaderTools(scope, worker, source, { info() }, { false }, {}, { p, _ -> navigated += p })
            tools.openSearch(); tools.updateQuery("text")
            until { !tools.searching }
            assertEquals(listOf(0, 2, 3), tools.matches.map { it.page })
            assertEquals(4, tools.searchedPages)
            assertTrue(tools.searchNotice!!.contains("1"))
            tools.goToMatch(-1); assertEquals(3, navigated.last())
            tools.goToMatch(3); assertEquals(0, navigated.last())
        } finally { scope.cancel(); worker.close() }
    }

    @Test fun changedQueryDiscardsAnAlreadyRunningNativeResult() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val source = object : Source() {
                override fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int): SearchPage {
                    if (query == "old") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return SearchPage(page.toUInt(), revision, listOf(hit(if (query == "old") 1 else 20)), false)
                }
            }
            val tools = ReaderTools(scope, worker, source, { info() }, { false }, {}, { _, _ -> })
            tools.openSearch(); tools.updateQuery("old")
            until { entered.count == 0L }
            tools.updateQuery("new"); release.countDown()
            until { !tools.searching }
            assertEquals(4, tools.matches.size)
            assertTrue(tools.matches.all { it.start == 20 })
        } finally { release.countDown(); scope.cancel(); worker.close() }
    }

    @Test fun openingAnotherDocumentInvalidatesPendingTextAndSelection() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val source = object : Source() {
                override fun read(page: Int, revision: ULong): SelectablePage {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return super.read(page, revision)
                }
            }
            val tools = ReaderTools(scope, worker, source, { info() }, { false }, {}, { _, _ -> })
            tools.requestPages(listOf(0))
            until { entered.count == 0L }
            tools.invalidate(newDocument = true); release.countDown()
            // Queue a barrier after the blocked call: no obsolete page may be published.
            withContext(worker) { Unit }
            yield()
            assertTrue(tools.pages.isEmpty()); assertNull(tools.selection)
        } finally { release.countDown(); scope.cancel(); worker.close() }
    }

    @Test fun searchStopsAtGlobalLimitAndPageCacheStaysBoundedWithSelectionPinned() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            var searches = 0
            val source = object : Source() {
                override fun search(page: Int, revision: ULong, query: String, case: Boolean, whole: Boolean, limit: Int): SearchPage {
                    searches++
                    return SearchPage(page.toUInt(), revision, List(limit) { hit(it) }, false)
                }
            }
            val tools = ReaderTools(scope, worker, source, { info() }, { false }, {}, { _, _ -> })
            tools.openSearch(); tools.updateQuery("e")
            until { !tools.searching }
            assertEquals(1, searches); assertEquals(1000, tools.matches.size)
            assertTrue(tools.searchNotice!!.contains("1000"))
            tools.closeSearch(); tools.requestPages(listOf(0, 1, 2, 3))
            until { tools.pages.size == 3 }
            assertEquals(setOf(0, 1, 2), tools.pages.keys)
            tools.selectPage(0); tools.requestPages(listOf(2, 3))
            until { tools.pages.keys == setOf(0, 2, 3) }
            assertEquals("text", tools.selectedText())
            tools.invalidate(); assertEquals("", tools.selectedText())
        } finally { scope.cancel(); worker.close() }
    }

    @Test fun failedPageReportsErrorInsteadOfLoadingForeverAndCanRetryAfterNavigation() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            var attempts = 0
            val source = object : Source() {
                override fun read(page: Int, revision: ULong): SelectablePage {
                    if (page == 0) { attempts++; error("Cannot decode text") }
                    return super.read(page, revision)
                }
            }
            val messages = mutableListOf<String>()
            val tools = ReaderTools(scope, worker, source, { info() }, { false }, { messages += it }, { _, _ -> })
            tools.requestPages(listOf(0)); until { 0 in tools.pageErrors }
            assertFalse(tools.selectWord(PagePoint(0, 5f, 5f), 20f))
            assertEquals("Cannot decode text", messages.last())
            tools.requestPages(listOf(1)); until { 1 in tools.pages }
            tools.requestPages(listOf(0)); until { 0 in tools.pageErrors }
            assertEquals(2, attempts)
        } finally { scope.cancel(); worker.close() }
    }

}
