package org.pdfcraft.android

/** Human page numbers -> zero-based indices, preserving the requested insertion order. */
fun parsePageSelection(text: String, count: Int): List<Int> {
    require(text.isNotBlank() && text.length <= 512) { "Укажите страницы: например, 1, 3–5" }
    val pages = linkedSetOf<Int>()
    for (token in text.replace('–', '-').replace('—', '-').split(',')) {
        val ends = token.trim().split('-')
        require(ends.size in 1..2) { "Неверный диапазон страниц" }
        val first = ends[0].trim().toIntOrNull() ?: error("Неверный номер страницы")
        val last = if (ends.size == 2) ends[1].trim().toIntOrNull() ?: error("Неверный номер страницы") else first
        require(first >= 1 && last >= first && last <= count) { "Страницы должны быть в пределах 1–$count" }
        require(last.toLong() - first < 500) { "За один раз можно выбрать до 500 страниц" }
        for (page in first..last) {
            pages.add(page - 1)
            require(pages.size <= 500) { "За один раз можно выбрать до 500 страниц" }
        }
    }
    return pages.toList()
}
