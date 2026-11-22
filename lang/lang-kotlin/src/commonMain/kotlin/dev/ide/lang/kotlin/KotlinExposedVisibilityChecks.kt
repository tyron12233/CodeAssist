package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeElement
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.localFunctionsInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.parse.unwrapParen

/**
 * K2's effective visibility (`core/compiler.common/.../descriptors/EffectiveVisibility.kt`, Apache 2.0): how far a
 * declaration can be seen once its containers are taken into account, and how two such visibilities compare. A
 * protected visibility carries the class it is protected in, by FQN. Package-private and the unknown visibility
 * have no source here and are left out.
 */
internal sealed class EffectiveVisibility(val label: String) {
    object PrivateInClass : EffectiveVisibility("private-in-class")
    object Local : EffectiveVisibility("local")
    object PrivateInFile : EffectiveVisibility("private-in-file")
    object Public : EffectiveVisibility("public")
    object Internal : EffectiveVisibility("internal")
    data class Protected(val container: String?) : EffectiveVisibility("protected")
    object ProtectedBound : EffectiveVisibility("protected (in different classes)")
    data class InternalProtected(val container: String?) : EffectiveVisibility("internal & protected")
    object InternalProtectedBound : EffectiveVisibility("internal & protected (in different classes)")

    enum class Permissiveness { LESS, SAME, MORE, UNKNOWN }

    /** `relation` of each K2 class, case for case. [isSubclass] answers whether the first class inherits the second. */
    fun relation(other: EffectiveVisibility, isSubclass: (String, String) -> Boolean): Permissiveness = when (this) {
        PrivateInClass -> if (other == this || other == Local) Permissiveness.SAME else Permissiveness.LESS
        Local -> if (other == this || other == PrivateInClass) Permissiveness.SAME else Permissiveness.LESS
        PrivateInFile -> when (other) {
            this -> Permissiveness.SAME
            PrivateInClass, Local -> Permissiveness.MORE
            else -> Permissiveness.LESS
        }
        Public -> if (other == this) Permissiveness.SAME else Permissiveness.MORE
        Internal -> when (other) {
            Public -> Permissiveness.LESS
            PrivateInClass, PrivateInFile, Local, InternalProtectedBound, is InternalProtected -> Permissiveness.MORE
            this -> Permissiveness.SAME
            else -> Permissiveness.UNKNOWN
        }
        is Protected -> when (other) {
            Public -> Permissiveness.LESS
            PrivateInClass, PrivateInFile, Local, ProtectedBound, InternalProtectedBound -> Permissiveness.MORE
            is Protected -> containerRelation(container, other.container, isSubclass)
            is InternalProtected -> when (containerRelation(container, other.container, isSubclass)) {
                Permissiveness.SAME, Permissiveness.MORE -> Permissiveness.MORE
                else -> Permissiveness.UNKNOWN
            }
            else -> Permissiveness.UNKNOWN
        }
        ProtectedBound -> when (other) {
            Public, is Protected -> Permissiveness.LESS
            PrivateInClass, PrivateInFile, Local, InternalProtectedBound -> Permissiveness.MORE
            ProtectedBound -> Permissiveness.SAME
            else -> Permissiveness.UNKNOWN
        }
        is InternalProtected -> when (other) {
            Public, Internal -> Permissiveness.LESS
            PrivateInClass, PrivateInFile, Local, InternalProtectedBound -> Permissiveness.MORE
            is InternalProtected -> containerRelation(container, other.container, isSubclass)
            is Protected -> when (containerRelation(container, other.container, isSubclass)) {
                Permissiveness.SAME, Permissiveness.LESS -> Permissiveness.LESS
                else -> Permissiveness.UNKNOWN
            }
            else -> Permissiveness.UNKNOWN
        }
        InternalProtectedBound -> when (other) {
            Public, is Protected, is InternalProtected, ProtectedBound, Internal -> Permissiveness.LESS
            PrivateInClass, PrivateInFile, Local -> Permissiveness.MORE
            else -> Permissiveness.SAME
        }
    }

    /** `lowerBound` of each K2 class: the most permissive visibility not more visible than both. */
    fun lowerBound(other: EffectiveVisibility, isSubclass: (String, String) -> Boolean): EffectiveVisibility = when (this) {
        Internal -> when (other) {
            Public -> this
            is Protected -> InternalProtected(other.container)
            ProtectedBound -> InternalProtectedBound
            else -> other
        }
        is Protected -> when (other) {
            Public -> this
            PrivateInClass, PrivateInFile, Local, ProtectedBound, InternalProtectedBound -> other
            is Protected -> when (relation(other, isSubclass)) {
                Permissiveness.SAME, Permissiveness.LESS -> this
                Permissiveness.MORE -> other
                Permissiveness.UNKNOWN -> ProtectedBound
            }
            is InternalProtected -> if (relation(other, isSubclass) == Permissiveness.MORE) other else InternalProtectedBound
            Internal -> InternalProtected(container)
        }
        ProtectedBound -> when (other) {
            Public, is Protected -> this
            Internal, is InternalProtected -> InternalProtectedBound
            else -> other
        }
        is InternalProtected -> when (other) {
            Public, Internal -> this
            PrivateInClass, PrivateInFile, Local, InternalProtectedBound -> other
            is Protected, is InternalProtected -> when (relation(other, isSubclass)) {
                Permissiveness.SAME, Permissiveness.LESS -> this
                Permissiveness.MORE -> other
                Permissiveness.UNKNOWN -> InternalProtectedBound
            }
            ProtectedBound -> InternalProtectedBound
        }
        else -> when (relation(other, isSubclass)) {
            Permissiveness.SAME, Permissiveness.LESS -> this
            Permissiveness.MORE -> other
            Permissiveness.UNKNOWN -> PrivateInClass
        }
    }

    companion object {
        private fun containerRelation(first: String?, second: String?, isSubclass: (String, String) -> Boolean): Permissiveness = when {
            first == null || second == null -> Permissiveness.UNKNOWN
            first == second -> Permissiveness.SAME
            isSubclass(first, second) -> Permissiveness.LESS
            isSubclass(second, first) -> Permissiveness.MORE
            else -> Permissiveness.UNKNOWN
        }
    }
}

private fun KotlinSemanticChecks.isSubclassOf(sub: String, sup: String): Boolean = service.supertypesOf(sub).any { it.qualifiedName == sup }

/** The class a member is declared in (the class whose body or primary constructor holds it), or null. */
private fun owningClass(d: KtElement): KtClassOrObject? = when (val p = d.parent) {
    is KtClassBody -> p.parent as? KtClassOrObject
    is KtClassOrObject -> p
    else -> null
}

private fun isInLocalScope(d: KtElement): Boolean = d.parent is KtBlockExpression

/**
 * The effective visibility of a source declaration, as `FirStatusResolver` computes it: its own visibility (a
 * private class at the top level is private to the file, any other private declaration to its class) bounded by
 * its container's. Null when it cannot be told: an `override` with no visibility of its own inherits one.
 */
internal fun KotlinSemanticChecks.effectiveVisibilityOf(d: KtDeclaration): EffectiveVisibility? {
    // A constructor `val`/`var` is a member of the class the constructor belongs to.
    val cls = if (d is KtParameter) (d.parent?.parent as? KtPrimaryConstructor)?.parent as? KtClassOrObject ?: return null else owningClass(d)
    val parent: EffectiveVisibility = when {
        d is KtPropertyAccessor -> return d.property?.let { effectiveVisibilityOf(it) }
        cls == null -> EffectiveVisibility.Public
        cls is KtObjectDeclaration && cls.isObjectLiteral() -> EffectiveVisibility.Local
        else -> effectiveVisibilityOf(cls) ?: return null
    }
    val self: EffectiveVisibility = when {
        d.hasModifier(KtTokens.PRIVATE_KEYWORD) ->
            if (cls == null && d is KtClassOrObject && !isInLocalScope(d)) EffectiveVisibility.PrivateInFile else EffectiveVisibility.PrivateInClass
        d.hasModifier(KtTokens.PROTECTED_KEYWORD) -> EffectiveVisibility.Protected(cls?.let { classFqnOf(it) })
        // `@PublishedApi` makes an internal declaration part of the API inline bodies may use; K2 tracks that as a
        // second, published visibility, and exposing it is not judged here.
        d.hasModifier(KtTokens.INTERNAL_KEYWORD) ->
            if (d.annotationEntries.any { it.shortName?.asString() == "PublishedApi" }) EffectiveVisibility.Public else EffectiveVisibility.Internal
        // An enum class's constructors are private unless written otherwise.
        (d is KtPrimaryConstructor || d is KtSecondaryConstructor) && cls is KtClass && cls.isEnum() -> EffectiveVisibility.PrivateInClass
        isInLocalScope(d) || (d is KtObjectDeclaration && d.isObjectLiteral()) -> EffectiveVisibility.Local
        d.hasModifier(KtTokens.PUBLIC_KEYWORD) -> EffectiveVisibility.Public
        d.hasModifier(KtTokens.OVERRIDE_KEYWORD) -> return null
        else -> EffectiveVisibility.Public
    }
    return parent.lowerBound(self) { a, b -> isSubclassOf(a, b) }
}

private fun classFqnOf(cls: KtClassOrObject): String? =
    cls.fqName?.asString() ?: if (cls is KtEnumEntry) null else dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(cls)

/** A class whose effective visibility a type use cannot be wider than: the class named, the visibility it has, and
 *  whether it was found in a type argument. */
private class Exposure(val name: String, val visibility: EffectiveVisibility, val fromArgument: Boolean)

/**
 * `findVisibilityExposure`: the first class named in [typeRef] (the type itself, then its arguments, depth first)
 * whose effective visibility is less permissive than [base], or not comparable with it. Function types count as
 * their `FunctionN` arguments. Type parameters, library classes (always public from here) and anything that does
 * not resolve are skipped; a project typealias is followed to the class it names.
 */
private fun KotlinSemanticChecks.findExposure(typeRef: KtTypeReference, base: EffectiveVisibility, resolver: KotlinResolver): Exposure? {
    fun visit(element: KtTypeElement?, argument: Boolean): Exposure? {
        when (element) {
            is KtNullableType -> return visit(element.innerType, argument)
            is KtFunctionType -> {
                element.receiverTypeReference?.let { r -> visit(r.typeElement, true)?.let { return it } }
                for (p in element.parameters) p.typeReference?.let { r -> visit(r.typeElement, true)?.let { return it } }
                element.returnTypeReference?.let { r -> return visit(r.typeElement, true) }
                return null
            }
            is KtUserType -> {
                val name = element.referencedName ?: return null
                if (element.qualifier == null && resolver.isTypeParameterInScope(name, element.textRange.startOffset)) return null
                val fqn = userTypeClassFqn(element, typeRef, resolver)
                if (fqn != null) {
                    val cls = ((service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject)
                        ?.takeIf { accessibleFrom(it, element) }
                    val ev = cls?.let { effectiveVisibilityOf(it) }
                    if (ev != null) {
                        val rel = ev.relation(base) { a, b -> isSubclassOf(a, b) }
                        if (rel == EffectiveVisibility.Permissiveness.LESS || rel == EffectiveVisibility.Permissiveness.UNKNOWN) {
                            return Exposure(cls.name ?: name, ev, argument)
                        }
                    }
                }
                for (arg in element.typeArguments) {
                    arg.typeReference?.let { r -> visit(r.typeElement, true)?.let { return it } }
                }
                return null
            }
            else -> return null
        }
    }
    return visit(typeRef.typeElement, false)
}

private fun KotlinSemanticChecks.userTypeClassFqn(user: KtUserType, context: KtElement, resolver: KotlinResolver): String? {
    val text = user.text.substringBefore('<').filterNot { it.isWhitespace() }
    if (user.qualifier != null && '<' in user.text.substringBeforeLast('.')) return null
    if (user.qualifier == null) {
        val name = user.referencedName ?: return null
        if (service.isProjectTypeAlias(name)) return service.typeAliasChainTargetFqn(name, resolver.fileContext)
    }
    val fqn = service.resolveTypeName(text, resolver.fileContext, enclosingClassFqnOf(context)) ?: return null
    return fqn.takeIf { service.isKnownType(it) }
}

private fun exposedMessage(what: String, base: EffectiveVisibility, e: Exposure): String =
    "'${base.label}' $what exposes its '${e.visibility.label}' type${if (e.fromArgument) " argument" else ""} '${e.name}'"

private fun exposedAt(at: KtElement, message: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.EXPOSED_VISIBILITY)
}

/**
 * The EXPOSED_* family, from `FirExposedVisibilityDeclarationChecker`: a declaration's signature may not name a
 * class less visible than the declaration itself. Covered here: a function's return type, parameter types and
 * receiver (EXPOSED_FUNCTION_RETURN_TYPE, EXPOSED_PARAMETER_TYPE, EXPOSED_RECEIVER_TYPE), a property's type and
 * receiver (EXPOSED_PROPERTY_TYPE), a constructor property's type (EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR_ERROR),
 * a class's supertypes and type-parameter bounds (EXPOSED_SUPER_CLASS, EXPOSED_SUPER_INTERFACE,
 * EXPOSED_TYPE_PARAMETER_BOUND) and a typealias's expansion (EXPOSED_TYPEALIAS_EXPANDED_TYPE). Only types written
 * out are judged; an inferred return or property type is not.
 */
internal fun KotlinSemanticChecks.exposedVisibility(d: KtDeclaration, resolver: KotlinResolver): List<Diagnostic> {
    when (d) {
        is KtNamedFunction -> {
            if (d.name == null || isInLocalScope(d)) return emptyList()
            // The narrower check in KotlinTypedChecks reports the same defect for a private class of the file.
            if (exposedPrivateType(d) != null) return emptyList()
            val base = effectiveVisibilityOf(d) ?: return emptyList()
            if (base == EffectiveVisibility.Local) return emptyList()
            val out = ArrayList<Diagnostic>()
            d.typeReference?.let { t -> findExposure(t, base, resolver)?.let { out += exposedAt(t, exposedMessage("function", base, it)) } }
            if (d.typeReference == null && !d.hasBlockBody()) {
                implicitExposure(d.bodyExpression, base, resolver)?.let { out += exposedAt(d.nameIdentifier ?: d, exposedMessage("function", base, it)) }
            }
            for (p in d.valueParameters) {
                val t = p.typeReference ?: continue
                findExposure(t, base, resolver)?.let { out += exposedAt(p, exposedMessage("function", base, it)) }
            }
            d.receiverTypeReference?.let { t -> findExposure(t, base, resolver)?.let { out += exposedAt(t, exposedMessage("member", base, it)) } }
            return out
        }
        is KtSecondaryConstructor, is KtPrimaryConstructor -> {
            val cls = owningClass(d) ?: return emptyList()
            if (cls is KtObjectDeclaration || (cls is KtClass && cls.isInterface())) return emptyList()
            var base = effectiveVisibilityOf(d) ?: return emptyList()
            if (cls is KtClass && cls.isSealed()) base = EffectiveVisibility.PrivateInClass
            if (base == EffectiveVisibility.Local) return emptyList()
            val params = if (d is KtPrimaryConstructor) d.valueParameters else (d as KtSecondaryConstructor).valueParameters
            val out = ArrayList<Diagnostic>()
            for (p in params) {
                val t = p.typeReference ?: continue
                val exposure = findExposure(t, base, resolver)
                if (exposure != null) { out += exposedAt(p, exposedMessage("constructor", base, exposure)); continue }
                if (d !is KtPrimaryConstructor || !p.hasValOrVar()) continue
                val propertyVisibility = effectiveVisibilityOf(p) ?: continue
                if (propertyVisibility == EffectiveVisibility.Local) continue
                findExposure(t, propertyVisibility, resolver)?.let { out += exposedAt(p, exposedMessage("property", propertyVisibility, it)) }
            }
            return out
        }
        is KtProperty -> {
            if (isInLocalScope(d) || exposedPrivateType(d) != null) return emptyList()
            val base = effectiveVisibilityOf(d) ?: return emptyList()
            if (base == EffectiveVisibility.Local) return emptyList()
            val out = ArrayList<Diagnostic>()
            d.typeReference?.let { t -> findExposure(t, base, resolver)?.let { out += exposedAt(t, exposedMessage("property", base, it)) } }
            if (d.typeReference == null && !d.hasDelegate()) {
                implicitExposure(d.initializer, base, resolver)?.let { out += exposedAt(d.nameIdentifier ?: d, exposedMessage("property", base, it)) }
            }
            d.receiverTypeReference?.let { t -> findExposure(t, base, resolver)?.let { out += exposedAt(t, exposedMessage("member", base, it)) } }
            return out
        }
        is KtClassOrObject -> {
            if (d is KtEnumEntry || (d is KtObjectDeclaration && d.isObjectLiteral())) return emptyList()
            val base = effectiveVisibilityOf(d) ?: return emptyList()
            if (base == EffectiveVisibility.Local) return emptyList()
            val out = ArrayList<Diagnostic>()
            val isInterface = d is KtClass && d.isInterface()
            for (entry in d.superTypeListEntries) {
                val t = entry.typeReference ?: continue
                val fqn = (t.typeElement as? KtUserType)?.let { userTypeClassFqn(it, t, resolver) } ?: continue
                if (service.isInterfaceType(fqn) != isInterface) continue
                findExposure(t, base, resolver)?.let { out += exposedAt(t, exposedMessage(if (isInterface) "sub-interface" else "subclass", base, it)) }
            }
            for (tp in d.typeParameters) {
                val bounds = listOfNotNull(tp.extendsBound) +
                    d.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == tp.name }.mapNotNull { it.boundTypeReference }
                for (b in bounds) findExposure(b, base, resolver)?.let { out += exposedAt(b, exposedMessage("generic", base, it)) }
            }
            return out
        }
        is KtTypeAlias -> {
            if (isInLocalScope(d)) return emptyList()
            val base = effectiveVisibilityOf(d) ?: return emptyList()
            if (base == EffectiveVisibility.Local) return emptyList()
            val t = d.typeReference ?: return emptyList()
            val e = findExposure(t, base, resolver) ?: return emptyList()
            return listOf(exposedAt(d.nameIdentifier ?: d, exposedMessage("typealias", base, e)))
        }
        else -> return emptyList()
    }
}

/**
 * The exposure an inferred type brings, judged where the type is plainly one class of the project: a constructor
 * call (`fun make() = Hidden()`) or a reference to an object (`val o = HiddenObject`).
 */
private fun KotlinSemanticChecks.implicitExposure(body: KtExpression?, base: EffectiveVisibility, resolver: KotlinResolver): Exposure? {
    val e = body?.let { unwrapParen(it) } ?: return null
    val cls = implicitClassOf(e, resolver) ?: return null
    if (!accessibleFrom(cls, e)) return null
    val ev = effectiveVisibilityOf(cls) ?: return null
    val rel = ev.relation(base) { a, b -> isSubclassOf(a, b) }
    if (rel != EffectiveVisibility.Permissiveness.LESS && rel != EffectiveVisibility.Permissiveness.UNKNOWN) return null
    val name = cls.name ?: return null
    if (!noTopLevelCallableNamed(name)) return null
    return Exposure(name, ev, false)
}

/** The project class [e] evaluates to an instance of, when that is certain from its shape: `Name(...)` naming a
 *  class (no local, member, function of this file or import of the name that a call could reach instead), or a
 *  bare `Name` naming an object. A caller confirms [noTopLevelCallableNamed] once the rest of its rule holds: that
 *  lookup scans the project's top-level declarations. */
/** No top-level function or property of the project or its libraries is called [name]. */
internal fun KotlinSemanticChecks.noTopLevelCallableNamed(name: String): Boolean = service.topLevelByName(name).isEmpty()

internal fun KotlinSemanticChecks.implicitClassOf(e: KtExpression, resolver: KotlinResolver): KtClassOrObject? {
    val call = e as? KtCallExpression
    val ref = (call?.calleeExpression ?: e) as? KtNameReferenceExpression ?: return null
    if (call != null && call.typeArgumentList != null) return null
    val name = ref.getReferencedName()
    if (name.isEmpty() || !name[0].isUpperCase()) return null
    val offset = ref.textRange.startOffset
    if (resolver.localsAt(offset, name).isNotEmpty() || resolver.localFunctionsInScope(offset, name).isNotEmpty()) return null
    if (resolver.enclosingClassMembersContain(offset, name)) return null
    val file = e.containingFile as? KtFile ?: return null
    if (file.declarations.any { (it is KtNamedFunction || it is KtProperty) && (it as KtCallableDeclaration).name == name }) return null
    if (file.importDirectives.any { it.aliasName == name }) return null
    val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(e))?.takeIf { service.isKnownType(it) } ?: return null
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
    return when {
        call != null -> cls.takeIf { it is KtClass && !it.isInterface() && !it.isEnum() && !it.isAnnotation() && it !is KtEnumEntry && !it.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !it.isSealed() }
        cls is KtObjectDeclaration && !cls.isCompanion() && !cls.isObjectLiteral() -> cls
        else -> null
    }
}

/**
 * Whether [use] can see [cls] at all: a private class only from inside its container (the file, for a top-level
 * one). A class it cannot see is a resolution error of its own (or a sign the name resolved to the wrong class),
 * never an exposure.
 */
private fun accessibleFrom(cls: KtClassOrObject, use: KtElement): Boolean {
    var c: KtClassOrObject? = cls
    while (c != null) {
        if (c.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            val container = owningClass(c)
            if (container == null) {
                if (c.containingFile !== use.containingFile) return false
            } else if (!isInside(use, container)) {
                return false
            }
        }
        c = owningClass(c)
    }
    return true
}

private fun isInside(e: KtElement, container: KtElement): Boolean {
    var node: KtElement? = e
    while (node != null) {
        if (node === container) return true
        node = node.parent
    }
    return false
}
