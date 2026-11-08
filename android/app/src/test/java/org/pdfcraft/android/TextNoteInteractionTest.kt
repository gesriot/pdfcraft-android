package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import org.pdfcraft.android.engine.NoteColor

class TextNoteInteractionTest {
    private val snapshot = PageSnapshot(2, 7u, 3)
    private val dimensions = PageDimensions(600f, 800f)
    private fun draft() = placeTextNote(snapshot, PagePoint(1, 40f, 60f), dimensions).copy(value = "Моя заметка\nMy note")
    @Test fun placementAtEdgesKeepsFullFieldInsidePage() {
        for (x in listOf(0f, 300f, 599f, 600f)) for (y in listOf(0f, 400f, 799f, 800f)) {
            val draft = placeTextNote(snapshot, PagePoint(1, x, y), dimensions)
            val bounds = noteBounds(draft)!!
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 600 && bounds.bottom <= 800)
        }
        val small = noteBounds(placeTextNote(snapshot, PagePoint(0, 9f, 7f), PageDimensions(20f, 12f)))!!
        assertEquals(20f, small.right - small.left, 0f)
        assertEquals(12f, small.bottom - small.top, 0f)
    }
    @Test fun repositionPreservesTextSizeColorAndWidthOnAnotherPage() {
        val original = draft().copy(size = "18,5", color = NoteColor.RED, width = "260")
        val moved = placeTextNote(snapshot, PagePoint(2, 790f, 590f), PageDimensions(800f, 600f), original)
        assertEquals(original.value, moved.value); assertEquals(original.size, moved.size); assertEquals(original.color, moved.color)
        assertEquals(2, moved.page)
        assertEquals(800f, noteBounds(moved)!!.right, 0f); assertEquals(600f, noteBounds(moved)!!.bottom, 0f)
    }
    @Test fun nativeInputKeepsUnicodeAndRussianDecimal() {
        val draft = draft().copy(size = "12,5", width = "200,5")
        val input = textNoteInput(draft, dimensions)
        assertEquals("Моя заметка\nMy note", input.text); assertEquals(12.5, input.size, 0.0)
        assertEquals(240.5f, input.bounds.right, 0.001f); assertEquals(7uL, input.revision)
    }
    @Test fun oversizedOrInvalidDraftsCannotBePreviewed() {
        val variants = listOf(draft().copy(value = " "), draft().copy(value = "Ё".repeat(4097)), draft().copy(value = "x\u0000y"),
            draft().copy(size = "NaN"), draft().copy(size = "145"), draft().copy(width = "Infinity"),
            draft().copy(height = "0"), draft().copy(width = "600"), draft().copy(top = -1f))
        variants.forEach { assertTrue(runCatching { textNoteInput(it, dimensions) }.isFailure) }
    }
    @Test fun selectedFrameUsesSameRasterCoordinatesAfterZoomPanAndScroll() {
        val layout = DocumentLayout(listOf(dimensions, dimensions), 420f, 12f)
        val page = layout.pages[1]
        val frame = noteBounds(draft())!!
        for (zoom in listOf(1f, 2.8f, 8f)) {
            val position = DocumentPosition(zoom, -20f, page.top * zoom + 20)
            val screen = page.screenRect(layout.width, position, frame)
            val hit = layout.pagePoint(position, screen.left + (screen.right - screen.left) / 2, screen.top + (screen.bottom - screen.top) / 2)!!
            assertEquals(1, hit.page); assertEquals((frame.left + frame.right) / 2, hit.x, 0.001f)
            assertEquals((frame.top + frame.bottom) / 2, hit.y, 0.001f)
        }
    }
    @Test fun hugeFiniteDimensionsNeverReachCanvasAtHighZoom() {
        val oversized = draft().copy(width = "1e38", height = "1e38")
        val layout = DocumentLayout(listOf(dimensions, dimensions), 420f, 12f)
        val unsafe = layout.pages[1].screenRect(layout.width, DocumentPosition(8f), noteBounds(oversized)!!)
        assertTrue(!unsafe.right.isFinite() || !unsafe.bottom.isFinite())
        assertNull(noteFrame(oversized, dimensions))
        assertNull(noteFrame(draft().copy(left = Float.NaN), dimensions))
        assertNotNull(noteFrame(draft(), dimensions))
    }

}
