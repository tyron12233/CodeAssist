package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.core.RenameInfo
import dev.ide.core.RenameOutcome
import dev.ide.core.runSync
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.java.JavaSourceAnalyzer
import dev.ide.lang.java.navigate.JavaNavKind
import dev.ide.lang.java.navigate.JavaNavLocation
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.jdt.rename.JdtRename
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinUsageTarget
import dev.ide.model.Module
import dev.ide.vfs.local.fileFor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * WORKSPACE-scoped engine service: Find Usages and Rename for code symbols, carved out of
 * [dev.ide.core.IdeServices].
 *
 * Java resolves the symbol under the caret through its own backend (a JDT binding key or an IntelliJ-PSI
 * target) and sweeps the project's `.java` files. Kotlin identifies a symbol by its declaration and sweeps the
 * `.kt` files ([KotlinEditorService.usages]). The two meet in the middle: a Java symbol's Kotlin references are
 * found by its declaration's location, so renaming a Java class also updates the Kotlin code that uses it.
 *
 * A rename applies a multi-file edit to disk + the editor overlay, renames the backing file when a top-level
 * type's name matched it, and fires ONE batched mutation event so the invalidation/reindex runs exactly once.
 * File/package move/copy/delete are a separate concern and stay on the engine.
 */
internal class RefactorService(
    private val ctx: EngineContext,
    private val kotlin: KotlinEditorService,
) {

    /** A rename target distilled from either backend: name/kind for the UI, the flags the orchestration needs,
     *  and the opaque backend-specific [token] fed back to [renameRefsIn]. */
    private data class RenameHit(
        val oldName: String, val kind: String, val fileLocal: Boolean, val isType: Boolean, val token: Any,
    )

    private fun isKotlin(file: Path): Boolean = ctx.languageFor(file) == KotlinLanguageBackend.LANGUAGE_ID

    /** The rename target under [offset] in [file]/[text], via whichever Java backend edits it (JDT or the
     *  IntelliJ-PSI backend), or null when not renameable. */
    private fun renameHitAt(module: Module, file: Path, text: String, offset: Int): RenameHit? {
        val vf = ctx.store.vfs.fileFor(file)
        val name = file.fileName?.toString() ?: "Renamed.java"
        return when (val a = ctx.analyzerFor(module, LanguageId("java"))) {
            is JdtSourceAnalyzer -> JdtRename.targetAt(a.parse(vf, text), offset)
                ?.let { RenameHit(it.oldName, it.kind, it.fileLocal, it.isType, it) }
            is JavaSourceAnalyzer -> a.renameTargetAt(name, text, offset)
                ?.let { RenameHit(it.oldName, it.kind, it.fileLocal, it.isType, it) }
            else -> null
        }
    }

    /** Reference ranges in [file]/[text] for [token] (from [renameHitAt]), via the file's Java backend. */
    private fun renameRefsIn(module: Module, file: Path, text: String, token: Any): List<TextRange> {
        val vf = ctx.store.vfs.fileFor(file)
        val name = file.fileName?.toString() ?: "Renamed.java"
        return when (val a = ctx.analyzerFor(module, LanguageId("java"))) {
            is JdtSourceAnalyzer ->
                (token as? dev.ide.lang.jdt.rename.RenameTarget)?.let { JdtRename.referencesIn(a.parse(vf, text), it) } ?: emptyList()
            is JavaSourceAnalyzer ->
                (token as? dev.ide.lang.java.rename.JavaRenameTarget)?.let { a.renameReferencesIn(name, text, it) } ?: emptyList()
            else -> emptyList()
        }
    }

    /** Every Java reference to [target] project-wide (its declaration included, as the backends report it). */
    private fun javaRefs(module: Module, file: Path, text: String, target: RenameHit): List<Pair<Path, TextRange>> {
        val fileAbs = file.toAbsolutePath().normalize()
        if (target.fileLocal) return renameRefsIn(module, file, text, target.token).map { fileAbs to it }
        val out = ArrayList<Pair<Path, TextRange>>()
        for (cand in ctx.projectJavaFiles()) {
            val candAbs = cand.toAbsolutePath().normalize()
            val candText = textOf(candAbs) ?: continue
            if (!candText.contains(target.oldName)) continue // cheap pre-filter before the binding parse
            val m = ctx.moduleForFile(cand) ?: continue
            renameRefsIn(m, cand, candText, target.token).forEach { out += candAbs to it }
        }
        return out
    }

    /**
     * The Kotlin references to the Java symbol [target] under [offset]: it is located by Java's own go-to-
     * declaration (the caret on its name answers itself), which is where Kotlin's resolution lands too.
     * Empty for a file-local symbol, or one declared in a library.
     */
    private fun kotlinRefsToJava(module: Module, file: Path, text: String, offset: Int, target: RenameHit): List<Pair<Path, TextRange>> {
        if (target.fileLocal || ctx.projectKotlinFiles().isEmpty()) return emptyList()
        val (path, at) = javaDeclarationAt(module, file, text, offset) ?: return emptyList()
        val importFqn = if (target.isType) javaTypeFqn(path, target.oldName) else null
        val kt = kotlin.javaDeclarationTarget(file, path, at, target.oldName, importFqn) ?: return emptyList()
        return kotlin.usages(kt)
    }

    /**
     * Where the Java symbol under [offset] is declared (file, name offset), when in project source. Go-to-
     * declaration answers for a reference; on the declaration's own name it answers nothing, and then the name
     * under the caret IS the declaration.
     */
    private fun javaDeclarationAt(module: Module, file: Path, text: String, offset: Int): Pair<Path, Int>? {
        val java = ctx.analyzerFor(module, LanguageId("java")) as? JavaSourceAnalyzer ?: return null
        val fileAbs = file.toAbsolutePath().normalize()
        val decl = runCatching { java.navigationTargets(ctx.store.vfs.fileFor(file), text, offset, JavaNavKind.DECLARATION) }
            .getOrNull()?.firstOrNull()
        return when (val loc = decl?.location) {
            is JavaNavLocation.ThisFile -> fileAbs to loc.offset
            is JavaNavLocation.SourceFile -> Paths.get(loc.path).toAbsolutePath().normalize() to loc.offset
            is JavaNavLocation.Library -> null // a library symbol: no project declaration to find Kotlin uses of
            // Nothing, or the class this very buffer declares (an open file resolves to its generated stand-in).
            else -> identifierStart(text, offset)?.let { fileAbs to it }
        }
    }

    /**
     * Find Usages: [findReferences] without the symbol's own declaration(s), which is what the editor lists.
     */
    fun usages(file: Path, text: String, offset: Int): List<Pair<Path, TextRange>> {
        if (ctx.analysisDisabled(file)) return emptyList()
        pluginReferences(file, text, offset, includeDeclaration = false)?.let { return it }
        if (isKotlin(file)) return kotlin.usageTarget(file, text, offset)?.let(::kotlinTargetUsages).orEmpty()
        val refs = findReferences(file, text, offset)
        if (refs.isEmpty()) return refs
        val module = ctx.moduleForFile(file) ?: return refs
        val decl = runCatching { javaDeclarationAt(module, file, text, offset) }.getOrNull() ?: return refs
        return refs.filterNot { (p, r) -> p == decl.first && r.start == decl.second }
    }

    /**
     * Highlight-usages-at-caret: the names in [file]'s own buffer that refer to the symbol under [offset], read,
     * written or declared. Null when this file's language has no resolved answer (the JDT backend, XML, …) or
     * the caret is on nothing that resolves; the editor then falls back to its textual whole-word scan.
     */
    fun usageHighlights(file: Path, text: String, offset: Int): List<dev.ide.lang.highlight.UsageHighlight>? {
        if (ctx.analysisDisabled(file)) return null
        val highlighters = pluginProviders(file, dev.ide.analysis.REFERENCE_PROVIDER_EP) { it.languages }
        if (highlighters.isNotEmpty()) {
            val target = ctx.pluginTarget(file, text) ?: return null
            return highlighters.firstNotNullOfOrNull { p ->
                runCatching { runSync { p.highlights(target, offset) } }.getOrNull()
            }
        }
        if (isKotlin(file)) return kotlin.usageHighlights(file, text, offset)
        if (!file.toString().endsWith(".java")) return null
        val module = ctx.moduleForFile(file) ?: return null
        val java = ctx.analyzerFor(module, LanguageId("java")) as? JavaSourceAnalyzer ?: return null
        return try {
            java.usageHighlightsAt(ctx.store.vfs.fileFor(file), text, offset)
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file)); null
        }
    }

    /** Where the identifier covering [offset] (or ending there) starts, or null at none. */
    private fun identifierStart(text: String, offset: Int): Int? {
        var i = offset.coerceIn(0, text.length)
        if (i < text.length && !Character.isJavaIdentifierPart(text[i]) && i > 0 && Character.isJavaIdentifierPart(text[i - 1])) i--
        if (i >= text.length || !Character.isJavaIdentifierPart(text[i])) return null
        while (i > 0 && Character.isJavaIdentifierPart(text[i - 1])) i--
        return i.takeIf { Character.isJavaIdentifierStart(text[it]) }
    }

    /** A Kotlin target's references: in Kotlin, and in the Java code that reaches it through its light class. */
    private fun kotlinTargetUsages(target: KotlinUsageTarget): List<Pair<Path, TextRange>> =
        kotlin.usages(target) + javaRefsToKotlin(target)

    /**
     * The Java references to Kotlin [target], each the range of the name Java writes: the declaration's own, or
     * for a property its accessor (`getName()` / `setName()`, or `isOpen()` / `setOpen()` for an `isOpen`).
     * Every whole-word occurrence in a project Java file is resolved with Java's go-to-declaration, which lands a
     * light-class member on its Kotlin declaration, and kept when that is the target's. A local or private Kotlin
     * symbol is out of Java's reach.
     */
    private fun javaRefsToKotlin(target: KotlinUsageTarget): List<Pair<Path, TextRange>> {
        if (target.scope != null || !target.renameable) return emptyList()
        val names = javaNamesOf(target)
        val out = ArrayList<Pair<Path, TextRange>>()
        for (f in ctx.projectJavaFiles()) {
            val path = f.toAbsolutePath().normalize()
            val text = textOf(path) ?: continue
            for (name in names) {
                if (!text.contains(name)) continue
                for (m in Regex("""\b${Regex.escape(name)}\b""").findAll(text)) {
                    val decl = runCatching { ctx.javaDeclarationAt(path, text, m.range.first) }.getOrNull() ?: continue
                    if (target.isDeclaredAt(decl.first.toString(), decl.second)) out += path to TextRange(m.range.first, m.range.last + 1)
                }
            }
        }
        return out
    }

    /** The names Java writes [target] by: its own, and for a property its getter and setter. */
    private fun javaNamesOf(target: KotlinUsageTarget): List<String> =
        if (target.kind == "property") listOf(target.name) + accessorsOf(target.name).toList() else listOf(target.name)

    /** A Kotlin property's JVM getter and setter names: `getName`/`setName`, or `isOpen`/`setOpen` for `isOpen`. */
    private fun accessorsOf(property: String): Pair<String, String> =
        if (property.length > 2 && property.startsWith("is") && property[2].isUpperCase()) property to "set" + property.substring(2)
        else "get" + property.replaceFirstChar { it.uppercaseChar() } to "set" + property.replaceFirstChar { it.uppercaseChar() }

    /** `package.Name` for a top-level Java type [name] declared in [path]. */
    private fun javaTypeFqn(path: Path, name: String): String {
        val pkg = textOf(path)?.let { PACKAGE.find(it)?.groupValues?.get(1) }.orEmpty()
        return if (pkg.isEmpty()) name else "$pkg.$name"
    }

    /**
     * Every reference to the symbol under [offset] in [file]/[text], project-wide, as (file, range) pairs:
     * Kotlin usages for a Kotlin file; for a Java file, the Java references (its declaration included) plus the
     * Kotlin code that uses it. Read-only. Empty when the position is not a resolvable symbol.
     */
    fun findReferences(file: Path, text: String, offset: Int): List<Pair<Path, TextRange>> {
        if (ctx.analysisDisabled(file)) return emptyList()
        pluginReferences(file, text, offset, includeDeclaration = true)?.let { return it }
        if (isKotlin(file)) return kotlin.usageTarget(file, text, offset)?.let(::kotlinTargetUsages).orEmpty()
        if (!file.toString().endsWith(".java")) return emptyList()
        val module = ctx.moduleForFile(file) ?: return emptyList()
        return try {
            val target = renameHitAt(module, file, text, offset) ?: return emptyList()
            javaRefs(module, file, text, target) + kotlinRefsToJava(module, file, text, offset, target)
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file)); emptyList()
        }
    }

    /** The rename target under [offset] in [file]'s buffer (old name + kind), or null when not renameable. */
    fun prepareRename(file: Path, text: String, offset: Int): RenameInfo? {
        if (ctx.analysisDisabled(file)) return null
        val renamers = pluginProviders(file, dev.ide.analysis.RENAME_PROVIDER_EP) { it.languages }
        if (renamers.isNotEmpty()) {
            val target = ctx.pluginTarget(file, text) ?: return null
            return renamers.firstNotNullOfOrNull { p -> runCatching { runSync { p.prepare(target, offset) } }.getOrNull() }
                ?.let { RenameInfo(it.name, it.kind) }
        }
        if (isKotlin(file)) return kotlin.usageTarget(file, text, offset, forRename = true)?.takeIf { it.renameable }?.let { RenameInfo(it.name, it.kind) }
        if (!file.toString().endsWith(".java")) return null
        val module = ctx.moduleForFile(file) ?: return null
        return try {
            renameHitAt(module, file, text, offset)?.let { RenameInfo(it.oldName, it.kind) }
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file)); null
        }
    }

    /**
     * Rename the symbol under the caret to [newName] across the whole project, Java and Kotlin, then apply the
     * multi-file edit to disk + the editor overlay. When a top-level type whose name matches its file is
     * renamed, the file is renamed too (its new path is returned so the editor can reopen it). Re-indexes and
     * invalidates analyzers.
     */
    suspend fun rename(file: Path, text: String, offset: Int, newName: String): RenameOutcome {
        val name = newName.trim()
        if (ctx.analysisDisabled(file)) return RenameOutcome(false, "Code analysis is unavailable for this file.")
        val renamers = pluginProviders(file, dev.ide.analysis.RENAME_PROVIDER_EP) { it.languages }
        if (renamers.isNotEmpty()) return renameThroughPlugin(renamers, file, text, offset, name)
        return if (isKotlin(file)) renameKotlin(file, text, offset, name) else renameJava(file, text, offset, name, newName)
    }

    private suspend fun renameJava(file: Path, text: String, offset: Int, name: String, typed: String): RenameOutcome {
        if (!ctx.isValidJavaIdentifier(name)) return RenameOutcome(false, "'$typed' is not a valid Java identifier.")
        val module = ctx.moduleForFile(file) ?: return RenameOutcome(false, "This file is not in a source module.")
        val target: RenameHit
        val refs: List<Pair<Path, TextRange>>
        try {
            target = renameHitAt(module, file, text, offset)
                ?: return RenameOutcome(false, "Place the caret on a symbol to rename.")
            if (name == target.oldName) return RenameOutcome(false, "The new name is the same as the current one.")
            refs = javaRefs(module, file, text, target) + kotlinRefsToJava(module, file, text, offset, target)
        } catch (e: LinkageError) {
            ctx.markAnalysisUnavailable(ctx.languageFor(file))
            return RenameOutcome(false, "Java analysis is unavailable.")
        }
        val fileAbs = file.toAbsolutePath().normalize()
        val renameFile = if (target.isType && fileAbs.fileName?.toString() == "${target.oldName}.java") fileAbs else null
        return apply(target.oldName, name, refs, renameFile, ".java", note = null, focal = fileAbs to text)
    }

    private suspend fun renameKotlin(file: Path, text: String, offset: Int, name: String): RenameOutcome {
        if (!isValidKotlinName(name)) return RenameOutcome(false, "'$name' is not a valid Kotlin name.")
        val target = kotlin.usageTarget(file, text, offset, forRename = true)
            ?: return RenameOutcome(false, "Place the caret on a symbol to rename.")
        if (!target.renameable) return RenameOutcome(false, "'${target.name}' is not declared in this project's Kotlin code.")
        if (name == target.name) return RenameOutcome(false, "The new name is the same as the current one.")
        val declarations = target.declarations.map { (p, r) -> Paths.get(p).toAbsolutePath().normalize() to r }
        // A Java class overriding the member is part of its family: its declaration is in [declarations], and the
        // Java code calling that override is found by the Java side, from the override's own name.
        val javaOverrideRefs = declarations.filter { it.first.toString().endsWith(".java") }.flatMap { (path, r) ->
            val m = ctx.moduleForFile(path) ?: return@flatMap emptyList()
            val t = textOf(path) ?: return@flatMap emptyList()
            runCatching { renameHitAt(m, path, t, r.start)?.let { javaRefs(m, path, t, it) } }.getOrNull().orEmpty()
        }
        val refs = declarations + kotlinTargetUsages(target) + javaOverrideRefs
        // Java calls a property through its accessors, which take the new name too.
        val accessorRenames = if (target.kind == "property") {
            val (oldGet, oldSet) = accessorsOf(target.name)
            val (newGet, newSet) = accessorsOf(name)
            mapOf(oldGet to newGet, oldSet to newSet)
        } else emptyMap()
        val renameFile = target.fileNamedAfter?.let { Paths.get(it).toAbsolutePath().normalize() }
        return apply(
            target.name, name, refs, renameFile, ".kt", note = null, focal = file.toAbsolutePath().normalize() to text,
            alsoReplace = accessorRenames,
        )
    }

    /**
     * Rewrite every range in [refs] (each holding [oldName]) to [newName], file by file, to disk and the live
     * overlay together; rename [renameFile] to `newName + extension` when given; report one batched mutation.
     * A range whose text is no longer [oldName] (an edit since it was found) is skipped, never overwritten.
     */
    private suspend fun apply(
        oldName: String, newName: String, refs: List<Pair<Path, TextRange>>, renameFile: Path?, extension: String, note: String?,
        /** The file the rename started from and the editor's text of it, which is the text the ranges were found in. */
        focal: Pair<Path, String>,
        /** Other texts a range may hold and what each becomes: a property's Java accessor names. */
        alsoReplace: Map<String, String> = emptyMap(),
    ): RenameOutcome {
        val byPath = LinkedHashMap<Path, MutableSet<TextRange>>()
        refs.forEach { (p, r) -> byPath.getOrPut(p) { LinkedHashSet() } += r }
        if (byPath.isEmpty()) return RenameOutcome(false, "No occurrences of '$oldName' found.")

        var occurrences = 0
        val changed = ArrayList<Path>()
        // Each file's edits descending, so earlier offsets stay valid while later ones are replaced.
        for ((path, ranges) in byPath) {
            val current = (if (path == focal.first) focal.second else textOf(path)) ?: continue
            val sb = StringBuilder(current)
            var applied = 0
            for (r in ranges.sortedByDescending { it.start }) {
                if (r.start < 0 || r.end > sb.length) continue
                val found = sb.substring(r.start, r.end)
                val replacement = if (found == oldName || found == "`$oldName`") newName else alsoReplace[found] ?: continue
                sb.replace(r.start, r.end, replacement)
                applied++
            }
            if (applied == 0) continue
            val updated = sb.toString()
            ctx.updateDocument(path, updated)
            runCatching { path.parent?.let { Files.createDirectories(it) }; path.writeText(updated) }
            occurrences += applied
            changed.add(path)
        }
        if (changed.isEmpty()) return RenameOutcome(false, "No occurrences of '$oldName' found.")

        var newPath: Path? = null
        if (renameFile != null) {
            val dest = renameFile.resolveSibling("$newName$extension")
            if (!Files.exists(dest)) runCatching {
                Files.move(renameFile, dest)
                ctx.removeOverlay(renameFile)?.let { ctx.updateDocument(dest, it) }
                newPath = dest
            }
        }

        // One batched mutation event: the hub coalesces the reaction (a multi-file edit / file rename
        // invalidates analyzers + synthetics and re-syncs the index exactly once).
        ctx.events.filesMutated(changed, newPath?.let { renameFile!! to it })
        val message = "Renamed '$oldName' to '$newName'" + (note?.let { ". $it" } ?: "")
        return RenameOutcome(true, message, occurrences, changed.size, newPath?.toString())
    }

    /**
     * The plugin providers on [ep] for [file]'s language. Java and Kotlin are the built-in backends' own, so a
     * provider never answers for them, even one that claims every language.
     */
    private fun <T : Any> pluginProviders(
        file: Path, ep: dev.ide.platform.ExtensionPoint<T>, languages: (T) -> Set<LanguageId>,
    ): List<T> {
        val name = file.toString()
        if (name.endsWith(".java") || isKotlin(file)) return emptyList()
        val language = ctx.languageFor(file)
        return ctx.platform.extensions.extensions(ep).filter { languages(it).isEmpty() || language in languages(it) }
    }

    /** Usages from the [dev.ide.analysis.REFERENCE_PROVIDER_EP] plugins, or null when none serves [file]. */
    private fun pluginReferences(file: Path, text: String, offset: Int, includeDeclaration: Boolean): List<Pair<Path, TextRange>>? {
        val providers = pluginProviders(file, dev.ide.analysis.REFERENCE_PROVIDER_EP) { it.languages }
        if (providers.isEmpty()) return null
        val target = ctx.pluginTarget(file, text) ?: return emptyList()
        return providers.flatMap { p ->
            runCatching { runSync { p.references(target, offset, includeDeclaration) } }.getOrDefault(emptyList())
        }.filter { includeDeclaration || !it.isDeclaration }
            .map { Paths.get(it.path).toAbsolutePath().normalize() to it.range }
            .distinct()
    }

    private suspend fun renameThroughPlugin(
        providers: List<dev.ide.analysis.RenameProvider>, file: Path, text: String, offset: Int, name: String,
    ): RenameOutcome {
        if (name.isEmpty()) return RenameOutcome(false, "Enter a new name.")
        val target = ctx.pluginTarget(file, text) ?: return RenameOutcome(false, "This file is not in a source module.")
        val oldName = identifierAround(text, offset)
        if (name == oldName) return RenameOutcome(false, "The new name is the same as the current one.")
        var rejection: String? = null
        for (provider in providers) {
            val result = runCatching { provider.rename(target, offset, name) }.getOrElse {
                return RenameOutcome(false, "Rename failed: ${it.message ?: it}")
            }
            val edit = result.edit
            if (edit == null || edit.isEmpty) { rejection = rejection ?: result.error; continue }
            return applyWorkspaceEdit(edit, file.toAbsolutePath().normalize() to text, oldName, name)
        }
        return RenameOutcome(false, rejection ?: "Place the caret on a symbol to rename.")
    }

    /**
     * Apply a plugin's [edit] to disk and the live overlay, file by file, as one batched mutation: what a
     * rename computed by a language server becomes. [focal] is the file the rename started in and the
     * editor's text of it, which is what its offsets index. A file the edit creates is written whole.
     */
    private fun applyWorkspaceEdit(
        edit: dev.ide.analysis.WorkspaceEdit, focal: Pair<Path, String>, oldName: String?, newName: String,
    ): RenameOutcome {
        var occurrences = 0
        val changed = ArrayList<Path>()
        for ((file, edits) in edit.edits) {
            if (edits.isEmpty()) continue
            val path = runCatching { Paths.get(file.path).toAbsolutePath().normalize() }.getOrNull() ?: continue
            val current = when {
                path == focal.first -> focal.second
                !file.exists && !Files.exists(path) -> ""
                else -> textOf(path) ?: continue
            }
            val sb = StringBuilder(current)
            for (e in edits.sortedByDescending { it.offset }) {
                val start = e.offset.coerceIn(0, sb.length)
                val end = (e.offset + e.oldLength).coerceIn(start, sb.length)
                sb.replace(start, end, e.newText.toString())
            }
            val updated = sb.toString()
            ctx.updateDocument(path, updated)
            runCatching { path.parent?.let { Files.createDirectories(it) }; path.writeText(updated) }
            occurrences += edits.size
            changed.add(path)
        }
        if (changed.isEmpty()) return RenameOutcome(false, "Nothing to rename here.")
        ctx.events.filesMutated(changed, null)
        val what = oldName?.let { "'$it' to '$newName'" } ?: "to '$newName'"
        return RenameOutcome(true, "Renamed $what", occurrences, changed.size)
    }

    /** The identifier around [offset] in [text], for a rename's message. */
    private fun identifierAround(text: String, offset: Int): String? {
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && part(text[start - 1])) start--
        while (end < text.length && part(text[end])) end++
        return if (end > start) text.substring(start, end) else null
    }

    private fun textOf(path: Path): String? = ctx.overlayText(path) ?: runCatching { path.readText() }.getOrNull()

    private fun isValidKotlinName(s: String): Boolean = ctx.isValidJavaIdentifier(s) && s !in KOTLIN_HARD_KEYWORDS

    private fun containsWord(text: String, word: String): Boolean = Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text)

    private companion object {
        val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")

        /** Words a Kotlin name can never be without backticks. */
        val KOTLIN_HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )
    }
}
