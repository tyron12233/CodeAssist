package dev.ide.lang.java

import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.PositionedImport
import dev.ide.lang.imports.ImportOrganizerService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile

/**
 * "Optimize Imports" for Java. Re-emits the file's import section through the language-neutral [ImportLayout]:
 * the regular block, a blank line, then the static block (IntelliJ's default) — de-duplicated,
 * wildcard-collapsed at the thresholds, and with unreferenced single-type imports dropped.
 *
 * Unused detection mirrors [dev.ide.lang.java.analysis]'s `java.unusedImport` heuristic: a plain (non-static,
 * non-on-demand) import is dropped only when its simple name never appears in the file body OUTSIDE the import
 * section. Static and on-demand imports are always kept (their use can't be told from a simple-name scan), so
 * the command never removes an import that is actually in use.
 */
class JavaImportOrganizer(private val parse: (String, CharSequence) -> PsiJavaFile) : ImportOrganizerService {

    override suspend fun organizeImports(file: VirtualFile, text: CharSequence): List<DocumentEdit> {
        val src = text.toString()
        if (src.isEmpty()) return emptyList()
        val psi = parse(file.name, src)
        val positioned = JavaImportEdits.existingImports(psi)
        if (positioned.isEmpty()) return emptyList()

        val body = bodyOutsideImports(src, positioned)
        val kept = positioned.map { it.entry }.filter { keep(it, body) }
        val blocks = ImportLayout.organize(kept, JavaImportEdits.CONFIG)
        val rendered = ImportLayout.renderBlocks(blocks, JavaImportEdits::render)

        val regionStart = positioned.first().start
        val regionEnd = positioned.last().endExclusive // past the last import's newline
        val current = src.substring(regionStart, regionEnd)
        val replacement = if (rendered.isEmpty()) "" else "$rendered\n"
        if (replacement == current) return emptyList()
        return listOf(minimalEdit(src, regionStart, regionEnd, replacement))
    }

    /** Keep [entry] unless it is a plain single import whose simple name never appears in the file [body]. */
    private fun keep(entry: ImportEntry, body: String): Boolean {
        if (entry.isStatic || entry.isWildcard) return true
        val name = entry.fqn.substringAfterLast('.')
        return name.isEmpty() || Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body)
    }

    /** The file text with the import statements blanked out (so a name that appears only in its own import
     *  line reads as unused) — matching the analyzer's non-import-lines heuristic. */
    private fun bodyOutsideImports(src: String, imports: List<PositionedImport>): String {
        val sb = StringBuilder(src)
        for (imp in imports) for (i in imp.start until imp.endExclusive.coerceAtMost(sb.length)) {
            if (sb[i] != '\n') sb.setCharAt(i, ' ')
        }
        return sb.toString()
    }

    private fun minimalEdit(src: String, regionStart: Int, regionEnd: Int, replacement: String): DocumentEdit {
        val current = src.substring(regionStart, regionEnd)
        var p = 0
        val max = minOf(current.length, replacement.length)
        while (p < max && current[p] == replacement[p]) p++
        var s = current.length
        var d = replacement.length
        while (s > p && d > p && current[s - 1] == replacement[d - 1]) { s--; d-- }
        return DocumentEdit(regionStart + p, s - p, replacement.substring(p, d))
    }
}
