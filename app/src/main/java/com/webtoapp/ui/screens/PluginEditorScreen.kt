package com.webtoapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webtoapp.core.i18n.Strings
import com.webtoapp.core.plugin.*
import com.webtoapp.ui.components.PremiumTextField
import com.webtoapp.ui.components.WtaCodeEditorDialog
import com.webtoapp.ui.design.*
import kotlinx.coroutines.launch

/**
 * Plugin editor: manifest fields on the first tab, package files on the rest.
 * No DSL forms — what you write is what ships inside the .hcj directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginEditorScreen(
    pluginId: String?,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore.getInstance(context) }

    var loaded by remember { mutableStateOf(pluginId == null) }
    var isNew by remember { mutableStateOf(pluginId == null) }
    var kind by remember { mutableStateOf(PluginKind.HCJ) }

    // Manifest fields
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var homepage by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf("*") }
    var excludeMatches by remember { mutableStateOf("") }
    var runAt by remember { mutableStateOf(PluginRunAt.DOCUMENT_END) }
    var permissions by remember { mutableStateOf(setOf<PluginPermission>()) }
    var showEntry by remember { mutableStateOf(true) }

    // Package files
    var mainJs by remember { mutableStateOf("") }
    var css by remember { mutableStateOf("") }
    var panelHtml by remember { mutableStateOf("") }
    var extraFiles by remember { mutableStateOf(mapOf<String, String>()) }

    var tab by remember { mutableIntStateOf(0) }
    var codeEditTarget by remember { mutableStateOf<Int?>(null) } // 1=js 2=css 3=panel
    var nameError by remember { mutableStateOf(false) }
    // Fields the form doesn't edit (userscript grants/requires, legacyCompat,
    // noframes, preferredEntry) — preserved from the on-disk manifest on save
    // so editing a userscript or a migrated plugin doesn't silently strip them.
    var preservedManifest by remember { mutableStateOf<PluginManifest?>(null) }

    LaunchedEffect(pluginId) {
        if (pluginId == null) {
            mainJs = NEW_PLUGIN_STUB
            return@LaunchedEffect
        }
        val plugin = store.getPlugin(pluginId) ?: run { onNavigateBack(); return@LaunchedEffect }
        kind = plugin.kind
        id = plugin.id
        name = plugin.name
        version = plugin.versionName
        author = plugin.authorName
        description = plugin.description
        homepage = plugin.homepage
        matches = plugin.matches.filter { !it.exclude }.joinToString("\n") {
            if (it.isRegex) "/${it.pattern}/" else it.pattern
        }.ifBlank { "*" }
        excludeMatches = plugin.matches.filter { it.exclude }.joinToString("\n") {
            if (it.isRegex) "/${it.pattern}/" else it.pattern
        }
        runAt = plugin.runAt
        permissions = plugin.permissions.toSet()
        showEntry = plugin.showInToolbar

        val files = store.readPackageFiles(plugin.id)
        // Manifest on disk may carry fields not mirrored into the Plugin record
        // (e.g. gmGrants) — prefer it when present.
        val rawManifest = files[PluginStore.MANIFEST_FILE]
            ?.let { PluginManifest.fromJson(it) }
        preservedManifest = rawManifest
        if (rawManifest != null) {
            matches = rawManifest.matches.joinToString("\n").ifBlank { "*" }
            excludeMatches = rawManifest.excludeMatches.joinToString("\n")
            runAt = rawManifest.resolvedRunAt()
            permissions = rawManifest.resolvedPermissions()
            showEntry = rawManifest.toolbar
            homepage = rawManifest.homepage
        }
        mainJs = files[PluginStore.MAIN_FILE].orEmpty()
        css = files[PluginStore.CSS_FILE].orEmpty()
        panelHtml = files[PluginStore.PANEL_FILE].orEmpty()
        extraFiles = files - PluginStore.MANIFEST_FILE - PluginStore.MAIN_FILE -
            PluginStore.CSS_FILE - PluginStore.PANEL_FILE
        loaded = true
    }

    fun save() {
        if (name.isBlank()) {
            nameError = true
            tab = 0
            return
        }
        val manifest = PluginManifest(
            id = if (isNew) id.ifBlank { slugFor(name) } else id,
            name = name.trim(),
            version = version.ifBlank { "1.0.0" },
            description = description.trim(),
            author = author.trim(),
            homepage = homepage.trim(),
            matches = matches.lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("*") },
            excludeMatches = excludeMatches.lines().map { it.trim() }.filter { it.isNotEmpty() },
            runAt = runAt.name.lowercase(),
            permissions = permissions.map { it.name },
            toolbar = showEntry,
            preferredEntry = preservedManifest?.preferredEntry.orEmpty(),
            gmGrants = preservedManifest?.gmGrants.orEmpty(),
            requireUrls = preservedManifest?.requireUrls.orEmpty(),
            resources = preservedManifest?.resources.orEmpty(),
            noframes = preservedManifest?.noframes ?: false,
            legacyCompat = preservedManifest?.legacyCompat ?: false
        )
        val files = buildMap {
            put(PluginStore.MAIN_FILE, mainJs)
            if (css.isNotBlank()) put(PluginStore.CSS_FILE, css)
            if (panelHtml.isNotBlank()) put(PluginStore.PANEL_FILE, panelHtml)
            putAll(extraFiles)
        }
        scope.launch {
            val result = store.installPackage(manifest, kind, files)
            result.onSuccess {
                Toast.makeText(context, Strings.saveSuccess, Toast.LENGTH_SHORT).show()
                onNavigateBack()
            }.onFailure {
                Toast.makeText(context, Strings.saveFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) Strings.pluginNew else Strings.pluginEditorEdit) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Strings.back)
                    }
                },
                actions = {
                    IconButton(onClick = ::save) {
                        Icon(Icons.Filled.Check, contentDescription = Strings.save)
                    }
                }
            )
        }
    ) { padding ->
        WtaBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WtaTabRow(
                    tabs = listOf(
                        WtaTab(Strings.pluginTabInfo),
                        WtaTab(Strings.pluginScriptTab),
                        WtaTab("CSS"),
                        WtaTab(Strings.pluginTabPanel)
                    ),
                    selectedIndex = tab,
                    onTabSelected = { tab = it }
                )

                when (tab) {
                    0 -> InfoTab(
                        isNew = isNew,
                        id = id, onId = { id = it },
                        name = name, onName = { name = it; nameError = false },
                        nameError = nameError,
                        version = version, onVersion = { version = it },
                        author = author, onAuthor = { author = it },
                        description = description, onDescription = { description = it },
                        homepage = homepage, onHomepage = { homepage = it },
                        matches = matches, onMatches = { matches = it },
                        excludeMatches = excludeMatches, onExcludes = { excludeMatches = it },
                        runAt = runAt, onRunAt = { runAt = it },
                        permissions = permissions, onPermissions = { permissions = it },
                        showEntry = showEntry, onShowEntry = { showEntry = it }
                    )
                    1 -> CodeTab(
                        content = mainJs,
                        language = "JavaScript",
                        fileName = PluginStore.MAIN_FILE,
                        placeholder = JS_PLACEHOLDER,
                        onEdit = { codeEditTarget = 1 }
                    )
                    2 -> CodeTab(
                        content = css,
                        language = "CSS",
                        fileName = PluginStore.CSS_FILE,
                        placeholder = CSS_PLACEHOLDER,
                        onEdit = { codeEditTarget = 2 }
                    )
                    else -> CodeTab(
                        content = panelHtml,
                        language = "HTML",
                        fileName = PluginStore.PANEL_FILE,
                        placeholder = HTML_PLACEHOLDER,
                        onEdit = { codeEditTarget = 3 }
                    )
                }
            }
        }
    }

    codeEditTarget?.let { target ->
        val (content, language, placeholder) = when (target) {
            1 -> Triple(mainJs, "JavaScript", JS_PLACEHOLDER)
            2 -> Triple(css, "CSS", CSS_PLACEHOLDER)
            else -> Triple(panelHtml, "HTML", HTML_PLACEHOLDER)
        }
        WtaCodeEditorDialog(
            language = language,
            initialContent = content,
            placeholder = placeholder,
            onSave = { newCode ->
                when (target) {
                    1 -> mainJs = newCode
                    2 -> css = newCode
                    else -> panelHtml = newCode
                }
                codeEditTarget = null
            },
            onDismiss = { codeEditTarget = null }
        )
    }
}

private fun slugFor(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "my-plugin" }

private const val JS_PLACEHOLDER = "// main.js — runs inside matching pages\n// hcj.config / hcj.fetch / hcj.badge / hcj.panel …\n"
private const val CSS_PLACEHOLDER = "/* style.css — injected into matching pages */\n"
private const val HTML_PLACEHOLDER = "<!-- panel.html — plugin popup UI hosted by the app -->\n"

private val NEW_PLUGIN_STUB = """
// main.js — runs inside matching pages.
// API: hcj.config · hcj.fetch · hcj.badge · hcj.panel · hcj.notify · hcj.on · hcj.emit

hcj.on('action', () => {
    // Fired when the user taps this plugin's entry.
});

""".trimStart()

@Composable
private fun InfoTab(
    isNew: Boolean,
    id: String, onId: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    nameError: Boolean,
    version: String, onVersion: (String) -> Unit,
    author: String, onAuthor: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    homepage: String, onHomepage: (String) -> Unit,
    matches: String, onMatches: (String) -> Unit,
    excludeMatches: String, onExcludes: (String) -> Unit,
    runAt: PluginRunAt, onRunAt: (PluginRunAt) -> Unit,
    permissions: Set<PluginPermission>, onPermissions: (Set<PluginPermission>) -> Unit,
    showEntry: Boolean, onShowEntry: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Field(Strings.pluginFieldName, name, onName, isError = nameError, errorText = Strings.pluginNameRequired)
        if (isNew) {
            Field(Strings.pluginFieldId, id, onId, placeholder = slugFor(name))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                Field(Strings.pluginFieldVersion, version, onVersion)
            }
            Box(modifier = Modifier.weight(1f)) {
                Field(Strings.author, author, onAuthor)
            }
        }
        Field(Strings.description, description, onDescription)
        Field("Homepage", homepage, onHomepage)
        Field(Strings.pluginFieldMatches, matches, onMatches, minLines = 3, hint = Strings.pluginFieldMatchesHint)
        Field(Strings.pluginFieldExcludes, excludeMatches, onExcludes, minLines = 2, hint = Strings.pluginFieldMatchesHint)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Strings.pluginFieldRunAt, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WtaChip(runAt == PluginRunAt.DOCUMENT_START, { onRunAt(PluginRunAt.DOCUMENT_START) }, Strings.runAtStart)
                WtaChip(runAt == PluginRunAt.DOCUMENT_END, { onRunAt(PluginRunAt.DOCUMENT_END) }, Strings.runAtEnd)
                WtaChip(runAt == PluginRunAt.DOCUMENT_IDLE, { onRunAt(PluginRunAt.DOCUMENT_IDLE) }, Strings.runAtIdle)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Strings.pluginFieldPermissions, style = MaterialTheme.typography.labelLarge)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                permChip(permissions, onPermissions, PluginPermission.STORAGE, Strings.pluginPermStorage)
                permChip(permissions, onPermissions, PluginPermission.FETCH, Strings.pluginPermFetch)
                permChip(permissions, onPermissions, PluginPermission.NOTIFY, Strings.pluginPermNotify)
                permChip(permissions, onPermissions, PluginPermission.BADGE, Strings.pluginPermBadge)
                permChip(permissions, onPermissions, PluginPermission.CLIPBOARD, Strings.pluginPermClipboard)
                permChip(permissions, onPermissions, PluginPermission.DOWNLOAD, Strings.pluginPermDownload)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(Strings.pluginShowEntry, modifier = Modifier.weight(1f))
            WtaSwitch(checked = showEntry, onCheckedChange = onShowEntry)
        }
    }
}

@Composable
private fun RowScope.permChip(
    permissions: Set<PluginPermission>,
    onPermissions: (Set<PluginPermission>) -> Unit,
    perm: PluginPermission,
    label: String
) {
    WtaChip(
        selected = perm in permissions,
        onClick = {
            onPermissions(if (perm in permissions) permissions - perm else permissions + perm)
        },
        label = label
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    minLines: Int = 1,
    isError: Boolean = false,
    errorText: String? = null,
    placeholder: String? = null,
    hint: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        PremiumTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            isError = isError,
            placeholder = placeholder?.let { p -> { Text(p) } },
            shape = RoundedCornerShape(WtaRadius.Button)
        )
        when {
            isError && errorText != null -> Text(
                errorText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            hint != null -> Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CodeTab(
    content: String,
    language: String,
    fileName: String,
    placeholder: String,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fileName,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(WtaRadius.Button))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable { onEdit() }
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (content.isNotBlank()) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 40,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
