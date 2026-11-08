package org.pdfcraft.android

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private val PositionSaver = listSaver<DocumentPosition, Double>(
    save = { listOf(it.zoom.toDouble(), it.panX.toDouble(), it.scrollY) },
    restore = { DocumentPosition(it[0].toFloat(), it[1].toFloat(), it[2]) },
)

@Composable
fun DocumentCanvas(reader: ReaderViewModel, onTap: () -> Unit, onShowControls: () -> Unit, modifier: Modifier = Modifier) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var position by rememberSaveable(reader.documentKey, stateSaver = PositionSaver) { mutableStateOf(DocumentPosition()) }
    var lastNavigation by rememberSaveable(reader.documentKey) { mutableIntStateOf(-1) }
    var previousWidth by rememberSaveable(reader.documentKey) { mutableFloatStateOf(0f) }
    var previousGap by rememberSaveable(reader.documentKey) { mutableFloatStateOf(0f) }
    var previousLayout by remember(reader.documentKey) { mutableStateOf<DocumentLayout?>(null) }
    val gap = with(LocalDensity.current) { 12.dp.toPx() }
    val layout = remember(reader.pageSizes, viewport.width, gap) {
        if (viewport.width > 0) DocumentLayout(reader.pageSizes, viewport.width.toFloat(), gap) else null
    }
    val height = viewport.height.toFloat()
    val latestTap by rememberUpdatedState(onTap)
    val showControls by rememberUpdatedState(onShowControls)
    val enabled = !reader.busy && !reader.organizerOpen && !reader.noteEditorOpen
    val scope = rememberCoroutineScope()
    var fling by remember { mutableStateOf<Job?>(null) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    LaunchedEffect(layout, height, reader.navigationSerial) {
        val current = layout ?: return@LaunchedEffect
        fling?.cancel()
        val before = previousLayout ?: if (previousWidth > 0f) DocumentLayout(reader.pageSizes, previousWidth, previousGap) else current
        position = if (lastNavigation != reader.navigationSerial) reader.navigationRect?.let {
            current.focus(position, reader.navigationPage, it, height)
        } ?: current.jump(position, reader.navigationPage, height)
        else current.reflow(position, before, height)
        lastNavigation = reader.navigationSerial
        previousLayout = current
        previousWidth = current.width
        previousGap = gap
    }
    LaunchedEffect(enabled) { if (!enabled) fling?.cancel() }
    DisposableEffect(Unit) { onDispose { fling?.cancel() } }
    val demand = remember(layout, position, height) { layout?.let { renderDemand(it, position, height) }.orEmpty() }
    LaunchedEffect(demand, reader.renderEpoch, enabled) { if (enabled) reader.requestRasters(demand) }
    val currentPage = layout?.pageAt((position.scrollY + height / 2f) / position.zoom) ?: 0
    LaunchedEffect(currentPage, enabled) { if (enabled) reader.visiblePage(currentPage) }
    val textPages = remember(layout, position, height, currentPage) {
        (listOf(currentPage) + layout?.visible(position, height).orEmpty().map { it.index }).distinct()
    }
    LaunchedEffect(textPages, reader.textEditing, reader.renderEpoch, enabled) {
        if (enabled) reader.requestTextPages(textPages)
    }
    LaunchedEffect(textPages, reader.textEditing, reader.reading.artworkMode, reader.renderEpoch, enabled) {
        if (enabled && !reader.textEditing) reader.reading.requestPages(textPages)
    }
    fun selectionHandles(): Pair<Offset, Offset>? {
        val selected = reader.reading.selection ?: return null
        val glyphs = reader.reading.pages[selected.page]?.selected(selected).orEmpty()
        val page = layout?.pages?.getOrNull(selected.page) ?: return null
        val first = glyphs.firstOrNull()?.rect ?: return null
        val last = glyphs.last().rect
        val a = page.screenRect(layout.width, position, first)
        val b = page.screenRect(layout.width, position, last)
        return Offset(a.left, a.bottom) to Offset(b.right, b.bottom)
    }
    val images = remember(reader.rasterVersion, demand) { demand.associateWith(reader::raster) }
    Canvas(modifier.fillMaxSize().background(Color(0xFFE5E6E8)).clipToBounds().onSizeChanged { viewport = it }
        .semantics {
            contentDescription = "Документ PDF, страница ${currentPage + 1}"
            verticalScrollAxisRange = ScrollAxisRange(
                value = { position.scrollY.toFloat() },
                maxValue = { ((layout?.height ?: 0.0) * position.zoom - height).coerceAtLeast(0.0).toFloat() },
            )
            scrollBy { x, y ->
                if (enabled) layout?.let { position = it.constrain(position.copy(panX = position.panX - x, scrollY = position.scrollY + y), height) }
                enabled
            }
            customActions = listOf(
                CustomAccessibilityAction("Показать или скрыть панели") { latestTap(); true },
                CustomAccessibilityAction("Сбросить масштаб") {
                    layout?.let { position = it.transform(position, 1f / position.zoom, 0f, 0f, it.width / 2f, height / 2f, height) }; true
                },
                CustomAccessibilityAction("Следующая страница") { reader.goTo(1); true },
                CustomAccessibilityAction("Предыдущая страница") { reader.goTo(-1); true },
            )
        }
        .pointerInput(reader.documentKey, layout, viewport, enabled, reader.textEditing, reader.reading.artworkMode, reader.addingText) {
            val strip = layout ?: return@pointerInput
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val stoppedFling = fling?.isActive == true
                fling?.cancel()
                val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                var multiple = false
                var moved = false
                var longPressed = false
                var selecting = false
                var handle: Boolean? = selectionHandles()?.let { (a, b) ->
                    when {
                        (down.position - a).getDistance() < 26.dp.toPx() -> true
                        (down.position - b).getDistance() < 26.dp.toPx() -> false
                        else -> null
                    }
                }
                var travel = Offset.Zero
                var lastTime = down.uptimeMillis
                do {
                    val event = if (!moved && !multiple && !longPressed && handle == null && !reader.textEditing && !reader.reading.artworkMode && !reader.addingText) {
                        withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - (lastTime - down.uptimeMillis)).coerceAtLeast(1)) { awaitPointerEvent() }
                    } else awaitPointerEvent()
                    if (event == null) {
                        longPressed = true
                        strip.pagePoint(position, down.position.x, down.position.y)?.let { point ->
                            val scale = (strip.pages.getOrNull(point.page)?.fit ?: 1f) * position.zoom
                            selecting = reader.reading.selectWord(point, 24.dp.toPx() / scale)
                            if (selecting) showControls()
                        }
                        continue
                    }
                    if (event.changes.count { it.pressed || it.previousPressed } > 1) { multiple = true; selecting = false; handle = null }
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    primary?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    val pan = event.calculatePan()
                    travel += pan
                    if (travel.getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!multiple && (handle != null || selecting)) {
                        if (moved) primary?.let { change ->
                            strip.pagePoint(position, change.position.x, change.position.y)?.let { reader.reading.dragBoundary(it, handle ?: false) }
                        }
                        event.changes.forEach { it.consume() }
                    } else if (multiple || moved) {
                        val focus = event.calculateCentroid(useCurrent = false)
                        position = strip.transform(position, event.calculateZoom(), pan.x, pan.y, focus.x, focus.y, height)
                        event.changes.forEach { it.consume() }
                    }
                    lastTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastTime
                    if (event.changes.none { it.pressed }) break
                } while (true)
                if (!multiple && !moved && !longPressed && handle == null && !stoppedFling) {
                    val point = strip.pagePoint(position, down.position.x, down.position.y)
                    val scale = (point?.let { strip.pages.getOrNull(it.page)?.fit } ?: 1f) * position.zoom
                    when {
                        reader.addingText -> point?.let { reader.placeNote(it); showControls() }
                        reader.textEditing -> point?.let { reader.selectText(it, 6.dp.toPx() / scale) }
                        reader.reading.artworkMode -> point?.let { reader.reading.hitArtwork(it, 6.dp.toPx() / scale) }
                        reader.reading.selection != null -> reader.reading.clearSelection()
                        else -> latestTap()
                    }
                }
                else if (!multiple && moved && !selecting && handle == null && !longPressed) {
                    val speed = velocity.calculateVelocity()
                    fling = scope.launch {
                        var vx = speed.x.coerceIn(-12000f, 12000f)
                        var vy = speed.y.coerceIn(-12000f, 12000f)
                        var time = withFrameNanos { it }
                        while (abs(vx) > 20f || abs(vy) > 20f) {
                            val now = withFrameNanos { it }
                            val dt = ((now - time) / 1_000_000_000f).coerceIn(0f, 0.032f)
                            time = now
                            val before = position
                            position = strip.constrain(before.copy(panX = before.panX + vx * dt, scrollY = before.scrollY - vy * dt), height)
                            if (position.panX == before.panX) vx = 0f
                            if (position.scrollY == before.scrollY) vy = 0f
                            val decay = exp(-5f * dt)
                            vx *= decay
                            vy *= decay
                        }
                    }
                }
            }
        }) {
        val strip = layout ?: return@Canvas
        for (page in strip.visible(position, height)) {
            val left = strip.width / 2f + (page.left - strip.width / 2f) * position.zoom + position.panX
            val top = (page.top * position.zoom - position.scrollY).toFloat()
            val right = left + page.width * position.zoom
            val bottom = top + (page.height * position.zoom).toFloat()
            drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top))
            clipRect(left, top, right, bottom) {
                fun bitmap(key: RasterKey, bounds: RectF) {
                    images[key]?.let { image -> drawContext.canvas.nativeCanvas.drawBitmap(image.asAndroidBitmap(), null, bounds, paint) }
                }
                bitmap(previewKey(page), RectF(left, top, right, bottom))
                for (key in demand) if (key.page == page.index && !key.preview) {
                    val ratio = page.fit * position.zoom / key.scale
                    bitmap(key, RectF(left + key.x * ratio, top + key.y * ratio,
                        left + (key.x + key.width) * ratio, top + (key.y + key.height) * ratio))
                }
                fun highlight(rect: DisplayRect, color: Color, outline: Boolean = false) {
                    val r = page.screenRect(strip.width, position, rect)
                    val bounds = Size((r.right - r.left).coerceAtLeast(1f), (r.bottom - r.top).coerceAtLeast(1f))
                    if (outline) drawRect(color, Offset(r.left, r.top), bounds, style = Stroke(2.dp.toPx()))
                    else drawRect(color, Offset(r.left, r.top), bounds)
                }
                reader.reading.matches.forEachIndexed { index, hit ->
                    if (hit.page == page.index) hit.bounds.forEach { highlight(it, if (index == reader.reading.matchIndex) Color(0x88FF8F00) else Color(0x66FFEB3B)) }
                }
                reader.reading.selection?.takeIf { it.page == page.index }?.let { selected ->
                    reader.reading.pages[page.index]?.selectionRects(selected)?.forEach { highlight(it, Color(0x66145FC8)) }
                    selectionHandles()?.let { (a, b) ->
                        for (point in listOf(a, b)) {
                            drawLine(Color(0xFF145FC8), point, point + Offset(0f, 8.dp.toPx()), 2.dp.toPx())
                            drawCircle(Color(0xFF145FC8), 6.dp.toPx(), point + Offset(0f, 8.dp.toPx()))
                        }
                    }
                }
                if (reader.reading.artworkMode) for (block in reader.reading.artwork[page.index]?.blocks.orEmpty()) {
                    val chosen = reader.reading.selectedArtwork
                    if (chosen?.page == page.index && chosen.block.index == block.index && chosen.block.kind == block.kind)
                        highlight(block.bounds.displayRect(), Color(0x55FF9800))
                    highlight(block.bounds.displayRect(), Color(0xFFE07800), outline = true)
                }
                if (reader.textEditing) for (block in reader.textPages[page.index]?.blocks.orEmpty()) {
                    val r = page.screenRect(strip.width, position, block.displayRect())
                    drawRect(Color(0xB3145FC8), Offset(r.left, r.top),
                        Size((r.right - r.left).coerceAtLeast(1f), (r.bottom - r.top).coerceAtLeast(1f)), style = Stroke(1.dp.toPx()))
                }
            }
        }
        reader.noteDraft?.let { draft ->
            val page = strip.pages.getOrNull(draft.page)
            val bounds = page?.let { noteFrame(draft, it.size) }
            if (page != null && bounds != null) {
                val frame = page.screenRect(strip.width, position, bounds)
                drawRect(Color(0xFF145FC8), Offset(frame.left, frame.top), Size(frame.right - frame.left, frame.bottom - frame.top), style = Stroke(2.dp.toPx()))
            }
        }

    }
}
