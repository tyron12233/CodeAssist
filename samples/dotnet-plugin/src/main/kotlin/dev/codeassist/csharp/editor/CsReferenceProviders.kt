package dev.codeassist.csharp.editor

import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsNavigationLines
import dev.codeassist.csharp.completion.CsSemanticCompletion
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.REFERENCE_PROVIDER_EP
import dev.ide.analysis.RENAME_PROVIDER_EP
import dev.ide.analysis.ReferenceProvider
import dev.ide.analysis.RenameProvider
import dev.ide.analysis.RenameResult
import dev.ide.analysis.RenameTarget
import dev.ide.analysis.SymbolReference
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.UsageAccess
import dev.ide.lang.highlight.UsageHighlight
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.platform.ContentHash
import dev.ide.plugin.PluginRegistration
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Find Usages for C#, and the highlighting of a name's other uses in the file: Roslyn resolves the name at
 * the caret and every place in the project that resolves to the same symbol is a usage.
 */
internal class CsReferenceProvider(
    private val semantic: CsSemanticCompletion,
) : ReferenceProvider {

    override val id = "csharp.references"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    override suspend fun references(
        target: AnalysisTarget,
        offset: Int,
        includeDeclaration: Boolean,
    ): List<SymbolReference> {
        val found = find(target, offset) ?: return emptyList()
        return found.places
            .filter { includeDeclaration || it.access != DECLARED }
            .map { SymbolReference(it.path, TextRange(it.start, it.end), it.access == DECLARED) }
    }

    override suspend fun highlights(target: AnalysisTarget, offset: Int): List<UsageHighlight>? {
        val found = find(target, offset) ?: return null
        val here = runCatching { Paths.get(target.file.path).toAbsolutePath().normalize().toString() }
            .getOrDefault(target.file.path)
        return found.places.filter { it.path == here }.map {
            val access = when (it.access) {
                DECLARED -> UsageAccess.DECLARATION
                WRITTEN -> UsageAccess.WRITE
                else -> UsageAccess.READ
            }
            UsageHighlight(TextRange(it.start, it.end), access)
        }
    }

    private suspend fun find(target: AnalysisTarget, offset: Int): CsNavigationLines.References? {
        CsModules.remember(target.module)
        return semantic.referencesAt(target.file.path, target.parsed.text().toString(), offset)
    }

    private companion object {
        const val DECLARED = 'd'
        const val WRITTEN = 'w'
    }
}

/**
 * Rename for C#: every place Find Usages reports takes the new name. A symbol the project does not declare
 * (a framework or package type) is refused, since its declaration cannot be changed.
 */
internal class CsRenameProvider(
    private val semantic: CsSemanticCompletion,
) : RenameProvider {

    override val id = "csharp.rename"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    override suspend fun prepare(target: AnalysisTarget, offset: Int): RenameTarget? {
        val found = semantic.referencesAt(target.file.path, target.parsed.text().toString(), offset) ?: return null
        if (!found.symbol.inSource) return null
        return RenameTarget(found.symbol.name, TextRange(found.symbol.start, found.symbol.end), found.symbol.kind)
    }

    override suspend fun rename(target: AnalysisTarget, offset: Int, newName: String): RenameResult {
        val name = newName.trim()
        if (!IDENTIFIER.matches(name)) return RenameResult.rejected("\"$newName\" is not a valid C# name.")
        if (name in KEYWORDS) return RenameResult.rejected("\"$name\" is a C# keyword. Write @$name to use it as a name.")

        val found = semantic.referencesAt(target.file.path, target.parsed.text().toString(), offset)
            ?: return RenameResult.rejected("There is nothing to rename here.")
        if (!found.symbol.inSource) {
            return RenameResult.rejected("${found.symbol.name} is not declared in this project, so it cannot be renamed.")
        }
        if (found.places.isEmpty()) return RenameResult.rejected("No use of ${found.symbol.name} was found.")

        val edits = found.places
            .groupBy { it.path }
            .mapKeys { (path, _) -> CsFile(path) as VirtualFile }
            .mapValues { (_, places) ->
                // Last first, so that each edit's offsets are still those of the text as it was read.
                places.distinctBy { it.start }.sortedByDescending { it.start }
                    .map { DocumentEdit(it.start, it.end - it.start, name) }
            }
        return RenameResult.of(WorkspaceEdit(edits))
    }

    private companion object {
        val IDENTIFIER = Regex("""@?[\p{L}_][\p{L}\p{Nd}_]*""")
        val KEYWORDS = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const",
            "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit",
            "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
            "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out",
            "override", "params", "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
            "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try",
            "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void", "volatile",
            "while",
        )
    }
}

/** A file on disk that a [WorkspaceEdit] edits. The host reads only its [path]. */
internal class CsFile(override val path: String) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/')
    override val isDirectory: Boolean get() = false
    override val exists: Boolean get() = true
    override val length: Long get() = runCatching { Files.size(Paths.get(path)) }.getOrDefault(0L)
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = runCatching { Files.readAllBytes(Paths.get(path)) }.getOrDefault(ByteArray(0))
    override fun readText(): CharSequence = String(readBytes())
    override fun equals(other: Any?): Boolean = other is CsFile && other.path == path
    override fun hashCode(): Int = path.hashCode()
}

/**
 * Registers Find Usages and Rename. Each in a call of its own: a host that has one extension point and not
 * the other keeps the one it has.
 */
internal object CsReferenceRegistration {
    fun references(reg: PluginRegistration, semantic: CsSemanticCompletion) {
        reg.register(REFERENCE_PROVIDER_EP, CsReferenceProvider(semantic))
    }

    fun rename(reg: PluginRegistration, semantic: CsSemanticCompletion) {
        reg.register(RENAME_PROVIDER_EP, CsRenameProvider(semantic))
    }
}
