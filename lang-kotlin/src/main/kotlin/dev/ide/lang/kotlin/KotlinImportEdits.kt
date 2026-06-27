package dev.ide.lang.kotlin

import org.jetbrains.kotlin.psi.KtFile

/**
 * Where a new `import` directive is spliced into a Kotlin file. Both the analyzer (the unresolved-reference
 * quick-fix) and completion (auto-import when an unimported symbol is accepted) add imports; this is the
 * single home for the insertion-point math so the two stay consistent. They keep distinct entry points
 * because they emit different edit shapes: the analyzer writes a DocumentEdit carrying its own trailing
 * newline, while completion writes a TextEdit that reuses the file's surrounding blank lines.
 */
object KotlinImportEdits {

    /** Offset of a fresh line just after the last import (else the package directive, else the file start),
     *  where the analyzer splices `import <fqn>\n`. */
    fun insertOffset(ktFile: KtFile): Int {
        val text = ktFile.text
        val anchor = ktFile.importDirectives.maxOfOrNull { it.textRange.endOffset }
            ?: ktFile.packageDirective?.takeIf { it.text.isNotBlank() }?.textRange?.endOffset
            ?: return 0
        var i = anchor.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++  // to the end of the anchor's line
        if (i < text.length) i++                          // past its newline to the start of a fresh line
        return i
    }

    /** Where completion splices a new import: the [offset] to insert at, plus the [prefix]/[suffix] blank
     *  lines that keep the import block well-formed (after the last import, else the package, else the top). */
    data class Anchor(val offset: Int, val prefix: String, val suffix: String)

    fun anchorOf(ktFile: KtFile): Anchor {
        ktFile.importDirectives.lastOrNull()?.let { return Anchor(it.textRange.endOffset, "\n", "") }
        val pkg = ktFile.packageDirective
        if (pkg != null && pkg.textLength > 0 && pkg.text.isNotBlank()) {
            return Anchor(pkg.textRange.endOffset, "\n\n", "")
        }
        return Anchor(0, "", "\n\n")
    }
}
