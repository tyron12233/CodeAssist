package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Rewriting a Kotlin file's `package` directive when the file is relocated (moved/copied) to a new directory,
 * plus the top-level declaration names other files import it by. Returns plain data so callers can drive the
 * relocation without depending on the Kotlin PSI — the compiler types stay encapsulated here, mirroring how
 * the Java side's `JavaSourceIndexer` hands back a resolution-free [Result] rather than a live tree.
 */
object KotlinPackageRewrite {

    /**
     * [updatedText] with the package set to the target; the file's prior [oldPackage] (`""` = the default
     * package); and its top-level declaration [topLevelNames] (the simple names a cross-file import references,
     * for the caller's import sweep).
     */
    class Result(val updatedText: String, val oldPackage: String, val topLevelNames: List<String>)

    /**
     * [Result] of setting [text]'s package directive to [newPackage] (`""` = the default package), or null when
     * the file already declares [newPackage] (nothing to do) or it cannot be parsed. Edits the directive as
     * text: replace its name, drop the line for the default package, or insert `package …` ahead of the first
     * import/declaration so any leading `@file:` annotation block stays first. [fileName] should end in `.kt`.
     */
    fun rewrite(fileName: String, text: String, newPackage: String): Result? {
        val kt = runCatching { KotlinParserHost.parse(fileName, text) }.getOrNull() ?: return null
        val oldPackage = kt.packageFqName.asString()
        if (oldPackage == newPackage) return null
        val names = kt.declarations.mapNotNull { (it as? KtNamedDeclaration)?.name?.takeIf(String::isNotBlank) }
        val directive = kt.packageDirective?.takeIf { it.textLength > 0 && it.text.isNotBlank() }
        val updated = when {
            directive == null -> { // default package → named: insert after any file annotations
                val at = kt.importDirectives.firstOrNull()?.textRange?.startOffset
                    ?: kt.declarations.firstOrNull()?.textRange?.startOffset ?: text.length
                if (at >= text.length) {
                    text + (if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + "package $newPackage\n"
                } else {
                    text.substring(0, at) + "package $newPackage\n\n" + text.substring(at)
                }
            }

            newPackage.isEmpty() -> { // named → default package: drop the directive line and its line break
                var end = directive.textRange.endOffset
                while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
                if (end < text.length && text[end] == '\r') end++
                if (end < text.length && text[end] == '\n') end++
                text.removeRange(directive.textRange.startOffset, end)
            }

            else -> text.replaceRange(
                directive.textRange.startOffset, directive.textRange.endOffset, "package $newPackage"
            )
        }
        return Result(updated, oldPackage, names)
    }
}
