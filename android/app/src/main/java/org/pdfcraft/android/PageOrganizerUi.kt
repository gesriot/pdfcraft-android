package org.pdfcraft.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.isActive

@Composable
fun PageOrganizer(reader: ReaderViewModel, onImport: (Int) -> Unit, onExtract: (PageSelection) -> Unit) {
    val snapshot = reader.pageSnapshot() ?: return
    val count = snapshot.count
    val selected = reader.selectedPages
    val ready = !reader.busy
    val grid = rememberLazyGridState()
    var drag by remember(snapshot) { mutableStateOf<PageSelection?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var gap by remember { mutableStateOf<Int?>(null) }
    var deletion by remember { mutableStateOf<PageSelection?>(null) }
    var moving by remember { mutableStateOf<PageSelection?>(null) }
    var inserting by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 600.dp.toPx() }
    fun cells() = grid.layoutInfo.visibleItemsInfo.map {
        PageCell(it.index, it.offset.x.toFloat(), it.offset.y.toFloat(), it.size.width.toFloat(), it.size.height.toFloat())
    }
    LaunchedEffect(reader.organizerScrollSerial) { grid.scrollToItem(reader.organizerScrollPage.coerceIn(0, count - 1)) }
    val visible by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.map { it.index } } }
    LaunchedEffect(visible, snapshot, reader.busy) { if (ready) reader.thumbnails.request(visible) }
    LaunchedEffect(drag != null) {
        var previous = withFrameNanos { it }
        while (isActive && drag != null) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val scroll = dragScrollSpeed(pointer.y, grid.layoutInfo.viewportSize.height.toFloat(), edge)
            if (scroll != 0f) grid.scrollBy(scroll * speed * dt)
            gap = dropGap(cells(), pointer.x, pointer.y)
        }
    }
    Dialog(onDismissRequest = { reader.closeOrganizer() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { reader.closeOrganizer() }, enabled = ready,
                        modifier = Modifier.semantics { contentDescription = "Закрыть организацию страниц" }) { ReaderIcon(ReaderGlyph.Back) }
                    Column(Modifier.weight(1f)) {
                        Text("Страницы", style = MaterialTheme.typography.titleLarge)
                        Text("Выбрано ${selected.size} из $count", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = reader::undo, enabled = ready && reader.info?.canUndo == true,
                        modifier = Modifier.semantics { contentDescription = "Отменить" }) { ReaderIcon(ReaderGlyph.Undo) }
                    IconButton(onClick = reader::redo, enabled = ready && reader.info?.canRedo == true,
                        modifier = Modifier.semantics { contentDescription = "Повторить" }) { ReaderIcon(ReaderGlyph.Redo) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { reader.selectPages(if (selected.isEmpty()) (0 until count.coerceAtMost(500)).toSet() else emptySet()) }, enabled = ready) {
                        Text(if (selected.isEmpty()) if (count <= 500) "Выбрать все" else "Первые 500" else "Снять выбор")
                    }
                    TextButton(onClick = { ranges = true }, enabled = ready) { Text("Диапазон") }
                    TextButton(onClick = { selected.minOrNull()?.let { reader.closeOrganizer(it) } }, enabled = ready && selected.isNotEmpty()) { Text("Открыть") }
                }
                Text(if (drag != null) "Перенос: ${drag?.pages?.size} стр. · ${gap?.let { if (it == count) "в конец" else "перед страницей ${it + 1}" } ?: "выберите место"}"
                    else "Коснитесь для выбора · удерживайте для переноса",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                if (reader.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                reader.message?.let { text ->
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = reader::dismissMessage) { Text("OK") }
                    }
                }
                LazyVerticalGrid(GridCells.Adaptive(112.dp), state = grid, contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF0F1F3))
                        .pointerInput(snapshot, ready) {
                            if (!ready) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    val cell = pageAt(cells(), start.x, start.y)
                                    if (cell != null) {
                                        val pages = if (cell.index in reader.selectedPages) reader.selectedPages.sorted() else listOf(cell.index)
                                        reader.selectPages(pages.toSet()); drag = PageSelection(snapshot, pages)
                                        pointer = start; gap = cell.index
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (drag != null) { change.consume(); pointer += amount; gap = dropGap(cells(), pointer.x, pointer.y) }
                                },
                                onDragCancel = { drag = null; gap = null },
                                onDragEnd = {
                                    val selection = drag; val target = gap
                                    drag = null; gap = null
                                    if (selection != null && target != null) reader.movePages(selection, moveDestination(selection.pages, target, count))
                                },
                            )
                        }) {
                    items(count, key = { it }) { index ->
                        val version = reader.thumbnails.version
                        val bitmap = remember(version, index) { reader.thumbnails.get(index) }
                        val active = index in selected
                        val target = gap == index || gap == count && index == count - 1
                        Column(Modifier.fillMaxWidth().border(if (active || target) 2.dp else 1.dp,
                            if (target) Color(0xFFD17A00) else if (active) MaterialTheme.colorScheme.primary else Color(0xFFD4D6DA))
                            .background(if (active) Color(0xFFE6F0FF) else Color.White)
                            .clickable(enabled = ready && drag == null) { reader.togglePage(index) }
                            .semantics { contentDescription = "Страница ${index + 1}, ${if (active) "выбрана" else "не выбрана"}" }
                            .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(152.dp).background(Color.White), contentAlignment = Alignment.Center) {
                                if (bitmap != null) Image(bitmap, "Миниатюра страницы ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else if (reader.thumbnails.errors[index] != null) Text(reader.thumbnails.errors[index]!!, style = MaterialTheme.typography.labelSmall, maxLines = 5)
                                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Checkbox(active, onCheckedChange = { reader.togglePage(index) }, enabled = ready, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { reader.selectedSnapshot()?.let { reader.movePages(it, ((it.pages.minOrNull() ?: 0) - 1).coerceAtLeast(0)) } },
                        enabled = ready && selected.isNotEmpty() && (selected.minOrNull() ?: 0) > 0) { Text("← Раньше") }
                    TextButton(onClick = { moving = reader.selectedSnapshot() }, enabled = ready && selected.isNotEmpty()) { Text("На позицию…") }
                    TextButton(onClick = { reader.selectedSnapshot()?.let {
                        val to = moveDestination(it.pages, ((it.pages.maxOrNull() ?: 0) + 2).coerceAtMost(count), count)
                        reader.movePages(it, to)
                    } }, enabled = ready && selected.isNotEmpty() && (selected.maxOrNull() ?: count - 1) < count - 1) { Text("Позже →") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OrganizerAction(ReaderGlyph.Add, "Вставить", ready) { inserting = true }
                    OrganizerAction(ReaderGlyph.Delete, "Удалить", ready && selected.isNotEmpty() && selected.size < count) { deletion = reader.selectedSnapshot() }
                    OrganizerAction(ReaderGlyph.Save, "Извлечь", ready && selected.isNotEmpty()) { reader.selectedSnapshot()?.let(onExtract) }
                }
            }
        }
        deletion?.let { selection ->
            AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Удалить страниц: ${selection.pages.size}?") },
                text = { Text("Удаление можно отменить. Исходный файл останется без изменений до сохранения копии.") },
                confirmButton = { TextButton(onClick = { deletion = null; reader.removePages(selection) }) { Text("Удалить") } },
                dismissButton = { TextButton(onClick = { deletion = null }) { Text("Отмена") } })
        }
        moving?.let { selection ->
            var value by remember(selection) { mutableStateOf("${(selection.pages.minOrNull() ?: 0) + 1}") }
            val max = count - selection.pages.size + 1
            val position = value.toIntOrNull()?.takeIf { it in 1..max }
            AlertDialog(onDismissRequest = { moving = null }, title = { Text("Переместить страницы") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Номер первой перемещённой страницы в итоговом документе. Выбранные страницы сохранят свой порядок.")
                    OutlinedTextField(value, { value = it.take(10) }, singleLine = true, label = { Text("Позиция 1–$max") }, isError = position == null)
                }
            }, confirmButton = { TextButton(enabled = position != null, onClick = { position?.let { reader.movePages(selection, it - 1) }; moving = null }) { Text("Переместить") } },
                dismissButton = { TextButton(onClick = { moving = null }) { Text("Отмена") } })
        }
        if (inserting) InsertPagesDialog(snapshot, selected, { inserting = false }, { at -> inserting = false; onImport(at) },
            { at, landscape -> inserting = false; reader.insertBlank(at, landscape, snapshot) })
        if (ranges) {
            var value by remember { mutableStateOf("") }
            val parsed = runCatching { parsePageSelection(value, count) }
            AlertDialog(onDismissRequest = { ranges = false }, title = { Text("Выбрать страницы") },
                text = { OutlinedTextField(value, { value = it.take(512) }, label = { Text("Например, 1, 3–5") }, isError = value.isNotEmpty() && parsed.isFailure,
                    supportingText = { Text(parsed.exceptionOrNull()?.message ?: "Выбрано: ${parsed.getOrNull()?.size}") }) },
                confirmButton = { TextButton(enabled = parsed.isSuccess, onClick = { reader.selectPages(parsed.getOrThrow().toSet()); ranges = false }) { Text("Выбрать") } },
                dismissButton = { TextButton(onClick = { ranges = false }) { Text("Отмена") } })
        }
    }
}

@Composable
private fun RowScope.OrganizerAction(glyph: ReaderGlyph, label: String, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = action),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ReaderIcon(glyph, color = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray)
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) Color.Black else Color.Gray)
    }
}

@Composable
private fun InsertPagesDialog(snapshot: PageSnapshot, selected: Set<Int>, dismiss: () -> Unit,
    file: (Int) -> Unit, blank: (Int, Boolean) -> Unit) {
    var where by remember { mutableStateOf(if (selected.isEmpty()) 3 else 1) }
    var landscape by remember { mutableStateOf(false) }
    val at = when (where) { 0 -> selected.minOrNull() ?: 0; 1 -> (selected.maxOrNull() ?: (snapshot.count - 1)) + 1; 2 -> 0; else -> snapshot.count }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Вставить страницы") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("Перед выбранными", "После выбранных", "В начало", "В конец").forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().clickable { where = index }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(where == index, { where = index }); Text(label)
                }
            }
            Button(onClick = { file(at) }, modifier = Modifier.fillMaxWidth()) { Text("Из PDF / JPG / PNG") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(landscape, { landscape = it }); Text("Альбомная пустая A4") }
            OutlinedButton(onClick = { blank(at, landscape) }, modifier = Modifier.fillMaxWidth()) { Text("Пустая страница A4") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}

@Composable
fun NewDocumentDialog(reader: ReaderViewModel, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("Новый документ") }
    var landscape by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Новый PDF") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название") }, singleLine = true)
            Text("Документ начнётся с одной пустой страницы A4. Через «Страницы» можно добавить PDF или изображения, затем удалить пустую страницу.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(landscape, { landscape = it }); Text("Альбомная ориентация") }
        }
    }, confirmButton = { TextButton(onClick = { reader.createNew(name, landscape); dismiss() }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@Composable
fun PageExportDialog(snapshot: PageSnapshot, pages: List<Int>?, title: String, encrypted: Boolean,
    dismiss: () -> Unit, choose: (PageExportRequest) -> Unit) {
    var format by remember { mutableStateOf(if (pages == null) PageExportFormat.JPG else PageExportFormat.PDF) }
    var dpi by remember { mutableStateOf(150) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (pages == null) "Все страницы в изображения" else "Извлечь страниц: ${pages.size}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (pages == null) "Каждая страница будет отдельным файлом в новой папке." else "Сохранить выбранные страницы отдельно. Исходный документ останется без изменений.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageExportFormat.entries.filter { pages != null || it != PageExportFormat.PDF }.forEach {
                    FilterChip(format == it, { format = it }, label = { Text(it.name) })
                }
            }
            if (format != PageExportFormat.PDF) {
                Text("Качество изображений")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(72, 150, 300).forEach { FilterChip(dpi == it, { dpi = it }, label = { Text("$it dpi") }) }
                }
                Text("Для очень больших страниц разрешение будет снижено до безопасного размера (12 Мп).", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("PDF сохраняет текст и векторные рисунки.")
                if (encrypted) Text("Извлечённый PDF будет без пароля.", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = { TextButton(onClick = { choose(PageExportRequest(snapshot, pages, format, dpi, title)); dismiss() }) {
        Text(if (format == PageExportFormat.PDF) "Сохранить PDF" else "Выбрать папку")
    } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@Composable
fun PageExportProgressDialog(exporter: PageExporter) {
    val progress = exporter.progress ?: return
    AlertDialog(onDismissRequest = { if (progress.finished) exporter.dismiss() }, title = {
        Text(if (!progress.finished) "Экспорт изображений" else if (progress.error != null) "Экспорт прерван" else if (progress.stopped) "Экспорт остановлен" else "Изображения сохранены")
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Сохранено ${progress.completed} из ${progress.total}")
            if (!progress.finished) {
                LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                progress.page?.let { Text("Страница ${it + 1}") }
                Text("Остановка завершит текущую обработку. Готовые файлы останутся в папке.", style = MaterialTheme.typography.bodySmall)
            }
            progress.folder?.let { Text("Папка: $it") }
            if (progress.reduced > 0) Text("Разрешение снижено для ${progress.reduced} страниц из-за их размера.")
            progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (progress.finished) exporter.dismiss() else exporter.stop() }) { Text(if (progress.finished) "Готово" else "Остановить") } })
}
