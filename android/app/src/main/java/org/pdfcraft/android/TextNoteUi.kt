package org.pdfcraft.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.pdfcraft.android.engine.NoteColor

@Composable
fun AddTextBar(reader: ReaderViewModel) {
    Surface(color = Color(0xFFEAF2FF)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (reader.noteDraft == null) "Коснитесь страницы, чтобы добавить текст" else "Коснитесь нового места для поля",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (reader.noteDraft != null) TextButton(onClick = reader::openNoteEditor, enabled = !reader.busy) { Text("Ввод") }
            TextButton(onClick = { reader.leaveNoteMode() }, enabled = !reader.busy) { Text("Готово") }
        }
    }
}
@Composable
fun TextNoteEditor(reader: ReaderViewModel) {
    val draft = reader.noteDraft ?: return
    val dimensions = reader.pageSizes.getOrNull(draft.page) ?: return
    val input = remember(draft, dimensions) { runCatching { textNoteInput(draft, dimensions) } }
    val preview = reader.notePreview
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = { reader.leaveNoteMode() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Добавить текст", style = MaterialTheme.typography.titleLarge)
                        Text("Страница ${draft.page + 1} · Inter RU/EN", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { keyboard?.hide(); reader.chooseNotePosition() }, enabled = !reader.busy) { Text("Место") }
                    TextButton(onClick = { reader.leaveNoteMode() }, enabled = !reader.busy) { Text("Отмена") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft.value, { reader.updateNoteDraft(draft.copy(value = it)) }, label = { Text("Ваш текст") },
                        minLines = 3, maxLines = 7, enabled = !reader.busy, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("До 8 КиБ · переносы строк поддерживаются") })
                    Text("Текст будет постоянно виден на странице. Фон поля прозрачный.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.size, { reader.updateNoteDraft(draft.copy(size = it.take(12))) }, label = { Text("Размер шрифта, пт") },
                        singleLine = true, enabled = !reader.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(draft.width, { reader.updateNoteDraft(draft.copy(width = it.take(12))) }, label = { Text("Ширина, пт") },
                            modifier = Modifier.weight(1f), singleLine = true, enabled = !reader.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(draft.height, { reader.updateNoteDraft(draft.copy(height = it.take(12))) }, label = { Text("Высота, пт") },
                            modifier = Modifier.weight(1f), singleLine = true, enabled = !reader.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(NoteColor.BLACK to "Чёрный", NoteColor.BLUE to "Синий", NoteColor.RED to "Красный", NoteColor.GREEN to "Зелёный").forEach { (color, label) ->
                            FilterChip(draft.color == color, { reader.updateNoteDraft(draft.copy(color = color)) }, enabled = !reader.busy,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    input.exceptionOrNull()?.message?.takeIf { draft.value.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    reader.noteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (reader.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (preview != null) {
                        Text("Предпросмотр PDF", style = MaterialTheme.typography.titleMedium)
                        Image(preview.bitmap, "Добавленный текст на странице PDF",
                            Modifier.fillMaxWidth().aspectRatio(preview.bitmap.width.toFloat() / preview.bitmap.height))
                        if (preview.warnings.isNotEmpty()) Text(preview.warnings.joinToString(), color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { keyboard?.hide(); reader.previewNote() }, enabled = !reader.busy && input.isSuccess,
                        modifier = Modifier.weight(1f)) { Text("Предпросмотр") }
                    Button(onClick = { keyboard?.hide(); reader.applyNote() }, enabled = !reader.busy && preview != null,
                        modifier = Modifier.weight(1f)) { Text("Добавить") }
                }
            }
        }
    }
}
@Composable
fun DiscardTextNote(reader: ReaderViewModel) {
    AlertDialog(onDismissRequest = reader::keepNoteDraft, title = { Text("Отменить добавление текста?") },
        text = { Text("Текст ещё не добавлен в PDF.") },
        confirmButton = { TextButton(onClick = { reader.leaveNoteMode(force = true) }) { Text("Отменить") } },
        dismissButton = { TextButton(onClick = reader::keepNoteDraft) { Text("Продолжить ввод") } })
}
