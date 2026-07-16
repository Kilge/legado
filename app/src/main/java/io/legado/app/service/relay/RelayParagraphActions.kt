package io.legado.app.service.relay

import com.script.rhino.runScriptWithContext
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okio.ByteString.Companion.toByteString
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.util.LinkedHashMap

internal object RelayParagraphActions {
    private const val MAX_ACTIONS = 768
    private const val ACTION_TTL_MILLIS = 30 * 60 * 1000L
    private val random = SecureRandom()
    private val imageRegex = Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val actions = object : LinkedHashMap<String, Action>(MAX_ACTIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Action>?): Boolean = size > MAX_ACTIONS
    }

    data class BrowserResult(
        val type: String = "browser_panel",
        val title: String = "段评",
        val url: String,
        val html: String?,
        val preloadJs: String?,
        val config: String?
    )

    private data class CapturedBrowser(
        val url: String,
        val html: String?,
        val preloadJs: String?,
        val config: String?,
        val sourceKey: String
    )

    private data class ActionRequest(val actionId: String = "", val bookUrl: String = "", val chapterIndex: Int = -1)
    private data class Action(
        val bookUrl: String,
        val chapterIndex: Int,
        val source: String,
        val click: String,
        val paragraphRule: Boolean,
        val createdAt: Long
    )

    fun decorate(book: Book, chapter: BookChapter, content: String): String {
        prune()
        return imageRegex.replace(content) { match ->
            val element = Jsoup.parseBodyFragment(match.value).selectFirst("img") ?: return@replace match.value
            val src = element.attr("src").trim()
            if (!src.startsWith("dp:", true) && !src.startsWith("bubble://paragraph", true)) return@replace match.value
            val comma = src.indexOf(',')
            val count = when {
                src.startsWith("dp:", true) -> src.substring(3, if (comma >= 0) comma else src.length).trim()
                else -> element.attr("data-count").ifBlank { "•" }
            }.take(16)
            val options = if (comma >= 0) {
                GSON.fromJsonObject<Map<String, String>>(src.substring(comma + 1)).getOrNull().orEmpty()
            } else emptyMap()
            val click = options["pclick"]?.takeIf(String::isNotBlank)
                ?: options["click"]?.takeIf(String::isNotBlank)
                ?: return@replace "<span class=\"legado-paragraph-bubble legado-paragraph-bubble-disabled\">${escapeHtml(count)}</span>"
            val id = newId()
            synchronized(actions) {
                actions[id] = Action(
                    bookUrl = book.bookUrl,
                    chapterIndex = chapter.index,
                    source = src,
                    click = click,
                    paragraphRule = ParagraphRuleProcessor.isParagraphClick(click),
                    createdAt = System.currentTimeMillis()
                )
            }
            "<span class=\"legado-paragraph-bubble\" data-legado-action=\"$id\" data-legado-count=\"${escapeHtml(count)}\">${escapeHtml(count)}</span>"
        }
    }

    suspend fun execute(body: String): ReturnData {
        val request = GSON.fromJsonObject<ActionRequest>(body).getOrNull()
            ?: return ReturnData().setErrorMsg("动作格式无效")
        val action = synchronized(actions) { actions[request.actionId] }
            ?: return ReturnData().setErrorMsg("段评动作已过期，请刷新正文")
        if (action.bookUrl != request.bookUrl || action.chapterIndex != request.chapterIndex ||
            System.currentTimeMillis() - action.createdAt > ACTION_TTL_MILLIS
        ) return ReturnData().setErrorMsg("段评动作无效或已过期")
        val book = appDb.bookDao.getBook(action.bookUrl)
            ?: return ReturnData().setErrorMsg("未找到书籍")
        val chapter = appDb.bookChapterDao.getChapter(action.bookUrl, action.chapterIndex)
            ?: return ReturnData().setErrorMsg("未找到章节")
        var browser: CapturedBrowser? = null
        return runCatching {
            if (action.paragraphRule) {
                ParagraphRuleProcessor.evalClick(
                    book,
                    chapter,
                    action.click,
                    action.source,
                    object : ParagraphRuleProcessor.BrowserCallback {
                        override fun showBrowser(url: String, html: String?, preloadJs: String?, config: String?, sourceKey: String?): Boolean {
                            browser = CapturedBrowser(url, html, preloadJs, config, sourceKey.orEmpty())
                            return true
                        }
                    }
                )
            } else {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                    ?: error("未找到书源")
                val sourceCallback = object : SourceLoginJsExtensions.Callback {
                    override fun upUiData(data: Map<String, Any?>?) = Unit
                    override fun reUiView(deltaUp: Boolean) = Unit
                    override fun showBrowser(url: String, html: String?, preloadJs: String?, config: String?): Boolean {
                        browser = CapturedBrowser(url, html, preloadJs, config, source.getKey())
                        return true
                    }
                }
                val java = SourceLoginJsExtensions(null, source, callback = sourceCallback)
                runScriptWithContext {
                    source.evalJS(action.click) {
                        put("java", java)
                        put("book", book)
                        put("chapter", chapter)
                        put("result", action.source)
                    }
                }
            }
            browser?.let { ReturnData().setData(materialize(it)) }
                ?: ReturnData().setErrorMsg("此段评动作没有打开可显示内容")
        }.getOrElse { ReturnData().setErrorMsg(it.localizedMessage ?: "段评动作执行失败") }
    }

    private fun newId(): String = ByteArray(18).also(random::nextBytes).toByteString().base64Url().trimEnd('=')

    private suspend fun materialize(browser: CapturedBrowser): BrowserResult {
        val url = browser.url.trim()
        validateBrowserUrl(url)
        val script = buildString {
            if (!browser.preloadJs.isNullOrBlank()) append(browser.preloadJs).append('\n')
            append(";document.documentElement.outerHTML")
        }
        val response = BackstageWebView(
            url = url,
            html = browser.html,
            tag = browser.sourceKey,
            javaScript = script,
            delayTime = 500L,
            timeout = 20_000L,
            isRule = true,
            poolScope = WebViewPool.Scope.GLOBAL
        ).getStrResponse()
        val rendered = requireNotNull(response.body) { "段评页面为空" }
        require(rendered.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "段评页面过大" }
        val document = Jsoup.parse(rendered, response.url)
        document.select("[src]").forEach { element ->
            element.absUrl("src").takeIf(String::isNotBlank)?.let { element.attr("src", it) }
        }
        document.select("a[href]").forEach { element ->
            element.absUrl("href").takeIf(String::isNotBlank)?.let { element.attr("href", it) }
        }
        return BrowserResult(
            url = response.url,
            html = document.outerHtml(),
            preloadJs = null,
            config = browser.config
        )
    }

    private fun validateBrowserUrl(value: String) {
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("段评地址无效")
        require(url.scheme == "http" || url.scheme == "https") { "不支持的段评地址" }
        val host = url.host.lowercase()
        require(host != "localhost" && !host.endsWith(".local") && !isPrivateAddress(host)) {
            "不允许访问本机或局域网地址"
        }
    }

    private fun isPrivateAddress(host: String): Boolean {
        if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 127 || parts[0] == 0 ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> char
            })
        }
    }

    private fun prune() {
        val deadline = System.currentTimeMillis() - ACTION_TTL_MILLIS
        synchronized(actions) { actions.entries.removeAll { it.value.createdAt < deadline } }
    }
}
