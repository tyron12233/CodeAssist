package dev.ide.lang.java.resolve

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

/** Override / abstract-member analysis over IntelliJ PSI, shared by the abstract-not-implemented diagnostic
 *  ([JavaSemanticDiagnostics]) and the implement-members fix (`analysis/JavaQuickFixes`). */
internal object JavaOverrides {

    /**
     * The abstract methods [cls] (a concrete or partially-abstract class) inherits but hasn't implemented — the
     * still-abstract winner of each visible signature after concrete overrides / `default`s are accounted for.
     * Empty for an interface / annotation / enum / record (their abstract-member rules differ).
     *
     * Records are excluded because `java.lang.Record` declares `equals`/`hashCode`/`toString` abstract and the
     * compiler synthesizes them for every record — but PSI does not surface the synthesized implementations, so
     * they would otherwise report as unimplemented on every record.
     *
     * Uses the platform's own [PsiClass.getVisibleSignatures] (generic-substitution-aware override resolution)
     * rather than a name+parameter-text key: a signature key can't match an override across type substitution
     * (`Comparable<Money>.compareTo(Money)` implements the inherited `compareTo(T)`, but the two have different
     * parameter texts — and even erased they differ, `Object` vs `Money`), so signature matching false-positived
     * on the extremely common "implement a generic interface" case.
     */
    fun unimplemented(cls: PsiClass): List<PsiMethod> {
        if (cls.isInterface || cls.isAnnotationType || cls.isEnum || cls.isRecord) return emptyList()
        return cls.visibleSignatures.mapNotNull { sig ->
            val m = sig.method
            if (m.isConstructor || m.hasModifierProperty(PsiModifier.STATIC) || m.hasModifierProperty(PsiModifier.PRIVATE)) return@mapNotNull null
            m.takeIf { it.hasModifierProperty(PsiModifier.ABSTRACT) }
        }
    }

    /** Name + erased parameter types — a coarse override-matching key (good enough for stubs + duplicate/abstract
     *  detection; not full JLS override equivalence). */
    fun erasedSignature(m: PsiMethod): String =
        m.name + "(" + m.parameterList.parameters.joinToString(",") { it.type.canonicalText } + ")"
}
