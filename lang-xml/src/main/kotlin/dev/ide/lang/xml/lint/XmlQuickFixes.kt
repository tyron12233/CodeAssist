package dev.ide.lang.xml.lint

import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit

/**
 * The XML quick-fixes [XmlDiagnosticProvider] attaches to its findings, factored out so the provider stays a
 * thin detection→diagnostic mapping. Two flavors: a *buffer fix* whose effect is a single [DocumentEdit] on the
 * open file, and a *creating fix* whose effect is host filesystem I/O (creating a resource) via [XmlResourceHost].
 */
internal object XmlQuickFixes {

    /** A fix whose edit is a single in-buffer [DocumentEdit] (no host I/O). [edit] is computed lazily at apply. */
    fun bufferFix(title: String, edit: () -> DocumentEdit): QuickFix = object : QuickFix {
        override val title = title
        override val kind = CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = WorkspaceEdit.of(ctx.target.file, edit())
    }

    /** A fix whose effect is host filesystem I/O (creating a resource) rather than an edit to the open buffer. */
    fun creatingFix(title: String, create: (FixContext) -> Unit): QuickFix = object : QuickFix {
        override val title = title
        override val kind = CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit { create(ctx); return WorkspaceEdit.EMPTY }
    }

    /** Add ` xmlns:prefix="uri"` to the root element (spliced at [insertAt], just after the root tag name). */
    fun addNamespace(prefix: String, uri: String, insertAt: Int): QuickFix =
        bufferFix("Add xmlns:$prefix declaration") { DocumentEdit(insertAt, 0, " xmlns:$prefix=\"$uri\"") }

    /** Extract the hardcoded string [value] (occupying [range]) to a generated `@string` resource (host I/O). */
    fun extractToString(host: XmlResourceHost, range: TextRange, value: String): QuickFix = object : QuickFix {
        override val title = "Extract to @string resource"
        override val kind = CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
            val name = host.appendValueResource(ctx.target.file, "string", snakeName(value), value)
            return WorkspaceEdit.of(ctx.target.file, DocumentEdit(range.start, range.length, "@string/$name"))
        }
    }

    /** Splice `android:[dim]="wrap_content"` onto a view missing it (at [insertAt], just after the tag name). */
    fun addSize(dim: String, insertAt: Int): QuickFix =
        bufferFix("Add android:$dim=\"wrap_content\"") { DocumentEdit(insertAt, 0, "\n    android:$dim=\"wrap_content\"") }

    /** Append a `<rClass name=…/>` value-resource entry to res/values (host I/O). */
    fun createValueResource(host: XmlResourceHost, rClass: String, name: String): QuickFix =
        creatingFix("Create @$rClass/$name") { host.appendValueResource(it.target.file, rClass, name, "") }

    /** Create a `res/<type>/<name>.xml` file resource from a stub (host I/O). */
    fun createResourceFile(host: XmlResourceHost, rClass: String, name: String): QuickFix =
        creatingFix("Create @$rClass/$name file") { host.createResourceFile(it.target.file, rClass, name) }

    /** Remove a wrong attribute [attribute] occupying [range] (the whole `name="value"` plus a leading space). */
    fun removeAttribute(attribute: String, range: TextRange): QuickFix =
        bufferFix("Remove attribute $attribute") { DocumentEdit(range.start, range.length, "") }

    /** A deterministic snake_case resource name from arbitrary [value] (for extract-to-@string). */
    fun snakeName(value: String): String {
        val base = value.trim().lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .trim('_').replace(Regex("_+"), "_").take(40).ifEmpty { "text" }
        return if (base.first().isDigit()) "_$base" else base
    }

    fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()
}
