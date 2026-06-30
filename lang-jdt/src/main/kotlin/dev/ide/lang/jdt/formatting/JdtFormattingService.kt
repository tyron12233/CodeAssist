package dev.ide.lang.jdt.formatting

import dev.ide.lang.dom.TextRange
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.text.edits.DeleteEdit
import org.eclipse.text.edits.InsertEdit
import org.eclipse.text.edits.ReplaceEdit
import org.eclipse.text.edits.TextEdit

/**
 * Java formatter on Eclipse JDT's own [CodeFormatter] (the engine Eclipse uses). Reuses the module's
 * compliance level so newer syntax parses, drives the formatter with a Google-style option map (see
 * [GoogleJavaStyle]), and flattens the returned [TextEdit] tree into neutral [DocumentEdit]s, so the host
 * applies a reformat through the same surgical edit path as a quick-fix and untouched lines keep their bytes.
 *
 * On a buffer the formatter can't parse (an unrecoverable syntax error) `format(...)` returns null; we treat
 * that as a no-op (empty edit list) rather than mangling the source.
 */
class JdtFormattingService(private val compliance: String) : FormattingService {

    override suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit> {
        val src = text.toString()
        return runFormatter(src, style, 0, src.length)
    }

    override suspend fun formatRange(
        file: VirtualFile,
        text: CharSequence,
        range: TextRange,
        style: FormatStyle,
    ): List<DocumentEdit> {
        val src = text.toString()
        val start = range.start.coerceIn(0, src.length)
        val end = range.end.coerceIn(start, src.length)
        if (end <= start) return emptyList()
        return runFormatter(src, style, start, end - start)
    }

    /** Format whole [text] without a [VirtualFile] (the formatter ignores the file). For previews/tests. */
    fun formatText(text: CharSequence, style: FormatStyle): List<DocumentEdit> {
        val src = text.toString()
        return runFormatter(src, style, 0, src.length)
    }

    private fun runFormatter(src: String, style: FormatStyle, offset: Int, length: Int): List<DocumentEdit> {
        val options = GoogleJavaStyle.options(style, compliance)
        val formatter = ToolFactory.createCodeFormatter(options)
        // K_COMPILATION_UNIT parses the whole source for context; F_INCLUDE_COMMENTS reflows javadoc/comments too.
        val kind = CodeFormatter.K_COMPILATION_UNIT or CodeFormatter.F_INCLUDE_COMMENTS
        val edit = runCatching { formatter.format(kind, src, offset, length, 0, "\n") }.getOrNull() ?: return emptyList()
        val out = ArrayList<DocumentEdit>()
        collect(edit, out)
        return out
    }

    /** Flatten a [TextEdit] tree (a MultiTextEdit of leaves) into neutral document edits. */
    private fun collect(edit: TextEdit, out: MutableList<DocumentEdit>) {
        when (edit) {
            is ReplaceEdit -> out += DocumentEdit(edit.offset, edit.length, edit.text)
            is InsertEdit -> out += DocumentEdit(edit.offset, 0, edit.text)
            is DeleteEdit -> out += DocumentEdit(edit.offset, edit.length, "")
            else -> for (child in edit.children) collect(child, out)
        }
    }
}
