package org.pdfcraft.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WordExportDialog(snapshot: PageSnapshot, currentPage: Int, title: String, encrypted: Boolean,
    dismiss: () -> Unit, export: (WordExportRequest) -> Unit) {
    var mode by remember(snapshot) { mutableIntStateOf(if (snapshot.count > 500) 1 else 0) }
    var range by remember(snapshot) { mutableStateOf("") }
    val parsed = remember(snapshot, mode, currentPage, range) { runCatching { wordExportPages(snapshot, mode, currentPage, range) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Экспорт в Word") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все страницы (${snapshot.count})", "Текущая страница (${currentPage + 1})", "Выбрать страницы").forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().clickable { mode = index }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == index, onClick = { mode = index }); Text(label, Modifier.weight(1f))
                }
            }
            if (mode == 2) OutlinedTextField(range, onValueChange = { if (it.length <= 4096) range = it }, singleLine = true,
                label = { Text("Страницы") }, placeholder = { Text("1, 3–5") }, isError = parsed.isFailure)
            parsed.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Текст, простые таблицы и изображения. Шрифты и расположение могут измениться; формулы, схемы и колонки могут потребовать правки в Word.", style = MaterialTheme.typography.bodySmall)
            Text("Сканы и страницы, которые не удаётся разобрать, сохраняются картинками. Добавленные надписи и комментарии отдельно не переносятся.", style = MaterialTheme.typography.bodySmall)
            if (encrypted) Text("Создаётся отдельный DOCX без пароля. Исходный PDF сохраняет защиту.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = parsed.isSuccess, onClick = {
        parsed.getOrNull()?.let { export(WordExportRequest(snapshot, it, title)) }
    }) { Text("Создать DOCX") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}

@Composable
fun WordExportProgressDialog(exporter: WordExporter, save: () -> Unit) {
    val progress = exporter.progress ?: return
    AlertDialog(onDismissRequest = { if (progress.finished) exporter.dismiss() },
        title = { Text(when { progress.error != null -> "Ошибка экспорта"; progress.stopped -> "Экспорт отменён"; progress.readyToSave -> "DOCX готов"; progress.finished -> "DOCX сохранён"; else -> "Экспорт в Word" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!progress.finished) {
                    LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text(if (progress.saving) "Подготовка / сохранение файла…" else "Обработано страниц: ${progress.completed} из ${progress.total}")
                    if (!progress.saving) Text("Остановка выполняется после текущей страницы.", style = MaterialTheme.typography.bodySmall)
                } else if (progress.error != null) Text(progress.error)
                else if (progress.stopped) Text("Экспорт остановлен. Исходный PDF не изменён.")
                else {
                    Text("Преобразовано страниц: ${progress.total}")
                    if (progress.imagePages.isNotEmpty()) Text("Как изображения: ${progress.imagePages.take(50).joinToString(", ")}${if (progress.imagePages.size > 50) "…" else ""}. Текст этих страниц в Word не редактируется.")
                }
            }
        }, confirmButton = {
            if (!progress.saving || progress.finished) TextButton(onClick = {
                if (progress.readyToSave) save() else if (progress.finished) exporter.dismiss() else exporter.stop()
            }) { Text(if (progress.readyToSave) "Сохранить как…" else if (progress.finished) "Готово" else "Остановить") }
        }, dismissButton = { if (progress.readyToSave) TextButton(onClick = exporter::dismiss) { Text("Отмена") } })
}
