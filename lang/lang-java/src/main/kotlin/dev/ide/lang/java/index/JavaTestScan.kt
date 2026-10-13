package dev.ide.lang.java.index

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier

/**
 * Finds the tests in a `.java` file from a structural PSI parse (no resolution): methods annotated with a test
 * framework's `@Test` (or `@ParameterizedTest`/`@RepeatedTest`) in a concrete class, and the classes holding
 * them. What the editor's gutter offers to run.
 *
 * An annotation counts when it names one of [TEST_ANNOTATIONS]: fully qualified, imported by name, or through
 * an on-demand import of its package; a bare `@Test` the imports do not explain is left alone.
 */
object JavaTestScan {

    /** A test or a test class. [classFqn] is the binary name (`$` for nesting), [method] null for the class
     *  itself, [offset] where its name starts. */
    data class Hit(val classFqn: String, val method: String?, val offset: Int)

    val TEST_ANNOTATIONS = setOf(
        "org.junit.Test",
        "org.junit.jupiter.api.Test",
        "org.testng.annotations.Test",
        "org.junit.jupiter.params.ParameterizedTest",
        "org.junit.jupiter.api.RepeatedTest",
    )

    fun scan(text: String): List<Hit> = JavaSourceIndexer.parseStructural(text) { testsOf(it) } ?: emptyList()

    fun testsOf(psi: PsiJavaFile?): List<Hit> {
        if (psi == null) return emptyList()
        val byName = HashMap<String, String>()
        val onDemand = HashSet<String>()
        psi.importList?.importStatements?.forEach { imp ->
            val fq = imp.qualifiedName ?: return@forEach
            if (imp.isOnDemand) onDemand.add(fq) else byName[fq.substringAfterLast('.')] = fq
        }
        fun isTest(a: PsiAnnotation): Boolean {
            val written = a.nameReferenceElement?.text?.trim() ?: return false
            if ('.' in written) return written in TEST_ANNOTATIONS
            byName[written]?.let { return it in TEST_ANNOTATIONS }
            return TEST_ANNOTATIONS.any { it.substringAfterLast('.') == written && it.substringBeforeLast('.') in onDemand }
        }
        val pkg = psi.packageName
        val out = ArrayList<Hit>()
        fun visit(cls: PsiClass, binary: String) {
            val concrete = !cls.isInterface && !cls.isEnum && !cls.isAnnotationType && !cls.hasModifierProperty(PsiModifier.ABSTRACT)
            if (concrete) {
                val tests = cls.ownMethodsSafe().filter { m -> !m.isConstructor && m.modifierList.annotations.any(::isTest) }
                if (tests.isNotEmpty()) {
                    cls.nameIdentifier?.let { out.add(Hit(binary, null, it.textRange.startOffset)) }
                    for (m in tests) out.add(Hit(binary, m.name, (m.nameIdentifier ?: m).textRange.startOffset))
                }
            }
            cls.ownInnerClassesSafe().forEach { inner -> inner.name?.let { visit(inner, "$binary\$$it") } }
        }
        psi.classes.forEach { c -> c.name?.let { visit(c, if (pkg.isEmpty()) it else "$pkg.$it") } }
        return out
    }
}
