package org.pdfcraft.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Hand-drawn control glyphs: consistent thin outlines and compact PDF-reader
// silhouettes. No Adobe binary artwork, brand mark or avatar is bundled.
enum class ReaderGlyph(val paths: List<String>) {
    Back(listOf("M15 5 L8 12 L15 19", "M8 12 H22")),
    Previous(listOf("M15 5 L8 12 L15 19")),
    Next(listOf("M9 5 L16 12 L9 19")),
    More(listOf("M5 11 a1 1 0 1 0 0 2 a1 1 0 1 0 0-2", "M12 11 a1 1 0 1 0 0 2 a1 1 0 1 0 0-2", "M19 11 a1 1 0 1 0 0 2 a1 1 0 1 0 0-2")),
    Search(listOf("M16 16 L22 22", "M18 10 A8 8 0 1 0 2 10 A8 8 0 1 0 18 10")),
    Image(listOf("M3 3 H21 V21 H3 Z", "M3 17 L9 10 L14 16 L17 12 L21 17", "M16 6 H17 V7 H16 Z")),
    Open(listOf("M3 7 V4 H9 L11 6 H20 V9", "M3 9 H22 L19 20 H2 Z")),
    Save(listOf("M5 3 H14 L19 8 V13", "M14 3 V8 H19", "M5 3 V21 H11", "M17 12 V22 M13 18 L17 22 L21 18")),
    Pages(listOf("M3 3 H9 V10 H3 Z M14 3 H20 V10 H14 Z M3 14 H9 V21 H3 Z M14 14 H20 V21 H14 Z")),
    EditText(listOf("M3 4 H16 M9 4 V21 M5 21 H12", "M14 17 L20 11 L23 14 L17 20 L13 21 Z")),
    Add(listOf("M5 3 H14 L19 8 V12 M14 3 V8 H19 M5 3 V21 H11", "M17 14 V22 M13 18 H21")),
    Delete(listOf("M3 6 H21 M8 6 V3 H16 V6", "M5 6 L6 22 H18 L19 6", "M10 10 V18 M14 10 V18")),
    Rotate(listOf("M20 8 A9 9 0 1 0 21 15", "M20 2 V8 H14")),
    Undo(listOf("M8 4 L2 10 L8 16", "M2 10 H15 A6 6 0 0 1 15 22")),
    Redo(listOf("M16 4 L22 10 L16 16", "M22 10 H9 A6 6 0 0 0 9 22")),
    Close(listOf("M5 5 L19 19 M19 5 L5 19")),
    Document(listOf("M5 2 H14 L20 8 V22 H5 Z M14 2 V8 H20", "M9 12 H16 M9 16 H16")),
}

@Composable
fun ReaderIcon(glyph: ReaderGlyph, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val paths = remember(glyph) { glyph.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}
