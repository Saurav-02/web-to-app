package com.webtoapp.core.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.webtoapp.util.GsonProvider
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * HCJ plugin platform — unified model for the three plugin kinds:
 *
 *  - [PluginKind.HCJ]: first-party packages. A directory with `plugin.json`,
 *    `main.js`, optional `style.css`, optional `panel.html`, optional icon.
 *  - [PluginKind.USERSCRIPT]: Greasemonkey/Tampermonkey scripts. Imported into the
 *    same package layout (metadata block -> plugin.json); the GM_* polyfill is
 *    injected by the runtime, not stored.
 *  - [PluginKind.CHROME_EXTENSION]: MV3 extensions. The unpacked extension tree
 *    keeps its own directory; this model carries only the normalized entry data
 *    (action/popup, host permissions live in the extension engine).
 *
 * The model deliberately knows nothing about *how* a plugin is presented — that
 * is the user's choice via [PluginEntryStyle] / [PluginPanelStyle].
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum class PluginKind {
    HCJ,
    USERSCRIPT,
    CHROME_EXTENSION;

    companion object {
        fun parse(raw: String?): PluginKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HCJ
    }
}

enum class PluginRunAt(val jsEvent: String) {
    DOCUMENT_START(""),
    DOCUMENT_END("DOMContentLoaded"),
    DOCUMENT_IDLE("load");

    companion object {
        fun parse(raw: String?): PluginRunAt =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: DOCUMENT_END
    }
}

/**
 * Where the plugin entry point lives. User-selectable per app; plugins may hint
 * a preference through [PluginManifest.preferredEntry] but the app setting wins.
 */
enum class PluginEntryStyle {
    /** Plugins button + optional pinned icons inside the native browser toolbar. */
    TOOLBAR,
    /** Native floating handle (draggable, auto-collapses). Never page-DOM. */
    FLOATING_HANDLE,
    /** Entry inside the runtime overflow menu only. */
    MENU;

    companion object {
        fun parse(raw: String?): PluginEntryStyle =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TOOLBAR
    }
}

/** How a plugin's `panel.html` (or a Chrome popup) is hosted. */
enum class PluginPanelStyle {
    /** Modal bottom sheet hosting a WebView. */
    BOTTOM_SHEET,
    /** Draggable floating window. */
    FLOATING_WINDOW,
    /** Fullscreen dialog. */
    FULLSCREEN;

    companion object {
        fun parse(raw: String?): PluginPanelStyle =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BOTTOM_SHEET
    }
}

/**
 * Capability gates for the `hcj` bridge. Page DOM access is implicit — a plugin
 * *is* page JavaScript — so only elevated abilities are declared here.
 */
enum class PluginPermission {
    /** `hcj.config.*` persistent KV storage. */
    STORAGE,
    /** `hcj.fetch` cross-origin requests through the host. */
    FETCH,
    /** `hcj.notify` Android notifications. */
    NOTIFY,
    /** `hcj.badge` toolbar badge. */
    BADGE,
    /** `hcj.clipboard.*` clipboard access. */
    CLIPBOARD,
    /** `hcj.download` file downloads. */
    DOWNLOAD;

    companion object {
        fun parse(raw: String?): PluginPermission? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// URL matching (Chrome match-pattern semantics, same as the old module system)
// ---------------------------------------------------------------------------

data class PluginMatchRule(
    @SerializedName("pattern")
    val pattern: String,
    @SerializedName("exclude")
    val exclude: Boolean = false,
    @SerializedName("isRegex")
    val isRegex: Boolean = false
)

private const val REGEX_TIMEOUT_MS = 200L

private val regexExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
        Thread(r, "PluginSafeRegex").apply { isDaemon = true }
    }
}

private val regexCache = object : LinkedHashMap<String, Regex>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?) = size > 64
}

internal fun pluginSafeRegexMatch(pattern: String, input: String): Boolean {
    val future = try {
        val compiledRegex = synchronized(regexCache) {
            regexCache.getOrPut(pattern) { Regex(pattern) }
        }
        regexExecutor.submit<Boolean> { compiledRegex.containsMatchIn(input) }
    } catch (e: Exception) {
        return false
    }
    return try {
        future.get(REGEX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        // Kill the runaway task — the executor is single-threaded, so an
        // un-cancelled catastrophic regex would stall every later match.
        future.cancel(true)
        false
    } catch (e: Exception) {
        false
    }
}

internal fun pluginGlobMatches(url: String, rule: PluginMatchRule): Boolean {
    if (rule.isRegex) return pluginSafeRegexMatch(rule.pattern, url)
    val pattern = rule.pattern
    if (pattern == "*" || pattern == "<all_urls>") return true

    val regexPattern = buildString {
        append("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.startsWith("*://", i) -> {
                    append("(https?|ftp|file)://")
                    i += 4
                }
                c == '*' -> {
                    append(".*")
                    i++
                }
                c in ".+?^\${}()|[]\\/" -> {
                    append("\\")
                    append(c)
                    i++
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
        append("$")
    }
    return try {
        synchronized(regexCache) {
            regexCache.getOrPut("i:$regexPattern") {
                Regex(regexPattern, setOf(RegexOption.IGNORE_CASE))
            }
        }.matches(url)
    } catch (e: Exception) {
        url.contains(pattern, ignoreCase = true)
    }
}

// ---------------------------------------------------------------------------
// plugin.json — the only authored contract
// ---------------------------------------------------------------------------

/**
 * `plugin.json` manifest. Every field except [name] is optional; the package
 * directory supplies `main.js` / `style.css` / `panel.html` / icon by
 * convention so the manifest stays declarative only.
 */
data class PluginManifest(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String,
    @SerializedName("version")
    val version: String = "1.0.0",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("author")
    val author: String = "",
    @SerializedName("homepage")
    val homepage: String = "",
    /** Material icon name (e.g. "dark_mode") or a package icon filename. */
    @SerializedName("icon")
    val icon: String = "extension",
    @SerializedName("matches")
    val matches: List<String> = listOf("*"),
    @SerializedName("excludeMatches")
    val excludeMatches: List<String> = emptyList(),
    @SerializedName("runAt")
    val runAt: String = "document_end",
    @SerializedName("permissions")
    val permissions: List<String> = emptyList(),
    /** Show an entry for this plugin in the plugin surface. */
    @SerializedName("toolbar")
    val toolbar: Boolean = true,
    /** Plugin's suggested entry style; the app-level user choice wins. */
    @SerializedName("preferredEntry")
    val preferredEntry: String = "",
    /** Greasemonkey grants carried over for USERSCRIPT packages. */
    @SerializedName("gmGrants")
    val gmGrants: List<String> = emptyList(),
    /** `@require` dependency URLs — fetched/cached by the host, injected before main.js. */
    @SerializedName("requireUrls")
    val requireUrls: List<String> = emptyList(),
    /** `@resource` name -> URL map for `GM_getResourceText/URL`. */
    @SerializedName("resources")
    val resources: Map<String, String> = emptyMap(),
    @SerializedName("noframes")
    val noframes: Boolean = false,
    /**
     * Packages converted from the retired self-developed module format. The
     * injector prepends a small prelude that maps the old globals
     * (`getConfig`, `__MODULE_INFO__`, `__WTA_MODULE_UI__`) onto `hcj.*` so
     * already-published content keeps working without the old DSL runtime.
     */
    @SerializedName("legacyCompat")
    val legacyCompat: Boolean = false
) {
    fun resolvedId(fallback: String): String = id.takeIf { it.isNotBlank() } ?: fallback

    fun resolvedRunAt(): PluginRunAt = PluginRunAt.parse(runAt)

    fun resolvedPermissions(): Set<PluginPermission> =
        permissions.mapNotNull { PluginPermission.parse(it) }.toSet()

    fun matchRules(): List<PluginMatchRule> =
        matches.map { toRule(it, exclude = false) } +
            excludeMatches.map { toRule(it, exclude = true) }

    /** `/pattern/` denotes a regex (Tampermonkey @include convention). */
    private fun toRule(raw: String, exclude: Boolean): PluginMatchRule {
        val t = raw.trim()
        return if (t.length > 2 && t.startsWith("/") && t.endsWith("/")) {
            PluginMatchRule(pattern = t.substring(1, t.length - 1), exclude = exclude, isRegex = true)
        } else {
            PluginMatchRule(pattern = t, exclude = exclude)
        }
    }

    companion object {
        private val gson get() = GsonProvider.gson

        private fun str(obj: JsonObject, key: String, def: String): String =
            obj.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: def

        private fun bool(obj: JsonObject, key: String, def: Boolean): Boolean =
            obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: def

        private fun strList(obj: JsonObject, key: String, def: List<String>): List<String> =
            obj.getAsJsonArray(key)
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: def

        private fun strMap(obj: JsonObject, key: String): Map<String, String> =
            obj.getAsJsonObject(key)?.entrySet()
                ?.mapNotNull { (k, v) ->
                    v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it }
                }?.toMap() ?: emptyMap()

        /**
         * Hand-written parser: hand-authored manifests omit optional fields, and
         * Gson's Unsafe path would leave absent fields JVM-null despite the
         * non-null Kotlin types — every accessor below applies its own default.
         */
        fun fromJson(json: String): PluginManifest? = try {
            val obj = JsonParser.parseString(json).asJsonObject
            PluginManifest(
                id = str(obj, "id", ""),
                name = str(obj, "name", ""),
                version = str(obj, "version", "1.0.0"),
                description = str(obj, "description", ""),
                author = str(obj, "author", ""),
                homepage = str(obj, "homepage", ""),
                icon = str(obj, "icon", "extension"),
                matches = strList(obj, "matches", listOf("*")),
                excludeMatches = strList(obj, "excludeMatches", emptyList()),
                runAt = str(obj, "runAt", "document_end"),
                permissions = strList(obj, "permissions", emptyList()),
                toolbar = bool(obj, "toolbar", true),
                preferredEntry = str(obj, "preferredEntry", ""),
                gmGrants = strList(obj, "gmGrants", emptyList()),
                requireUrls = strList(obj, "requireUrls", emptyList()),
                resources = strMap(obj, "resources"),
                noframes = bool(obj, "noframes", false),
                legacyCompat = bool(obj, "legacyCompat", false)
            ).takeIf { it.name.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------------------------
// Plugin — the normalized runtime record (index.json + shell embedding)
// ---------------------------------------------------------------------------

/**
 * One installed plugin. For HCJ / USERSCRIPT kinds, `packageDir` names a
 * directory under `files/plugins/` holding `plugin.json`, `main.js`, optional
 * `style.css` / `panel.html` / `config.json`. For CHROME_EXTENSION,
 * [chromeExtId] points into the extension engine's own storage.
 */
data class Plugin(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),
    @SerializedName("kind")
    val kind: PluginKind = PluginKind.HCJ,
    @SerializedName("name")
    val name: String,
    @SerializedName("description")
    val description: String = "",
    @SerializedName("icon")
    val icon: String = "extension",
    @SerializedName("versionName")
    val versionName: String = "1.0.0",
    @SerializedName("authorName")
    val authorName: String = "",
    @SerializedName("homepage")
    val homepage: String = "",

    @SerializedName("packageDir")
    val packageDir: String = "",
    @SerializedName("matches")
    val matches: List<PluginMatchRule> = emptyList(),
    @SerializedName("runAt")
    val runAt: PluginRunAt = PluginRunAt.DOCUMENT_END,
    @SerializedName("permissions")
    val permissions: List<PluginPermission> = emptyList(),

    @SerializedName("builtIn")
    val builtIn: Boolean = false,
    @SerializedName("showInToolbar")
    val showInToolbar: Boolean = true,

    @SerializedName("hasPanel")
    val hasPanel: Boolean = false,
    @SerializedName("hasCss")
    val hasCss: Boolean = false,

    // --- userscript extras ---
    @SerializedName("gmGrants")
    val gmGrants: List<String> = emptyList(),
    @SerializedName("requireUrls")
    val requireUrls: List<String> = emptyList(),
    @SerializedName("resources")
    val resources: Map<String, String> = emptyMap(),
    @SerializedName("noframes")
    val noframes: Boolean = false,

    /** See [PluginManifest.legacyCompat]. */
    @SerializedName("legacyCompat")
    val legacyCompat: Boolean = false,

    // --- chrome extension extras (kind == CHROME_EXTENSION only) ---
    @SerializedName("chromeExtId")
    val chromeExtId: String = "",
    @SerializedName("manifestJson")
    val manifestJson: String = "",
    @SerializedName("backgroundScript")
    val backgroundScript: String = "",
    @SerializedName("popupPath")
    val popupPath: String = "",
    @SerializedName("optionsPagePath")
    val optionsPagePath: String = "",
    @SerializedName("world")
    val world: String = "ISOLATED",

    @SerializedName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun matchesUrl(url: String): Boolean {
        if (matches.isEmpty()) return true
        var hasInclude = false
        for (rule in matches) {
            if (rule.exclude) {
                if (pluginGlobMatches(url, rule)) return false
            } else {
                hasInclude = true
            }
        }
        if (!hasInclude) return true
        for (rule in matches) {
            if (!rule.exclude && pluginGlobMatches(url, rule)) return true
        }
        return false
    }

    fun hasPermission(permission: PluginPermission): Boolean =
        permission in permissions

    /** Page-context injection happens for script kinds only. */
    val isScriptPlugin: Boolean
        get() = kind == PluginKind.HCJ || kind == PluginKind.USERSCRIPT

    companion object {
        private val gson get() = GsonProvider.gson

        fun fromJson(json: String): Plugin? = try {
            gson.fromJson(json, Plugin::class.java)
        } catch (e: Exception) {
            null
        }

        /**
         * Build a record from a parsed manifest. `packageDir`/`hasPanel`/`hasCss`
         * describe what is actually on disk, so stale manifests can't claim files
         * that were deleted.
         */
        fun fromManifest(
            manifest: PluginManifest,
            packageDir: String,
            kind: PluginKind = PluginKind.HCJ,
            hasPanel: Boolean,
            hasCss: Boolean,
            builtIn: Boolean = false
        ): Plugin = Plugin(
            id = manifest.resolvedId(packageDir),
            kind = kind,
            name = manifest.name,
            description = manifest.description,
            icon = manifest.icon,
            versionName = manifest.version,
            authorName = manifest.author,
            homepage = manifest.homepage,
            packageDir = packageDir,
            matches = manifest.matchRules(),
            runAt = manifest.resolvedRunAt(),
            permissions = manifest.resolvedPermissions().toList(),
            builtIn = builtIn,
            showInToolbar = manifest.toolbar,
            hasPanel = hasPanel,
            hasCss = hasCss,
            gmGrants = manifest.gmGrants,
            requireUrls = manifest.requireUrls,
            resources = manifest.resources,
            noframes = manifest.noframes,
            legacyCompat = manifest.legacyCompat
        )
    }
}

/**
 * Injection-ready payload for one plugin. The host builds these from
 * [PluginStore] packages; generated APKs build them from embedded config data.
 * Keeping payloads self-contained means the runtime never touches the store.
 */
data class PluginPayload(
    val plugin: Plugin,
    val mainJs: String = "",
    val css: String = "",
    val panelHtml: String = ""
)

internal fun String.escapeForJsSingleQuote(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("", "\\u2028")
        .replace("", "\\u2029")

internal fun String.escapeForJsTemplate(): String =
    replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\${", "\\\${")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
