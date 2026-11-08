package org.pdfcraft.android

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.pdfcraft.android.engine.MobileDocument
import org.pdfcraft.android.engine.WordExportJob

data class WordExportProgress(val total: Int, val completed: Int = 0, val saving: Boolean = false,
    val finished: Boolean = false, val readyToSave: Boolean = false, val stopped: Boolean = false,
    val imagePages: List<Int> = emptyList(), val error: String? = null)

class WordExporter(private val app: Application, private val worker: CoroutineDispatcher) {
    var progress: WordExportProgress? by mutableStateOf(null)
        private set
    var name = "PdfCraft.docx"
        private set
    private var prepared: File? = null
    private val stopped = AtomicBoolean(false)
    fun stop() { stopped.set(true) }
    fun dismiss() { if (progress?.finished == true) { prepared?.delete(); prepared = null; progress = null } }
    fun close() { stop(); prepared?.delete(); prepared = null; progress = null }
    private fun checkStop() { if (stopped.get()) throw ExportStopped() }

    suspend fun prepare(doc: MobileDocument?, request: WordExportRequest, current: PageSnapshot?) {
        stopped.set(false)
        prepared?.delete(); prepared = null
        name = exportBaseName(request.title) + ".docx"
        progress = WordExportProgress(request.pages.size)
        val file = File(app.cacheDir, "word-${UUID.randomUUID()}.docx")
        try {
            val (staged, images) = withContext(worker) {
                var job: WordExportJob? = null
                try {
                    request.snapshot.requireCurrent(current)
                    checkStop()
                    job = requireNotNull(doc).beginWordExport(request.pages.map(Int::toUInt), request.snapshot.revision)
                    for (ordinal in request.pages.indices) {
                        checkStop()
                        job.advance()
                        withContext(Dispatchers.Main.immediate) { progress = WordExportProgress(request.pages.size, ordinal + 1) }
                    }
                    checkStop()
                    withContext(Dispatchers.Main.immediate) { progress = WordExportProgress(request.pages.size, request.pages.size, saving = true) }
                    val pictures = job.finish(file.absolutePath).map { it.toInt() + 1 }
                    checkStop()
                    file to pictures
                } catch (error: Throwable) {
                    file.delete() // Only the app-private temporary file, never a document URI.
                    throw error
                } finally { job?.let { runCatching { it.release() }; it.destroy() } }
            }
            prepared = staged
            progress = WordExportProgress(request.pages.size, request.pages.size, finished = true, readyToSave = true, imagePages = images)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ExportStopped) { progress = progress?.copy(finished = true, stopped = true, saving = false) }
        catch (error: Exception) { fail(error.message ?: "Не удалось создать DOCX") }
        catch (_: OutOfMemoryError) { fail("Недостаточно памяти. Выберите меньше страниц.") }
        catch (error: LinkageError) { fail("Ошибка движка: ${error.message}") }
        finally { if (prepared != file) file.delete() }
    }

    suspend fun save(uri: Uri) {
        val staged = prepared ?: run { fail("Временный DOCX недоступен. Повторите экспорт. Выбранный файл может остаться пустым."); return }
        progress = progress?.copy(finished = false, saving = true, readyToSave = false, error = null)
        try {
            withContext(worker) {
                app.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "Нет доступа к выбранному файлу" }
                    staged.inputStream().use { it.copyTo(output, 64 * 1024) }
                }
            }
            staged.delete(); prepared = null
            progress = progress?.copy(finished = true, saving = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) {
            progress = progress?.copy(finished = true, saving = false, readyToSave = true,
                error = "Недостаточно памяти при сохранении. Готовый DOCX доступен для повторной попытки.")
        }
        catch (error: Exception) {
            // SAF providers do not universally support atomic writes. Keep the complete
            // private DOCX for retry; never delete or truncate the URI again as cleanup.
            progress = progress?.copy(finished = true, saving = false, readyToSave = true,
                error = "Не удалось сохранить файл: ${error.message}. Выберите другое место. Неудачная копия может быть неполной.")
        }
    }
    private fun fail(message: String) { progress = (progress ?: WordExportProgress(0)).copy(finished = true, saving = false, error = message) }
}
