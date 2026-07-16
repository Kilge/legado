package io.legado.app.service.relay

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import android.graphics.Bitmap
import io.legado.app.utils.GSON
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import okio.ByteString.Companion.decodeBase64

internal class RelayCreditWindow {
    private val grants = Channel<Int>(capacity = 16)
    private val lock = Any()
    private var available = 0
    private var outstanding = 0

    fun grant(bytes: Int): Boolean {
        if (bytes !in 1..RelayProtocol.MAX_UNCONSUMED_BYTES) return false
        synchronized(lock) {
            if (outstanding + bytes > RelayProtocol.MAX_UNCONSUMED_BYTES) return false
            if (!grants.trySend(bytes).isSuccess) return false
            outstanding += bytes
            return true
        }
    }

    suspend fun take(maxBytes: Int): Int {
        while (available <= 0) available += withTimeout(RelayProtocol.REQUEST_TIMEOUT_MILLIS) { grants.receive() }
        return minOf(maxBytes, available).also { taken ->
            available -= taken
            synchronized(lock) { outstanding -= taken }
        }
    }

    fun close() = grants.close()
}

internal class RelayReadDispatcher(
    private val sendControl: (RelayControlMessage) -> Boolean,
    private val sendBinary: (RelayProtocol.BinaryFrame) -> Boolean
) {
    private val streamGson = Gson()
    suspend fun dispatch(
        request: RelayControlMessage,
        epoch: Long,
        credit: RelayCreditWindow
    ) = coroutineScope {
        val requestId = requireNotNull(request.requestId)
        val requestPath = requireNotNull(request.path)
        if (requestPath.substringBefore('?') == "/searchBook") {
            dispatchSearch(request, epoch, credit)
            return@coroutineScope
        }
        val parameters = parseParameters(requestPath)
        val result = withTimeout(RelayProtocol.RESPONSE_START_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                when (request.path.substringBefore('?')) {
                    "/getBookshelf" -> BookController.bookshelf
                    "/getChapterList" -> BookController.getChapterList(parameters)
                    "/getBookContent" -> BookController.getBookContent(parameters)
                    "/getBookContentEx" -> BookController.getRelayBookContent(parameters)
                    "/paragraph/action" -> {
                        val body = requireNotNull(request.bodyBase64).decodeBase64()?.utf8()
                            ?: throw IllegalArgumentException("Invalid request body")
                        RelayParagraphActions.execute(body)
                    }
                    "/getReadConfig" -> BookController.getWebReadConfig()
                    "/getBookCover" -> BookController.getRelayBookCover(parameters)
                    "/saveBookProgress" -> {
                        val body = requireNotNull(request.bodyBase64).decodeBase64()?.utf8()
                            ?: throw IllegalArgumentException("Invalid request body")
                        BookController.saveBookProgress(body)
                    }
                    else -> ReturnData().setErrorMsg("Route is not available")
                }
            }
        }
        val bitmap = result.data as? Bitmap
        check(sendControl(
            RelayControlMessage(
                type = "http_response",
                requestId = requestId,
                epoch = epoch,
                status = 200,
                headers = mapOf(
                    "content-type" to if (bitmap != null) "image/png" else "application/json; charset=utf-8",
                    "cache-control" to if (bitmap != null) "private, max-age=86400" else "private, no-store"
                )
            )
        )) { "Unable to queue response metadata" }

        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val producer = launch(Dispatchers.IO) {
            output.use { stream ->
                if (bitmap != null) {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)) { "Unable to encode cover" }
                } else {
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer -> GSON.toJson(result, writer) }
                }
            }
        }
        input.use { stream ->
            val buffer = ByteArray(RelayProtocol.MAX_CHUNK_BYTES)
            var sequence = 0
            var total = 0L
            while (true) {
                val read = withContext(Dispatchers.IO) { stream.read(buffer, 0, buffer.size) }
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    val permitted = credit.take(read - offset)
                    total += permitted
                    check(total <= RelayProtocol.MAX_BODY_BYTES) { "Response is too large" }
                    val payload = buffer.copyOfRange(offset, offset + permitted)
                    check(sendBinary(
                        RelayProtocol.BinaryFrame(
                            RelayProtocol.BinaryType.HttpResponseChunk,
                            flags = 0,
                            requestId = requestId,
                            sequence = sequence++,
                            payload = payload
                        )
                    )) { "Unable to queue response body" }
                    offset += permitted
                }
            }
        }
        producer.join()
        check(sendControl(
            RelayControlMessage(type = "http_response_end", requestId = requestId, epoch = epoch)
        )) { "Unable to queue response end" }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.dispatchSearch(
        request: RelayControlMessage,
        epoch: Long,
        credit: RelayCreditWindow
    ) {
        val requestId = requireNotNull(request.requestId)
        val body = requireNotNull(request.bodyBase64).decodeBase64()?.utf8()
            ?: throw IllegalArgumentException("Invalid search body")
        val key = GSON.fromJson(body, SearchRequest::class.java).key.trim()
        require(key.isNotEmpty() && key.length <= 100 && key.none { it.code < 0x20 || it.code == 0x7f }) {
            "Invalid search key"
        }
        check(sendControl(
            RelayControlMessage(
                type = "http_response",
                requestId = requestId,
                epoch = epoch,
                status = 200,
                headers = mapOf(
                    "content-type" to "application/x-ndjson; charset=utf-8",
                    "cache-control" to "private, no-store"
                )
            )
        )) { "Unable to queue response metadata" }

        val events = Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val model = SearchModel(this, object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)
            override fun onSearchStart() = Unit
            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                events.trySend(streamGson.toJson(SearchEvent("results", searchBooks)))
            }
            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                events.trySend(streamGson.toJson(SearchEvent("finish", emptyList(), hasMore)))
                events.close()
            }
            override fun onSearchCancel(exception: Throwable?) {
                events.trySend(streamGson.toJson(SearchEvent("error", emptyList(), false, exception?.localizedMessage)))
                events.close()
            }
        })
        var sequence = 0
        var total = 0L
        try {
            model.search(System.currentTimeMillis(), key)
            for (event in events) {
                val bytes = (event + "\n").toByteArray(Charsets.UTF_8)
                var offset = 0
                while (offset < bytes.size) {
                    val permitted = credit.take(minOf(RelayProtocol.MAX_CHUNK_BYTES, bytes.size - offset))
                    total += permitted
                    check(total <= RelayProtocol.MAX_BODY_BYTES) { "Search response is too large" }
                    check(sendBinary(
                        RelayProtocol.BinaryFrame(
                            RelayProtocol.BinaryType.HttpResponseChunk,
                            flags = 0,
                            requestId = requestId,
                            sequence = sequence++,
                            payload = bytes.copyOfRange(offset, offset + permitted)
                        )
                    )) { "Unable to queue search response" }
                    offset += permitted
                }
            }
        } finally {
            model.close()
            events.close()
        }
        check(sendControl(RelayControlMessage(type = "http_response_end", requestId = requestId, epoch = epoch))) {
            "Unable to queue response end"
        }
    }

    private data class SearchRequest(val key: String = "")
    private data class SearchEvent(
        val type: String,
        val books: List<SearchBook>,
        val hasMore: Boolean = false,
        val message: String? = null
    )

    private fun parseParameters(target: String): Map<String, List<String>> {
        val url = ("https://relay.invalid" + target).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid request target")
        require(url.querySize <= 32) { "Too many query parameters" }
        val result = LinkedHashMap<String, MutableList<String>>()
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = url.queryParameterValue(index).orEmpty()
            require(name.length <= 128 && value.length <= 8192) { "Query parameter is too large" }
            require(name.none(::isControl) && value.none(::isControl)) { "Invalid query parameter" }
            result.getOrPut(name) { ArrayList() }.add(value)
        }
        return result
    }

    private fun isControl(char: Char): Boolean = char.code < 0x20 || char.code == 0x7f
}
