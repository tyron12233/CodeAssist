package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.contextualFunctionType
import dev.ide.lang.kotlin.resolve.localFunctionsInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.typeDenotationFqn

/*
 * INACCESSIBLE_OUTER_CLASS_RECEIVER (FirCallResolver / the tower's implicit receivers): a nested class (not
 * `inner`) or an object has no instance of the classes around it, so from inside one an instance member of an
 * outer class, an `inner` class of it, or `this@Outer` names something there is no receiver for:
 * `class Outer { fun f() {}; class Nested { fun g() = f() } }`. Found by walking outward from the use: the first
 * class that supplies the name decides, and the use is an error when a nested class or an object was crossed on
 * the way. A local class, an object expression and an enum entry are transparent (they see the enclosing
 * receivers) but supply their own declarations.
 */

/** A bare name (a value, a call, or a constructor written as a type in a supertype list) that only an
 *  unreachable outer instance supplies. */
internal fun KotlinSemanticChecks.inaccessibleOuterReceiver(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val parent = ref.parent
    // A named argument's label, an import or a package names no value here.
    if (parent is dev.ide.kotlin.syntax.psi.KtValueArgumentName) return null
    if (ref.getStrictParentOfType<dev.ide.kotlin.syntax.psi.KtImportDirective>() != null ||
        ref.getStrictParentOfType<dev.ide.kotlin.syntax.psi.KtPackageDirective>() != null) return null
    if (parent is KtQualifiedExpression && parent.selectorExpression === ref) return null
    if (parent is KtCallableReferenceExpression) return null
    val call = (parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref }
    if (call != null && (call.parent as? KtQualifiedExpression)?.selectorExpression === call) return null
    // A type is judged only as a supertype constructor call (`object O : InnerClass()`).
    val userType = parent as? KtUserType
    if (userType != null && (userType.qualifier != null || userType.getStrictParentOfType<KtSuperTypeCallEntry>() == null)) return null
    val asConstructor = call != null || userType != null
    if (userType == null && hasTypeReferenceAncestor(ref)) return null
    val off = ref.textRange.startOffset
    if (userType == null) {
        if (resolver.localsAt(off, name).isNotEmpty() || resolver.localFunctionsInScope(off, name).isNotEmpty()) return null
        // A top-level or imported declaration of the name is a candidate the call can resolve to instead.
        if (service.topLevelByName(name).isNotEmpty()) return null
    }
    if (resolver.fileContext.imports.any { it.alias == name || !it.isStar && it.fqn.substringAfterLast('.') == name }) return null
    val (owner, crossed) = outerSupplierCrossing(ref, resolver) { cls, fqn -> supplies(cls, fqn, name, asConstructor) } ?: return null
    val r = ref.textRange
    // An inner class constructed from its outer class's companion: the constructor has no outer instance to take.
    val fromCompanion = crossed.singleOrNull()?.let { it is KtObjectDeclaration && it.isCompanion() && it.parent?.parent === owner } == true
    if (call != null && fromCompanion) return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Constructor of inner class '$name' can be called only with a receiver of '${owner.name}'",
        KotlinDiagnosticCodes.INNER_CLASS_CONSTRUCTOR_NO_RECEIVER,
    )
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "'$name' needs an instance of '${owner.name}', which is not available from inside a nested class or an object",
        KotlinDiagnosticCodes.INACCESSIBLE_OUTER_CLASS_RECEIVER,
    )
}

/**
 * INNER_CLASS_CONSTRUCTOR_NO_RECEIVER: an inner class constructed through its outer class's TYPE (`Outer.Inner()`,
 * `Outer.Inner.InnerInner()`) or by a bare name an import brings into code with no class around it: there is no
 * instance of the outer class to construct it on. (`Outer().Inner()` has one.)
 */
internal fun KotlinSemanticChecks.innerConstructorWithoutReceiver(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val call = (ref.parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref } ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty() || !name[0].isUpperCase()) return null
    val dot = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    val fqn = if (dot != null) {
        val outer = resolver.typeDenotationFqn(dot.receiverExpression) ?: return null
        "$outer.$name"
    } else {
        // Bare: only where nothing around the call could supply an outer instance.
        var node: KtElement? = ref.parent
        while (node != null && node !is KtFile) {
            if (node is KtClassOrObject || node is KtLambdaExpression) return null
            if (node is KtNamedFunction && node.receiverTypeReference != null) return null
            if (node is KtProperty && node.receiverTypeReference != null) return null
            node = node.parent
        }
        if (resolver.localsAt(ref.textRange.startOffset, name).isNotEmpty()) return null
        resolver.constructorTypeFqn(name, ref.textRange.startOffset) ?: return null
    }
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
    if (!cls.isInner()) return null
    val outer = cls.getStrictParentOfType<KtClassOrObject>() ?: return null
    val r = ref.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Constructor of inner class '$name' can be called only with a receiver of '${outer.name}'",
        KotlinDiagnosticCodes.INNER_CLASS_CONSTRUCTOR_NO_RECEIVER,
    )
}

/** `this@Outer` naming a class whose instance a nested class or object between here and there cuts off. */
internal fun KotlinSemanticChecks.inaccessibleOuterThis(e: KtThisExpression, resolver: KotlinResolver): Diagnostic? {
    val label = e.getLabelName() ?: return null
    val owner = outerSupplier(e, resolver) { cls, _ -> cls.name == label } ?: return null
    val r = e.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "'this@$label' is not available from inside a nested class or an object of '${owner.name}'",
        KotlinDiagnosticCodes.INACCESSIBLE_OUTER_CLASS_RECEIVER,
    )
}

private enum class Supply { NONE, ACCESSIBLE, INSTANCE, UNKNOWN }

/**
 * The class that supplies what [at] names when a nested class or object lies between them (so its instance is
 * out of reach), or null when the name is reached without crossing one, is supplied by nothing walked, or the
 * walk meets something it cannot judge (a lambda or extension receiver that might supply it).
 */
private fun KotlinSemanticChecks.outerSupplier(
    at: KtElement, resolver: KotlinResolver, supplies: (KtClassOrObject, String?) -> Any,
): KtClassOrObject? = outerSupplierCrossing(at, resolver, supplies)?.first

/** [outerSupplier], with the nested classes and objects crossed on the way to it. */
private fun KotlinSemanticChecks.outerSupplierCrossing(
    at: KtElement, resolver: KotlinResolver, supplies: (KtClassOrObject, String?) -> Any,
): Pair<KtClassOrObject, List<KtClassOrObject>>? {
    var cut = false
    val crossed = ArrayList<KtClassOrObject>()
    var node: KtElement? = at.parent
    while (node != null && node !is KtFile) {
        when (node) {
            is KtLambdaExpression -> {
                // A lambda with a receiver (`with(x) { }`) brings that receiver's members into scope.
                val ft = resolver.contextualFunctionType(node) ?: return null
                val shape = service.functionalShape(ft) ?: return null
                if (shape.receiverType != null) return null
            }
            // An extension receiver supplies its type's members.
            is KtNamedFunction -> if (node.receiverTypeReference != null) return null
            is KtProperty -> if (node.receiverTypeReference != null) return null
            is KtClassOrObject -> {
                val memberLevel = node.parent is KtClassBody || node.parent is KtFile
                // An enum entry's body is a subclass of its enum: it sees what the enum sees.
                val transparent = !memberLevel || (node is KtObjectDeclaration && node.isObjectLiteral()) ||
                    node is dev.ide.kotlin.syntax.psi.KtEnumEntry
                val fqn = if (transparent) null else node.fqName?.asString()
                when (val s = supplies(node, fqn)) {
                    true, Supply.INSTANCE -> return if (cut) node to crossed else null
                    Supply.ACCESSIBLE -> return null
                    Supply.UNKNOWN -> return null
                    else -> {}
                }
                if (!transparent && (node is KtObjectDeclaration || (node is KtClass && !node.isInner()))) { cut = true; crossed += node }
            }
        }
        node = node.parent
    }
    return null
}

/** What [cls] supplies under [name]: an instance member or an `inner` class (reached only through its instance),
 *  a companion member or a nested class or object (reached without one), nothing, or something not judged. */
private fun KotlinSemanticChecks.supplies(cls: KtClassOrObject, fqn: String?, name: String, asConstructor: Boolean): Supply {
    // Its own declarations, read off the tree (the only way to see a local or anonymous class's).
    val own = cls.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name } +
        (cls as? KtClass)?.primaryConstructor?.valueParameters.orEmpty().filter { it.hasValOrVar() && it.name == name }
    if (fqn == null) return if (own.isNotEmpty()) Supply.ACCESSIBLE else Supply.NONE
    // The class and its supertypes: a classifier or a companion member of any of them is in this class's scope.
    val supers = listOf(fqn) + service.supertypesOf(fqn).mapNotNull { (it as? dev.ide.lang.kotlin.symbols.KotlinType)?.qualifiedName }
    if (asConstructor) {
        for (s in supers) {
            val nested = service.sourceClass("$s.$name") ?: continue
            val psi = (nested.node as? KotlinDomNode)?.psi
            return if (psi is KtClass && psi.isInner()) Supply.INSTANCE else Supply.ACCESSIBLE
        }
    }
    // A companion's member, the class's own or a supertype's, needs no instance: where the outer instance is out of
    // reach it is what the name resolves to (`@Anno(CONST)` in a nested class reads a supertype companion's const).
    if (supers.any { s -> service.companionMembersFor(s, name).any { it.name == name } }) return Supply.ACCESSIBLE
    // An enum's entries and its `values()` / `valueOf()` / `entries` are static.
    if (cls is KtClass && cls.isEnum() &&
        (name in ENUM_STATICS || cls.declarations.any { it is dev.ide.kotlin.syntax.psi.KtEnumEntry && it.name == name })) return Supply.ACCESSIBLE
    val members = service.membersNamedForCheck(fqn, emptyList(), name).filter { it.name == name }
    if (members.isEmpty()) return if (own.isNotEmpty()) Supply.UNKNOWN else Supply.NONE
    if (members.any { !it.origin.fromSource || it.isExtension }) return Supply.UNKNOWN
    // A member of an object is reached through the object, which needs no instance.
    if (cls is KtObjectDeclaration) return Supply.ACCESSIBLE
    return Supply.INSTANCE
}

private fun hasTypeReferenceAncestor(e: KtElement): Boolean =
    e.getStrictParentOfType<dev.ide.kotlin.syntax.psi.KtTypeReference>() != null

private val ENUM_STATICS = setOf("values", "valueOf", "entries")
