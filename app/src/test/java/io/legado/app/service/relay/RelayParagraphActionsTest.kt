package io.legado.app.service.relay

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayParagraphActionsTest {
    @Test
    fun decorateReplacesVirtualImageWithOpaqueAction() {
        val content = "正文<img src='dp:18,{&quot;click&quot;:&quot;showCmt(1,2,3,4)&quot;}'>结尾"

        val decorated = RelayParagraphActions.decorate(
            Book(bookUrl = "book", origin = "source"),
            BookChapter(url = "chapter", bookUrl = "book", index = 7, title = "title"),
            content
        )

        assertTrue(decorated.contains("legado-paragraph-bubble"))
        assertTrue(decorated.contains("data-legado-count=\"18\""))
        assertTrue(decorated.contains(Regex("data-legado-action=\"[A-Za-z0-9_-]{24}\"")))
        assertFalse(decorated.contains("showCmt"))
        assertFalse(decorated.contains("dp:18"))
    }

    @Test
    fun decorateLeavesNormalImagesUntouched() {
        val content = "<img src=\"https://example.com/a.png\">"
        val decorated = RelayParagraphActions.decorate(
            Book(bookUrl = "book"),
            BookChapter(url = "chapter", bookUrl = "book", index = 1, title = "title"),
            content
        )
        assertTrue(decorated.contains("https://example.com/a.png"))
        assertFalse(decorated.contains("data-legado-action"))
    }
}
