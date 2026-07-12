package io.legado.app.ui.book.cache

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCachePackageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyManifestIsNeverTreatedAsComplete() {
        val manifest = GSON.fromJsonObject<AudioCacheManifest>(
            """{"bookName":"Book","author":"Author","bookUrl":"book","chapters":[]}"""
        ).getOrThrow()

        assertEquals(1, manifest.schemaVersion)
        assertFalse(manifest.hasCompleteCatalog)
        assertFalse(manifest.canReplaceCatalog("book"))
    }

    @Test
    fun incompleteRestoreKeepsExistingCatalogAndVolumes() {
        val existing = listOf(
            chapter(0, "volume", isVolume = true),
            chapter(1, "one", resourceUrl = "audio-1"),
            chapter(2, "two"),
            chapter(3, "volume-2", isVolume = true),
            chapter(4, "four")
        )
        val incoming = listOf(chapter(2, "two", resourceUrl = "audio-2"))

        val merged = mergeRestoredAudioCatalog(existing, incoming, replaceCatalog = false)

        assertEquals(5, merged.size)
        assertEquals(2, merged.count { it.isVolume })
        assertEquals("audio-2", merged.first { it.index == 2 }.resourceUrl)
    }

    @Test
    fun completeRestoreCanReplaceCatalog() {
        val existing = listOf(chapter(0, "old"), chapter(1, "old-2"))
        val incoming = listOf(chapter(0, "new"))

        val restored = mergeRestoredAudioCatalog(existing, incoming, replaceCatalog = true)

        assertEquals(listOf("new"), restored.map { it.title })
    }

    @Test
    fun actualFileCountIgnoresInvalidAndEmptyFiles() {
        val dir = temporaryFolder.newFolder("audio")
        dir.resolve("0_0_4_cache.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        dir.resolve("1_10_2_other.bin").writeBytes(byteArrayOf(1, 2))
        dir.resolve("invalid.bin").writeBytes(byteArrayOf(1))
        dir.resolve("0_20_0_empty.bin").writeBytes(byteArrayOf(1))
        dir.resolve("0_30_1_zero.bin").writeBytes(byteArrayOf())

        assertEquals(2, countImportableAudioFiles(dir))
    }

    @Test
    fun v2CompleteManifestRequiresMatchingBookAndUniqueIndexes() {
        val valid = AudioCacheManifest(
            version = 2,
            catalogComplete = true,
            bookUrl = "book",
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "one"),
                AudioCacheManifest.Chapter(index = 1, title = "two")
            )
        )
        val duplicate = valid.copy(
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "one"),
                AudioCacheManifest.Chapter(index = 0, title = "two")
            )
        )

        assertTrue(valid.canReplaceCatalog("book"))
        assertFalse(valid.canReplaceCatalog("other"))
        assertFalse(duplicate.canReplaceCatalog("book"))
    }

    private fun chapter(
        index: Int,
        title: String,
        isVolume: Boolean = false,
        resourceUrl: String? = null
    ): BookChapter {
        return BookChapter(
            url = "chapter-$index",
            title = title,
            isVolume = isVolume,
            bookUrl = "book",
            index = index,
            resourceUrl = resourceUrl
        )
    }
}
