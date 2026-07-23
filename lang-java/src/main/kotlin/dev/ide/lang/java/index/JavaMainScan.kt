package dev.ide.lang.java.index

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter

/**
 * Detects runnable `main` entry points in a `.java` file from a structural PSI parse — the IntelliJ-PSI
 * replacement for the JDT `JavaMainScan`. Recognizes the JVM-standard `public static void main(String[])` (in
 * any top-level or nested type), plus — as a convenience — an *instance* `void main()` / `void main(String[])`
 * on a concrete top-level class with a no-arg constructor (mirroring Java's newer instance-`main`). Public so
 * the run service can scan on demand while the index is still building.
 */
object JavaMainScan {

    /** Standalone scan of [text] (input-less callers, e.g. the run service's cold-start fallback). */
    fun scan(text: String): List<Pair<String, Boolean>> =
        JavaSourceIndexer.parseStructural(text) { mainsOf(it) } ?: emptyList()

    /** Each hit: the class FQN to launch (`.`-package with `$`-nested binary name) paired with whether it must
     *  be invoked on an instance (no static `main`). */
    fun mainsOf(psi: PsiJavaFile?): List<Pair<String, Boolean>> {
        if (psi == null) return emptyList()
        val pkg = psi.packageName.ifEmpty { null }
        val out = LinkedHashSet<Pair<String, Boolean>>()
        psi.classes.forEach { visit(it, pkg, null, topLevel = true, out) }
        return out.toList()
    }

    private fun visit(
        cls: PsiClass, pkg: String?, enclosingBinary: String?, topLevel: Boolean,
        out: MutableSet<Pair<String, Boolean>>,
    ) {
        val name = cls.name ?: return
        val binary = when {
            enclosingBinary != null -> "$enclosingBinary\$$name"
            pkg.isNullOrEmpty() -> name
            else -> "$pkg.$name"
        }
        var staticFound = false
        for (m in cls.methods) {
            if (m.isConstructor || m.name != "main" || !isVoid(m)) continue
            if (m.hasModifierProperty(PsiModifier.STATIC) && m.hasModifierProperty(PsiModifier.PUBLIC) && isStringArrayParams(m)) {
                out.add(binary to false); staticFound = true
            }
        }
        if (topLevel && !staticFound && !cls.isInterface && !cls.hasModifierProperty(PsiModifier.ABSTRACT) && hasNoArgCtor(cls)) {
            for (m in cls.methods) {
                if (m.isConstructor || m.name != "main" || !isVoid(m)) continue
                if (m.hasModifierProperty(PsiModifier.STATIC) || m.hasModifierProperty(PsiModifier.ABSTRACT) ||
                    m.hasModifierProperty(PsiModifier.PRIVATE)
                ) continue
                if (m.parameterList.isEmpty || isStringArrayParams(m)) { out.add(binary to true); break }
            }
        }
        cls.innerClasses.forEach { visit(it, pkg, binary, topLevel = false, out) }
    }

    private fun hasNoArgCtor(cls: PsiClass): Boolean {
        val ctors = cls.constructors
        return ctors.isEmpty() || ctors.any { it.parameterList.isEmpty }
    }

    private fun isVoid(m: PsiMethod): Boolean = m.returnType?.canonicalText == "void"

    /** True when [m] takes exactly one `String[]` / `String...` parameter (its only valid `main` shape). */
    private fun isStringArrayParams(m: PsiMethod): Boolean {
        val params = m.parameterList.parameters
        if (params.size != 1) return false
        return isStringArray(params[0])
    }

    private fun isStringArray(p: PsiParameter): Boolean {
        // PsiEllipsisType (varargs) is a PsiArrayType, so this covers both `String[]` and `String...`.
        val arr = p.type as? PsiArrayType ?: return false
        val comp = arr.componentType
        if (comp is PsiArrayType) return false // depth-1 only
        val simple = comp.canonicalText.substringAfterLast('.') // no classpath => may be "String"
        return simple == "String"
    }
}
