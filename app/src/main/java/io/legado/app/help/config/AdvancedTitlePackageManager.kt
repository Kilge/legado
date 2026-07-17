package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.util.UUID

object AdvancedTitlePackageManager {

    const val BUILTIN_ID = "builtin_default"
    const val MAX_JSON_BYTES = 2L * 1024L * 1024L
    private const val MAX_PACKAGES = 64
    private const val MANIFEST_FILE = "package.json"
    private const val LOTTIE_FILE = "title.json"

    @Keep
    data class Config(
        val id: String,
        val name: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    data class Entry(
        val config: Config,
        val directory: File? = null,
        val isBuiltin: Boolean = false
    ) {
        val id: String get() = config.id
        val name: String get() = config.name
        val updatedAt: Long get() = config.updatedAt
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile("advancedTitlePackages")

    @Volatile
    private var cachedId: String? = null
    @Volatile
    private var cachedStamp: Long = Long.MIN_VALUE
    @Volatile
    private var cachedJson: String? = null
    @Volatile
    private var builtinJsonCache: String? = null
    private val mutationLock = Any()

    fun builtinEntry(): Entry = Entry(
        config = Config(
            id = BUILTIN_ID,
            name = appCtx.getString(R.string.advanced_title_builtin),
            updatedAt = 0L
        ),
        isBuiltin = true
    )

    fun activeId(): String = appCtx.getPrefString(PreferKey.advancedTitlePackage)
        ?.takeIf(::isValidId)
        ?: BUILTIN_ID

    suspend fun loadEntries(): List<Entry> = withContext(IO) {
        rootDir.mkdirs()
        migrateLegacyIfNeeded()
        var local = loadLocalEntries()
        val validIds = local.asSequence().map { it.id }.toSet() + BUILTIN_ID
        if (activeId() !in validIds) {
            val recovery = legacyTemplate()
                ?.takeIf { runCatching { validateJson(it) }.isSuccess }
                ?.let { addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), it) }
            appCtx.putPrefString(
                PreferKey.advancedTitlePackage,
                recovery?.id ?: BUILTIN_ID
            )
            if (recovery != null) local = loadLocalEntries()
            invalidate()
        }
        listOf(builtinEntry()) + local.sortedWith(
            compareByDescending<Entry> { it.updatedAt }.thenBy { it.name }
        )
    }

    fun currentTemplate(): String? {
        val explicitId = appCtx.getPrefString(PreferKey.advancedTitlePackage)
            ?.takeIf(::isValidId)
        if (explicitId == null) {
            legacyTemplate()?.let { return it }
        }
        val id = explicitId ?: BUILTIN_ID
        return if (id == BUILTIN_ID) {
            builtinJson()
        } else {
            val file = lottieFile(localDir(id))
            readCached(id, file) ?: legacyTemplate() ?: builtinJson()
        }
    }

    fun readTemplate(entry: Entry): String {
        return if (entry.isBuiltin) {
            builtinJson()
        } else {
            val directory = requireNotNull(entry.directory) { "Missing advanced title directory" }
            readJsonFile(lottieFile(directory))
        }
    }

    fun addOrUpdate(name: String, json: String, oldEntry: Entry? = null): Entry =
        synchronized(mutationLock) {
        val normalizedName = normalizeName(name)
        validateJson(json)
        val editableOld = oldEntry?.takeUnless { it.isBuiltin }
        if (editableOld == null) {
            val packageCount = rootDir.listFiles().orEmpty().count {
                it.isDirectory && !it.name.startsWith('.')
            }
            require(packageCount < MAX_PACKAGES) {
                appCtx.getString(R.string.advanced_title_package_limit)
            }
        }
        val id = editableOld?.id ?: "title_${UUID.randomUUID().toString().replace("-", "")}".take(38)
        require(isValidId(id)) { "Invalid advanced title id" }
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        val target = File(parent, id).canonicalFile
        require(target.parentFile == parent) { "Advanced title directory escaped its root" }
        val staging = File(parent, ".$id.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".$id.backup-${UUID.randomUUID()}")
        val config = Config(id, normalizedName, System.currentTimeMillis())
        staging.mkdirs()
        File(staging, MANIFEST_FILE).writeText(GSON.toJson(config))
        lottieFile(staging).writeText(json)
        verifyInstalledDirectory(staging, expectedId = id)
        val installed = BubbleDirectoryTransaction().install(target, staging, backup) { installedDir ->
            val verified = verifyInstalledDirectory(installedDir, expectedId = id)
            Entry(verified, installedDir)
        }
        invalidate()
        installed
    }

    fun apply(entry: Entry) = synchronized(mutationLock) {
        val json = readTemplate(entry)
        validateJson(json)
        appCtx.putPrefString(PreferKey.advancedTitlePackage, entry.id)
        // Keep the active JSON in the legacy backup field as a recovery copy. Rendering still
        // uses the bounded file cache above, so chapter changes do not repeatedly parse prefs.
        AdvancedTitleConfig.lottieJson = json
        AdvancedTitleConfig.lottiePath = null
        invalidate()
    }

    fun delete(entry: Entry) {
        synchronized(mutationLock) {
            if (entry.isBuiltin || entry.id == BUILTIN_ID) return@synchronized
            val parent = rootDir.canonicalFile
            val target = (entry.directory ?: localDir(entry.id)).canonicalFile
            require(target.parentFile == parent) { "Advanced title directory escaped its root" }
            if (target.exists() && !target.deleteRecursively() && target.exists()) {
                throw IOException("Unable to delete advanced title")
            }
            if (activeId() == entry.id) {
                appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
                AdvancedTitleConfig.lottieJson = builtinJson()
                AdvancedTitleConfig.lottiePath = null
            }
            invalidate()
        }
    }

    fun validateJson(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { appCtx.getString(R.string.advanced_title_invalid_json) }
        require(bytes.size <= MAX_JSON_BYTES) { appCtx.getString(R.string.advanced_title_too_large) }
        require(AdvancedTitleConfig.isValidLottieJson(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
    }

    fun invalidate() {
        cachedId = null
        cachedStamp = Long.MIN_VALUE
        cachedJson = null
    }

    private fun loadLocalEntries(): List<Entry> {
        val parent = rootDir.apply { mkdirs() }.canonicalFile
        return parent.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .take(MAX_PACKAGES * 2)
            .mapNotNull { directory ->
                runCatching {
                    val canonical = directory.canonicalFile
                    require(canonical.parentFile == parent)
                    val config = verifyInstalledDirectory(canonical)
                    Entry(config, canonical)
                }.getOrNull()
            }
            .take(MAX_PACKAGES)
            .toList()
    }

    private fun verifyInstalledDirectory(directory: File, expectedId: String? = null): Config {
        val manifest = File(directory, MANIFEST_FILE)
        require(manifest.isFile && manifest.length() in 1..64L * 1024L) {
            "Advanced title manifest is invalid"
        }
        val config = GSON.fromJsonObject<Config>(manifest.readText()).getOrThrow()
        require(isValidId(config.id)) { "Advanced title id is invalid" }
        require(expectedId == null || config.id == expectedId) { "Advanced title id changed" }
        require(config.id == directory.name) { "Advanced title directory does not match its id" }
        require(config.name.isNotBlank() && config.name.length <= 100) { "Advanced title name is invalid" }
        val json = readJsonFile(lottieFile(directory))
        require(AdvancedTitleConfig.hasRenderableLayers(json)) {
            appCtx.getString(R.string.advanced_title_invalid_json)
        }
        return config.copy(name = config.name.trim())
    }

    private fun migrateLegacyIfNeeded() {
        if (!appCtx.getPrefString(PreferKey.advancedTitlePackage).isNullOrBlank()) return
        val legacy = legacyTemplate()
            ?.takeIf { runCatching { validateJson(it) }.isSuccess }
        if (legacy == null) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            return
        }
        val builtin = builtinJson()
        val activeJson: String
        if (legacy == builtin) {
            appCtx.putPrefString(PreferKey.advancedTitlePackage, BUILTIN_ID)
            activeJson = builtin
        } else {
            val migrated = addOrUpdate(appCtx.getString(R.string.advanced_title_migrated), legacy)
            appCtx.putPrefString(PreferKey.advancedTitlePackage, migrated.id)
            activeJson = legacy
        }
        AdvancedTitleConfig.lottieJson = activeJson
        AdvancedTitleConfig.lottiePath = null
        invalidate()
    }

    private fun legacyTemplate(): String? {
        AdvancedTitleConfig.lottieJson?.takeIf { it.isNotBlank() }?.let { return it }
        val path = AdvancedTitleConfig.lottiePath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { readJsonFile(File(path)) }.getOrNull()
    }

    private fun readCached(id: String, file: File): String? {
        if (!file.isFile) return null
        val stamp = file.lastModified() xor file.length()
        if (cachedId == id && cachedStamp == stamp) return cachedJson
        return runCatching { readJsonFile(file) }.getOrNull()?.also { json ->
            cachedJson = json
            cachedStamp = stamp
            cachedId = id
        }
    }

    private fun builtinJson(): String {
        builtinJsonCache?.let { return it }
        return appCtx.resources.openRawResource(R.raw.advanced_title_lottie)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { builtinJsonCache = it }
    }

    private fun readJsonFile(file: File): String {
        require(file.isFile) { "Advanced title file is missing" }
        require(file.length() in 1..MAX_JSON_BYTES) {
            appCtx.getString(R.string.advanced_title_too_large)
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun localDir(id: String): File = rootDir.getFile(id)

    private fun lottieFile(directory: File): File = directory.getFile(LOTTIE_FILE)

    private fun normalizeName(value: String): String {
        return value.trim().replace(Regex("[\\r\\n\\t]+"), " ")
            .take(100)
            .ifBlank { appCtx.getString(R.string.advanced_title_unnamed) }
    }

    private fun isValidId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))
}
