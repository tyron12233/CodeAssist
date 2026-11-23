package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enumConstantNames
import dev.ide.lang.kotlin.resolve.typeDenotationFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt

/**
 * A segment of a QUALIFIED type that names nothing (`x: Outer.Foo`, `x: Foo.Bar`); [unresolvedTypeReference]
 * judges only a simple name. Two shapes are judged, each only where the scope is completely known:
 *  - a segment under a project-source class whose nested classifiers are all in the source model
 *    (`Outer.Foo`, `Outer.Nested.Foo`), when no supertype of that class could contribute one either;
 *  - the head of the chain (`Foo` of `Foo.Bar`), Capitalized like a type and resolving to no type, alias,
 *    type parameter or package the file or the classpath knows.
 */
internal fun KotlinSemanticChecks.unresolvedQualifiedType(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
    val ref = userType.referenceExpression ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty() || ref.text.startsWith("`")) return null
    val off = userType.textRange.startOffset
    val qualifier = userType.qualifier
    if (qualifier == null) {
        if (userType.parent !is KtUserType) return null
        // "No such package" is read off the classpath index, which answers it only once built.
        if (!name[0].isUpperCase() || !service.classpathIndexReady()) return null
        if (resolver.isTypeParameterInScope(name, off) || resolver.localTypesInScope(off).containsKey(name)) return null
        val ctx = resolver.fileContext
        if (service.resolveTypeName(name, ctx, resolver.enclosingClassFqn(off)) != null) return null
        if (service.isProjectTypeAlias(name) || service.isNestedTypeAliasInScope(name, resolver.enclosingClassFqn(off))) return null
        if (service.isRootPackage(name) || ctx.packageName.split('.').firstOrNull() == name) return null
        if (ctx.imports.any { imp -> imp.fqn.split('.').any { it == name } || imp.alias == name }) return null
        return unresolved(ref, name)
    }
    val outer = qualifiedSourceClassFqn(qualifier, resolver) ?: return null
    if (service.isKnownType("$outer.$name") || service.isProjectTypeAliasFqn("$outer.$name")) return null
    // A nested classifier of a supertype is not reached through the subclass's name, but a supertype the model
    // does not hold completely could still be what the code means; only a fully known chain is judged.
    if (!service.supertypeClosureKnown(outer)) return null
    enumEntryAsType(userType, ref, outer, name, resolver)?.let { return it }
    return unresolved(ref, name)
}

/**
 * ENUM_ENTRY_AS_TYPE / IS_ENUM_ENTRY: `Color.RED` written as a type names an enum ENTRY, a value, not a class;
 * in an `is` check K2 words it as IS_ENUM_ENTRY.
 */
private fun KotlinSemanticChecks.enumEntryAsType(
    userType: KtUserType, ref: KtNameReferenceExpression, outer: String, name: String, resolver: KotlinResolver,
): Diagnostic? {
    if (name !in resolver.enumConstantNames(outer)) return null
    val typeRef = generateSequence<KtElement>(userType) { (it.parent as? KtElement)?.takeIf { p -> p is KtUserType || p is dev.ide.kotlin.syntax.psi.KtNullableType || p is dev.ide.kotlin.syntax.psi.KtTypeReference } }
        .lastOrNull() as? dev.ide.kotlin.syntax.psi.KtTypeReference
    val inIs = typeRef != null && (typeRef.parent is dev.ide.kotlin.syntax.psi.KtIsExpression || typeRef.parent is dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern)
    val r = ref.textRange
    return if (inIs) Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "'is' over enum entry is prohibited, use comparison instead", KotlinDiagnosticCodes.IS_ENUM_ENTRY,
    ) else Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Use of enum entry names as types is prohibited, use enum type instead", KotlinDiagnosticCodes.ENUM_ENTRY_AS_TYPE,
    )
}

/**
 * Whether the type parameter [name] declared around [e] is visible there: false when the nearest declaration
 * of it is on a class that a NESTED class, object, companion or enum between them shuts out (only an `inner`
 * class, a local class or an anonymous object sees its outer class's type parameters); null when no enclosing
 * declaration has a type parameter of that name.
 */
internal fun typeParameterVisible(name: String, e: KtElement): Boolean? {
    var closed = false
    var node: KtElement? = e
    while (node != null) {
        if (node is KtTypeParameterListOwner && node.typeParameters.any { it.name == name }) return !closed
        if (node is KtClassOrObject && node.hasQualifiedName && (node is KtObjectDeclaration || (node as? KtClass)?.isInner() == false)) {
            closed = true
        }
        node = node.parent
    }
    return null
}

/** Whether a supertype of [typeFqn] (any level up) declares a classifier named [name]. */
internal fun KotlinSemanticChecks.supertypeDeclaresClassifier(typeFqn: String, name: String): Boolean {
    val seen = HashSet<String>()
    val queue = ArrayDeque<String>()
    service.supertypesOf(typeFqn).forEach { queue += it.qualifiedName }
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (!seen.add(t) || t == "kotlin.Any") continue
        if (service.isKnownType("$t.$name") || service.isProjectTypeAliasFqn("$t.$name")) return true
        service.supertypesOf(t).forEach { queue += it.qualifiedName }
    }
    return false
}

/**
 * Whether [receiver] is certainly a VALUE (a local or parameter read, a call, `this`) and [nestedFqn] a
 * project-source classifier nested in its type that is not `inner`: such a classifier is reached through the
 * outer class's NAME only, so `p.Nested()` names nothing. A name that may be a type or object qualifier
 * (`Outer.Nested`, `Obj.Nested`) is never taken for a value.
 */
/**
 * `C.Self.Nested` / `TA.Obj()` with `typealias Self = C` / `typealias TA = C`: K2 gives a typealias qualifier the
 * scope of the class's companion object only, so a classifier nested in the class (a nested class or object, the
 * companion by its own name, another alias) is not reached through it: UNRESOLVED_REFERENCE. Judged for project
 * aliases with no type parameters whose target is a known class that declares the nested classifier, when the
 * companion has no member of that name. An import alias (`import a.C as D`) renames the class itself and is not one.
 */
internal fun KotlinSemanticChecks.nestedThroughTypeAlias(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = expr.parent
    val receiver = when {
        parent is KtDotQualifiedExpression && parent.selectorExpression === expr -> parent.receiverExpression
        parent is KtCallExpression && parent.calleeExpression === expr ->
            (parent.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
        else -> null
    } ?: return null
    val name = expr.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val r = unwrapParen(receiver)
    val ctx = resolver.fileContext
    val target = when (r) {
        is KtNameReferenceExpression -> {
            val written = r.getReferencedName()
            val off = r.textRange.startOffset
            if (ctx.imports.any { it.alias == written }) return null
            if (resolver.localsAt(off, written).isNotEmpty() || resolver.localTypesInScope(off).containsKey(written)) return null
            // A class of that name in scope is the class, not an alias.
            if (service.resolveTypeName(written, ctx, resolver.enclosingClassFqn(off))?.let { service.isKnownType(it) } == true) return null
            service.typeAliasTargetFqn(written, ctx)
        }
        is KtDotQualifiedExpression -> {
            val written = (r.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
            val owner = resolver.typeDenotationFqn(r.receiverExpression) ?: return null
            if (service.isKnownType("$owner.$written")) return null
            service.typeAliasClassifierByFqn("$owner.$written")
        }
        else -> null
    } ?: return null
    val nested = service.isKnownType("$target.$name") || service.isProjectTypeAliasFqn("$target.$name") ||
        service.companionObjectFqn(target)?.substringAfterLast('.') == name
    if (!nested) return null
    if (service.companionMembersFor(target, name).any { it.name == name }) return null
    val rr = expr.textRange
    return Diagnostic(TextRange(rr.startOffset, rr.endOffset), Severity.ERROR, "Unresolved reference: $name", KotlinDiagnosticCodes.UNRESOLVED)
}

internal fun KotlinSemanticChecks.nestedClassThroughValue(receiver: KtExpression, nestedFqn: String, resolver: KotlinResolver): Boolean {
    val cls = (service.sourceClass(nestedFqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return false
    if (cls is KtClass && cls.isInner()) return false
    return when (val r = unwrapParen(receiver)) {
        is KtNameReferenceExpression -> resolver.localsAt(r.textRange.startOffset, r.getReferencedName()).isNotEmpty()
        is KtCallExpression, is KtThisExpression -> true
        // An enum entry (`C.E1.Nested`) is a value of its enum class, not the class.
        is KtDotQualifiedExpression -> {
            val entry = (r.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            val owner = resolver.typeDenotationFqn(r.receiverExpression)
            entry != null && owner != null && entry in resolver.enumConstantNames(owner)
        }
        else -> false
    }
}

/**
 * `val x = ::foo` (or a bare `::foo` statement) where this file declares several top-level `foo` functions:
 * with no expected type to pick one, the reference is ambiguous. Only overloads the file itself declares are
 * counted, and only where nothing nearer (a local, a member of an enclosing class) is named so.
 */
internal fun KotlinSemanticChecks.ambiguousCallableReference(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val cr = ref.parent as? KtCallableReferenceExpression ?: return null
    if (cr.callableReference !== ref || cr.receiverExpression != null) return null
    val holder = cr.parent
    // An initializer with no declared type, or a statement of a function body (a branch or lambda block may be
    // a value its context gives a type to).
    val noExpectedType = (holder is KtProperty && holder.initializer === cr && holder.typeReference == null) ||
        (holder is KtBlockExpression && holder.parent is KtNamedFunction)
    if (!noExpectedType) return null
    val name = ref.getReferencedName()
    val off = ref.textRange.startOffset
    if (resolver.localsAt(off, name).isNotEmpty() || resolver.enclosingClassMembersContain(off, name)) return null
    val file = ref.containingFile as? KtFile ?: return null
    val candidates = file.declarations.filterIsInstance<KtNamedFunction>().filter {
        it.name == name && it.receiverTypeReference == null &&
            it.annotationEntries.none { a -> a.shortName?.asString() == "Deprecated" && "HIDDEN" in a.text }
    }
    val overloads = candidates.size
    // With as many parameters on two of them, one may be the more specific (`bar(a: Any)` / `bar(a: Int)`),
    // which the compiler then picks; only overloads that differ in arity leave it nothing to choose by.
    if (overloads < 2 || candidates.map { it.valueParameters.size }.distinct().size != overloads) return null
    if (candidates.any { f -> f.valueParameters.any { it.isVarArg || it.hasDefaultValue() } }) return null
    if (file.declarations.any { it is KtProperty && it.name == name }) return null
    val r = ref.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Overload resolution ambiguity: '$name' has $overloads overloads and nothing to choose one by",
        KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY,
    )
}

/**
 * The class whose scope a type written at [e] resolves in: the innermost enclosing class, except inside that
 * class's own HEADER (its supertype list, type-parameter bounds and constraints), which sees the scope around
 * the class and not the class's own nested classifiers (`class A : Nested()` with `class Nested` declared in
 * `A` is unresolved).
 */
internal fun headerAwareEnclosingClassFqn(e: KtElement): String? {
    var child: KtElement = e
    var node: KtElement? = e.parent
    while (node != null) {
        if (node is KtClassOrObject) {
            val inHeader = child === node.superTypeList || child === node.typeParameterList || child === node.typeConstraintList
            if (!inHeader) return enclosingClassFqnOf(node)
            return enclosingClassFqnOf(node.parent ?: return null)
        }
        child = node
        node = node.parent
    }
    return null
}

private fun unresolved(at: KtElement, name: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", KotlinDiagnosticCodes.UNRESOLVED)
}

/** The project-source class a qualifier names, every segment resolved; null for a package segment, a library
 *  class (whose nested classifiers may not all be indexed) or anything unknown. */
private fun KotlinSemanticChecks.qualifiedSourceClassFqn(t: KtUserType, resolver: KotlinResolver): String? {
    val name = t.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return null
    val q = t.qualifier
    val fqn = if (q == null) {
        val off = t.textRange.startOffset
        if (resolver.isTypeParameterInScope(name, off) || resolver.localTypesInScope(off).containsKey(name)) return null
        service.resolveTypeName(name, resolver.fileContext, resolver.enclosingClassFqn(off)) ?: return null
    } else {
        "${qualifiedSourceClassFqn(q, resolver) ?: return null}.$name"
    }
    // A class that is itself a typealias target reached by the alias's name is not the alias's scope.
    if (fqn.substringAfterLast('.') != name) return null
    return fqn.takeIf { service.isSourceClass(it) }
}
