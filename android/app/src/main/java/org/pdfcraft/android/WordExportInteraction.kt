package org.pdfcraft.android

const val WORD_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
data class WordExportRequest(val snapshot: PageSnapshot, val pages: List<Int>, val title: String)
fun wordExportPages(snapshot: PageSnapshot, mode: Int, current: Int, range: String): List<Int> {
    require(snapshot.count > 0) { "Откройте PDF" }
    val pages = when (mode) {
        0 -> { require(snapshot.count <= 500) { "За один раз можно экспортировать до 500 страниц. Укажите диапазон." }; (0 until snapshot.count).toList() }
        1 -> { require(current in 0 until snapshot.count) { "Страница не существует" }; listOf(current) }
        2 -> { require(range.isNotBlank()) { "Укажите страницы, например 1, 3–5" }; parsePageSelection(range, snapshot.count) }
        else -> error("Неверный режим экспорта")
    }
    require(pages.isNotEmpty() && pages.size <= 500) { "Выберите от 1 до 500 страниц" }
    return pages
}
