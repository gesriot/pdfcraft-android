package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test

class ReadingInteractionTest {
    private fun glyph(text: String, x: Float, y: Float = 10f, line: Int = 0, space: Boolean = false) =
        SelectableGlyph(text, DisplayRect(x, y, x + 8f, y + 12f), line, space)

    @Test fun copyingUsesGlyphsWithoutSplittingUnicodeOrLigaturesAndRestoresBreaks() {
        val page = SelectablePage(2, 9u, listOf(glyph("Я", 0f), glyph("😀", 8f), glyph("fi", 16f),
            glyph("мир", 35f, space = true), glyph("English", 0f, 30f, 1)))
        assertEquals("Я😀fi мир\nEnglish", page.copyText(TextSelection(2, 9u, 0, 5)))
        assertEquals("😀fi мир", page.copyText(TextSelection(2, 9u, 1, 4)))
        assertEquals("мир", page.copyText(TextSelection(2, 9u, 3, 4)))
    }

    @Test fun longPressWordStopsAtInsertedSpaceBlankGlyphOrLineBoundary() {
        val page = SelectablePage(0, 1u, listOf(glyph("П", 0f), glyph("р", 8f), glyph("и", 16f),
            glyph("в", 32f, space = true), glyph(" ", 40f), glyph("e", 50f), glyph("n", 0f, 30f, 1)))
        assertEquals(TextSelection(0, 1u, 0, 3), page.word(1))
        assertEquals(TextSelection(0, 1u, 3, 4), page.word(3))
        assertNull(page.word(4))
        assertEquals(TextSelection(0, 1u, 5, 6), page.word(5))
        assertEquals(TextSelection(0, 1u, 6, 7), page.word(6))
    }

    @Test fun staleOrWrongPageSelectionNeverCopiesUnrelatedText() {
        val page = SelectablePage(0, 3u, listOf(glyph("secret", 0f)))
        for (selection in listOf(TextSelection(0, 2u, 0, 1), TextSelection(1, 3u, 0, 1), TextSelection(0, 3u, -1, 1),
            TextSelection(0, 3u, 0, 2), TextSelection(0, 3u, 1, 0))) {
            assertEquals("", page.copyText(selection)); assertTrue(page.selectionRects(selection).isEmpty())
        }
    }

    @Test fun highlightsMergeOnlySelectedRunsOnTheirLines() {
        val page = SelectablePage(0, 0u, listOf(glyph("one", 0f), glyph("two", 25f, space = true),
            glyph("three", 0f, 40f, 1), glyph("excluded", 100f, 40f, 1)))
        val rects = page.selectionRects(TextSelection(0, 0u, 0, 3))
        assertEquals(listOf(DisplayRect(0f, 10f, 33f, 22f), DisplayRect(0f, 40f, 8f, 52f)), rects)
    }

    @Test fun initialSelectionRejectsEmptyMarginsWhileDraggingCanReachNearestLine() {
        val page = SelectablePage(0, 0u, listOf(glyph("top", 10f), glyph("bottom", 10f, 50f, 1)))
        assertEquals(0, page.nearest(14f, 14f, 20f))
        assertNull(page.nearest(200f, 14f, 20f))
        assertEquals(1, page.nearest(200f, 55f))
        assertNull(page.nearest(Float.NaN, 2f))
    }

    @Test fun handleCrossingDoesNotInvertOrLoseSelection() {
        val original = TextSelection(0, 2u, 2, 5)
        assertEquals(TextSelection(0, 2u, 4, 5), moveSelectionBoundary(original, 8, true))
        assertEquals(TextSelection(0, 2u, 2, 3), moveSelectionBoundary(original, 0, false))
        assertEquals(TextSelection(0, 2u, 0, 5), moveSelectionBoundary(original, 0, true))
        assertEquals(TextSelection(0, 2u, 2, 9), moveSelectionBoundary(original, 8, false))
    }

    @Test fun searchNavigationExposesOffscreenMatchWithoutResettingZoom() {
        val layout = DocumentLayout(List(5) { PageDimensions(600f, 800f) }, 420f, 12f)
        val rect = DisplayRect(480f, 620f, 540f, 640f)
        for (zoom in listOf(1f, 2f, 8f)) {
            val position = layout.focus(DocumentPosition(zoom, 0f, 0.0), 3, rect, 500f)
            val screen = layout.pages[3].screenRect(layout.width, position, rect)
            assertEquals(zoom, position.zoom)
            assertTrue((screen.left + screen.right) / 2 in 0f..420f)
            assertEquals(250f, (screen.top + screen.bottom) / 2, 0.01f)
            val hit = layout.pagePoint(position, (screen.left + screen.right) / 2, 250f)
            assertEquals(3, hit!!.page)
            assertEquals(510f, hit.x, 0.01f)
            assertEquals(630f, hit.y, 0.01f)
        }
    }
}
