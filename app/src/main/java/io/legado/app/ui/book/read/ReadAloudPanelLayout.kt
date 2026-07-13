package io.legado.app.ui.book.read

object ReadAloudPanelLayout {

    fun centeredScrollDelta(
        viewportHeight: Int,
        itemOffset: Int,
        itemSize: Int
    ): Float {
        val viewportCenter = viewportHeight.coerceAtLeast(0) / 2f
        val itemCenter = itemOffset + itemSize.coerceAtLeast(0) / 2f
        return itemCenter - viewportCenter
    }
}
