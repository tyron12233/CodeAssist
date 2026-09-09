package dev.ide.lang.jdt.analysis

import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.PositionedImport

/**
 * Sorted placement of a single new Java `import` over the backend-neutral DOM (the JDT add-import quick-fix
 * has only node text + ranges, not IntelliJ PSI). Parses the file's `IMPORT_DECL`/`PACKAGE_DECL` nodes into
 * [ImportEntry]s and defers ordering to the language-neutral [ImportLayout], so the JDT quick-fix places an
 * import exactly where the completion auto-import (in the IntelliJ-PSI backend) does.
 */
internal object JdtImportLayout {

    private val CONFIG = ImportLayout.ImportLayoutConfig.JAVA

    fun render(entry: ImportEntry): String {
        val static = if (entry.isStatic) "static " else ""
        val path = if (entry.isWildcard) "${entry.fqn}.*" else entry.fqn
        return "import $static$path;"
    }

    /** Plan splicing `import <fqn>;` in sorted position, or null when it (or an on-demand import of its
     *  package) is already present. */
    fun planImport(parsed: ParsedFile, fqn: String): ImportLayout.InsertPlan? {
        val text = parsed.text()
        val existing = existingImports(parsed, text)
        val pkg = fqn.substringBeforeLast('.', "")
        if (existing.any { it.entry.isWildcard && !it.entry.isStatic && it.entry.fqn == pkg }) return null
        return ImportLayout.planInsert(
            entry = ImportEntry(fqn),
            existing = existing,
            config = CONFIG,
            text = text,
            packageLineEnd = packageLineEnd(parsed, text),
            render = ::render,
        )
    }

    private fun existingImports(parsed: ParsedFile, text: CharSequence): List<PositionedImport> {
        val out = ArrayList<PositionedImport>()
        for (n in parsed.nodesIn(parsed.range)) {
            if (n.kind != NodeKind.IMPORT_DECL) continue
            val body = n.text().toString().trim().removePrefix("import").trim().removeSuffix(";").trim()
            val isStatic = body.startsWith("static ")
            val path = body.removePrefix("static ").trim()
            val wildcard = path.endsWith(".*")
            val entry = ImportEntry(path.removeSuffix(".*"), isStatic, wildcard)
            out += PositionedImport(entry, n.range.start, lineEndAfter(text, n.range.end))
        }
        return out.sortedBy { it.start }
    }

    private fun packageLineEnd(parsed: ParsedFile, text: CharSequence): Int? {
        var end = -1
        for (n in parsed.nodesIn(parsed.range)) if (n.kind == NodeKind.PACKAGE_DECL) end = maxOf(end, n.range.end)
        if (end < 0) return null
        return lineEndAfter(text, end)
    }

    private fun lineEndAfter(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        if (i < text.length) i++
        return i
    }
}
