package dev.ide.core.completion

import dev.ide.plugin.PluginCapabilities
import dev.ide.core.plugins.PluginProject
import dev.ide.index.IndexService
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.plugin.PLUGIN_API_VERSION

/**
 * Completion inside a plugin's packaged manifest, `res/raw/codeassist_plugin.toml`.
 *
 * Everything offered here is read from something real rather than from a hardcoded list of guesses: the keys
 * are the ones [dev.ide.core.plugins.PluginManifestToml] actually reads, `dependsOn` completes the plugin ids
 * the running IDE has loaded, and `entryPoints` / `uiEntryPoints` complete classes in the project that
 * implement the engine and UI facet interfaces, taken from the subtype index. A manifest is short,
 * hand-written, and only validated on someone else's device, so knowing the accepted spelling while typing is
 * most of the value.
 *
 * The file is plain text as far as the editor is concerned, so the context is read from the line at the caret
 * rather than from a DOM.
 */
class PluginManifestCompletion(
    /** Plugin ids that can be depended on: the running IDE's loaded plugins. */
    private val knownPluginIds: () -> List<String>,
    /** The open project's index, for the `Plugin` implementations in it. Null when no project is open. */
    private val index: () -> IndexService?,
) : CompletionContributor {

    override val id = "codeassist.pluginManifest"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val name = params.document.file.path.substringAfterLast('/')
        if (name != PluginProject.MANIFEST_NAME) return

        val text = params.document.text
        val lineStart = text.lastIndexOf('\n', (params.offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val head = text.subSequence(lineStart, params.offset).toString()

        when (val key = keyBeingValued(head)) {
            null -> keys(params, result)
            "dependsOn" -> knownPluginIds().forEach { id ->
                add(params, result, id, CompletionItemKind.VARIABLE, "an installed or built-in plugin id")
            }
            "capabilities" -> CAPABILITIES.forEach { (value, detail) ->
                add(params, result, value, CompletionItemKind.VARIABLE, detail)
            }
            "entryPoints" -> implementationsOf(PLUGIN_INTERFACE).forEach { fqcn ->
                add(params, result, fqcn, CompletionItemKind.CLASS, "implements $PLUGIN_INTERFACE")
            }
            "uiEntryPoints" -> implementationsOf(UI_PLUGIN_INTERFACE).forEach { fqcn ->
                add(params, result, fqcn, CompletionItemKind.CLASS, "implements $UI_PLUGIN_INTERFACE")
            }
            "apiVersion" -> add(
                params, result, PLUGIN_API_VERSION.toString(), CompletionItemKind.KEYWORD,
                "the plugin API this IDE loads",
            )
            else -> if (key in KEYS) Unit // a value with nothing to suggest
        }
    }

    /** The manifest's keys, each with what it means, for a caret at the start of a line. */
    private fun keys(params: CompletionParams, result: CompletionResultSet) {
        for ((key, detail) in KEYS) {
            if (!params.prefixMatches(key)) continue
            result.addElement(
                CompletionItem(
                    label = key,
                    insertText = "$key = ",
                    kind = CompletionItemKind.FIELD,
                    detail = detail,
                    container = "[plugin]",
                )
            )
        }
    }

    /** Classes in the open project implementing [supertype], so an entry point can be picked rather than typed. */
    private fun implementationsOf(supertype: String): List<String> {
        val index = index()?.takeIf { it.status.ready } ?: return emptyList()
        return SubtypeIndex.ALL
            .flatMap { index.exact<SubtypeValue>(it, SubtypeIndex.key(supertype)).toList() }
            .map { it.fqn }
            .distinct()
            .sorted()
    }

    private fun add(
        params: CompletionParams,
        result: CompletionResultSet,
        value: String,
        kind: CompletionItemKind,
        detail: String,
    ) {
        if (!params.prefixMatches(value)) return
        result.addElement(CompletionItem(label = value, insertText = value, kind = kind, detail = detail))
    }

    /**
     * The key whose value the caret sits in, or null when the caret is at a key position. A manifest is one
     * flat table of `key = value` (values are strings, numbers, or single-line arrays), so the text before
     * the caret on this line is enough: an `=` earlier on the line means a value, and an unclosed `[` from a
     * preceding line means an array value continues.
     */
    private fun keyBeingValued(head: String): String? {
        val equals = head.indexOf('=')
        if (equals < 0) return null
        val key = head.take(equals).trim()
        return key.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
    }

    private companion object {
        const val PLUGIN_INTERFACE = "dev.ide.plugin.Plugin"
        const val UI_PLUGIN_INTERFACE = "dev.ide.plugin.ui.UiPlugin"

        /** Exactly the keys the manifest parser reads. `essential` and `trusted` are left out on purpose:
         *  the parser ignores them whatever the file says, so offering them would invite a wasted edit. */
        val KEYS = linkedMapOf(
            "id" to "the plugin's identity; letters, digits, '.', '-', '_'",
            "name" to "display name, shown in Settings > Plugins",
            "version" to "the plugin's own version",
            "apiVersion" to "must equal the IDE's plugin API version",
            "description" to "one line, shown under the name",
            "entryPoints" to "fully-qualified classes implementing Plugin",
            "uiEntryPoints" to "fully-qualified classes implementing UiPlugin (Compose UI)",
            "dependsOn" to "plugin ids that must load first",
            "capabilities" to "what the plugin declares it does",
            "minHostVersion" to "oldest CodeAssist this plugin runs on",
        )

        /**
         * What each capability in [PluginCapabilities.KNOWN] means, for the completion detail line. The set
         * itself lives in the SPI, so this offers exactly what the manifest checks accept; a capability added
         * there without a line here is offered with no detail rather than not offered (see the test).
         */
        val CAPABILITIES: Map<String, String> = PluginCapabilities.KNOWN.associateWith { capability ->
            when (capability) {
                PluginCapabilities.UI_ACTION -> "contributes a command to the palette or menus"
                PluginCapabilities.UI_SETTINGS_PAGE -> "contributes a Settings category"
                PluginCapabilities.UI_EDITOR_ACTION -> "contributes an action at the caret"
                PluginCapabilities.UI_TOOL_WINDOW -> "contributes a tool window"
                PluginCapabilities.UI_SCREEN -> "contributes a full screen"
                PluginCapabilities.UI_OVERLAY -> "contributes an app-wide overlay"
                PluginCapabilities.BUILD_TASK -> "adds steps to the build"
                PluginCapabilities.BUILD_SOURCE_GENERATOR -> "generates source at build time"
                PluginCapabilities.BUILD_RUN_TASK -> "contributes a Run-picker row"
                PluginCapabilities.FS_READ -> "reads project files"
                PluginCapabilities.FS_WRITE -> "changes project files"
                PluginCapabilities.NET -> "makes network requests"
                else -> ""
            }
        }
    }
}
