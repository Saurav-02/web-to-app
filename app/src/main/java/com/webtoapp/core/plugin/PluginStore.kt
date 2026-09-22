package com.webtoapp.core.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.webtoapp.core.i18n.AppLanguage
import com.webtoapp.core.i18n.Strings
import com.webtoapp.core.logging.AppLogger
import com.webtoapp.util.GsonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Host-side plugin store. The package directory is the source of truth:
 *
 * ```
 * files/plugins/
 *   <id>/plugin.json     manifest (authored for HCJ, generated for userscripts)
 *   <id>/main.js         page script
 *   <id>/style.css       optional
 *   <id>/panel.html      optional — hosted by the plugin panel surface
 *   <id>/icon.*          optional package icon
 *   <id>/files/...       optional extra files (migrated multi-file modules)
 * files/plugin_state.json   list order + chrome-extension records
 * ```
 *
 * CHROME_EXTENSION records live in the state file only; their content stays in
 * the extension engine's own directory (`ExtensionFileManager`).
 *
 * Built-in HCJ packages ship read-only in `assets/plugins/`. There is no
 * enable/pin state — a plugin runs where the app's config attaches it.
 */
@Suppress("StaticFieldLeak")
class PluginStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PluginStore"
        const val PLUGINS_DIR = "plugins"
        const val STATE_FILE = "plugin_state.json"
        const val BUILTIN_ASSET_DIR = "plugins"
        const val PANEL_FILE = "panel.html"
        const val MAIN_FILE = "main.js"
        const val CSS_FILE = "style.css"
        const val MANIFEST_FILE = "plugin.json"

        @Volatile
        private var INSTANCE: PluginStore? = null

        fun getInstance(context: Context): PluginStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginStore(context.applicationContext).also { INSTANCE = it }
            }

        fun release() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }

    private val gson: Gson = GsonBuilder().setLenient().serializeNulls().create()
    private val saveMutex = Mutex()

    private val pluginsDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    }
    private val stateFile: File get() = File(context.filesDir, STATE_FILE)

    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    private val _builtInPlugins = MutableStateFlow<List<Plugin>>(emptyList())
    val builtInPlugins: StateFlow<List<Plugin>> = _builtInPlugins.asStateFlow()

    @Volatile
    private var allCache: List<Plugin> = emptyList()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var released = false

    @Volatile
    private var builtInsLanguage: AppLanguage? = null

    init {
        loadBuiltIns()
        rebuildCache()
        scope.launch {
            PluginMigrator(context).migrateIfNeeded()
            loadPackages()
            rebuildCache()
        }.invokeOnCompletion {
            _isLoading.value = false
        }
    }

    private fun shutdown() {
        released = true
        scope.cancel()
    }

    suspend fun awaitLoaded() {
        isLoading.first { !it }
    }

    // ------------------------------------------------------------------
    // State overlay
    // ------------------------------------------------------------------

    private data class StateOverlay(
        val order: MutableList<String> = mutableListOf(),
        val chromeRecords: MutableList<Plugin> = mutableListOf()
    )

    private fun readOverlay(): StateOverlay {
        if (!stateFile.exists()) return StateOverlay()
        return try {
            val obj = JsonParser.parseString(stateFile.readText()).asJsonObject
            val overlay = StateOverlay()
            obj.getAsJsonArray("order")?.forEach { overlay.order.add(it.asString) }
            obj.getAsJsonArray("chromeRecords")?.forEach { el ->
                try {
                    gson.fromJson(el, Plugin::class.java)?.let {
                        overlay.chromeRecords.add(it.copy(kind = PluginKind.CHROME_EXTENSION))
                    }
                } catch (_: Exception) {
                }
            }
            overlay
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to read plugin state", e)
            StateOverlay()
        }
    }

    private suspend fun writeOverlay() = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            try {
                val overlay = JsonParser.parseString("{}").asJsonObject
                val order = com.google.gson.JsonArray()
                (_plugins.value.map { it.id } + _builtInPlugins.value.map { it.id })
                    .forEach { order.add(it) }
                overlay.add("order", order)
                val chrome = com.google.gson.JsonArray()
                _plugins.value.filter { it.kind == PluginKind.CHROME_EXTENSION }
                    .forEach { chrome.add(gson.toJsonTree(it)) }
                overlay.add("chromeRecords", chrome)
                stateFile.writeText(gson.toJson(overlay))
            } catch (e: Exception) {
                AppLogger.e(TAG, "failed to write plugin state", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private suspend fun loadPackages() = withContext(Dispatchers.IO) {
        try {
            val overlay = readOverlay()
            val loaded = mutableListOf<Plugin>()

            pluginsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val manifestFile = File(dir, MANIFEST_FILE)
                if (!manifestFile.exists()) return@forEach
                val manifest = PluginManifest.fromJson(manifestFile.readText()) ?: return@forEach
                val kind = PluginKind.parse(
                    try {
                        JsonParser.parseString(manifestFile.readText())
                            .asJsonObject.get("kind")?.asString
                    } catch (e: Exception) {
                        null
                    }
                )
                loaded.add(
                    Plugin.fromManifest(
                        manifest = manifest,
                        packageDir = dir.name,
                        kind = kind,
                        hasPanel = File(dir, PANEL_FILE).exists(),
                        hasCss = File(dir, CSS_FILE).exists(),
                        builtIn = false
                    )
                )
            }

            loaded.addAll(overlay.chromeRecords)

            // Stored order first; anything new (fresh installs, migrated) after.
            val byId = loaded.associateBy { it.id }
            val ordered = overlay.order.mapNotNull { byId[it] } +
                loaded.filter { it.id !in overlay.order }
            _plugins.value = ordered
            AppLogger.d(TAG, "loaded ${ordered.size} plugins")
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to load plugins", e)
            _plugins.value = emptyList()
        }
    }

    private fun loadBuiltIns() {
        builtInsLanguage = Strings.lang
        val overlay = readOverlay()
        val loaded = mutableListOf<Plugin>()
        try {
            val dirs = context.assets.list(BUILTIN_ASSET_DIR) ?: emptyArray()
            for (dirName in dirs) {
                val manifestPath = "$BUILTIN_ASSET_DIR/$dirName/$MANIFEST_FILE"
                val manifest = try {
                    context.assets.open(manifestPath).bufferedReader().use { it.readText() }
                        .let { PluginManifest.fromJson(it) }
                } catch (e: Exception) {
                    null
                } ?: continue
                loaded.add(
                    Plugin.fromManifest(
                        manifest = manifest,
                        packageDir = "$BUILTIN_ASSET_DIR/$dirName",
                        kind = PluginKind.HCJ,
                        hasPanel = assetExists("$BUILTIN_ASSET_DIR/$dirName/$PANEL_FILE"),
                        hasCss = assetExists("$BUILTIN_ASSET_DIR/$dirName/$CSS_FILE"),
                        builtIn = true
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to load built-in plugins", e)
        }
        val byId = loaded.associateBy { it.id }
        _builtInPlugins.value = overlay.order.mapNotNull { byId[it] } +
            loaded.filter { it.id !in overlay.order }
    }

    fun reloadBuiltInsIfLanguageChanged() {
        if (_builtInPlugins.value.isNotEmpty() && builtInsLanguage == Strings.lang) return
        loadBuiltIns()
        rebuildCache()
    }

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (e: Exception) {
        false
    }

    private fun rebuildCache() {
        val userIds = _plugins.value.map { it.id }.toSet()
        allCache = _builtInPlugins.value.filter { it.id !in userIds } + _plugins.value
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    fun getAllPlugins(): List<Plugin> = allCache

    fun getPlugin(id: String): Plugin? = allCache.firstOrNull { it.id == id }

    fun getPluginsByIds(ids: List<String>): List<Plugin> {
        val all = allCache
        return ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
    }

    // ------------------------------------------------------------------
    // Code loading (injection path)
    // ------------------------------------------------------------------

    data class PackageCode(
        val mainJs: String = "",
        val css: String = "",
        val panelHtml: String = ""
    )

    fun loadPackageCode(plugin: Plugin): PackageCode {
        return if (plugin.builtIn) loadAssetPackageCode(plugin) else loadDirPackageCode(plugin)
    }

    private fun loadDirPackageCode(plugin: Plugin): PackageCode {
        val dir = File(pluginsDir, plugin.packageDir)
        fun read(name: String) = try {
            File(dir, name).takeIf { it.exists() }?.readText().orEmpty()
        } catch (e: Exception) {
            ""
        }
        return PackageCode(
            mainJs = read(MAIN_FILE),
            css = read(CSS_FILE),
            panelHtml = read(PANEL_FILE)
        )
    }

    private fun loadAssetPackageCode(plugin: Plugin): PackageCode {
        fun read(name: String) = try {
            context.assets.open("${plugin.packageDir}/$name").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        return PackageCode(
            mainJs = read(MAIN_FILE),
            css = read(CSS_FILE),
            panelHtml = read(PANEL_FILE)
        )
    }

    /**
     * Resolve plugins into injectable payloads. `ids == null` resolves every
     * installed plugin (marked unattached so local-runtime pages can skip
     * them); an explicit id list marks everything app-attached.
     */
    fun resolveForInjection(ids: List<String>? = null): List<PluginSession.Resolved> {
        val attached = ids != null
        val base = if (ids == null) allCache else getPluginsByIds(ids)
        return base.map { plugin ->
            if (plugin.isScriptPlugin) {
                val code = loadPackageCode(plugin)
                PluginSession.Resolved(
                    plugin = plugin.copy(hasPanel = code.panelHtml.isNotBlank()),
                    mainJs = code.mainJs,
                    css = code.css,
                    panelHtml = code.panelHtml,
                    attached = attached
                )
            } else {
                PluginSession.Resolved(plugin = plugin, attached = attached)
            }
        }
    }

    fun panelHtmlFor(plugin: Plugin): String? =
        loadPackageCode(plugin).panelHtml.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------
    // Raw package access (editor)
    // ------------------------------------------------------------------

    /** One text file inside an installed package dir; null when absent. */
    suspend fun readPackageFile(pluginId: String, rel: String): String? =
        withContext(Dispatchers.IO) {
            if (rel.isBlank() || rel.contains("..")) return@withContext null
            val f = File(pluginsDir, pluginId).resolve(rel)
            try {
                f.takeIf { it.isFile }?.readText()
            } catch (e: Exception) {
                null
            }
        }

    /** Every file in the package dir keyed by relative path (editor round-trip). */
    suspend fun readPackageFiles(pluginId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val dir = File(pluginsDir, pluginId)
            if (!dir.isDirectory) return@withContext emptyMap()
            dir.walkTopDown().filter { it.isFile }.associate { f ->
                f.relativeTo(dir).path to runCatching { f.readText() }.getOrDefault("")
            }
        }

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /**
     * Persist a new list order (long-press drag in the manager). Ids not in
     * [orderedIds] keep their relative position at the end.
     */
    suspend fun reorder(orderedIds: List<String>, builtIn: Boolean) {
        val flow = if (builtIn) _builtInPlugins else _plugins
        val byId = flow.value.associateBy { it.id }
        flow.value = orderedIds.mapNotNull { byId[it] } +
            flow.value.filter { it.id !in orderedIds }
        writeOverlay()
        rebuildCache()
    }

    suspend fun removePlugin(id: String): Boolean = withContext(Dispatchers.IO) {
        val plugin = _plugins.value.find { it.id == id } ?: return@withContext false
        if (plugin.kind != PluginKind.CHROME_EXTENSION) {
            File(pluginsDir, plugin.packageDir).deleteRecursively()
        }
        _plugins.value = _plugins.value.filter { it.id != id }
        PluginConfigStore(context).clear(id)
        writeOverlay()
        rebuildCache()
        true
    }

    /**
     * Install/overwrite an HCJ package. Returns the installed plugin id.
     * `files` maps package-relative paths ("main.js", "panel.html", "files/x.js").
     */
    suspend fun installPackage(
        manifest: PluginManifest,
        kind: PluginKind = PluginKind.HCJ,
        files: Map<String, String>
    ): Result<Plugin> = withContext(Dispatchers.IO) {
        try {
            val id = manifest.id.takeIf { it.isNotBlank() }
                ?: "p" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            val dir = File(pluginsDir, id)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            val effectiveManifest = manifest.copy(id = id)
            File(dir, MANIFEST_FILE).writeText(
                gson.toJson(
                    JsonParser.parseString(gson.toJson(effectiveManifest)).asJsonObject.apply {
                        addProperty("kind", kind.name)
                    }
                )
            )
            files.forEach { (rel, content) ->
                val safeRel = rel.removePrefix("/")
                if (safeRel.isBlank() || safeRel.contains("..")) return@forEach
                val f = File(dir, safeRel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }

            val existing = _plugins.value.firstOrNull { it.id == id }
            val plugin = Plugin.fromManifest(
                manifest = effectiveManifest,
                packageDir = id,
                kind = kind,
                hasPanel = File(dir, PANEL_FILE).exists(),
                hasCss = File(dir, CSS_FILE).exists(),
                builtIn = false
            ).copy(
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            _plugins.value = _plugins.value.filter { it.id != plugin.id } + plugin
            writeOverlay()
            rebuildCache()
            Result.success(plugin)
        } catch (e: Exception) {
            AppLogger.e(TAG, "installPackage failed", e)
            Result.failure(e)
        }
    }

    /** Register/update a chrome-extension record (content lives in ext engine). */
    suspend fun upsertChromeRecord(plugin: Plugin) {
        _plugins.value =
            _plugins.value.filter { it.id != plugin.id } + plugin.copy(kind = PluginKind.CHROME_EXTENSION)
        writeOverlay()
        rebuildCache()
    }

    suspend fun removeChromeRecordsFor(extId: String) {
        val before = _plugins.value.size
        _plugins.value = _plugins.value.filter { it.chromeExtId != extId }
        if (_plugins.value.size != before) {
            writeOverlay()
            rebuildCache()
        }
    }

}
