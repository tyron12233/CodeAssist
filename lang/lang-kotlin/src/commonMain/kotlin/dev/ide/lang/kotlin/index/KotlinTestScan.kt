package dev.ide.lang.kotlin.index

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtTokens

/**
 * Finds the tests in a `.kt` file from a real parse (no resolution): functions annotated with a test framework's
 * `@Test` (or `@ParameterizedTest`/`@RepeatedTest`) in a concrete class or a `@Nested` inner class, and the classes
 * holding them. What the editor's gutter offers to run.
 *
 * An annotation counts when it names one of [TEST_ANNOTATIONS]: fully qualified, imported by name, or through a
 * star import of its package. A bare `@Test` the imports do not explain is left alone, because it could be
 * anything (kotlin.test is not a default import).
 */
object KotlinTestScan {

    /** A test or a test class. [classFqn] is the binary name (`$` for nesting), [method] null for the class
     *  itself, [offset] where its name starts. */
    data class Hit(val classFqn: String, val method: String?, val offset: Int)

    val TEST_ANNOTATIONS = setOf(
        "org.junit.Test",
        "org.junit.jupiter.api.Test",
        "kotlin.test.Test",
        "org.testng.annotations.Test",
        "org.junit.jupiter.params.ParameterizedTest",
        "org.junit.jupiter.api.RepeatedTest",
    )

    fun scan(fileName: String, text: String): List<Hit> = testsOf(KotlinMainScan.parse(fileName, text))

    fun testsOf(kt: KtFile?): List<Hit> {
        if (kt == null) return emptyList()
        val imports = Imports(kt)
        val pkg = kt.packageFqName.asString()
        val out = ArrayList<Hit>()
        for (d in kt.declarations) if (d is KtClass) visit(d, if (pkg.isEmpty()) "" else "$pkg.", imports, out)
        return out
    }

    private fun visit(c: KtClass, prefix: String, imports: Imports, out: MutableList<Hit>) {
        val name = c.name ?: return
        val binary = prefix + name
        // An inner class is a test class only as JUnit 5's `@Nested`, which the runner builds inside its outer one.
        val concrete = !c.isInterface() && !c.isEnum() && !c.isAnnotation() &&
            !c.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !c.hasModifier(KtTokens.SEALED_KEYWORD) &&
            (!c.hasModifier(KtTokens.INNER_KEYWORD) || c.annotationEntries.any { it.shortName?.asString() == "Nested" })
        if (concrete) {
            val tests = c.declarations.filterIsInstance<KtNamedFunction>()
                .filter { f -> f.receiverTypeReference == null && f.annotationEntries.any { imports.isTest(it) } }
            if (tests.isNotEmpty()) {
                c.nameIdentifier?.let { out.add(Hit(binary, null, it.textOffset)) }
                for (f in tests) {
                    val fn = f.name ?: continue
                    out.add(Hit(binary, fn, (f.nameIdentifier ?: f).textOffset))
                }
            }
        }
        for (inner in c.declarations) if (inner is KtClass) visit(inner, "$binary$", imports, out)
    }

    private class Imports(kt: KtFile) {
        private val byName = HashMap<String, String>()
        private val starPackages = HashSet<String>()

        init {
            for (imp in kt.importDirectives) {
                val fq = imp.importedFqName?.asString() ?: continue
                if (imp.isAllUnder) starPackages.add(fq) else byName[fq.substringAfterLast('.')] = fq
            }
        }

        fun isTest(entry: KtAnnotationEntry): Boolean {
            val written = entry.typeReference?.text?.substringBefore('<')?.trim() ?: return false
            if ('.' in written) return written in TEST_ANNOTATIONS
            byName[written]?.let { return it in TEST_ANNOTATIONS }
            return TEST_ANNOTATIONS.any { it.substringAfterLast('.') == written && it.substringBeforeLast('.') in starPackages }
        }
    }
}
