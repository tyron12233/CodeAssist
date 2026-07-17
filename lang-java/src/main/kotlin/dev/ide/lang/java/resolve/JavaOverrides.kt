package dev.ide.lang.java.resolve

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

/** Override / abstract-member analysis over IntelliJ PSI, shared by the abstract-not-implemented diagnostic
 *  ([JavaSemanticDiagnostics]) and the implement-members fix (`analysis/JavaQuickFixes`). */
internal object JavaOverrides {

    /**
     * The abstract methods [cls] (a concrete or partially-abstract class) inherits but hasn't implemented — the
     * still-abstract winner per erased signature after concrete overrides / `default`s are accounted for.
     * Empty for an interface / annotation / enum (their abstract-member rules differ).
     */
    fun unimplemented(cls: PsiClass): List<PsiMethod> {
        if (cls.isInterface || cls.isAnnotationType || cls.isEnum) return emptyList()
        val bySig = LinkedHashMap<String, PsiMethod>()
        for (m in cls.allMethods) {
            if (m.isConstructor || m.hasModifierProperty(PsiModifier.STATIC) || m.hasModifierProperty(PsiModifier.PRIVATE)) continue
            val key = erasedSignature(m)
            val cur = bySig[key]
            if (cur == null || (cur.hasModifierProperty(PsiModifier.ABSTRACT) && !m.hasModifierProperty(PsiModifier.ABSTRACT))) {
                bySig[key] = m
            }
        }
        return bySig.values.filter { it.hasModifierProperty(PsiModifier.ABSTRACT) }
    }

    /** Name + erased parameter types — a coarse override-matching key (good enough for stubs + duplicate/abstract
     *  detection; not full JLS override equivalence). */
    fun erasedSignature(m: PsiMethod): String =
        m.name + "(" + m.parameterList.parameters.joinToString(",") { it.type.canonicalText } + ")"
}
