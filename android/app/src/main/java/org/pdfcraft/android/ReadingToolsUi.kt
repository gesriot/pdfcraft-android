package org.pdfcraft.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun SearchBar(reader: ReaderViewModel) {
    val tools = reader.reading
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(tools.query, tools::updateQuery, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    label = { Text("Найти в документе") }, enabled = !reader.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); tools.goToMatch(tools.matchIndex.coerceAtLeast(0)) }))
                IconButton(onClick = { tools.closeSearch(); keyboard?.hide() }, modifier = Modifier.semantics { contentDescription = "Закрыть поиск" }) { ReaderIcon(ReaderGlyph.Close) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (tools.matches.isEmpty()) "0" else "${tools.matchIndex + 1} / ${tools.matches.size}", Modifier.weight(1f))
                if (tools.searching) Text("Стр. ${tools.searchedPages}/${reader.info?.pageCount}", style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { keyboard?.hide(); tools.goToMatch(tools.matchIndex - 1) }, modifier = Modifier.semantics { contentDescription = "Предыдущее совпадение" }, enabled = tools.matches.isNotEmpty() && !reader.busy) { ReaderIcon(ReaderGlyph.Previous) }
                IconButton(onClick = { keyboard?.hide(); tools.goToMatch(tools.matchIndex + 1) }, modifier = Modifier.semantics { contentDescription = "Следующее совпадение" }, enabled = tools.matches.isNotEmpty() && !reader.busy) { ReaderIcon(ReaderGlyph.Next) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tools.caseSensitive, { tools.searchOptions(!tools.caseSensitive, tools.wholeWords) }, label = { Text("Регистр") }, enabled = !reader.busy)
                FilterChip(tools.wholeWords, { tools.searchOptions(tools.caseSensitive, !tools.wholeWords) }, label = { Text("Целое слово") }, enabled = !reader.busy)
            }
            if (tools.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            tools.searchNotice?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun SelectionBar(reader: ReaderViewModel) {
    val tools = reader.reading
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !reader.busy, onClick = {
            val text = tools.selectedText()
            if (text.isNotEmpty()) {
                val notice = try {
                    require(text.length <= 200_000) { "Выделите меньший фрагмент: слишком много текста для буфера обмена" }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Текст PDF", text))
                    "Текст скопирован"
                } catch (_: Exception) { "Не удалось скопировать. Попробуйте выделить меньший фрагмент." }
                android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("Копировать") }
        TextButton(enabled = !reader.busy, onClick = { tools.selection?.let { tools.selectPage(it.page) } }) { Text("Вся страница") }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = tools::clearSelection, modifier = Modifier.semantics { contentDescription = "Снять выделение" }) { ReaderIcon(ReaderGlyph.Close) }
    }
}

@Composable
fun ArtworkBar(reader: ReaderViewModel, onList: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Выберите изображение", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !reader.busy, onClick = onList) { Text("Объекты") }
        TextButton(enabled = !reader.busy, onClick = { reader.reading.changeArtworkMode(false) }) { Text("Готово") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkList(reader: ReaderViewModel, onDismiss: () -> Unit) {
    val page = reader.reading.artwork[reader.page]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Объекты – страница ${reader.page + 1}", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        if (page == null) Text(reader.reading.pageErrors[reader.page] ?: "Загрузка объектов…", Modifier.padding(24.dp))
        else if (page.blocks.isEmpty()) Text("Нет доступных изображений или групп. Отдельные векторные контуры пока не поддерживаются.", Modifier.padding(24.dp))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
            itemsIndexed(page.blocks) { i, block ->
                ListItem(headlineContent = { Text(if (block.grouped) "Группа рисунка ${i + 1}" else "Изображение ${i + 1}") },
                    supportingContent = { Text(if (block.grouped) "Может содержать подписи и вложенные объекты" else "Удалить это размещение") },
                    modifier = Modifier.clickable(enabled = !reader.busy) { reader.reading.chooseArtwork(reader.page, block); onDismiss() })
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DeleteArtworkDialog(reader: ReaderViewModel) {
    val selected = reader.reading.selectedArtwork ?: return
    AlertDialog(onDismissRequest = reader.reading::cancelArtwork,
        title = { Text(if (selected.block.grouped) "Удалить группу рисунка?" else "Удалить изображение?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Страница ${selected.page + 1}. " + if (selected.block.grouped)
            "Группа удалится целиком, включая находящиеся внутри неё подписи и вложенные изображения. Можно отменить стрелкой отмены."
            else "Удалится только выбранное размещение. Можно отменить стрелкой отмены.")
            reader.reading.artworkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = reader::deleteArtwork, enabled = !reader.busy) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = reader.reading::cancelArtwork, enabled = !reader.busy) { Text("Отмена") } })
}
