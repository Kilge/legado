package io.legado.app.ui.book.read

import io.legado.app.constant.EventBus
import io.legado.app.utils.postEvent

/**
 * Bridges a system floating-window click to the singleTask reader activity.
 * The visual floating window itself is owned by BaseReadAloudService.
 */
object ReadAloudAppCapsuleHost {

    private data class PendingPanelOpenRequest(
        val bookUrl: String,
        val requestedAt: Long
    )

    private var pendingPanelOpenRequest: PendingPanelOpenRequest? = null
    private var readBookPanelVisible = false
    private var readBookForeground = false

    fun updateReadBookPanelActive(active: Boolean) {
        readBookPanelVisible = active
        publishPanelSuppression()
    }

    fun updateReadBookForegroundActive(active: Boolean) {
        readBookForeground = active
        publishPanelSuppression()
    }

    fun requestReadAloudPanelOpen(bookUrl: String) {
        if (bookUrl.isBlank()) return
        pendingPanelOpenRequest = PendingPanelOpenRequest(
            bookUrl = bookUrl,
            requestedAt = System.currentTimeMillis()
        )
    }

    fun consumeReadAloudPanelOpen(bookUrl: String?): Boolean {
        val request = pendingPanelOpenRequest ?: return false
        if (bookUrl.isNullOrBlank() || request.bookUrl != bookUrl) return false
        pendingPanelOpenRequest = null
        return System.currentTimeMillis() - request.requestedAt <= REQUEST_TIMEOUT_MILLIS
    }

    private fun publishPanelSuppression() {
        postEvent(
            EventBus.READ_ALOUD_PANEL_ACTIVE,
            readBookForeground && readBookPanelVisible
        )
    }

    private const val REQUEST_TIMEOUT_MILLIS = 30_000L
}
