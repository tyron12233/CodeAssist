package dev.ide.lang.java.resolve

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiUnaryExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.parse.JavaDiagnosticCodes

/**
 * Declaration / override / duplicate diagnostics over IntelliJ PSI — the compiler-error categories that
 * resolution alone doesn't surface (abstract-not-implemented, illegal overrides, duplicate members, bad member
 * shapes, cyclic inheritance, blank-`final`-field definite assignment). Each check is guarded to stay
 * false-positive-free: it backs off on incomplete (`PsiErrorElement`) code and unresolved supertypes, and only
 * reports what is unambiguously wrong by the JLS. Called per node from [JavaSemanticDiagnostics]'s single
 * locked walk. NOT complete javac parity — the build's ecj still catches the rest.
 */
internal object JavaDeclarationChecks {

    fun checkMethod(m: PsiMethod, out: MutableList<Diagnostic>) {
        if (m.isConstructor) return
        if (PsiTreeUtil.findChildOfType(m, PsiErrorElement::class.java) != null) return // incomplete → skip
        val cls = m.containingClass ?: return
        val nameEl = m.nameIdentifier ?: return
        val inInterface = cls.isInterface || cls.isAnnotationType
        val isAbstract = m.hasModifierProperty(PsiModifier.ABSTRACT)
        val isNative = m.hasModifierProperty(PsiModifier.NATIVE)
        val hasBody = m.body != null

        // Member-shape checks apply in a class body (interfaces allow abstract-no-body + default/static bodies).
        if (!inInterface) {
            if (isAbstract && hasBody) {
                out += diag(nameEl, "Abstract method '${m.name}' cannot have a body", JavaDiagnosticCodes.ILLEGAL_MEMBER)
            }
            if (!isAbstract && !isNative && !hasBody) {
                out += diag(nameEl, "Missing method body, or declare '${m.name}' abstract", JavaDiagnosticCodes.ILLEGAL_MEMBER)
            }
            if (isAbstract && !cls.hasModifierProperty(PsiModifier.ABSTRACT) && !cls.isEnum) {
                out += diag(nameEl, "Abstract method '${m.name}' in non-abstract class '${cls.name}'",
                    JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED)
            }
        }

        // Override checks — static methods hide (not override), so skip them; ditto private.
        if (m.hasModifierProperty(PsiModifier.STATIC) || m.hasModifierProperty(PsiModifier.PRIVATE)) return
        val supers = superMethodsOf(m)
        if (supers.isNotEmpty()) {
            if (supers.any { it.hasModifierProperty(PsiModifier.FINAL) }) {
                out += diag(nameEl, "'${m.name}' cannot override a final method", JavaDiagnosticCodes.INVALID_OVERRIDE)
            }
            // Weaker access: a public method must stay public when overridden (unambiguous reduction).
            if (supers.any { it.hasModifierProperty(PsiModifier.PUBLIC) } && !m.hasModifierProperty(PsiModifier.PUBLIC)) {
                out += diag(nameEl, "'${m.name}' cannot reduce the visibility of the overridden method",
                    JavaDiagnosticCodes.INVALID_OVERRIDE)
            }
            checkReturnType(m, cls, supers, nameEl, out)
            checkThrows(m, cls, supers, nameEl, out)
        } else if (
            m.hasAnnotation("java.lang.Override") &&
            !hasUnresolvedSupers(cls) &&
            !anySuperDeclaresMethodNamed(cls, m.name)
        ) {
            out += diag(nameEl, "Method '${m.name}' does not override or implement a method from a supertype",
                JavaDiagnosticCodes.INVALID_OVERRIDE)
        }
    }

    fun checkClass(cls: PsiClass, out: MutableList<Diagnostic>) {
        val nameEl = cls.nameIdentifier
        // Cyclic inheritance — a class that reaches itself through its extends/implements graph. Reported alone
        // (the supertype walk the other structural checks do is meaningless / could churn on a cycle).
        if (nameEl != null && isCyclic(cls)) {
            out += diag(nameEl, "Cyclic inheritance involving '${cls.name}'", JavaDiagnosticCodes.CYCLIC_INHERITANCE)
            return
        }
        // Abstract-not-implemented: a concrete class inheriting an unimplemented abstract method (its OWN
        // abstract methods are flagged per-method above, so restrict to inherited ones to avoid double-report).
        if (nameEl != null && !cls.isInterface && !cls.isAnnotationType && !cls.hasModifierProperty(PsiModifier.ABSTRACT) && !cls.isEnum) {
            val unimpl = JavaOverrides.unimplemented(cls).firstOrNull { it.containingClass != cls }
            if (unimpl != null) {
                out += diag(
                    nameEl,
                    "Class '${cls.name}' must implement abstract method '${unimpl.name}' in '${unimpl.containingClass?.name}'",
                    JavaDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
                )
            }
        }
        // Duplicate own methods (erased signature) + fields (name); report the later occurrence(s).
        val sigCount = HashMap<String, Int>()
        for (m in cls.methods) {
            if (m.nameIdentifier == null) continue
            if ((sigCount.merge(JavaOverrides.erasedSignature(m), 1, Int::plus) ?: 1) > 1) {
                out += diag(m.nameIdentifier!!, "'${m.name}' is already defined in '${cls.name}'", JavaDiagnosticCodes.DUPLICATE_MEMBER)
            }
        }
        val fieldCount = HashMap<String, Int>()
        for (f in cls.fields) {
            val id = f.nameIdentifier
            if ((fieldCount.merge(f.name, 1, Int::plus) ?: 1) > 1) {
                out += diag(id, "Variable '${f.name}' is already defined in '${cls.name}'", JavaDiagnosticCodes.DUPLICATE_MEMBER)
            }
        }
        checkBlankFinalFields(cls, out)
    }

    /**
     * A blank `final` field (no initializer) that is never assigned anywhere in the class is definitely
     * uninitialized — javac's "variable might not have been initialized". A final field can *only* be assigned
     * in a declaration/initializer/constructor, so "assigned nowhere" is unambiguous. Conservative on purpose:
     * a field assigned on *some* but not all constructor paths is NOT flagged (that needs per-constructor
     * definite-assignment with `this(...)`-delegation handling — FP-prone), and interfaces/annotations/records
     * (implicit initialization rules) plus incomplete (`PsiErrorElement`) classes are skipped entirely.
     */
    private fun checkBlankFinalFields(cls: PsiClass, out: MutableList<Diagnostic>) {
        if (cls.isInterface || cls.isAnnotationType || cls.isRecord) return
        val blanks = cls.fields.filter {
            // Enum constants are implicitly-final PsiFields with no `= expr` initializer (they init via an
            // argument list), and are never the target of an assignment — they must not be flagged as blank finals.
            it !is PsiEnumConstant &&
                it.hasModifierProperty(PsiModifier.FINAL) && it.initializer == null && it.nameIdentifier != null
        }
        if (blanks.isEmpty()) return
        if (PsiTreeUtil.findChildOfType(cls, PsiErrorElement::class.java) != null) return // half-typed → skip
        val assigned = assignedFieldsUnder(cls)
        for (f in blanks) {
            if (f !in assigned) {
                out += diag(f.nameIdentifier!!, "Variable '${f.name}' might not have been initialized",
                    JavaDiagnosticCodes.NOT_INITIALIZED)
            }
        }
    }

    /** The set of fields written (by `=`/compound assignment or `++`/`--`) anywhere under [cls]. Resolution
     *  disambiguates same-named fields of nested classes, so membership answers "was THIS field ever assigned". */
    private fun assignedFieldsUnder(cls: PsiClass): Set<PsiField> {
        val assigned = HashSet<PsiField>()
        cls.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                targetField(expression.lExpression)?.let { assigned += it }
            }

            override fun visitUnaryExpression(expression: PsiUnaryExpression) {
                super.visitUnaryExpression(expression)
                val op = expression.operationTokenType
                if (op == JavaTokenType.PLUSPLUS || op == JavaTokenType.MINUSMINUS)
                    targetField(expression.operand)?.let { assigned += it }
            }
        })
        return assigned
    }

    private fun targetField(e: PsiExpression?): PsiField? = (e as? PsiReferenceExpression)?.resolve() as? PsiField

    /** The overriding method's return type must be substitutable for EACH overridden method's (generic-
     *  substituted into this class's context). Only checked when both types are concrete (see [isConcrete]) so
     *  generic/inference edge cases never false-positive. */
    private fun checkReturnType(m: PsiMethod, cls: PsiClass, supers: List<PsiMethod>, nameEl: PsiElement, out: MutableList<Diagnostic>) {
        val myRet = m.returnType ?: return
        for (sm in supers) {
            val superCls = sm.containingClass ?: continue
            val subst = runCatching { TypeConversionUtil.getSuperClassSubstitutor(superCls, cls, PsiSubstitutor.EMPTY) }.getOrNull() ?: continue
            val superRet = subst.substitute(sm.returnType) ?: continue
            if (returnSubstitutable(myRet, superRet)) continue
            out += diag(nameEl, "Return type '${myRet.presentableText}' is incompatible with '${superRet.presentableText}' in the overridden method",
                JavaDiagnosticCodes.INVALID_OVERRIDE)
            return // one report per method
        }
    }

    private fun returnSubstitutable(sub: PsiType, sup: PsiType): Boolean = when {
        TypeConversionUtil.isVoidType(sup) -> TypeConversionUtil.isVoidType(sub)
        sup is PsiPrimitiveType || sub is PsiPrimitiveType -> sub == sup                 // primitives must be identical
        !isConcrete(sup) || !isConcrete(sub) -> true                                     // generics/unresolved → back off
        else -> TypeConversionUtil.isAssignable(sup, sub)                                // covariance: sub <: sup
    }

    /** No checked exception the override declares may fall outside EVERY overridden method's (substituted)
     *  throws set (an override can only narrow). Unchecked / unresolved exceptions are ignored. */
    private fun checkThrows(m: PsiMethod, cls: PsiClass, supers: List<PsiMethod>, nameEl: PsiElement, out: MutableList<Diagnostic>) {
        val declared = m.throwsList.referencedTypes.filterIsInstance<PsiClassType>().filter { JavaExceptions.isChecked(it) }
        if (declared.isEmpty()) return
        val superSets = supers.mapNotNull { sm ->
            val superCls = sm.containingClass ?: return@mapNotNull null
            val subst = runCatching { TypeConversionUtil.getSuperClassSubstitutor(superCls, cls, PsiSubstitutor.EMPTY) }.getOrNull()
                ?: return@mapNotNull null
            sm.throwsList.referencedTypes.mapNotNull { subst.substitute(it) as? PsiClassType }
        }
        if (superSets.size != supers.size) return // a substitutor failed → don't risk a false positive
        for (e in declared) {
            val coveredEverywhere = superSets.all { set -> set.any { TypeConversionUtil.isAssignable(it, e) } }
            if (!coveredEverywhere) {
                out += diag(nameEl, "Overridden method does not throw '${e.presentableText}'", JavaDiagnosticCodes.INVALID_OVERRIDE)
            }
        }
    }

    /** A type concrete enough to compare by assignability — resolved class / array-of-concrete, no type
     *  parameter / wildcard. Primitives are handled before this is consulted. */
    private fun isConcrete(t: PsiType?): Boolean = when (t) {
        null -> false
        is PsiPrimitiveType -> true
        is PsiArrayType -> isConcrete(t.componentType)
        is PsiClassType -> {
            val r = t.resolve()
            r != null && r !is PsiTypeParameter && t.parameters.all { isConcrete(it) }
        }
        else -> false
    }

    /**
     * The methods [m] directly overrides / implements — the ART-safe equivalent of [PsiMethod.findSuperMethods].
     * `findSuperMethods()` throws `NoClassDefFoundError: java.beans.Introspector` on device (a class absent from
     * ART; see `IntrospectorArtPass`), which — swallowed to an empty list — silently disabled the final-override
     * / visibility / covariance / throws checks below. The hierarchical method signature gives the same
     * immediately-overridden methods without touching `java.beans` (proven ART-safe by `FindSuperMethodsArtProbeTest`:
     * `hierSuperSigs` was non-zero even when `findSuperMethods` threw), so these checks hold on device INDEPENDENT
     * of the shim staying complete across compiler bumps. Still `runCatching`-guarded (belt-and-suspenders).
     */
    private fun superMethodsOf(m: PsiMethod): List<PsiMethod> =
        runCatching { m.hierarchicalMethodSignature.superSignatures.map { it.method } }.getOrDefault(emptyList())

    /**
     * Whether [cls]'s supertype hierarchy can't be fully walked — then `findSuperMethods()` may miss a real
     * override and the `@Override`-on-a-non-override check would false-positive, so it backs off. Walks the
     * TRANSITIVE closure, not just the direct `extends`/`implements` refs: a resolvable direct super whose OWN
     * ancestor is off the classpath (a missing transitive dependency, or the platform SDK jar) still breaks
     * override resolution. That is the common Android case — `AppCompatActivity` resolves from its AAR while
     * `android.app.Activity` (where `onCreate` is actually declared) is unresolved. [directSuperRefs] also reads
     * an anonymous class's base reference, which is NOT in its (empty) extends/implements lists — covering
     * `new View.OnClickListener() { @Override onClick }`.
     */
    private fun hasUnresolvedSupers(cls: PsiClass): Boolean {
        val visited = HashSet<PsiClass>().apply { add(cls) }
        val stack = ArrayDeque<PsiClass>().apply { add(cls) }
        while (stack.isNotEmpty()) {
            for (ref in directSuperRefs(stack.removeLast())) {
                val resolved = ref.resolve() as? PsiClass ?: return true
                if (resolved.qualifiedName == "java.lang.Object") continue
                if (visited.add(resolved)) stack.add(resolved)
            }
        }
        return false
    }

    /** The direct `extends`/`implements` references of [c] — including an anonymous class's base, which lives on
     *  [PsiAnonymousClass.getBaseClassReference], not the (empty) extends/implements lists. */
    private fun directSuperRefs(c: PsiClass): List<PsiJavaCodeReferenceElement> =
        if (c is PsiAnonymousClass) listOf(c.baseClassReference) else supersRefs(c).toList()

    /**
     * Whether any supertype of [cls] declares a method named [name] — a NAME-only scan over the resolved
     * supertype closure, reading each class's OWN declared methods ([PsiClass.findMethodsByName] with
     * `checkBases = false`). This is deliberately NOT `findSuperMethods()`: that also requires an exact
     * erased-signature match AND a fully-primed class-hierarchy cache, and it comes back empty for a genuine
     * override in some environments — notably the on-device (`Cls`-decompiled library/SDK) path — which
     * false-positives the `@Override`-on-a-non-override check on perfectly valid code (the reported
     * `MainActivity extends AppCompatActivity` / anonymous `View.OnClickListener`, where every type resolved).
     * A same-named method in a supertype means the annotation is almost certainly a real override (or at worst a
     * signature mismatch the build's compiler will catch), so the check backs off. Reading a class's own method
     * list stays reliable even when the platform's hierarchy walk isn't. [directSuperRefs] covers anonymous bases.
     */
    private fun anySuperDeclaresMethodNamed(cls: PsiClass, name: String): Boolean {
        val visited = HashSet<PsiClass>().apply { add(cls) }
        val stack = ArrayDeque<PsiClass>().apply { add(cls) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c !== cls && c.findMethodsByName(name, false).isNotEmpty()) return true
            for (ref in directSuperRefs(c)) (ref.resolve() as? PsiClass)?.let { if (visited.add(it)) stack.add(it) }
        }
        return false
    }

    private fun supersRefs(c: PsiClass) =
        (c.extendsList?.referenceElements ?: emptyArray()) + (c.implementsList?.referenceElements ?: emptyArray())

    /** Whether [start] reaches itself through its extends/implements graph (walking the RAW references, which
     *  resolve to the cyclic class even though `getSuperClass()` breaks the cycle). Visited-guarded. */
    private fun isCyclic(start: PsiClass): Boolean {
        val visited = HashSet<PsiClass>()
        fun reaches(c: PsiClass): Boolean {
            for (ref in supersRefs(c)) {
                val s = ref.resolve() as? PsiClass ?: continue
                if (s === start) return true
                if (visited.add(s) && reaches(s)) return true
            }
            return false
        }
        return reaches(start)
    }

    private fun diag(element: PsiElement, message: String, code: String): Diagnostic {
        val r = element.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code = code)
    }
}
