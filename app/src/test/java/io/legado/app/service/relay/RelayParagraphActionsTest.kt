package io.legado.app.service.relay

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.login.SourceLoginJsExtensions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayParagraphActionsTest {
    @Test
    fun sourceLoginBridgeExposesAjaxToRhino() {
        val methods = SourceLoginJsExtensions::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("ajax"))
        assertTrue(methods.contains("ajaxAll"))
        assertTrue(methods.contains("showBrowser"))
    }

    @Test
    fun decorateReplacesVirtualImageWithOpaqueAction() {
        val content = "正文<img src='dp:18,{&quot;click&quot;:&quot;showCmt(1,2,3,4)&quot;}'>结尾"

        val decorated = RelayParagraphActions.decorate(
            Book(bookUrl = "book", origin = "source"),
            BookChapter(url = "chapter", bookUrl = "book", index = 7, title = "title"),
            content
        )

        assertTrue(decorated.contains("legado-paragraph-bubble"))
        assertTrue(decorated, decorated.contains("data-legado-count=\"18\""))
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

    @Test
    fun decorateSupportsBookSourceSvgCommentBubble() {
        val svg = "PHN2Zz48dGV4dD41PC90ZXh0Pjwvc3ZnPg=="
        val content = "<img src=\"data:image/svg+xml;base64,$svg,{\"style\":\"text\",\"type\":\"qd\",\"click\":\"showCmt(1,2,3,4)\"}\">"

        val decorated = RelayParagraphActions.decorate(
            Book(bookUrl = "book", origin = "source"),
            BookChapter(url = "chapter", bookUrl = "book", index = 2, title = "title"),
            content
        )

        assertTrue(decorated.contains("data-legado-count=\"5\""))
        assertTrue(decorated.contains("data-legado-action="))
        assertFalse(decorated.contains("legado-paragraph-bubble-disabled"))
        assertFalse(decorated.contains("showCmt"))
    }
}
