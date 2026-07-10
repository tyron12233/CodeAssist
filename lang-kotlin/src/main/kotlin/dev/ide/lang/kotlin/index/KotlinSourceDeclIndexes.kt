package dev.ide.lang.kotlin.index

import dev.ide.index.AnnotatedExternalizer
import dev.ide.index.AnnotatedValue
import dev.ide.index.AnnotationIndex
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SubtypeExternalizer
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import dev.ide.lang.kotlin.symbols.Builtins
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Shared static-name utilities for the Kotlin SOURCE index producers: a resolution-free parse sees simple
 * names (`Widget`, `@Composable`), so resolution is best-effort — an explicit import, the default-import
 * builtins, dotted-text-as-FQN, then the declaring file's package. A wrong guess only wastes an index
 * entry; consumers keyed by SHORT name verify through real resolution.
 */
internal object KotlinSourceNames {

    /** Non-wildcard imports keyed by imported simple name (honoring `as` aliases). */
    fun importsBySimpleName(kt: KtFile): Map<String, String> {
        val out = HashMap<String, String>()
        for (imp in kt.importDirectives) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (imp.isAllUnder) continue
            out[imp.aliasName ?: fqn.substringAfterLast('.')] = fqn
        }
        return out
    }

    /** Best-effort FQN for a simple/qualified type name: import > builtins > dotted-as-FQN > same package. */
    fun resolve(simple: String, pkg: String?, imports: Map<String, String>): String? = when {
        simple.isEmpty() -> null
        simple in imports -> imports[simple]
        Builtins.DEFAULT_SIMPLE_TYPES.containsKey(simple) -> Builtins.DEFAULT_SIMPLE_TYPES[simple]
        '.' in simple -> simple
        pkg != null -> "$pkg.$simple"
        else -> simple
    }

    /** A type reference's bare name: strips type arguments and nullability (`List<T>?` → `List`). */
    fun bareName(typeText: String): String = typeText.substringBefore('<').removeSuffix("?").trim()
}

/**
 * `subtypes.kotlinSource` — direct inheritors declared in project `.kt`: every class/object (including
 * nested) is indexed under each of its supertype entries' SHORT names ([SubtypeIndex.key]); the value
 * carries the best-effort resolved supertype for filtering. See [SubtypeIndex] for the family contract.
 */
object KotlinSourceSubtypeIndex : IndexExtension<String, SubtypeValue> {
    override val id: IndexId = SubtypeIndex.KOTLIN_SOURCE
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SubtypeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<SubtypeValue>> {
        val text = input.text() ?: return emptyMap()
        val name = input.sourcePath?.fileName?.toString() ?: input.unitName?.substringAfterLast('/') ?: "Source.kt"
        val kt = input.shared("kt.file") { KotlinMainScan.parse(name, text) } ?: return emptyMap()
        val pkg = kt.packageFqName.asString().ifEmpty { null }
        val imports = KotlinSourceNames.importsBySimpleName(kt)
        val out = HashMap<String, MutableList<SubtypeValue>>()

        fun visit(decls: List<KtDeclaration>) {
            for (d in decls) {
                val c = d as? KtClassOrObject ?: continue
                val fqn = c.fqName?.asString()
                if (fqn != null) {
                    val kind = kindOf(c)
                    for (entry in c.superTypeListEntries) {
                        val refText = entry.typeReference?.text ?: continue
                        val bare = KotlinSourceNames.bareName(refText)
                        val resolved = KotlinSourceNames.resolve(bare, pkg, imports) ?: bare
                        out.getOrPut(SubtypeIndex.key(bare)) { ArrayList() }
                            .add(SubtypeValue(fqn, kind, resolved, input.fileId))
                    }
                }
                visit(c.declarations)
            }
        }
        visit(kt.declarations)
        return out
    }

    private fun kindOf(c: KtClassOrObject): String = when {
        c is KtObjectDeclaration -> "object"
        c is KtClass && c.isInterface() -> "interface"
        c is KtClass && c.isEnum() -> "enum"
        c is KtClass && c.isAnnotation() -> "annotation"
        else -> "class"
    }
}

/**
 * `annotations.kotlinSource` — annotated declarations in project `.kt`, keyed by the annotation's SHORT
 * name: classes/objects (`fqn`), functions and properties (`owner#name` for members, `pkg.name` for
 * top-level). This is what generalizes the hardcoded `@Composable`/`@Preview` scans — "all `@X`
 * declarations" is one exact query. See [AnnotationIndex] for the family contract.
 */
object KotlinSourceAnnotationIndex : IndexExtension<String, AnnotatedValue> {
    override val id: IndexId = AnnotationIndex.KOTLIN_SOURCE
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = AnnotatedExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<AnnotatedValue>> {
        val text = input.text() ?: return emptyMap()
        val name = input.sourcePath?.fileName?.toString() ?: input.unitName?.substringAfterLast('/') ?: "Source.kt"
        val kt = input.shared("kt.file") { KotlinMainScan.parse(name, text) } ?: return emptyMap()
        val pkg = kt.packageFqName.asString().ifEmpty { null }
        val imports = KotlinSourceNames.importsBySimpleName(kt)
        val out = HashMap<String, MutableList<AnnotatedValue>>()

        fun emit(a: KtAnnotated, declFqn: String, declKind: String) {
            for (entry in a.annotationEntries) {
                val short = entry.shortName?.asString() ?: continue
                val resolved = KotlinSourceNames.resolve(short, pkg, imports) ?: short
                out.getOrPut(AnnotationIndex.key(short)) { ArrayList() }
                    .add(AnnotatedValue(declFqn, declKind, resolved, input.fileId))
            }
        }

        fun visit(decls: List<KtDeclaration>, owner: String?) {
            for (d in decls) {
                when (d) {
                    is KtClassOrObject -> {
                        val fqn = d.fqName?.asString()
                        if (fqn != null) emit(d, fqn, "class")
                        visit(d.declarations, fqn)
                    }
                    is KtNamedFunction -> {
                        val n = d.name ?: continue
                        emit(d, if (owner != null) "$owner#$n" else "${pkg?.plus(".") ?: ""}$n", "function")
                    }
                    is KtProperty -> {
                        val n = d.name ?: continue
                        emit(d, if (owner != null) "$owner#$n" else "${pkg?.plus(".") ?: ""}$n", "property")
                    }
                    else -> {}
                }
            }
        }
        visit(kt.declarations, null)
        return out
    }
}
