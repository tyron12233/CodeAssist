package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.customize.CustomizationCodec
import dev.ide.core.customize.CustomizationScope
import dev.ide.core.customize.DefaultCustomizations
import dev.ide.core.customize.EditorCustomizationStore
import dev.ide.core.customize.MacroDef
import dev.ide.core.customize.MacroVariables
import dev.ide.core.customize.SymbolKeyDef
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.template.DefaultSnippetEngine
import dev.ide.lang.template.SnippetContext
import dev.ide.lang.template.SnippetTemplate
import dev.ide.lang.template.SnippetVariableResolver
import dev.ide.ui.backend.CustomizationService
import dev.ide.ui.backend.UiMacro
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.vfs.VirtualFile
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * [CustomizationService] over an [EditorCustomizationStore]: the global set lives in the shared config dir
 * (`<sharedRoot>/editor-customizations.json`, applies to every project) and the project set under the open
 * project's `.platform/`. Both resolve lazily so a manager-less host (no shared dir) or the picker (no project)
 * simply reports that scope unavailable rather than failing. Stateless — each call reads the store, which reads
 * the files; the UI re-fetches after an edit.
 */
internal class CustomizationBackend(private val ctx: BackendContext) : CustomizationService {

    private val store = EditorCustomizationStore.standard(
        globalDir = { ctx.manager?.sharedRoot },
        projectRoot = { ctx.servicesOrNull?.workspaceRoot },
    )

    override fun symbolKeys(): List<UiSymbolKey> = store.effectiveSymbols().toUi()

    override fun scopedSymbols(scope: String): List<UiSymbolKey>? = store.scopeSet(scope.toScope()).symbols?.toUi()

    override fun setScopedSymbols(scope: String, symbols: List<UiSymbolKey>) {
        val s = scope.toScope()
        store.save(s, store.scopeSet(s).copy(symbols = symbols.map { it.toDef() }))
    }

    override fun clearScopedSymbols(scope: String) {
        val s = scope.toScope()
        store.save(s, store.scopeSet(s).copy(symbols = null))
    }

    override fun defaultSymbols(): List<UiSymbolKey> = DefaultCustomizations.SYMBOLS.toUi()

    override fun scopeAvailable(scope: String): Boolean = store.scopeAvailable(scope.toScope())

    override fun exportScope(scope: String): String = store.exportJson(scope.toScope())

    override fun importScope(scope: String, json: String): Boolean {
        // Reject anything that isn't at least a JSON object up front; a malformed-but-`{` payload decodes to an
        // empty set (the codec is tolerant), which the user can undo via Reset, so it isn't treated as an error.
        if (!json.trimStart().startsWith("{")) return false
        store.save(scope.toScope(), CustomizationCodec.decode(json))
        return true
    }

    /** Tallies the candidate coding symbols present in the file on disk and returns the most-frequent ones not
     *  already on the bar. Reads the saved file (best-effort) — unsaved edits aren't reflected, which is fine
     *  for a suggestion. */
    override fun suggestSymbols(filePath: String, existing: List<UiSymbolKey>): List<UiSymbolKey> {
        val text = runCatching { Paths.get(filePath).takeIf { it.exists() }?.readText() }.getOrNull() ?: return emptyList()
        val have = existing.mapTo(HashSet()) { it.insert }
        val counts = HashMap<Char, Int>()
        for (c in text) if (c in CANDIDATES) counts[c] = (counts[c] ?: 0) + 1
        return counts.entries
            .filter { it.key.toString() !in have }
            .sortedByDescending { it.value }
            .take(SUGGEST_LIMIT)
            .map { UiSymbolKey(it.key.toString(), it.key.toString()) }
    }

    override fun scopedMacros(scope: String): List<UiMacro> = store.scopeSet(scope.toScope()).macros.map { it.toUi() }

    override fun defaultMacros(): List<UiMacro> = DefaultCustomizations.MACROS.map { it.toUi() }

    override fun macroVariables(): List<String> = MacroVariables.NAMES

    override fun setScopedMacros(scope: String, macros: List<UiMacro>) {
        val s = scope.toScope()
        store.save(s, store.scopeSet(s).copy(macros = macros.map { it.toDef() }))
    }

    override fun previewMacro(template: String): String = runCatching {
        DefaultSnippetEngine().expand(
            SnippetTemplate(template),
            SnippetContext(PreviewDocument, offset = 0, indent = ""),
            PreviewResolver,
        ).text
    }.getOrDefault("")

    private fun List<SymbolKeyDef>.toUi(): List<UiSymbolKey> =
        map { UiSymbolKey(it.label, it.insert, it.pinned, it.action) }

    private fun UiSymbolKey.toDef(): SymbolKeyDef = SymbolKeyDef(label, insert, pinned, action)

    private fun MacroDef.toUi(): UiMacro = UiMacro(abbreviation, template, description, languages, enabled, builtIn, receiverType, static)
    private fun UiMacro.toDef(): MacroDef = MacroDef(abbreviation, template, description, languages, enabled, builtIn, receiverType?.takeIf { it.isNotBlank() }, static)

    private fun String.toScope(): CustomizationScope =
        if (this == CustomizationService.PROJECT) CustomizationScope.PROJECT else CustomizationScope.GLOBAL

    /** A throwaway document for [previewMacro] — [PreviewResolver] fills variables from samples and never reads
     *  it, so `file` can throw. */
    private object PreviewDocument : DocumentSnapshot {
        override val file: VirtualFile get() = error("preview: document is unused")
        override val version = 1L
        override val text: CharSequence = ""
        override fun length() = 0
    }

    /** Sample variable values for the editor's macro preview (no real file/selection in that context). */
    private object PreviewResolver : SnippetVariableResolver {
        override fun resolve(name: String, ctx: SnippetContext): String? = when (name.uppercase()) {
            "FILE", "FILENAME", "TM_FILENAME" -> "Example.kt"
            "CLASS", "FILE_BASE", "FILENAME_BASE" -> "Example"
            "FILEPATH", "TM_FILEPATH", "TM_DIRECTORY" -> "/src/Example.kt"
            "EXPR", "CURRENT_EXPRESSION", "WORD", "CURRENT_WORD" -> "value"
            "SELECTION", "TM_SELECTED_TEXT" -> "selection"
            "LINE", "TM_LINE_NUMBER" -> "42"
            "INDENT" -> ""
            "DATE", "CURRENT_DATE" -> LocalDate.now().toString()
            "TIME", "CURRENT_TIME" -> "12:00:00"
            "DATETIME" -> LocalDate.now().toString() + "T12:00:00"
            "YEAR", "CURRENT_YEAR" -> LocalDate.now().year.toString()
            "MONTH", "CURRENT_MONTH" -> "%02d".format(LocalDate.now().monthValue)
            "DAY", "CURRENT_DAY" -> "%02d".format(LocalDate.now().dayOfMonth)
            "USER", "USERNAME" -> System.getProperty("user.name") ?: "you"
            "UUID" -> "00000000-0000-0000-0000-000000000000"
            else -> null
        }
    }

    private companion object {
        const val SUGGEST_LIMIT = 8
        // Common coding punctuation/operators worth surfacing (escaped: backslash, dollar, quote).
        val CANDIDATES: Set<Char> = "{}()[]<>;:,.=+-*/%&|!?@#_\\~^\$\"'".toSet()
    }
}
