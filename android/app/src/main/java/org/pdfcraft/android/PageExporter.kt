package org.pdfcraft.android

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.pdfcraft.android.engine.MobileDocument
import org.pdfcraft.android.engine.PageImageFormat

data class PageExportProgress(val total: Int, val completed: Int = 0, val page: Int? = null,
    val folder: String? = null, val finished: Boolean = false, val stopped: Boolean = false,
    val reduced: Int = 0, val error: String? = null)

class PageExporter(private val app: Application, private val worker: CoroutineDispatcher) {
    var pending: PageExportRequest? = null
    var progress: PageExportProgress? by mutableStateOf(null)
        private set
    private val stopped = AtomicBoolean(false)
    fun stop() { stopped.set(true) }
    fun abandon() { stop(); pending = null; progress = null }
    fun dismiss() { if (progress?.finished == true) progress = null }
    private fun checkStop() { if (stopped.get()) throw ExportStopped() }

    suspend fun savePdf(doc: MobileDocument, request: PageExportRequest, uri: Uri) = withContext(worker) {
        val staged = File(app.cacheDir, "extract-${UUID.randomUUID()}.pdf")
        try {
            doc.extractPages(requireNotNull(request.pages).map(Int::toUInt), request.snapshot.revision, staged.absolutePath)
            app.contentResolver.openOutputStream(uri, "wt").use { output ->
                requireNotNull(output) { "Нет доступа к файлу" }
                staged.inputStream().use { it.copyTo(output) }
            }
        } catch (error: Throwable) {
            // This is the new file returned by CreateDocument, never the source document.
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            throw error
        } finally { staged.delete() }
    }

    suspend fun saveImages(doc: MobileDocument, request: PageExportRequest, tree: Uri) {
        stopped.set(false)
        progress = PageExportProgress(request.total)
        var folderName: String? = null
        try {
            val result = withContext(worker) {
                val resolver = app.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val base = exportBaseName(request.title)
                folderName = "$base-${UUID.randomUUID().toString().take(8)}"
                checkStop()
                val folder = requireNotNull(DocumentsContract.createDocument(resolver, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, folderName)) { "Не удалось создать папку" }
                // withContext(Main) is only used to publish state; image work remains serial on worker.
                runImageBatch(request, stopped::get, { completed, page ->
                    // The callback is synchronous. A main handler keeps updates ordered without blocking.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (progress?.finished == false) progress = PageExportProgress(request.total, completed, page, folderName)
                    }
                }) { page ->
                    val staged = File(app.cacheDir, "page-${UUID.randomUUID()}.${request.format.extension}")
                    var destination: Uri? = null
                    try {
                        val image = doc.exportPageImage(page.toUInt(), request.snapshot.revision, staged.absolutePath,
                            if (request.format == PageExportFormat.PNG) PageImageFormat.PNG else PageImageFormat.JPEG, request.dpi.toUInt())
                        checkStop()
                        destination = requireNotNull(DocumentsContract.createDocument(resolver, folder, request.format.mime,
                            pageImageName(base, page, request.snapshot.count, request.format))) { "Не удалось создать изображение" }
                        resolver.openOutputStream(destination, "wt").use { output ->
                            requireNotNull(output) { "Нет доступа к изображению" }
                            staged.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkStop()
                                    val length = input.read(buffer)
                                    if (length < 0) break
                                    output.write(buffer, 0, length)
                                }
                            }
                        }
                        image.reduced
                    } catch (error: Throwable) {
                        destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        throw error
                    } finally { staged.delete() }
                }
            }
            progress = PageExportProgress(request.total, result.completed, folder = folderName, finished = true,
                stopped = result.stopped, reduced = result.reduced, error = result.error)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ExportStopped) { progress = PageExportProgress(request.total, finished = true, stopped = true) }
        catch (error: Exception) { progress = PageExportProgress(request.total, folder = folderName, finished = true, error = error.message ?: "Ошибка экспорта") }
        catch (_: OutOfMemoryError) { progress = PageExportProgress(request.total, folder = folderName, finished = true, error = "Недостаточно памяти") }
        catch (error: LinkageError) { progress = PageExportProgress(request.total, folder = folderName, finished = true, error = "Ошибка движка: ${error.message}") }
    }
}
