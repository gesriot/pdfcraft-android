package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class PageOrganizerTest {
    private val snapshot = PageSnapshot(3, 7u, 6)
    private fun reordered(pages: List<Int>, gap: Int, count: Int): List<Int> {
        val result = (0 until count).filterNot { it in pages }.toMutableList()
        result.addAll(moveDestination(pages, gap, count), pages.sorted())
        return result
    }
    @Test fun draggingAcrossBothDirectionsAndNonAdjacentSelection() {
        assertEquals(listOf(1, 2, 0, 3), reordered(listOf(0), 3, 4))
        assertEquals(listOf(3, 0, 1, 2), reordered(listOf(3), 0, 4))
        assertEquals(listOf(1, 3, 0, 2), reordered(listOf(2, 0), 4, 4))
        assertEquals(listOf(0, 1, 2, 3), reordered(listOf(1, 2), 2, 4))
        assertEquals(listOf(0, 1, 2, 3), reordered(listOf(0, 1, 2, 3), 4, 4))
    }
    @Test fun everySinglePageGapPreservesOtherPagesAndTotal() {
        for (count in 1..20) for (page in 0 until count) for (gap in 0..count) {
            val result = reordered(listOf(page), gap, count)
            assertEquals(count, result.size)
            assertEquals((0 until count).filter { it != page }, result.filter { it != page })
            assertEquals(moveDestination(listOf(page), gap, count), result.indexOf(page))
        }
    }
    @Test fun invalidMovesAreRejectedBeforeNativeCall() {
        for ((pages, gap) in listOf(emptyList<Int>() to 0, listOf(2, 2) to 0, listOf(-1) to 0,
            listOf(6) to 0, listOf(0) to -1, listOf(0) to 7)) {
            assertTrue(runCatching { moveDestination(pages, gap, 6) }.isFailure)
        }
    }
    private val cells = listOf(PageCell(3, 12f, -30f, 100f, 160f), PageCell(4, 124f, -30f, 100f, 160f),
        PageCell(5, 12f, 142f, 100f, 160f), PageCell(6, 124f, 142f, 100f, 160f))
    @Test fun hitTestingUsesScrolledViewportAndGaps() {
        assertEquals(3, pageAt(cells, 30f, 0f)?.index)
        assertEquals(6, pageAt(cells, 170f, 180f)?.index)
        assertNull(pageAt(cells, 120f, 80f))
        assertEquals(3, dropGap(cells, 30f, 80f))
        assertEquals(4, dropGap(cells, 100f, 80f))
        assertEquals(5, dropGap(cells, 240f, 80f))
        assertEquals(7, dropGap(cells, 170f, 350f))
        assertEquals(3, dropGap(cells, 30f, -90f))
        assertNull(dropGap(emptyList(), 0f, 0f))
    }
    @Test fun edgeScrollHasDeadZoneAndBoundedSpeed() {
        assertEquals(0f, dragScrollSpeed(200f, 400f, 64f))
        assertEquals(-0.5f, dragScrollSpeed(32f, 400f, 64f))
        assertEquals(0.5f, dragScrollSpeed(368f, 400f, 64f))
        assertEquals(-1f, dragScrollSpeed(-100f, 400f, 64f))
        assertEquals(1f, dragScrollSpeed(900f, 400f, 64f))
        assertEquals(0f, dragScrollSpeed(20f, 0f, 64f))
    }
    @Test fun revisionDocumentAndPageCountMustAllMatch() {
        snapshot.requireCurrent(snapshot.copy())
        for (other in listOf(null, snapshot.copy(documentKey = 4), snapshot.copy(revision = 8u), snapshot.copy(count = 7))) {
            assertTrue(runCatching { snapshot.requireCurrent(other) }.isFailure)
        }
    }
    @Test fun fileNamesRetainRussianAndEnglishWithoutPathSeparators() {
        val base = exportBaseName("Статья / Science: 2026.pdf")
        assertEquals("Статья _ Science_ 2026", base)
        assertEquals("$base-003.jpg", pageImageName(base, 2, 10, PageExportFormat.JPG))
        assertEquals("$base-1000.png", pageImageName(base, 999, 1000, PageExportFormat.PNG))
        assertEquals("PdfCraft", exportBaseName(".pdf"))
        assertEquals(64, exportBaseName("A".repeat(200)).length)
    }
    private fun request(pages: List<Int>? = null) = PageExportRequest(snapshot, pages, PageExportFormat.PNG, 150, "test.pdf")
    @Test fun exportsAllPagesSequentiallyAndReportsReducedResolution() {
        val seen = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = runImageBatch(request(), { false }, { done, page -> progress.add(done to page) }) { seen.add(it); it == 2 }
        assertEquals((0..5).toList(), seen)
        assertEquals((0..5).map { it to it }, progress)
        assertEquals(ImageBatchResult(6, 1, false, null), result)
    }
    @Test fun selectedPagesRetainRequestedOrder() {
        val seen = mutableListOf<Int>()
        val result = runImageBatch(request(listOf(5, 1)), { false }, { _, _ -> }) { seen.add(it); false }
        assertEquals(listOf(5, 1), seen)
        assertEquals(2, result.completed)
    }
    @Test fun stoppingBetweenPagesPreservesOnlyCompletedCount() {
        var calls = 0
        val result = runImageBatch(request(), { calls == 2 }, { _, _ -> }) { calls++; false }
        assertEquals(2, calls)
        assertEquals(ImageBatchResult(2, 0, true, null), result)
        assertEquals(0, runImageBatch(request(), { true }, { _, _ -> }) { error("Must not render") }.completed)
    }
    @Test fun stopDuringCopyDoesNotCountPartialFile() {
        var calls = 0
        val result = runImageBatch(request(), { false }, { _, _ -> }) {
            calls++; if (calls == 3) throw ExportStopped(); true
        }
        assertEquals(3, calls)
        assertEquals(ImageBatchResult(2, 2, true, null), result)
    }
    @Test fun failureKeepsCompletedCountAndStopsFurtherPages() {
        var calls = 0
        val result = runImageBatch(request(), { false }, { _, _ -> }) {
            calls++; if (calls == 2) error("Disk full"); false
        }
        assertEquals(2, calls)
        assertEquals(ImageBatchResult(1, 0, false, "Disk full"), result)
    }
    @Test fun memoryErrorIsReportedAndCoroutineCancellationPropagates() {
        val memory = runImageBatch(request(), { false }, { _, _ -> }) { throw OutOfMemoryError() }
        assertEquals(0, memory.completed); assertNotNull(memory.error)
        val result = runCatching { runImageBatch(request(), { false }, { _, _ -> }) { throw java.util.concurrent.CancellationException() } }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
    }
}
