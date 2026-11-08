package org.pdfcraft.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.pdfcraft.android.engine.TextFont

@Composable
fun TextEditBar(reader: ReaderViewModel, onList: () -> Unit) {
    Surface(color = Color(0xFFEAF2FF)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Коснитесь текста в рамке", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onList, enabled = !reader.busy) { Text("Блоки") }
            TextButton(onClick = { reader.changeTextMode(false) }, enabled = !reader.busy) { Text("Готово") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextBlockList(reader: ReaderViewModel, onDismiss: () -> Unit) {
    val page = reader.page
    val data = reader.textPages[page]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Текст страницы ${page + 1}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when {
                data == null -> Text("Чтение текстовых блоков…")
                data.blocks.isEmpty() -> Text("Доступных текстовых блоков нет. Сканы и текст в кривых пока не редактируются.")
                else -> LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(data.blocks, key = { it.index.toLong() }) { block ->
                        Column(Modifier.fillMaxWidth().clickable {
                            reader.editTextBlock(page, block); onDismiss()
                        }.padding(vertical = 12.dp)) {
                            Text(block.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(block.fontName, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (data != null && data.skipped > 0u) Text("Пропущено неподдерживаемых блоков: ${data.skipped}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun TextEditorDialog(reader: ReaderViewModel) {
    val draft = reader.textDraft ?: return
    val preview = reader.textPreview
    val keyboard = LocalSoftwareKeyboardController.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val cancel = {
        if (!reader.busy) {
            if (draft.changed) confirmDiscard = true else reader.closeTextDraft()
        }
    }
    val sizeResult = remember(draft.size) { runCatching { textSizeOverride(draft.size) } }
    val tooLong = remember(draft.value) { draft.value.toByteArray(Charsets.UTF_8).size > 32768 }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Редактировать текст", style = MaterialTheme.typography.titleLarge)
                        Text("Страница ${draft.page + 1}", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = cancel, enabled = !reader.busy) { Text("Отмена") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft.value, onValueChange = { reader.updateTextDraft(draft.copy(value = it)) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Текст блока") }, enabled = !reader.busy,
                        minLines = 4, maxLines = 9, isError = tooLong,
                        supportingText = { if (tooLong) Text("Максимум 32 КиБ текста") else if (draft.value.isEmpty()) Text("Применение удалит текст выбранного блока") })
                    Text("Шрифт исходника: ${draft.block.fontName}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.font == TextFont.ORIGINAL, onClick = { reader.updateTextDraft(draft.copy(font = TextFont.ORIGINAL)) },
                            enabled = !reader.busy, label = { Text("Исходный") })
                        FilterChip(selected = draft.font == TextFont.INTER, onClick = { reader.updateTextDraft(draft.copy(font = TextFont.INTER)) },
                            enabled = !reader.busy, label = { Text("Inter · RU/EN") })
                    }
                    Text(if (draft.font == TextFont.INTER) "Inter Regular заменит шрифт блока и будет встроен в PDF."
                        else "Сохраняется оформление исходника, если шрифт содержит нужные символы. Для нового русского текста можно выбрать Inter.",
                        style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    OutlinedTextField(draft.size, onValueChange = { reader.updateTextDraft(draft.copy(size = it.take(16))) },
                        label = { Text("Размер, пт") }, placeholder = { Text("Как в исходнике") }, singleLine = true,
                        enabled = !reader.busy, isError = sizeResult.isFailure,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text(sizeResult.exceptionOrNull()?.message ?: "Пустое поле сохраняет исходные размеры") })
                    reader.textError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (reader.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (preview != null) {
                        Text("Предпросмотр PDF", style = MaterialTheme.typography.titleMedium)
                        preview.font?.let { Text("Шрифт результата: $it", style = MaterialTheme.typography.bodySmall) }
                        if (preview.overflow) Text("Текст выходит за исходную область. Проверьте соседние строки или уменьшите размер.", color = Color(0xFF9A5000))
                        if (preview.outsidePage) Text("Часть текста выходит за край страницы и будет обрезана.", color = MaterialTheme.colorScheme.error)
                        Image(preview.bitmap, contentDescription = "Фрагмент PDF после изменения текста",
                            modifier = Modifier.fillMaxWidth().aspectRatio(preview.bitmap.width.toFloat() / preview.bitmap.height))
                        if (preview.warnings.isNotEmpty()) Text("Предпросмотр: ${preview.warnings.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { keyboard?.hide(); reader.previewText() },
                        enabled = !reader.busy && draft.changed && !tooLong && sizeResult.isSuccess,
                        modifier = Modifier.weight(1f)) { Text("Предпросмотр") }
                    Button(onClick = { keyboard?.hide(); reader.applyText() }, enabled = !reader.busy && preview != null,
                        modifier = Modifier.weight(1f)) { Text("Применить") }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Отменить правку текста?") }, text = { Text("Черновик ещё не применён к документу.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; reader.closeTextDraft() }) { Text("Отменить правку") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить") } })
}
