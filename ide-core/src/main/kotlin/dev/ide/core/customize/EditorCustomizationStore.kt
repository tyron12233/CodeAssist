package dev.ide.core.customize

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Loads/saves editor customizations at two scopes and merges them into the effective view the editor reads.
 *
 * - **Global** ([globalFile]) lives under the shared config dir and applies to every project.
 * - **Project** ([projectFile]) lives under the open project's `.platform/` — checked in and shareable.
 *
 * Both are resolved through a provider that returns null when that scope has no home (no shared config dir on a
 * manager-less host; no project open) — a save to an unavailable scope is a silent no-op and its set reads as
 * empty. Effective = project overlaid on global overlaid on the shipped [DefaultCustomizations]. Reads are
 * tolerant (a missing or corrupt file loads as [CustomizationSet.EMPTY], never throws) and writes are crash-safe
 * (temp file + atomic move), so a killed process can't leave a half-written config. Stateless — it reads the
 * files on each call; the backend caches as needed.
 */
class EditorCustomizationStore(
    private val globalFile: () -> Path?,
    private val projectFile: () -> Path?,
) {

    companion object {
        /** The customization file's name in both the shared config dir and a project's `.platform/`. */
        const val FILE = "editor-customizations.json"

        /** A store over the standard locations — `<globalDir>/[FILE]` (per-user) and `<projectRoot>/.platform/[FILE]`
         *  (per-project). The single place those paths are formed, so every caller (backend, engine) agrees. */
        fun standard(globalDir: () -> Path?, projectRoot: () -> Path?): EditorCustomizationStore =
            EditorCustomizationStore(
                globalFile = { globalDir()?.resolve(FILE) },
                projectFile = { projectRoot()?.resolve(".platform")?.resolve(FILE) },
            )
    }

    private fun fileFor(scope: CustomizationScope): Path? = when (scope) {
        CustomizationScope.GLOBAL -> globalFile()
        CustomizationScope.PROJECT -> projectFile()
    }

    fun scopeSet(scope: CustomizationScope): CustomizationSet = fileFor(scope)?.let(::read) ?: CustomizationSet.EMPTY

    /** True when [scope] is writable (its home dir exists — a project is open, or a shared config dir is set). */
    fun scopeAvailable(scope: CustomizationScope): Boolean = fileFor(scope) != null

    fun save(scope: CustomizationScope, set: CustomizationSet) {
        write(fileFor(scope) ?: return, set)
    }

    // --- effective (merged) views the editor consumes ---

    /**
     * The symbol-bar keys to show: the first scope that DEFINES a list wins (project → global → shipped
     * defaults). A whole-list override — not an element merge — because the bar is an ordered sequence the user
     * arranges, so "the project set" or "the global set" reads more predictably than interleaving the two.
     */
    fun effectiveSymbols(): List<SymbolKeyDef> =
        projectFile()?.let { read(it).symbols } ?: globalFile()?.let { read(it).symbols } ?: DefaultCustomizations.SYMBOLS

    /**
     * The USER-defined macros for [languageId] (null = every language): the project set overlaid on the global
     * set, keyed by abbreviation (project wins), restricted to those whose [MacroDef.languages] admit the
     * language (empty = all). **Disabled entries are kept** — they're the "disable this built-in" override the
     * completion contributor acts on. The shipped [DefaultCustomizations.MACROS] are deliberately NOT folded in:
     * the language backends still emit those; this returns only the user's additions / overrides / disables.
     *
     * Filtering by language BEFORE keying keeps same-abbreviation-different-language built-ins (Java vs Kotlin
     * `ife`/`try`) from colliding — within one language an abbreviation is unique.
     */
    fun userMacros(languageId: String?): List<MacroDef> {
        fun admits(m: MacroDef) = languageId == null || m.languages.isEmpty() || languageId in m.languages
        val byAbbrev = LinkedHashMap<String, MacroDef>()
        globalFile()?.let { for (m in read(it).macros) if (admits(m)) byAbbrev[m.abbreviation] = m }
        projectFile()?.let { for (m in read(it).macros) if (admits(m)) byAbbrev[m.abbreviation] = m }
        return byAbbrev.values.toList()
    }

    // --- import / export (the on-disk JSON IS the share format) ---

    fun exportJson(scope: CustomizationScope): String = CustomizationCodec.encode(scopeSet(scope))

    /** Replaces [scope]'s set with the decoded [json]. A malformed payload decodes to empty (never throws);
     *  callers that need to reject bad input should pre-validate with [CustomizationCodec.decode]. */
    fun importJson(scope: CustomizationScope, json: String) = save(scope, CustomizationCodec.decode(json))

    // --- io ---

    private fun read(file: Path): CustomizationSet = runCatching {
        if (!Files.exists(file)) CustomizationSet.EMPTY else CustomizationCodec.decode(file.readText())
    }.getOrDefault(CustomizationSet.EMPTY)

    private fun write(file: Path, set: CustomizationSet) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            tmp.writeText(CustomizationCodec.encode(set))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Some filesystems (and some ART builds) don't support ATOMIC_MOVE — fall back to a plain replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
