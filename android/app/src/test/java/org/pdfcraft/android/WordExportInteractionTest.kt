package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class WordExportInteractionTest {
    private val snapshot = PageSnapshot(4, 9u, 12)
    @Test fun allCurrentAndRangesUseZeroBasedPages() {
        assertEquals((0..11).toList(), wordExportPages(snapshot, 0, 5, ""))
        assertEquals(listOf(5), wordExportPages(snapshot, 1, 5, ""))
        assertEquals(listOf(0, 2, 3, 4), wordExportPages(snapshot, 2, 5, "1, 3–5"))
    }
    @Test fun oversizedDocumentsNeedAnExplicitBoundedSelection() {
        val huge = snapshot.copy(count = Int.MAX_VALUE)
        assertTrue(runCatching { wordExportPages(huge, 0, 0, "") }.isFailure)
        assertEquals(listOf(0), wordExportPages(huge, 1, 0, ""))
        assertTrue(runCatching { wordExportPages(huge, 2, 0, "1-501") }.isFailure)
        assertEquals(500, wordExportPages(huge, 2, 0, "1-500").size)
    }
    @Test fun badAndStaleSelectionsCannotStartExport() {
        for (range in listOf("", "0", "13", "oops")) assertTrue(runCatching { wordExportPages(snapshot, 2, 0, range) }.isFailure)
        assertTrue(runCatching { wordExportPages(snapshot, 1, 12, "") }.isFailure)
        assertTrue(runCatching { snapshot.requireCurrent(snapshot.copy(revision = 10u)) }.isFailure)
        assertTrue(runCatching { snapshot.requireCurrent(snapshot.copy(documentKey = 5)) }.isFailure)
    }
}
