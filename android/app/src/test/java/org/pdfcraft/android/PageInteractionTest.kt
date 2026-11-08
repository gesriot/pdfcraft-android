package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class PageInteractionTest {
    @Test fun pageRangesPreserveSourceOrderAndDeduplicate() {
        assertEquals(listOf(4, 1, 2, 3), parsePageSelection("5, 2–4, 2", 7))
    }

    @Test fun invalidOrUnboundedPageRangesAreRejected() {
        for (text in listOf("", "0", "-1", "3-1", "1,,2", "2147483648", "1-1000000000", "8")) {
            assertTrue(text, runCatching { parsePageSelection(text, 7) }.isFailure)
        }
        assertTrue(runCatching { parsePageSelection("1-501", 1000) }.isFailure)
    }

}
