package org.pdfcraft.android

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pdfcraft.android.engine.DocumentInfo
import org.pdfcraft.android.engine.MobileDocument
import org.pdfcraft.android.engine.openDocument
import org.pdfcraft.android.engine.createDocument
import org.pdfcraft.android.engine.EditableTextBlock
import org.pdfcraft.android.engine.TextPage
import org.pdfcraft.android.engine.TextFont
import org.pdfcraft.android.engine.MobileException

data class ImportChoice(val title: String, val isImage: Boolean, val snapshot: PageSnapshot, val at: Int?)

data class TextDraft(val page: Int, val revision: ULong, val block: EditableTextBlock,
    val value: String = block.text, val font: TextFont = TextFont.ORIGINAL, val size: String = "") {
    val changed: Boolean get() = value != block.text || font != TextFont.ORIGINAL || size.isNotBlank()
}
data class DraftPreview(val token: ULong, val bitmap: ImageBitmap, val overflow: Boolean,
    val outsidePage: Boolean, val font: String?, val warnings: List<String>)

fun EditableTextBlock.displayRect() = DisplayRect(bounds.left, bounds.top, bounds.right, bounds.bottom)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var document: MobileDocument? = null
    private var inputFile: File? = null
    private var importFile: File? = null
    var info: DocumentInfo? by mutableStateOf(null)
        private set
    var pageSizes: List<PageDimensions> by mutableStateOf(emptyList())
        private set
    var navigationSerial by mutableStateOf(0)
        private set
    var navigationRect: DisplayRect? by mutableStateOf(null)
        private set
    var navigationPage by mutableStateOf(0)
        private set
    var rasterVersion by mutableStateOf(0)
        private set
    var renderEpoch by mutableStateOf(0L)
        private set
    private val rasters = RasterCache<RasterKey, ImageBitmap>(RASTER_CACHE_BYTES)
    private var wanted = emptyList<RasterKey>()
    private val failedPages = mutableSetOf<Int>()
    private var renderJob: Job? = null
    var title by mutableStateOf("PdfCraft")
        private set
    var documentKey by mutableStateOf(0)
        private set
    var page by mutableStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var message: String? by mutableStateOf(null)
        private set
    var importChoice: ImportChoice? by mutableStateOf(null)
        private set
    var textEditing by mutableStateOf(false)
        private set
    var textPages: Map<Int, TextPage> by mutableStateOf(emptyMap())
        private set
    var textDraft: TextDraft? by mutableStateOf(null)
        private set
    var textPreview: DraftPreview? by mutableStateOf(null)
        private set
    var textError: String? by mutableStateOf(null)
        private set
    private var textJob: Job? = null
    private var wantedTextPages = emptyList<Int>()
    private val failedTextPages = mutableSetOf<Int>()
    var passwordRequest: Uri? by mutableStateOf(null)
        private set
    var passwordError: String? by mutableStateOf(null)
        private set

    var addingText by mutableStateOf(false)
        private set
    var noteDraft: TextNoteDraft? by mutableStateOf(null)
        private set
    var noteEditorOpen by mutableStateOf(false)
        private set
    var notePreview: DraftPreview? by mutableStateOf(null)
        private set
    var noteError: String? by mutableStateOf(null)
        private set
    var discardNote by mutableStateOf(false)
        private set
    private var lastNoteSize = "16"
    private var lastNoteColor = org.pdfcraft.android.engine.NoteColor.BLACK

    fun startAddingText() {
        if (busy || info?.canEditText != true) return
        changeTextMode(false); reading.closeSearch(); reading.clearSelection(); reading.changeArtworkMode(false)
        addingText = true; noteDraft = null; notePreview = null; noteError = null; discardNote = false
    }
    fun placeNote(point: PagePoint) {
        if (busy || !addingText) return
        val snapshot = pageSnapshot() ?: return
        val dimensions = pageSizes.getOrNull(point.page) ?: return
        if (dimensions.width < 4 || dimensions.height < 4) { message = "Страница слишком мала для текстового поля"; return }
        noteDraft = placeTextNote(snapshot, point, dimensions, noteDraft).let {
            if (noteDraft == null) it.copy(size = lastNoteSize, color = lastNoteColor) else it
        }
        notePreview = null; noteError = null; noteEditorOpen = true
    }
    fun updateNoteDraft(draft: TextNoteDraft) {
        if (busy) return
        if (draft.value.length > 8192 || draft.value.toByteArray(Charsets.UTF_8).size > 8192) {
            noteError = "Максимум 8 КиБ текста. Сократите текст или добавьте несколько полей."
            return
        }
        if (draft != noteDraft) { noteDraft = draft; notePreview = null; noteError = null }
    }
    fun chooseNotePosition() { if (!busy) noteEditorOpen = false }
    fun openNoteEditor() { if (!busy && noteDraft != null) noteEditorOpen = true }
    fun leaveNoteMode(force: Boolean = false) {
        if (busy) return
        if (!force && noteDraft?.value?.isNotBlank() == true) { discardNote = true; return }
        addingText = false; noteDraft = null; notePreview = null; noteError = null; noteEditorOpen = false; discardNote = false
    }
    fun keepNoteDraft() { discardNote = false }
    fun previewNote() = runOperation {
        val draft = requireNotNull(noteDraft)
        draft.snapshot.requireCurrent(pageSnapshot())
        noteError = null; notePreview = null
        val input = textNoteInput(draft, requireNotNull(pageSizes.getOrNull(draft.page)))
        val result = withContext(worker) {
            val preview = requireNotNull(document).previewTextNote(input)
            val image = preview.image
            val bitmap = Bitmap.createBitmap(image.width.toInt(), image.height.toInt(), Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(image.rgba))
            }
            DraftPreview(preview.token, bitmap.asImageBitmap(), false, false, "Inter Regular", image.warnings)
        }
        if (noteDraft == draft) notePreview = result
    }
    fun applyNote() = runOperation {
        val draft = requireNotNull(noteDraft)
        val preview = requireNotNull(notePreview)
        draft.snapshot.requireCurrent(pageSnapshot())
        withContext(worker) { requireNotNull(document).applyTextPreview(preview.token) }
        lastNoteSize = draft.size; lastNoteColor = draft.color
        noteDraft = null; notePreview = null; noteError = null; noteEditorOpen = false
        refresh(navigate = false)
        message = "Текст добавлен. Можно отменить или сохранить копию."
    }

    var organizerOpen by mutableStateOf(false)
        private set
    var selectedPages: Set<Int> by mutableStateOf(emptySet())
        private set
    var organizerScrollPage by mutableStateOf(0)
        private set
    var organizerScrollSerial by mutableStateOf(0)
        private set
    private var newDocument by mutableStateOf(false)
    val hasUnsavedChanges: Boolean get() = newDocument || info?.dirty == true || noteDraft?.value?.isNotBlank() == true
    private var importTarget: Pair<PageSnapshot, Int?>? = null
    val thumbnails = PageThumbnails(viewModelScope, worker, { document }, ::pageSnapshot,
        { organizerOpen && !busy }, { pageSizes.getOrNull(it) })
    val pageExporter = PageExporter(application, worker)
    val wordExporter = WordExporter(application, worker)
    fun startWordExport(request: WordExportRequest) = runOperation {
        wordExporter.prepare(document, request, pageSnapshot())
    }
    fun finishWordExport(uri: Uri?) {
        if (uri != null) runOperation { wordExporter.save(uri) }
    }

    fun pageSnapshot(): PageSnapshot? = info?.let { PageSnapshot(documentKey, it.revision, it.pageCount.toInt()) }
    fun selectedSnapshot(): PageSelection? = pageSnapshot()?.let { snap ->
        selectedPages.takeIf { it.isNotEmpty() }?.let { PageSelection(snap, it.sorted()) }
    }
    fun showOrganizer() {
        if (busy || info == null) return
        changeTextMode(false); reading.closeSearch(); reading.clearSelection(); reading.changeArtworkMode(false)
        reading.pauseWorkers(); renderEpoch++; wanted = emptyList(); wantedTextPages = emptyList()
        organizerOpen = true; selectedPages = setOf(page); scrollOrganizer(page)
    }
    fun closeOrganizer(openPage: Int? = null) {
        if (busy) return
        organizerOpen = false; thumbnails.pause(); renderEpoch++; reading.resume()
        openPage?.let(::jumpTo)
    }
    fun selectPages(pages: Set<Int>) {
        if (busy) return
        if (pages.size > 500) { message = "За один раз можно выбрать до 500 страниц"; return }
        selectedPages = pages.filter { it in pageSizes.indices }.toSet()
    }
    fun togglePage(index: Int) = selectPages(if (index in selectedPages) selectedPages - index else selectedPages + index)
    private fun scrollOrganizer(index: Int) { organizerScrollPage = index; organizerScrollSerial++ }

    fun movePages(selection: PageSelection, to: Int) = runOperation {
        selection.snapshot.requireCurrent(pageSnapshot())
        withContext(worker) { requireNotNull(document).movePages(selection.pages.map(Int::toUInt), to.toUInt(), selection.snapshot.revision) }
        page = to; refresh()
        selectedPages = (to until to + selection.pages.size).toSet(); scrollOrganizer(to)
    }
    fun removePages(selection: PageSelection) = runOperation {
        selection.snapshot.requireCurrent(pageSnapshot())
        withContext(worker) { requireNotNull(document).removePages(selection.pages.map(Int::toUInt), selection.snapshot.revision) }
        page = selection.pages.minOrNull() ?: 0; refresh()
        selectedPages = setOf(page); scrollOrganizer(page)
        message = "Удалено страниц: ${selection.pages.size}. Можно отменить."
    }
    fun insertBlank(at: Int, landscape: Boolean, snapshot: PageSnapshot) = runOperation {
        snapshot.requireCurrent(pageSnapshot())
        withContext(worker) { requireNotNull(document).insertBlankPage(at.toUInt(), landscape, snapshot.revision) }
        page = at; refresh(); selectedPages = setOf(at); scrollOrganizer(at)
    }
    fun prepareImport(at: Int? = null) { importTarget = pageSnapshot()?.let { it to at } }

    fun createNew(name: String, landscape: Boolean) = runOperation {
        val filename = name.trim().ifBlank { "Новый документ" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        withContext(worker) {
            val next = createDocument(filename, landscape)
            document?.release(); document?.destroy(); document = next
            inputFile?.delete(); inputFile = null; importFile?.delete(); importFile = null
        }
        addingText = false; noteDraft = null; notePreview = null; noteEditorOpen = false; discardNote = false
        title = filename; documentKey++; newDocument = true; organizerOpen = false
        reading.invalidate(newDocument = true); importChoice = null; importTarget = null
        textEditing = false; textDraft = null; textPreview = null; passwordRequest = null; passwordError = null
        pageExporter.pending = null; page = 0; refresh()
    }
    fun preparePageExport(request: PageExportRequest) { pageExporter.pending = request }
    fun finishPageExport(uri: Uri?) {
        val request = pageExporter.pending ?: return
        pageExporter.pending = null
        if (uri == null) return
        runOperation {
            request.snapshot.requireCurrent(pageSnapshot())
            val doc = requireNotNull(document)
            if (request.format == PageExportFormat.PDF) {
                pageExporter.savePdf(doc, request, uri); message = "Выбранные страницы сохранены в PDF"
            } else pageExporter.saveImages(doc, request, uri)
        }
    }

    val reading = ReaderTools(viewModelScope, worker, NativeReadingSource { document }, { info }, { busy }, { message = it }) { index, rect ->
        navigationPage = index; navigationRect = rect; navigationSerial++
    }

    fun deleteArtwork() = runOperation {
        val target = requireNotNull(reading.selectedArtwork)
        withContext(worker) { requireNotNull(document).deleteArtwork(target.page.toUInt(), target.revision, target.block.kind, target.block.index) }
        refresh(navigate = false)
        message = "Объект удалён. Действие можно отменить."
    }

    private fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        reading.pauseWorkers()
        thumbnails.pause()
        // One in-flight native call may finish; its old epoch cannot publish pixels.
        renderEpoch++
        wanted = emptyList()
        message = null
        viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: "Не удалось выполнить операцию"
                if (noteDraft != null && noteEditorOpen) noteError = detail
                else if (textDraft != null) textError = detail
                else if (reading.selectedArtwork != null) reading.reportArtworkError(detail)
                else message = detail
            } catch (error: LinkageError) {
                val detail = "Не удалось загрузить PDF-движок: ${error.message}"
                if (noteDraft != null && noteEditorOpen) noteError = detail
                else if (textDraft != null) textError = detail
                else if (reading.selectedArtwork != null) reading.reportArtworkError(detail)
                else message = detail
            } catch (_: OutOfMemoryError) {
                val detail = "Недостаточно памяти для операции. Закройте редактор и попробуйте меньший текстовый блок."
                if (noteDraft != null && noteEditorOpen) noteError = detail
                else if (textDraft != null) textError = detail
                else if (reading.selectedArtwork != null) reading.reportArtworkError(detail)
                else message = detail
            } finally {
                busy = false
                if (!organizerOpen) reading.resume()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(200) else null
        } ?: "Документ.pdf"
    }

    private fun stage(uri: Uri): File {
        val context = getApplication<Application>()
        val file = File.createTempFile("pdfcraft-", ".input", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64L * 1024 * 1024) { "Поддерживаются файлы до 64 МиБ" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun open(uri: Uri, password: String? = null) = runOperation {
        val name = try { withContext(worker) {
            val name = displayName(uri)
            val staged = stage(uri)
            try {
                val next = openDocument(staged.absolutePath, password)
                document?.release()
                document?.destroy()
                inputFile?.delete()
                importFile?.delete()
                importFile = null
                document = next
                inputFile = staged
            } catch (error: Throwable) {
                staged.delete()
                throw error
            }
            name
        } } catch (_: MobileException.PasswordRequired) {
            passwordRequest = uri; passwordError = null
            return@runOperation
        } catch (_: MobileException.WrongPassword) {
            passwordRequest = uri; passwordError = "Неверный пароль. Попробуйте ещё раз."
            return@runOperation
        } catch (error: Exception) {
            if (passwordRequest != null) passwordError = error.message
            throw error
        }
        passwordRequest = null; passwordError = null
        title = name
        addingText = false; noteDraft = null; notePreview = null; noteEditorOpen = false; discardNote = false
        newDocument = false; organizerOpen = false; importTarget = null; pageExporter.pending = null
        documentKey++
        reading.invalidate(newDocument = true)
        importChoice = null
        textEditing = false
        textDraft = null
        textPreview = null
        page = 0
        refresh()
    }

    fun cancelPassword() {
        if (!busy) { passwordRequest = null; passwordError = null }
    }

    private suspend fun refresh(navigate: Boolean = true) {
        // Metadata/edits do not wait for a full page raster. Drawing has its own bounded queue.
        info = withContext(worker) { requireNotNull(document).info() }
        pageSizes = info?.pages.orEmpty().map {
            if (it.width.isFinite() && it.height.isFinite() && it.width >= 0.5f && it.height >= 0.5f)
                PageDimensions(it.width, it.height) else PageDimensions(600f, 800f)
        }
        page = page.coerceIn(0, ((info?.pageCount?.toInt() ?: 0) - 1).coerceAtLeast(0))
        rasters.clear()
        thumbnails.clear(); selectedPages = emptySet()
        failedPages.clear()
        rasterVersion++
        renderEpoch++
        textPages = emptyMap()
        failedTextPages.clear()
        reading.invalidate()
        if (navigate) {
            navigationPage = page
            navigationRect = null
            navigationSerial++
        }
    }

    fun changeTextMode(enabled: Boolean) {
        if (busy || textDraft != null) return
        if (enabled) { reading.closeSearch(); reading.changeArtworkMode(false) }
        textEditing = enabled && info?.canEditText == true
        if (!textEditing) { textPages = emptyMap(); wantedTextPages = emptyList() }
        failedTextPages.clear()
    }

    fun requestTextPages(pages: List<Int>) {
        if (!textEditing || busy || organizerOpen) return
        wantedTextPages = pages.distinct().take(4)
        // Retain only the demanded pages, bounded independently of the bitmap cache.
        textPages = textPages.filterKeys { it in wantedTextPages }
        if (textJob?.isActive == true) return
        textJob = viewModelScope.launch {
            try {
                while (textEditing && !busy) {
                    val index = wantedTextPages.firstOrNull { it !in textPages && it !in failedTextPages } ?: break
                    val epoch = renderEpoch
                    val revision = info?.revision ?: break
                    try {
                        val result = withContext(worker) { requireNotNull(document).textBlocks(index.toUInt(), revision) }
                        if (textEditing && epoch == renderEpoch && index in wantedTextPages) textPages = textPages + (index to result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (epoch == renderEpoch) { failedTextPages.add(index); message = error.message ?: "Не удалось прочитать текст страницы" }
                    } catch (error: LinkageError) {
                        failedTextPages.add(index); message = "Не удалось загрузить PDF-движок: ${error.message}"
                    } catch (_: OutOfMemoryError) {
                        failedTextPages.add(index); message = "Недостаточно памяти для текста страницы ${index + 1}"
                    }
                }
            } finally { textJob = null }
        }
    }

    fun selectText(point: PagePoint, tolerance: Float) {
        if (!textEditing || busy || organizerOpen) return
        val result = textPages[point.page]
        if (result == null) { message = "Текст страницы ещё загружается"; return }
        val hit = hitTextBlock(result.blocks.map { it.displayRect() }, point.x, point.y, tolerance)
        if (hit == null) {
            message = if (result.blocks.isEmpty()) "Нет доступного текста: сканы и текст в кривых пока не редактируются" else "Коснитесь текста внутри синей рамки"
            return
        }
        editTextBlock(point.page, result.blocks[hit])
    }

    fun editTextBlock(page: Int, block: EditableTextBlock) {
        if (busy) return
        val revision = textPages[page]?.revision ?: return
        textDraft = TextDraft(page, revision, block)
        textPreview = null
        textError = null
    }

    fun updateTextDraft(value: TextDraft) {
        if (busy) return
        if (value != textDraft) { textDraft = value; textPreview = null; textError = null }
    }

    fun closeTextDraft() {
        if (busy) return
        textDraft = null; textPreview = null; textError = null
    }

    fun previewText() = runOperation {
        val draft = requireNotNull(textDraft)
        textError = null
        textPreview = null
        val size = textSizeOverride(draft.size)
        val result = withContext(worker) {
            val preview = requireNotNull(document).previewTextEdit(draft.page.toUInt(), draft.block.index,
                draft.revision, draft.value, draft.font, size)
            val image = preview.image
            val bitmap = Bitmap.createBitmap(image.width.toInt(), image.height.toInt(), Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(image.rgba))
            }
            DraftPreview(preview.token, bitmap.asImageBitmap(), preview.overflow, preview.outsidePage,
                preview.substitutedFont, image.warnings)
        }
        if (textDraft == draft) textPreview = result
    }

    fun applyText() = runOperation {
        val preview = requireNotNull(textPreview)
        withContext(worker) { requireNotNull(document).applyTextPreview(preview.token) }
        textDraft = null; textPreview = null; textError = null
        refresh(navigate = false)
        message = "Текст изменён. Можно отменить или сохранить копию."
    }

    fun goTo(offset: Int) = jumpTo(page + offset)

    fun jumpTo(index: Int) {
        if (busy) return
        val count = info?.pageCount?.toInt() ?: 0
        if (count > 0) {
            page = index.coerceIn(0, count - 1)
            navigationPage = page
            navigationRect = null
            navigationSerial++
        }
    }

    fun visiblePage(index: Int) {
        if (!busy && index in pageSizes.indices) page = index
    }

    fun raster(key: RasterKey): ImageBitmap? = rasters[key]

    fun requestRasters(keys: List<RasterKey>) {
        if (busy || organizerOpen) return
        wanted = keys
        if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            try {
                while (!busy) {
                    val key = wanted.firstOrNull { !rasters.contains(it) && it.page !in failedPages } ?: break
                    val epoch = renderEpoch
                    val revision = info?.revision ?: break
                    try {
                        val pixels = withContext(worker) {
                            val doc = requireNotNull(document)
                            val image = if (key.preview) doc.renderPage(key.page.toUInt(), 384u)
                            else doc.renderTile(key.page.toUInt(), key.scale, key.x.toUInt(), key.y.toUInt(),
                                key.width.toUInt(), key.height.toUInt(), revision)
                            check(image.revision == revision) { "Документ изменился во время рендера" }
                            Bitmap.createBitmap(image.width.toInt(), image.height.toInt(), Bitmap.Config.ARGB_8888).apply {
                                copyPixelsFromBuffer(ByteBuffer.wrap(image.rgba))
                            }
                        }
                        if (epoch == renderEpoch && key in wanted) {
                            rasters.put(key, pixels.asImageBitmap(), pixels.allocationByteCount.toLong())
                            rasterVersion++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (epoch == renderEpoch) {
                            failedPages.add(key.page)
                            message = "Страница ${key.page + 1}: ${error.message ?: "ошибка рендера"}"
                        }
                    } catch (error: LinkageError) {
                        failedPages.add(key.page)
                        message = "Не удалось загрузить PDF-движок: ${error.message}"
                    } catch (_: OutOfMemoryError) {
                        rasters.clear()
                        failedPages.add(key.page)
                        rasterVersion++
                        message = "Недостаточно памяти для страницы ${key.page + 1}"
                    }
                }
            } finally {
                renderJob = null
            }
        }
    }

    fun rotate() = runOperation {
        withContext(worker) { requireNotNull(document).rotatePage(page.toUInt()) }
        refresh()
    }

    fun delete(selection: List<Int>) = runOperation {
        val nextPage = page - selection.count { it < page }
        withContext(worker) { requireNotNull(document).deletePages(selection.map(Int::toUInt)) }
        page = nextPage.coerceAtLeast(0)
        refresh()
        message = "Удалено страниц: ${selection.size}. Действие можно отменить."
    }

    fun chooseImport(uri: Uri) = runOperation {
        val target = requireNotNull(importTarget) { "Повторите добавление файла" }
        target.first.requireCurrent(pageSnapshot())
        val choice = withContext(worker) {
            val name = displayName(uri)
            val staged = stage(uri)
            try {
                val header = ByteArray(8)
                val count = staged.inputStream().use { it.read(header) }
                val png = count >= 8 && header.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                val jpg = count >= 3 && header[0] == (-1).toByte() && header[1] == (-40).toByte() && header[2] == (-1).toByte()
                importFile?.delete()
                importFile = staged
                ImportChoice(name, png || jpg, target.first, target.second)
            } catch (error: Throwable) {
                staged.delete()
                throw error
            }
        }
        importChoice = choice
    }

    fun cancelImport() = runOperation {
        withContext(worker) { importFile?.delete(); importFile = null }
        importChoice = null; importTarget = null
    }

    fun insert(at: Int, sourcePages: List<Int>?) = runOperation {
        val choice = requireNotNull(importChoice)
        choice.snapshot.requireCurrent(pageSnapshot())
        val name = choice.title
        val count = withContext(worker) {
            val source = requireNotNull(importFile)
            val inserted = requireNotNull(document).insertFile(source.absolutePath, name, at.toUInt(), sourcePages?.map(Int::toUInt))
            source.delete()
            importFile = null
            inserted
        }
        importChoice = null
        page = at
        refresh()
        selectedPages = (at until at + count.toInt()).toSet(); scrollOrganizer(at)
        importTarget = null
        message = "Добавлено страниц: $count"
    }

    fun undo() = runOperation {
        withContext(worker) { requireNotNull(document).undo() }
        refresh()
    }

    fun redo() = runOperation {
        withContext(worker) { requireNotNull(document).redo() }
        refresh()
    }

    fun dismissMessage() { message = null }

    /** Drop the open PDF and return to the start screen. Modes are left before this is called. */
    fun closeDocument() = runOperation {
        wordExporter.close()
        pageExporter.abandon()
        withContext(worker) {
            document?.release()
            document?.destroy()
            document = null
            inputFile?.delete()
            inputFile = null
            importFile?.delete()
            importFile = null
        }
        addingText = false
        noteDraft = null
        notePreview = null
        noteError = null
        noteEditorOpen = false
        discardNote = false
        title = "PdfCraft"
        newDocument = false
        organizerOpen = false
        importChoice = null
        importTarget = null
        documentKey++
        reading.invalidate(newDocument = true)
        textEditing = false
        textDraft = null
        textPreview = null
        textError = null
        passwordRequest = null
        passwordError = null
        page = 0
        info = null
        pageSizes = emptyList()
        rasters.clear()
        thumbnails.clear()
        selectedPages = emptySet()
        failedPages.clear()
        rasterVersion++
        textPages = emptyMap()
        failedTextPages.clear()
        wantedTextPages = emptyList()
    }

    fun export(uri: Uri) = runOperation {
        withContext(worker) {
            val context = getApplication<Application>()
            val staged = File(context.cacheDir, "export-${UUID.randomUUID()}.pdf")
            try {
                requireNotNull(document).exportCopy(staged.absolutePath)
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Нет доступа к выбранному месту сохранения" }
                    staged.inputStream().use { it.copyTo(output) }
                }
            } finally {
                staged.delete()
            }
        }
        newDocument = false
        message = "Копия сохранена"
    }

    override fun onCleared() {
        wordExporter.close()
        pageExporter.stop()
        worker.executor.execute {
            try {
                document?.release()
                document?.destroy()
            } finally {
                inputFile?.delete()
                importFile?.delete()
            }
        }
        worker.close()
    }
}
