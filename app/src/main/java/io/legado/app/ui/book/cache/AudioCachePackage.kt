package io.legado.app.ui.book.cache

import io.legado.app.data.entities.BookChapter
import java.io.File

internal const val AUDIO_CACHE_MANIFEST_VERSION = 2

internal data class AudioCacheManifest(
    val version: Int = 0,
    val catalogComplete: Boolean = false,
    val bookName: String = "",
    val author: String = "",
    val bookUrl: String = "",
    val chapters: List<Chapter>? = emptyList()
) {
    val schemaVersion: Int
        get() = version.takeIf { it > 0 } ?: 1

    val hasCompleteCatalog: Boolean
        get() = schemaVersion >= AUDIO_CACHE_MANIFEST_VERSION && catalogComplete

    fun chapterList(): List<Chapter> = chapters.orEmpty()

    data class Chapter(
        val index: Int = 0,
        val title: String = "",
        val isVolume: Boolean = false,
        val url: String = "",
        val baseUrl: String = "",
        val isVip: Boolean = false,
        val isPay: Boolean = false,
        val resourceUrl: String? = null,
        val tag: String? = null,
        val wordCount: String? = null,
        val start: Long? = null,
        val end: Long? = null,
        val startFragmentId: String? = null,
        val endFragmentId: String? = null,
        val variable: String? = null,
        val imgUrl: String? = null,
        val cacheDir: String? = null,
        val fileCount: Int = 0
    ) {
        fun toBookChapter(bookUrl: String): BookChapter {
            return BookChapter(
                url = url,
                title = title,
                isVolume = isVolume,
                baseUrl = baseUrl,
                bookUrl = bookUrl,
                index = index,
                isVip = isVip,
                isPay = isPay,
                resourceUrl = resourceUrl,
                tag = tag,
                wordCount = wordCount,
                start = start,
                end = end,
                startFragmentId = startFragmentId,
                endFragmentId = endFragmentId,
                variable = variable,
                imgUrl = imgUrl
            )
        }

        fun resolvedCacheDir(): String = cacheDir?.takeIf { it.isNotBlank() } ?: index.toString()

        companion object {
            fun from(chapter: BookChapter, fileCount: Int, cacheDir: String? = null): Chapter {
                return Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    isVolume = chapter.isVolume,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cacheDir = cacheDir,
                    fileCount = fileCount.coerceAtLeast(0)
                )
            }
        }
    }
}

internal fun AudioCacheManifest.canReplaceCatalog(expectedBookUrl: String): Boolean {
    val items = chapterList()
    return hasCompleteCatalog &&
        bookUrl.isNotBlank() &&
        bookUrl == expectedBookUrl &&
        items.isNotEmpty() &&
        items.map { it.index }.distinct().size == items.size
}

internal fun mergeRestoredAudioCatalog(
    existing: List<BookChapter>,
    incoming: List<BookChapter>,
    replaceCatalog: Boolean
): List<BookChapter> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty() || replaceCatalog) return incoming.sortedBy { it.index }

    val merged = existing.map { it.copy() }.toMutableList()
    incoming.forEach { candidate ->
        val matchIndex = merged.indexOfFirst { current ->
            current.matchesAudioChapter(candidate)
        }
        if (matchIndex >= 0) {
            val current = merged[matchIndex]
            if (current.resourceUrl.isNullOrBlank() && !candidate.resourceUrl.isNullOrBlank()) {
                merged[matchIndex] = current.copy(resourceUrl = candidate.resourceUrl)
            }
            return@forEach
        }

        val conflicts = merged.any { current ->
            current.index == candidate.index ||
                (current.url.isNotBlank() && current.url == candidate.url)
        }
        if (!conflicts) {
            merged.add(candidate.copy())
        }
    }
    return merged.sortedBy { it.index }
}

private fun BookChapter.matchesAudioChapter(other: BookChapter): Boolean {
    if (url.isNotBlank() && other.url.isNotBlank() && url == other.url) return true
    if (!resourceUrl.isNullOrBlank() &&
        !other.resourceUrl.isNullOrBlank() &&
        resourceUrl == other.resourceUrl
    ) {
        return true
    }
    return index == other.index && title.trim() == other.title.trim()
}

internal fun countImportableAudioFiles(directory: File): Int {
    return directory.listFiles()
        ?.count { file -> file.isFile && file.length() > 0L && file.hasAudioCacheFileName() }
        ?: 0
}

private fun File.hasAudioCacheFileName(): Boolean {
    val urlIndex = name.substringBefore('_', "").toIntOrNull() ?: return false
    val remainder = name.substringAfter('_', "")
    val position = remainder.substringBefore('_', "").toLongOrNull() ?: return false
    val length = remainder.substringAfter("${position}_", "")
        .substringBefore('_', "")
        .toLongOrNull()
        ?: return false
    return urlIndex >= 0 && position >= 0L && length > 0L
}
