package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class TextInteractionTest {
    @Test fun textBoxHitMatchesRasterAfterZoomPanAndContinuousScroll() {
        val layout = DocumentLayout(listOf(PageDimensions(600f, 800f), PageDimensions(800f, 600f)), 420f, 12f)
        for (zoom in listOf(1f, 2.3f, 8f)) for (page in layout.pages) {
            val position = DocumentPosition(zoom, -25f, page.top * zoom + 50.0)
            val rect = DisplayRect(100f, 120f, 260f, 160f)
            val onScreen = page.screenRect(layout.width, position, rect)
            val point = layout.pagePoint(position, (onScreen.left + onScreen.right) / 2f, (onScreen.top + onScreen.bottom) / 2f)
            assertNotNull(point)
            assertEquals(page.index, point!!.page)
            assertEquals(180f, point.x, 0.001f)
            assertEquals(140f, point.y, 0.001f)
            assertEquals(0, hitTextBlock(listOf(rect), point.x, point.y, 0f))
        }
    }

    @Test fun gapsAndOutsidePageDoNotSelectText() {
        val layout = DocumentLayout(listOf(PageDimensions(600f, 800f), PageDimensions(600f, 800f)), 420f, 12f)
        val first = layout.pages[0]
        assertNull(layout.pagePoint(DocumentPosition(), 200f, 2f))
        assertNull(layout.pagePoint(DocumentPosition(), 200f, (first.bottom + 6).toFloat()))
        assertNull(layout.pagePoint(DocumentPosition(), 2f, 60f))
        assertNull(layout.pagePoint(DocumentPosition(), Float.NaN, 60f))
    }

    @Test fun directAndSmallerBlocksWinOverNearbyPadding() {
        val large = DisplayRect(0f, 0f, 200f, 200f)
        val small = DisplayRect(10f, 10f, 30f, 20f)
        val neighbour = DisplayRect(33f, 10f, 40f, 20f)
        assertEquals(1, hitTextBlock(listOf(large, small), 15f, 15f, 10f))
        assertEquals(0, hitTextBlock(listOf(small, neighbour), 29f, 15f, 10f))
        assertNull(hitTextBlock(listOf(small), 100f, 100f, 5f))
    }

    @Test fun sizeOverrideAcceptsRussianDecimalAndRejectsNonFiniteValues() {
        assertNull(textSizeOverride(" "))
        assertEquals(12.5, textSizeOverride("12,5")!!, 0.0001)
        for (text in listOf("NaN", "Infinity", "-3", "0", "301", "wrong")) {
            assertTrue(text, runCatching { textSizeOverride(text) }.isFailure)
        }
    }
}
