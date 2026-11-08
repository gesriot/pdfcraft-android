package org.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class DocumentViewportTest {
    private fun strip(count: Int = 5, width: Float = 1000f) = DocumentLayout(List(count) { PageDimensions(600f, 800f) }, width, 12f)

    @Test fun scrollCrossesPageBoundaryWithoutChangingZoomOrHorizontalPosition() {
        val layout = strip()
        val old = DocumentPosition(3f, -350f, layout.pages[0].bottom * 3 - 100)
        val next = layout.transform(old, 1f, 0f, -500f, 500f, 400f, 800f)
        assertEquals(3f, next.zoom, 0f)
        assertEquals(-350f, next.panX, 0f)
        assertEquals(1, layout.pageAt(next.scrollY / next.zoom))
        assertEquals(old.scrollY + 500, next.scrollY, 0.001)
    }

    @Test fun pinchPreservesDocumentPointUnderFingers() {
        val layout = strip()
        val old = DocumentPosition(2f, -100f, 1800.0)
        val next = layout.transform(old, 1.5f, 0f, 0f, 650f, 300f, 900f)
        assertEquals((old.scrollY + 300) / old.zoom, (next.scrollY + 300) / next.zoom, 0.001)
        assertEquals((650 - 500 - old.panX) / old.zoom, (650 - 500 - next.panX) / next.zoom, 0.001f)
    }

    @Test fun oneViewportCanContainEndOfOnePageAndStartOfNext() {
        val layout = strip()
        val position = DocumentPosition(2f, 0f, layout.pages[0].bottom * 2 - 100)
        assertEquals(listOf(0, 1), layout.visible(position, 700f).map { it.index })
        val requests = renderDemand(layout, position, 700f).filter { !it.preview }
        assertEquals(setOf(0, 1), requests.map { it.page }.toSet())
    }

    @Test fun navigationKeepsZoomAndClampsAtDocumentEnd() {
        val layout = strip()
        val old = DocumentPosition(2f, 80f, 0.0)
        val next = layout.jump(old, 3, 900f)
        assertEquals(2f, next.zoom, 0f)
        assertEquals(80f, next.panX, 0f)
        assertEquals(layout.pages[3].top * 2, next.scrollY, 0.001)
        val end = layout.constrain(old.copy(scrollY = 1e20, panX = 1e20f), 900f)
        assertEquals(layout.height * 2 - 900, end.scrollY, 0.001)
        assertTrue(end.panX < layout.width)
        assertEquals(0.0, layout.constrain(old.copy(scrollY = -10.0), 900f).scrollY, 0.0)
    }

    @Test fun orientationReflowPreservesPageAndFraction() {
        val before = strip(width = 1000f)
        val after = strip(width = 1600f)
        val old = DocumentPosition(2.5f, -100f, (before.pages[2].top + before.pages[2].height * 0.42) * 2.5)
        val next = after.reflow(old, before, 900f)
        assertEquals(old.zoom, next.zoom, 0f)
        assertEquals(2, after.pageAt(next.scrollY / next.zoom))
        assertEquals(0.42, (next.scrollY / next.zoom - after.pages[2].top) / after.pages[2].height, 0.00001)
    }

    @Test fun hiddenToolbarsDoNotResetReadingPosition() {
        val layout = strip()
        val old = DocumentPosition(3f, 150f, 3500.0)
        val next = layout.reflow(old, layout, 1400f)
        assertEquals(old, next)
    }

    @Test fun tileResolutionAlwaysMeetsPhysicalDisplayScale() {
        for (displayScale in listOf(0.15f, 0.51f, 1f, 1.8f, 3.2f, 8.1f, 16f)) {
            val nativeScale = 2.0.pow(tileLevel(displayScale) / 4.0)
            assertTrue(nativeScale + 1e-6 >= displayScale)
            assertTrue(nativeScale <= displayScale * 1.19)
        }
    }

    @Test fun zoomedScientificPageRequestsOnlyBoundedVisibleTiles() {
        val layout = strip(100)
        val position = DocumentPosition(8f, -2000f, 2500.0)
        val demand = renderDemand(layout, position, 2200f)
        val tiles = demand.filter { !it.preview }
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.width in 1..514 && it.height in 1..514 })
        assertTrue(tiles.all { it.scale >= layout.pages[it.page].fit * position.zoom })
        assertTrue(demand.sumOf { it.bytes } < RASTER_CACHE_BYTES)
        assertTrue(demand.map { it.page }.distinct().size <= 3)
        assertTrue(tiles.size < 40)
    }

    @Test fun visibleScreenPixelsHaveHighResolutionTileCoverage() {
        val layout = strip()
        for (zoom in listOf(1f, 2.3f, 8f)) {
            val position = layout.constrain(DocumentPosition(zoom, -200f, layout.pages[0].bottom * zoom - 300), 1400f)
            val tiles = renderDemand(layout, position, 1400f).filter { !it.preview }
            for (page in layout.visible(position, 1400f)) {
                val left = layout.width / 2 + (page.left - layout.width / 2) * zoom + position.panX
                val top = page.top * zoom - position.scrollY
                for (sy in 0..1399 step 53) for (sx in 0..999 step 47) {
                    if (sx > left && sx < left + page.width * zoom && sy > top && sy < top + page.height * zoom) {
                        assertTrue("missing pixel ($sx,$sy), zoom=$zoom, page=${page.index}", tiles.any {
                            if (it.page != page.index) false else {
                                val ratio = page.fit * zoom / it.scale
                                val x = (sx - left) / ratio
                                val y = (sy - top) / ratio
                                x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height
                            }
                        })
                    }
                }
            }
        }
    }

    @Test fun longDocumentsKeepSubpixelScrollPrecision() {
        val layout = strip(20000)
        val old = layout.jump(DocumentPosition(8f), 19990, 900f)
        val next = layout.transform(old, 1f, 0f, -0.25f, 500f, 400f, 900f)
        assertEquals(0.25, next.scrollY - old.scrollY, 0.00001)
        assertTrue(layout.visible(next, 900f).size <= 2)
    }

    @Test fun invalidGestureAndViewportValuesCannotLoseDocument() {
        val layout = strip()
        val old = DocumentPosition(2f, 20f, 500.0)
        assertEquals(old, layout.transform(old, Float.NaN, 0f, 0f, 1f, 1f, 900f))
        assertEquals(old, layout.transform(old, 1f, Float.POSITIVE_INFINITY, 0f, 1f, 1f, 900f))
        val repaired = layout.constrain(DocumentPosition(Float.NaN, Float.NaN, Double.NaN), 900f)
        assertTrue(repaired.zoom.isFinite() && repaired.panX.isFinite() && repaired.scrollY.isFinite())
        assertTrue(abs(repaired.scrollY) < 0.001)
    }

    @Test fun cacheEvictsLeastRecentlyUsedImagesWithinByteBudget() {
        val cache = RasterCache<String, String>(10)
        cache.put("old", "one", 4)
        cache.put("visible", "two", 4)
        assertEquals("two", cache["visible"])
        cache.put("new", "three", 4)
        assertNull(cache["old"])
        assertEquals("two", cache["visible"])
        assertEquals(8L, cache.bytes)
        cache.put("visible", "replacement", 6)
        assertEquals(10L, cache.bytes)
        cache.clear()
        assertEquals(0L, cache.bytes)
        assertNull(cache["visible"])
    }
}
