package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Codes
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.model.Module

/**
 * A resource read through the wrong `R`: `com.google.android.material.R.attr.colorPrimary`, where `colorPrimary`
 * is declared by AppCompat. A library's `R` holds the resources that library lists and no others, so the name
 * is unresolved there, and the error alone ("Unresolved reference: colorPrimary") does not say the resource
 * exists one package over.
 *
 * Offers the two spellings that do resolve: through the `R` of the library that declares it, which is the one
 * Gradle accepts under any setting, and through the module's own `R`, which holds every resource the module can
 * see. Code like this compiled while every library package was given a copy of the whole table, so it is what
 * a project may be left with now that library `R` classes are made as AGP makes them.
 *
 * Host-level because finding the declaring package takes the module's `R` classes ([rClasses]): the editor's
 * own, one per package, each holding what that package declares.
 */
class LibraryRReferenceFixProvider internal constructor(
    private val rClasses: (Module) -> List<SyntheticClass>,
    private val ownNamespace: (Module) -> String?,
) : QuickFixProvider {
    override val forCodes: Set<String> = setOf(Codes.UNRESOLVED_REFERENCE, KOTLIN_UNRESOLVED)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val module = target.module
        // The cheap textual test first: almost no unresolved name is the tail of an R reference.
        val text = target.parsed.text()
        if (LibraryRReference.at(text, diagnostic.range.start, diagnostic.range.end) == null) return emptyList()
        return LibraryRReference.suggestions(text, diagnostic.range.start, diagnostic.range.end, ownNamespace(module), rClasses(module))
            .map { s ->
                object : QuickFix {
                    override val title = s.title
                    override val kind = CodeActionKind.QUICK_FIX
                    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit =
                        WorkspaceEdit.of(target.file, DocumentEdit(s.start, s.end - s.start, s.replacement))
                }
            }
    }

    private companion object {
        const val KOTLIN_UNRESOLVED = "kt.unresolved"
    }
}

/**
 * `<package>.R.<type>.<name>` (or `R.<type>.<name>` / `Alias.<type>.<name>` with the `R` imported) ending at an
 * unresolved [name]: [start]..[end] is the whole expression, [packageName] the package as written, or null when
 * the `R` is named through an import ([rName]).
 */
internal class LibraryRReference(
    val packageName: String?, val rName: String, val type: String, val name: String, val start: Int, val end: Int,
) {
    companion object {
        private val TYPES = setOf(
            "anim", "animator", "array", "attr", "bool", "color", "dimen", "drawable", "font", "fraction", "id",
            "integer", "interpolator", "layout", "menu", "mipmap", "navigation", "plurals", "raw", "string", "style",
            "styleable", "transition", "xml",
        )
        private val BEFORE = Regex("""((?:[A-Za-z_][\w]*\s*\.\s*)*)([A-Za-z_]\w*)\s*\.\s*([a-z]+)\s*\.\s*$""")

        /** The R reference whose last name is the text [from]..[to], or null when that is not what precedes it. */
        fun at(text: CharSequence, from: Int, to: Int): LibraryRReference? {
            if (from < 0 || to > text.length || from >= to) return null
            val name = text.subSequence(from, to).toString()
            if (!name.all { it == '_' || it.isLetterOrDigit() }) return null
            // Far enough back for a long package; an R reference is on one line in practice.
            val windowStart = maxOf(0, from - 300)
            val m = BEFORE.find(text.subSequence(windowStart, from)) ?: return null
            val type = m.groupValues[3]
            if (type !in TYPES) return null
            val qualifier = m.groupValues[1].filterNot { it.isWhitespace() }.trimEnd('.')
            val rName = m.groupValues[2]
            // Qualified, the class must be `R` itself; unqualified, it is `R` or an import alias of one.
            if (qualifier.isNotEmpty() && rName != "R") return null
            return LibraryRReference(qualifier.ifEmpty { null }, rName, type, name, windowStart + m.range.first, to)
        }

        /** The package an `import <package>.R [as <rName>]` gives the simple name [rName], Java or Kotlin. */
        fun importedRPackage(text: CharSequence, rName: String): String? {
            val import = Regex("""(?m)^[ \t]*import[ \t]+((?:[A-Za-z_]\w*\.)+)R(?:[ \t]+as[ \t]+([A-Za-z_]\w*))?[ \t]*;?[ \t]*$""")
            return import.findAll(text).firstOrNull { (it.groupValues[2].ifEmpty { "R" }) == rName }
                ?.groupValues?.get(1)?.trimEnd('.')
        }

        /** One spelling that resolves: replace [start]..[end] with [replacement]. */
        class Suggestion(val title: String, val start: Int, val end: Int, val replacement: String)

        /** A resource two libraries both declare is rare; more choices than this is a menu nobody reads. */
        private const val MAX_LIBRARIES = 2

        /**
         * The spellings that resolve for the unresolved name at [from]..[to] of [text], given the module's [own]
         * namespace and its R [classes]. Empty unless the name is read through an R that lacks it while another
         * R has it: a resource that exists nowhere is a different mistake, and so is one missing from the
         * module's own R.
         */
        fun suggestions(text: CharSequence, from: Int, to: Int, own: String?, classes: List<SyntheticClass>): List<Suggestion> {
            val ref = at(text, from, to) ?: return emptyList()
            val written = ref.packageName ?: importedRPackage(text, ref.rName) ?: return emptyList()
            if (written == own) return emptyList()
            val declaring = declaringPackages(classes, ref.type, ref.name)
            if (declaring.isEmpty() || written in declaring) return emptyList()
            val out = ArrayList<Suggestion>(3)
            // The library that declares it first: that spelling is right wherever the project is built.
            declaring.filter { it != own }.sorted().take(MAX_LIBRARIES).forEach { pkg ->
                out += Suggestion(
                    "Use $pkg.R.${ref.type}.${ref.name} (${ref.name} is declared there, not in ${written.substringAfterLast('.')})",
                    ref.start, ref.end, "$pkg.R.${ref.type}.${ref.name}",
                )
            }
            if (own != null && own in declaring) {
                out += Suggestion(
                    "Use $own.R.${ref.type}.${ref.name} (this module's R has every resource)",
                    ref.start, ref.end, "$own.R.${ref.type}.${ref.name}",
                )
            }
            return out
        }

        /** The packages whose `R.<type>` has a field [name], among [classes]. */
        fun declaringPackages(classes: List<SyntheticClass>, type: String, name: String): Set<String> =
            classes.filter { it.fqName.endsWith(".R") }
                .filter { r -> r.nestedClasses.any { n -> n.fqName == "${r.fqName}.$type" && n.fields.any { it.name == name } } }
                .mapTo(LinkedHashSet()) { it.fqName.removeSuffix(".R") }
    }
}
