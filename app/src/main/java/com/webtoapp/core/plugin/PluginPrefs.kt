package com.webtoapp.core.plugin

import android.content.Context

/**
 * Global user preferences for the plugin host surface — which entry form the
 * plugin launcher takes and which container hosts plugin panels. Per-app
 * overrides travel in the export config; these are the host-side defaults.
 */
class PluginPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var entryStyle: PluginEntryStyle
        get() = prefs.getString(KEY_ENTRY_STYLE, null)
            ?.let { runCatching { PluginEntryStyle.valueOf(it) }.getOrNull() }
            ?: PluginEntryStyle.TOOLBAR
        set(value) {
            prefs.edit().putString(KEY_ENTRY_STYLE, value.name).apply()
        }

    var panelStyle: PluginPanelStyle
        get() = prefs.getString(KEY_PANEL_STYLE, null)
            ?.let { runCatching { PluginPanelStyle.valueOf(it) }.getOrNull() }
            ?: PluginPanelStyle.BOTTOM_SHEET
        set(value) {
            prefs.edit().putString(KEY_PANEL_STYLE, value.name).apply()
        }

    companion object {
        private const val PREFS_NAME = "plugin_host_prefs"
        private const val KEY_ENTRY_STYLE = "entry_style"
        private const val KEY_PANEL_STYLE = "panel_style"
    }
}
