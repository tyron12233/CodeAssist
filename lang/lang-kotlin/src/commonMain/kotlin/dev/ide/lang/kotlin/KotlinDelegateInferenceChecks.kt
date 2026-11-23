package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.resolve.KotlinConstraintSystem
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * A delegate whose class's type variables the property's `getValue`/`setValue` calls cannot infer:
 * `val B.w: Int by MyProperty()` for `class MyProperty<R : A, T> { operator fun getValue(thisRef: R, …): T }`
 * (the receiver `B` is no `A`), `var a by MyProperty()` where nothing constrains the value type, `var a1: Int by A()`
 * for an `A<R>` whose special functions never mention `R`. The delegate expression `MyProperty()` is inferred
 * together with the special functions, from what the property passes (its `thisRef`: the extension receiver, else the
 * class instance, else `null` at top level) and its written type (the getter's result goes below it, the setter's
 * value above it). A variable those contradict, or leave with nothing but its declared bound, is uninferred:
 * CANNOT_INFER_PARAMETER_TYPE on the delegate's constructor, and the special functions become
 * DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE.
 *
 * Judged for a no-argument constructor call of a generic class of this file with no supertypes, declaring its
 * `getValue` (and, for a `var`, `setValue`) itself, when the file declares no extension special function and
 * star-imports nothing. A special function's own type parameters are not followed: no constraint through them.
 */
internal fun KotlinSemanticChecks.genericDelegateNotInferred(prop: KtProperty, resolver: KotlinResolver): List<Diagnostic> {
    val delegate = prop.delegate ?: return emptyList()
    val call = delegate.expression as? KtCallExpression ?: return emptyList()
    if (call.typeArgumentList != null || call.valueArguments.isNotEmpty() || call.lambdaArguments.isNotEmpty()) return emptyList()
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return emptyList()
    val name = callee.getReferencedName()
    val file = prop.containingKtFile
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return emptyList()
    val cls = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name } ?: return emptyList()
    if (cls.isInterface() || cls.isEnum() || cls.superTypeListEntries.isNotEmpty() || cls.typeConstraints.isNotEmpty()) return emptyList()
    val tps = cls.typeParameters.mapNotNull { it.name }
    if (tps.isEmpty() || tps.size != cls.typeParameters.size) return emptyList()
    // Nothing else may supply a special function: an extension anywhere in the file, or a `provideDelegate`.
    var extensions = false
    fun visit(e: KtElement) {
        if (e is KtNamedFunction && e.receiverTypeReference != null && e.name in SPECIAL_FUNCTIONS) extensions = true
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    if (extensions) return emptyList()
    val functions = cls.declarations.filterIsInstance<KtNamedFunction>()
    if (functions.any { it.name == "provideDelegate" }) return emptyList()
    val getters = functions.filter { it.name == "getValue" }
    val setters = functions.filter { it.name == "setValue" }
    val getter = getters.singleOrNull() ?: return emptyList()
    val setter = if (prop.isVar) setters.singleOrNull() ?: return emptyList() else null
    if (getter.valueParameters.size != 2 || (setter != null && setter.valueParameters.size != 3)) return emptyList()
    if ((listOfNotNull(getter, setter)).any { f -> f.receiverTypeReference != null || f.valueParameters.any { it.isVarArg || it.hasDefaultValue() } }) return emptyList()
    val clsFqn = cls.fqName?.asString() ?: return emptyList()
    val names = tps.toSet()
    fun type(ref: KtTypeReference?, owner: String?): KotlinType? =
        ref?.text?.let { service.typeFromText(it, resolver.fileContext, owner) }?.let { service.markTypeParameters(it, names) }
    // What the property passes as `thisRef`.
    val owner = (prop.parent as? KtClassBody)?.parent as? KtClassOrObject
    val thisRef: KotlinType? = when {
        prop.receiverTypeReference != null -> service.typeFromText(prop.receiverTypeReference!!.text, resolver.fileContext, owner?.fqName?.asString()) ?: return emptyList()
        owner != null -> if (owner.typeParameters.isNotEmpty()) return emptyList() else KotlinType(owner.fqName?.asString() ?: return emptyList())
        prop.parent is KtFile -> KotlinType("kotlin.Nothing", nullable = true)
        else -> return emptyList()
    }
    if (thisRef != null && !knownWhole(thisRef)) return emptyList()
    val propType = prop.typeReference?.let { service.typeFromText(it.text, resolver.fileContext, owner?.fqName?.asString()) ?: return emptyList() }
    if (propType != null && !knownWhole(propType)) return emptyList()
    val cs = KotlinConstraintSystem(service, nullableVariableBelowNonNull = true)
    for (tp in cls.typeParameters) {
        val bound = tp.extendsBound?.let { b -> type(b, clsFqn) ?: return emptyList() }
        if (bound != null && mentionsAnyOf(bound, names)) return emptyList()
        cs.registerVariable(tp.name!!, bound)
    }
    /** Constrain `sub <: sup` unless either names one of [f]'s own type parameters, which this does not follow. */
    fun constrain(f: KtNamedFunction, sub: KotlinType?, sup: KotlinType?) {
        if (sub == null || sup == null) return
        val own = f.typeParameters.mapNotNull { it.name }.toSet()
        if (mentionsAnyOf(sub, own) || mentionsAnyOf(sup, own)) return
        cs.addSubtypeConstraint(sub, sup)
    }
    for (f in listOfNotNull(getter, setter)) {
        val ownNames = f.typeParameters.mapNotNull { it.name }.toSet()
        val thisParam = type(f.valueParameters[0].typeReference, clsFqn) ?: return emptyList()
        if (!mentionsAnyOf(thisParam, ownNames) && !knownOrVariables(thisParam, names)) return emptyList()
        constrain(f, thisRef, thisParam)
    }
    if (propType != null) {
        val ret = type(getter.typeReference ?: return emptyList(), clsFqn) ?: return emptyList()
        constrain(getter, ret, propType)
        if (setter != null) {
            val value = type(setter.valueParameters[2].typeReference, clsFqn) ?: return emptyList()
            constrain(setter, propType, value)
        }
    }
    // Contradicted (a variable left no type it may take; two disagreeing bounds are not this), or unconstrained.
    val uninferred = if (cs.hasContradiction) {
        if (!cs.variableLeftWithoutType) return emptyList()
        tps.filter { it in cs.variablesLeftWithoutType }
    } else tps.filter { !cs.isConstrained(it) }
    if (uninferred.isEmpty()) return emptyList()
    val by = generateSequence(delegate.firstChild) { it.nextSibling }.firstOrNull { it.elementType == KtTokens.BY_KEYWORD } ?: return emptyList()
    val br = by.textRange
    val cr = callee.textRange
    return listOf(
        Diagnostic(
            TextRange(br.startOffset, br.endOffset), Severity.ERROR,
            "None of the 'getValue'/'setValue' functions of '$name' can be called: its type arguments cannot be inferred",
            KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE,
        ),
    ) + uninferred.map { tp ->
        Diagnostic(
            TextRange(cr.startOffset, cr.endOffset), Severity.ERROR,
            "Cannot infer type for type parameter '$tp'. Specify it explicitly.",
            KotlinDiagnosticCodes.CANNOT_INFER_TYPE,
        )
    }
}

private val SPECIAL_FUNCTIONS = setOf("getValue", "setValue", "provideDelegate")

/** Every part of [t] a known class. */
private fun KotlinSemanticChecks.knownWhole(t: KotlinType): Boolean =
    !t.isTypeParameter && t.projection.isEmpty() && service.isKnownType(t.qualifiedName) &&
        t.typeArguments.all { (it as? KotlinType)?.let { a -> knownWhole(a) } == true }

/** Every part of [t] a known class or one of the variables [names]. */
private fun KotlinSemanticChecks.knownOrVariables(t: KotlinType, names: Set<String>): Boolean =
    if (t.isTypeParameter) t.qualifiedName in names
    else t.projection.isEmpty() && service.isKnownType(t.qualifiedName) &&
        t.typeArguments.all { (it as? KotlinType)?.let { a -> knownOrVariables(a, names) } == true }

private fun mentionsAnyOf(t: KotlinType, names: Set<String>): Boolean =
    (t.isTypeParameter && t.qualifiedName in names) || t.typeArguments.any { (it as? KotlinType)?.let { a -> mentionsAnyOf(a, names) } == true }
