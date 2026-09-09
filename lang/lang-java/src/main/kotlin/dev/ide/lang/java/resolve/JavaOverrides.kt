package dev.ide.lang.java.resolve

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.TypeConversionUtil

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

    /**
     * Override-matching key for member ENUMERATION (`allMethods` de-duplication). Unlike [erasedSignature] the
     * parameter types are really erased, so an override that only re-states its parameters more precisely still
     * matches what it overrides (`addAll(Collection<? extends E>)` / `addAll(Collection<E>)` → `addAll(Collection)`).
     * Deliberately coarse: merging here only ever drops a row that would insert identical text, and a real
     * overload differs in erased parameters by definition. An override across type SUBSTITUTION
     * (`compareTo(T)` vs `compareTo(Money)`) still keys apart, so both rows survive as they did before.
     */
    fun enumerationKey(m: PsiMethod): String = buildString {
        append(m.name).append('(')
        m.parameterList.parameters.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append((TypeConversionUtil.erasure(p.type) ?: p.type).canonicalText)
        }
        append(')')
    }

    /**
     * Collapse a most-derived-first member sequence (`PsiClass.allMethods` / `allFields`) to the declaration
     * actually reachable through the receiver: the first one per [key]. Those APIs report every declaration in
     * the supertype graph, so an overridden method or a shadowed field arrives once per level: three identical
     * `setVisibility` rows on an Android `Button`, all inserting the same text.
     */
    fun <T : PsiMember> mostDerived(members: Iterable<T>, key: (T) -> String): Collection<T> {
        val out = LinkedHashMap<String, T>()
        for (m in members) out.putIfAbsent(key(m), m)
        return out.values
    }
}
