package org.pdfcraft.android

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val reader = ViewModelProvider(this)[ReaderViewModel::class.java]
        setContent {
            val inter = remember { FontFamily(Typeface.createFromAsset(assets, "fonts/Inter-Regular.ttf")) }
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFF145FC8), surface = Color.White, background = Color(0xFFF5F5F5)),
                typography = interTypography(inter),
            ) { ReaderScreen(reader) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReaderScreen(reader: ReaderViewModel) {
        var chrome by rememberSaveable(reader.documentKey) { mutableStateOf(true) }
        var menu by remember { mutableStateOf(false) }
        var confirmClose by remember { mutableStateOf<String?>(null) }
        var newDocument by remember { mutableStateOf(false) }
        var pageExport by remember { mutableStateOf<Pair<PageSnapshot, List<Int>?>?>(null) }
        var wordExport by remember { mutableStateOf<PageSnapshot?>(null) }
        var textTools by remember { mutableStateOf(false) }
        var textList by remember { mutableStateOf(false) }
        var artworkList by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) reader.open(uri)
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) reader.chooseImport(uri) else reader.cancelImport()
        }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) reader.export(uri)
        }
        val extractedPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), reader::finishPageExport)
        val wordFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WORD_MIME), reader::finishWordExport)
        val imageFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), reader::finishPageExport)
        val new = { if (reader.hasUnsavedChanges) confirmClose = "new" else newDocument = true }
        val open = {
            if (reader.hasUnsavedChanges) confirmClose = "open" else picker.launch(arrayOf("application/pdf"))
        }
        val save = { exporter.launch(reader.title.substringBeforeLast('.', reader.title) + "-copy.pdf") }
        val insert = { reader.prepareImport(); importer.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) }
        val close = {
            if (reader.organizerOpen) reader.closeOrganizer()
            else if (reader.addingText) reader.leaveNoteMode()
            else if (reader.reading.selectedArtwork != null) reader.reading.cancelArtwork()
            else if (reader.reading.selection != null) reader.reading.clearSelection()
            else if (reader.reading.searchOpen) reader.reading.closeSearch()
            else if (reader.reading.artworkMode) reader.reading.changeArtworkMode(false)
            else if (reader.textEditing) reader.changeTextMode(false)
            else if (!chrome) chrome = true
            else if (reader.info != null && reader.hasUnsavedChanges) confirmClose = "close"
            else if (reader.info != null) reader.closeDocument()
            else finish()
        }
        DisposableEffect(chrome) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = true
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
        LaunchedEffect(reader.message) {
            if (!reader.organizerOpen && reader.importChoice == null) reader.message?.let { snackbar.showSnackbar(it, actionLabel = "OK"); reader.dismissMessage() }
        }
        BackHandler(enabled = reader.textDraft == null && !reader.noteEditorOpen) {
            if (!reader.busy) close()
        }
        val count = reader.info?.pageCount?.toInt() ?: 0
        val ready = !reader.busy && count > 0 && !reader.addingText
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(if (chrome) Modifier.fillMaxSize().safeDrawingPadding().imePadding() else Modifier.fillMaxSize()) {
                if (chrome) {
                    Surface(shadowElevation = 1.dp) {
                        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphButton(ReaderGlyph.Back, "Назад", !reader.busy, close)
                            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text(reader.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(if (reader.hasUnsavedChanges) "Есть изменения" else "PdfCraft", style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
                            }
                            GlyphButton(ReaderGlyph.Search, "Поиск текста", ready) {
                                reader.changeTextMode(false); reader.reading.openSearch(); chrome = true
                            }
                            GlyphButton(ReaderGlyph.Save, "Сохранить копию", ready, save)
                            Box {
                                GlyphButton(ReaderGlyph.More, "Ещё", !reader.busy) { menu = true }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Новый PDF") }, leadingIcon = { ReaderIcon(ReaderGlyph.Document) }, onClick = { menu = false; new() })
                                    DropdownMenuItem(text = { Text("Открыть PDF") }, leadingIcon = { ReaderIcon(ReaderGlyph.Open) }, onClick = { menu = false; open() })
                                    DropdownMenuItem(text = { Text("Сохранить копию") }, leadingIcon = { ReaderIcon(ReaderGlyph.Save) }, enabled = ready, onClick = { menu = false; save() })
                                    DropdownMenuItem(text = { Text("Экспорт в Word (.docx)") }, leadingIcon = { ReaderIcon(ReaderGlyph.Document) }, enabled = ready, onClick = {
                                        menu = false; wordExport = reader.pageSnapshot()
                                    })
                                    DropdownMenuItem(text = { Text("Все страницы в JPG / PNG") }, leadingIcon = { ReaderIcon(ReaderGlyph.Image) }, enabled = ready, onClick = {
                                        menu = false; reader.pageSnapshot()?.let { pageExport = it to null }
                                    })
                                    DropdownMenuItem(text = { Text("Добавить текст") }, leadingIcon = { ReaderIcon(ReaderGlyph.EditText) }, enabled = ready && reader.info?.canEditText == true, onClick = {
                                        menu = false; reader.startAddingText(); chrome = true
                                    })
                                    DropdownMenuItem(text = { Text("Добавить страницы") }, leadingIcon = { ReaderIcon(ReaderGlyph.Add) }, enabled = ready, onClick = { menu = false; insert() })
                                    DropdownMenuItem(text = { Text("Выделить текст страницы") }, enabled = ready, onClick = {
                                        menu = false; reader.changeTextMode(false); reader.reading.changeArtworkMode(false); reader.reading.selectPage(reader.page)
                                    })
                                    DropdownMenuItem(text = { Text("Скрыть панели") }, enabled = count > 0, onClick = { menu = false; chrome = false })
                                }
                            }
                        }
                    }
                }
                if (chrome && reader.addingText) AddTextBar(reader)
                if (chrome && reader.textEditing) TextEditBar(reader) { textList = true }
                if (chrome && reader.reading.searchOpen) SearchBar(reader)
                if (chrome && reader.reading.selection != null) SelectionBar(reader)
                if (chrome && reader.reading.artworkMode) ArtworkBar(reader) { artworkList = true }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (reader.info != null) {
                        DocumentCanvas(reader, onTap = { chrome = !chrome }, onShowControls = { chrome = true })
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            ReaderIcon(ReaderGlyph.Document, Modifier.size(64.dp), MaterialTheme.colorScheme.primary)
                            Text("Ваши PDF – под рукой", style = MaterialTheme.typography.titleLarge)
                            Text("Открывайте документы и управляйте страницами", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = open, enabled = !reader.busy) { Text("Открыть PDF") }
                            OutlinedButton(onClick = new, enabled = !reader.busy) { Text("Создать новый PDF") }
                        }
                    }
                    if (reader.busy) CircularProgressIndicator(Modifier.size(36.dp))
                }
                if (chrome && reader.info != null && !reader.reading.searchOpen) {
                    Surface(shadowElevation = 4.dp) {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                GlyphButton(ReaderGlyph.Undo, "Отменить", ready && reader.info?.canUndo == true, reader::undo)
                                GlyphButton(ReaderGlyph.Previous, "Предыдущая страница", ready && reader.page > 0) { reader.goTo(-1) }
                                TextButton(onClick = { reader.showOrganizer() }, enabled = ready) { Text("${reader.page + 1} / $count") }
                                GlyphButton(ReaderGlyph.Next, "Следующая страница", ready && reader.page + 1 < count) { reader.goTo(1) }
                                GlyphButton(ReaderGlyph.Redo, "Повторить", ready && reader.info?.canRedo == true, reader::redo)
                            }
                            HorizontalDivider(color = Color(0xFFEAEAEA))
                            Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ToolButton(ReaderGlyph.EditText, "Текст", ready && reader.info?.canEditText == true) {
                                    chrome = true; textTools = true
                                }
                                ToolButton(ReaderGlyph.Pages, "Страницы", ready) { reader.showOrganizer() }
                                ToolButton(ReaderGlyph.Add, "Добавить", ready, insert)
                                ToolButton(ReaderGlyph.Rotate, "Повернуть", ready, reader::rotate)
                                ToolButton(ReaderGlyph.Image, "Объекты", ready && reader.info?.canEditText == true) {
                                    chrome = true; reader.changeTextMode(false); reader.reading.changeArtworkMode(!reader.reading.artworkMode)
                                }
                            }
                        }
                    }
                }
            }
            if (chrome) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (count > 0) 122.dp else 8.dp))
        }
        if (reader.organizerOpen) PageOrganizer(reader, onImport = { at ->
            reader.prepareImport(at); importer.launch(arrayOf("application/pdf", "image/png", "image/jpeg"))
        }, onExtract = { pageExport = it.snapshot to it.pages })
        if (newDocument) NewDocumentDialog(reader) { newDocument = false }
        pageExport?.let { (snapshot, pages) ->
            PageExportDialog(snapshot, pages, reader.title, reader.info?.encrypted == true, { pageExport = null }) { request ->
                reader.preparePageExport(request)
                if (request.format == PageExportFormat.PDF) extractedPdf.launch(exportBaseName(request.title) + "-pages.pdf")
                else imageFolder.launch(null)
            }
        }
        wordExport?.let { snapshot ->
            WordExportDialog(snapshot, reader.page, reader.title, reader.info?.encrypted == true, { wordExport = null }) { request ->
                wordExport = null; reader.startWordExport(request)
            }
        }
        if (reader.wordExporter.progress != null) WordExportProgressDialog(reader.wordExporter) { wordFile.launch(reader.wordExporter.name) }
        if (reader.pageExporter.progress != null) PageExportProgressDialog(reader.pageExporter)
        reader.importChoice?.let { choice -> ImportDialog(choice, reader, count) }
        if (textTools) AlertDialog(onDismissRequest = { textTools = false }, title = { Text("Текст в документе") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { textTools = false; reader.startAddingText() }, modifier = Modifier.fillMaxWidth()) { Text("Добавить свой текст") }
                OutlinedButton(onClick = { textTools = false; reader.changeTextMode(!reader.textEditing) }, modifier = Modifier.fillMaxWidth()) { Text("Редактировать существующий") }
            }
        }, confirmButton = { TextButton(onClick = { textTools = false }) { Text("Закрыть") } })
        if (reader.noteEditorOpen) TextNoteEditor(reader)
        if (reader.discardNote) DiscardTextNote(reader)
        if (textList) TextBlockList(reader) { textList = false }
        if (artworkList) ArtworkList(reader) { artworkList = false }
        if (reader.reading.selectedArtwork != null) DeleteArtworkDialog(reader)
        if (reader.textDraft != null) TextEditorDialog(reader)
        if (reader.passwordRequest != null) OpenPasswordDialog(reader)
        if (confirmClose != null) {
            AlertDialog(
                onDismissRequest = { confirmClose = null },
                title = { Text("Закрыть изменённый документ?") },
                text = { Text("Если вы ещё не сохранили копию, изменения будут потеряны.") },
                confirmButton = { TextButton(onClick = {
                    val action = confirmClose
                    confirmClose = null
                    when (action) { "open" -> picker.launch(arrayOf("application/pdf")); "new" -> newDocument = true; else -> reader.closeDocument() }
                }) { Text("Продолжить") } },
                dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Отмена") } },
            )
        }
    }
}

@Composable
private fun GlyphButton(glyph: ReaderGlyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) { ReaderIcon(glyph) }
}

@Composable
private fun RowScope.ToolButton(glyph: ReaderGlyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val color = if (enabled) Color(0xFF303030) else Color(0xFFB5B5B5)
        ReaderIcon(glyph, color = color)
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ImportDialog(choice: ImportChoice, reader: ReaderViewModel, count: Int) {
    var position by remember(choice) { mutableStateOf(1) }
    var range by remember(choice) { mutableStateOf("") }
    val parsed = remember(range) { runCatching { if (range.isBlank()) null else parsePageSelection(range, Int.MAX_VALUE) } }
    AlertDialog(
        onDismissRequest = { if (!reader.busy) reader.cancelImport() },
        title = { Text("Добавить страницы") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(choice.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (choice.at != null) Text("Место вставки: ${choice.at + 1}")
                else listOf("Перед текущей страницей", "После текущей страницы", "В конец документа").forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !reader.busy) { position = index }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = position == index, onClick = { position = index }, enabled = !reader.busy)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!choice.isImage) OutlinedTextField(range, onValueChange = { range = it }, label = { Text("Страницы из PDF") },
                    placeholder = { Text("Все страницы") }, singleLine = true, enabled = !reader.busy,
                    isError = parsed.isFailure, supportingText = { Text(parsed.exceptionOrNull()?.message ?: "Пусто – все; или 1, 3–5") })
                reader.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (reader.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !reader.busy && parsed.isSuccess, onClick = {
            val at = choice.at ?: when (position) { 0 -> reader.page; 1 -> reader.page + 1; else -> count }
            reader.insert(at, if (choice.isImage) null else parsed.getOrNull())
        }) { Text("Добавить") } },
        dismissButton = { TextButton(enabled = !reader.busy, onClick = reader::cancelImport) { Text("Отмена") } },
    )
}
