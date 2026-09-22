package com.webtoapp.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webtoapp.core.extension.ChromeExtensionParser
import com.webtoapp.core.extension.ExtensionFileManager
import com.webtoapp.core.i18n.Strings
import com.webtoapp.core.plugin.*
import com.webtoapp.ui.components.PremiumButton
import com.webtoapp.ui.components.PremiumTextField
import com.webtoapp.ui.design.*
import com.webtoapp.ui.plugin.kindLabel
import com.webtoapp.ui.plugin.pluginIcon
import kotlinx.coroutines.launch

/**
 * Unified plugin management: HCJ packages, userscripts and Chrome extensions
 * in one list. Kind is a badge, not a tab — the user picked this app for
 * plugins, not for taxonomies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToMarket: () -> Unit = {},
    onNavigateToAiDeveloper: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore.getInstance(context) }
    val importer = remember { PluginImporter(context) }
    val prefs = remember { PluginPrefs(context) }
    val extensionFileManager = remember { ExtensionFileManager(context) }

    val installed by store.plugins.collectAsStateWithLifecycle()
    val builtIns by store.builtInPlugins.collectAsStateWithLifecycle()
    val isLoading by store.isLoading.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showHostStyle by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Plugin?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    var chromePreview by remember { mutableStateOf<ChromeExtensionParser.ParseResult?>(null) }

    fun matches(p: Plugin): Boolean =
        searchQuery.isBlank() ||
            p.name.contains(searchQuery, ignoreCase = true) ||
            p.description.contains(searchQuery, ignoreCase = true)

    suspend fun importFrom(uri: Uri) {
        val name = importer.fileNameFor(uri)?.lowercase().orEmpty()
        when {
            name.endsWith(".user.js") || name.endsWith(".js") -> {
                when (val r = importer.importUserScript(uri)) {
                    is PluginImporter.ImportResult.Success ->
                        Toast.makeText(context, Strings.pluginImportSuccess(r.plugin.name), Toast.LENGTH_SHORT).show()
                    is PluginImporter.ImportResult.Error ->
                        Toast.makeText(context, Strings.moduleImportFailed(r.message), Toast.LENGTH_SHORT).show()
                }
            }
            name.endsWith(".crx") -> {
                when (val r = extensionFileManager.importChromeExtension(uri)) {
                    is ExtensionFileManager.ImportResult.ChromeExtension -> chromePreview = r.parseResult
                    is ExtensionFileManager.ImportResult.Error ->
                        Toast.makeText(context, Strings.moduleImportFailed(r.message), Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
            else -> {
                // .hcj / .zip / unknown: HCJ package first, Chrome zip as fallback.
                when (val r = importer.importHcj(uri)) {
                    is PluginImporter.ImportResult.Success ->
                        Toast.makeText(context, Strings.pluginImportSuccess(r.plugin.name), Toast.LENGTH_SHORT).show()
                    is PluginImporter.ImportResult.Error -> {
                        when (val cr = extensionFileManager.importChromeExtension(uri)) {
                            is ExtensionFileManager.ImportResult.ChromeExtension -> chromePreview = cr.parseResult
                            is ExtensionFileManager.ImportResult.Error ->
                                Toast.makeText(context, Strings.moduleImportFailed(r.message), Toast.LENGTH_SHORT).show()
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            try {
                importFrom(uri)
            } finally {
                isImporting = false
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(Strings.pluginsTitle) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Strings.back)
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = Strings.more)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(Strings.pluginImport) },
                                onClick = { showMenu = false; picker.launch("*/*") },
                                leadingIcon = { Icon(Icons.Default.FileOpen, null, Modifier.size(20.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.pluginNew) },
                                onClick = { showMenu = false; onNavigateToEditor(null) },
                                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(20.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.aiDevelop) },
                                onClick = { showMenu = false; onNavigateToAiDeveloper() },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text(Strings.communityExtStoreTitle) },
                                onClick = { showMenu = false; onNavigateToMarket() },
                                leadingIcon = { Icon(Icons.Default.Storefront, null, Modifier.size(20.dp)) }
                            )
                            WtaDivider()
                            DropdownMenuItem(
                                text = { Text(Strings.pluginHostStyle) },
                                onClick = { showMenu = false; showHostStyle = true },
                                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(20.dp)) }
                            )
                        }
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
                PremiumTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(Strings.searchPlugins) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = Strings.clear)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(WtaRadius.Button)
                )

                val shownInstalled = installed.filter(::matches)
                val shownBuiltIns = builtIns.filter(::matches)

                if (!isLoading && shownInstalled.isEmpty() && shownBuiltIns.isEmpty()) {
                    WtaFullEmptyState(
                        icon = Icons.Outlined.Extension,
                        title = Strings.pluginsEmpty,
                        message = Strings.pluginsEmptyHint,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (shownInstalled.isNotEmpty()) {
                            item(key = "hdr_installed") {
                                PluginSectionHeader(Strings.pluginSectionInstalled)
                            }
                            items(shownInstalled, key = { it.id }) { plugin ->
                                PluginRow(
                                    plugin = plugin,
                                    onToggle = { scope.launch { store.toggleEnabled(plugin.id) } },
                                    onPin = { scope.launch { store.setPinned(plugin.id, !plugin.pinned) } },
                                    onEdit = if (plugin.isScriptPlugin) {
                                        { onNavigateToEditor(plugin.id) }
                                    } else null,
                                    onExport = if (plugin.isScriptPlugin) {
                                        {
                                            scope.launch {
                                                importer.exportHcj(plugin)?.let { shareHcj(context, it) }
                                            }
                                        }
                                    } else null,
                                    onDelete = { pendingDelete = plugin }
                                )
                            }
                        }
                        if (shownBuiltIns.isNotEmpty()) {
                            item(key = "hdr_builtin") {
                                PluginSectionHeader(Strings.pluginSectionBuiltIn)
                            }
                            items(shownBuiltIns, key = { it.id }) { plugin ->
                                PluginRow(
                                    plugin = plugin,
                                    onToggle = { scope.launch { store.toggleEnabled(plugin.id) } },
                                    onPin = { scope.launch { store.setPinned(plugin.id, !plugin.pinned) } },
                                    onEdit = null,
                                    onExport = null,
                                    onDelete = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isImporting) {
        WtaAlertDialog(
            onDismissRequest = {},
            title = Strings.loading,
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(Strings.importing)
                }
            },
            confirmButton = {}
        )
    }

    showHostStyle.takeIf { it }?.let {
        var entry by remember { mutableStateOf(prefs.entryStyle) }
        var panel by remember { mutableStateOf(prefs.panelStyle) }
        WtaAlertDialog(
            onDismissRequest = { showHostStyle = false },
            title = Strings.pluginHostStyle,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Strings.pluginEntryStyle, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WtaChip(entry == PluginEntryStyle.TOOLBAR, {
                            entry = PluginEntryStyle.TOOLBAR; prefs.entryStyle = entry
                        }, Strings.entryStyleToolbar)
                        WtaChip(entry == PluginEntryStyle.MENU, {
                            entry = PluginEntryStyle.MENU; prefs.entryStyle = entry
                        }, Strings.entryStyleMenu)
                        WtaChip(entry == PluginEntryStyle.FLOATING_HANDLE, {
                            entry = PluginEntryStyle.FLOATING_HANDLE; prefs.entryStyle = entry
                        }, Strings.entryStyleFloating)
                    }
                    Text(Strings.pluginPanelStyleLabel, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WtaChip(panel == PluginPanelStyle.BOTTOM_SHEET, {
                            panel = PluginPanelStyle.BOTTOM_SHEET; prefs.panelStyle = panel
                        }, Strings.panelStyleSheet)
                        WtaChip(panel == PluginPanelStyle.FLOATING_WINDOW, {
                            panel = PluginPanelStyle.FLOATING_WINDOW; prefs.panelStyle = panel
                        }, Strings.panelStyleWindow)
                        WtaChip(panel == PluginPanelStyle.FULLSCREEN, {
                            panel = PluginPanelStyle.FULLSCREEN; prefs.panelStyle = panel
                        }, Strings.panelStyleFullscreen)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHostStyle = false }) { Text(Strings.confirm) }
            }
        )
    }

    pendingDelete?.let { plugin ->
        WtaAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = Strings.pluginDeleteConfirmTitle,
            text = plugin.name,
            confirmButton = {
                PremiumButton(onClick = {
                    scope.launch { store.removePlugin(plugin.id) }
                    pendingDelete = null
                }) { Text(Strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(Strings.btnCancel) }
            }
        )
    }

    chromePreview?.let { parseResult ->
        WtaAlertDialog(
            onDismissRequest = { chromePreview = null },
            title = Strings.installChromeExtension,
            content = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(parseResult.extensionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("v${parseResult.extensionVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (parseResult.extensionDescription.isNotBlank()) {
                        Text(parseResult.extensionDescription, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${Strings.contentScripts}: ${parseResult.modules.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (parseResult.unsupportedPermissions.isNotEmpty()) {
                        Text(
                            "${Strings.unsupportedApis}: ${parseResult.unsupportedPermissions.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                PremiumButton(onClick = {
                    scope.launch {
                        when (val r = importer.installChromeRecords(parseResult.modules)) {
                            is PluginImporter.ImportResult.Success ->
                                Toast.makeText(context, Strings.pluginImportSuccess(parseResult.extensionName), Toast.LENGTH_SHORT).show()
                            is PluginImporter.ImportResult.Error ->
                                Toast.makeText(context, Strings.moduleImportFailed(r.message), Toast.LENGTH_SHORT).show()
                        }
                        chromePreview = null
                    }
                }) { Text(Strings.install) }
            },
            dismissButton = {
                TextButton(onClick = { chromePreview = null }) { Text(Strings.btnCancel) }
            }
        )
    }
}

@Composable
private fun PluginSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
private fun PluginRow(
    plugin: Plugin,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onEdit: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    WtaCard(contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(WtaRadius.Control))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    pluginIcon(plugin.icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            kindLabel(plugin.kind),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (plugin.pinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            WtaSwitch(checked = plugin.enabled, onCheckedChange = { onToggle() })
            Box {
                var rowMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { rowMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = Strings.more, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                    onEdit?.let { edit ->
                        DropdownMenuItem(
                            text = { Text(Strings.edit) },
                            onClick = { rowMenu = false; edit() },
                            leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(20.dp)) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (plugin.pinned) Strings.pluginUnpin else Strings.pluginPin) },
                        onClick = { rowMenu = false; onPin() },
                        leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(20.dp)) }
                    )
                    onExport?.let { export ->
                        DropdownMenuItem(
                            text = { Text(Strings.pluginExportHcj) },
                            onClick = { rowMenu = false; export() },
                            leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(20.dp)) }
                        )
                    }
                    onDelete?.let { del ->
                        WtaDivider()
                        DropdownMenuItem(
                            text = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                            onClick = { rowMenu = false; del() },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun shareHcj(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, file.name)
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "share failed", Toast.LENGTH_SHORT).show()
    }
}
