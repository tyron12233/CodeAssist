package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtTypeElement
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope

/**
 * Declaration and type rules ported from the K2 compiler's checkers
 * (`compiler/fir/checkers/checkers.common/.../checkers/declaration` and `.../checkers/type`, Apache 2.0). Each
 * function names the checker it mirrors and keeps that checker's conditions; where a condition rests on
 * resolution this editor does not have, the rule is applied only to the cases it can decide, and backs off on
 * the rest. Registered from [KotlinSemanticChecks.checkers].
 */
internal fun KotlinSemanticChecks.declarationPortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtParameter::class) { psi ->
        psi as KtParameter
        KotlinPerf.span("sem.ported.param") {
            report(valueParameterWithoutType(psi))
            if (resolveReady) report(forbiddenVarargType(psi, resolver))
        }
    },
    KotlinChecker(KtNamedFunction::class) { psi ->
        psi as KtNamedFunction
        KotlinPerf.span("sem.ported.func") {
            report(declarationCantBeInlined(psi))
            report(notYetSupportedInInline(psi))
            report(methodOfAnyImplementedInInterface(psi))
            report(implicitNothingType(psi))
            reportAll(functionShape(psi))
            report(ambiguousAnonymousType(psi))
        }
    },
    KotlinChecker(KtPrimaryConstructor::class) { psi ->
        KotlinPerf.span("sem.ported.ctor") { report(constructorAllowed(psi as KtDeclaration)) }
    },
    KotlinChecker(KtSecondaryConstructor::class) { psi ->
        KotlinPerf.span("sem.ported.ctor") { report(constructorAllowed(psi as KtDeclaration)) }
    },
    KotlinChecker(KtProperty::class) { psi ->
        psi as KtProperty
        KotlinPerf.span("sem.ported.prop") {
            report(declarationCantBeInlined(psi))
            report(incorrectTypeParameterOfProperty(psi))
            report(propertyInitializerShape(psi))
            reportAll(propertyAccessorShape(psi))
            report(ambiguousAnonymousType(psi))
            report(implicitNothingType(psi))
        }
    },
    KotlinChecker(KtDeclaration::class) { psi ->
        KotlinPerf.span("sem.ported.publishedApi") { report(nonInternalPublishedApi(psi as KtDeclaration, resolver)) }
        KotlinPerf.span("sem.ported.exposed") { if (resolveReady) reportAll(exposedVisibility(psi as KtDeclaration, resolver)) }
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtTypeAlias::class) { psi ->
        KotlinPerf.span("sem.ported.typeAlias") { report(recursiveTypeAlias(psi as dev.ide.kotlin.syntax.psi.KtTypeAlias)) }
    },
    KotlinChecker(KtUserType::class) { psi ->
        KotlinPerf.span("sem.ported.userType") {
            if (resolveReady) report(typeArgumentsForOuterClass(psi as KtUserType, resolver) ?: outerClassArgumentsRequired(psi, resolver))
        }
    },
    KotlinChecker(KtTypeParameter::class) { psi ->
        KotlinPerf.span("sem.ported.reified") { report(reifiedTypeParameterNoInline(psi as KtTypeParameter)) }
    },
    KotlinChecker(KtTypeParameter::class) { psi ->
        KotlinPerf.span("sem.ported.bounds") { if (resolveReady) reportAll(typeParameterBounds(psi as KtTypeParameter, resolver)) }
    },
    KotlinChecker(KtClassOrObject::class) { psi ->
        psi as KtClassOrObject
        KotlinPerf.span("sem.ported.class") {
            if (psi is KtClass) report(notYetSupportedInInline(psi))
            reportAll(supertypeInitializedInInterface(psi))
            reportAll(supertypeList(psi, resolver.takeIf { resolveReady }))
            reportAll(constructorDelegation(psi))
            reportAll(classBodyShape(psi))
            reportAll(withoutPrimaryConstructor(psi, resolver.takeIf { resolveReady }))
            reportAll(annotationClassShape(psi, resolver.takeIf { resolveReady }))
            report(typeParametersInObjectLiteral(psi))
            report(nestedLocalObject(psi))
            if (resolveReady) {
                report(sealedSupertypeInLocalClass(psi, resolver))
                reportAll(genericThrowableSubclass(psi, resolver))
                reportAll(manyImplementationsNotOverridden(psi, resolver))
            }
        }
    },
)

/** The K2 names each code of this file stands for, merged into [KotlinSuppression.K2_NAMES] so `@Suppress` reads them. */
internal val PORTED_DECLARATION_K2_NAMES: Map<String, Set<String>> = mapOf(
    KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED to setOf("VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE", "FORBIDDEN_VARARG_PARAMETER_TYPE"),
    KotlinDiagnosticCodes.SUPERTYPE_LIST to setOf(
        "INTERFACE_WITH_SUPERCLASS", "SUPERTYPE_INITIALIZED_IN_INTERFACE", "SEALED_SUPERTYPE_IN_LOCAL_CLASS", "NULLABLE_SUPERTYPE",
        "SUPERTYPE_IS_EXTENSION_OR_CONTEXT_FUNCTION_TYPE", "ANNOTATION_ON_SUPERCLASS_ERROR", "PROJECTION_IN_IMMEDIATE_ARGUMENT_TO_SUPERTYPE",
        "SUPERTYPE_APPEARS_TWICE", "MANY_CLASSES_IN_SUPERTYPE_LIST", "CLASS_CANNOT_BE_EXTENDED_DIRECTLY", "DELEGATION_NOT_TO_INTERFACE",
    ),
    KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE to setOf(
        "CYCLIC_CONSTRUCTOR_DELEGATION_CALL", "PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED", "CONSTRUCTOR_IN_OBJECT", "CONSTRUCTOR_IN_INTERFACE",
        "NON_PRIVATE_CONSTRUCTOR_IN_ENUM", "NON_PRIVATE_OR_PROTECTED_CONSTRUCTOR_IN_SEALED", "SUPERTYPE_INITIALIZED_WITHOUT_PRIMARY_CONSTRUCTOR",
        "EXPLICIT_DELEGATION_CALL_REQUIRED",
    ),
    KotlinDiagnosticCodes.DECLARATION_FORM to setOf("MANY_COMPANION_OBJECTS", "ANONYMOUS_INITIALIZER_IN_INTERFACE"),
    KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE to setOf(
        "SUPERTYPES_FOR_ANNOTATION_CLASS", "LOCAL_ANNOTATION_CLASS_ERROR", "MISSING_VAL_ON_ANNOTATION_PARAMETER", "VAR_ANNOTATION_PARAMETER",
        "INVALID_TYPE_OF_ANNOTATION_MEMBER", "ANNOTATION_CLASS_MEMBER",
    ),
    KotlinDiagnosticCodes.FUNCTION_SHAPE to setOf(
        "FUNCTION_DECLARATION_WITH_NO_NAME", "TYPE_PARAMETERS_NOT_ALLOWED", "ANONYMOUS_FUNCTION_PARAMETER_WITH_DEFAULT_VALUE",
        "METHOD_OF_ANY_IMPLEMENTED_IN_INTERFACE", "IMPLICIT_NOTHING_RETURN_TYPE", "IMPLICIT_NOTHING_PROPERTY_TYPE",
        "AMBIGUOUS_ANONYMOUS_TYPE_INFERRED",
    ),
    KotlinDiagnosticCodes.PUBLISHED_API to setOf("NON_INTERNAL_PUBLISHED_API"),
    KotlinDiagnosticCodes.OVERRIDE_RETURN_TYPE to setOf("RETURN_TYPE_MISMATCH_ON_OVERRIDE", "PROPERTY_TYPE_MISMATCH_ON_OVERRIDE"),
    KotlinDiagnosticCodes.EXPOSED_VISIBILITY to setOf(
        "EXPOSED_PARAMETER_TYPE", "EXPOSED_FUNCTION_RETURN_TYPE", "EXPOSED_PROPERTY_TYPE", "EXPOSED_SUPER_CLASS", "EXPOSED_SUPER_INTERFACE",
        "EXPOSED_RECEIVER_TYPE", "EXPOSED_TYPEALIAS_EXPANDED_TYPE", "EXPOSED_TYPE_PARAMETER_BOUND", "EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR_ERROR",
    ),
    KotlinDiagnosticCodes.INLINE_DECLARATION to setOf("DECLARATION_CANT_BE_INLINED", "NOT_YET_SUPPORTED_IN_INLINE"),
    KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE to setOf(
        "INCORRECT_TYPE_PARAMETER_OF_PROPERTY", "REPEATED_BOUND", "ONLY_ONE_CLASS_BOUND_ALLOWED", "GENERIC_THROWABLE_SUBCLASS",
        "INNER_CLASS_OF_GENERIC_THROWABLE_SUBCLASS", "TYPE_ARGUMENTS_FOR_OUTER_CLASS_WHEN_NESTED_REFERENCED", "OUTER_CLASS_ARGUMENTS_REQUIRED",
        "RECURSIVE_TYPEALIAS_EXPANSION", "REIFIED_TYPE_PARAMETER_NO_INLINE", "TYPE_PARAMETERS_IN_ENUM",
        "UPPER_BOUND_IS_EXTENSION_OR_CONTEXT_FUNCTION_TYPE", "BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER",
    ),
    KotlinDiagnosticCodes.PROPERTY_SHAPE to setOf(
        "PROPERTY_INITIALIZER_IN_INTERFACE", "PROPERTY_INITIALIZER_NO_BACKING_FIELD", "EXTENSION_PROPERTY_WITH_BACKING_FIELD",
        "EXTENSION_PROPERTY_MUST_HAVE_ACCESSORS_OR_BE_ABSTRACT", "ABSTRACT_PROPERTY_WITH_GETTER", "ABSTRACT_PROPERTY_WITH_SETTER",
        "PRIVATE_PROPERTY_IN_INTERFACE", "BACKING_FIELD_IN_INTERFACE", "DELEGATED_PROPERTY_IN_INTERFACE",
    ),
)

private fun diagnostic(at: KtElement, message: String, code: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

/** The class whose body directly declares [d], or null for a top-level or local declaration. */
private fun containingClassOf(d: KtDeclaration): KtClassOrObject? = (d.parent as? KtClassBody)?.parent as? KtClassOrObject

/**
 * The class [typeRef] names, as an FQN: a project typealias is followed to its class, a type parameter or a
 * name that does not resolve gives null. Function, nullable-function and intersection types give null too.
 */
internal fun KotlinSemanticChecks.classifierFqnOf(typeRef: KtTypeReference, resolver: KotlinResolver): String? {
    var element = typeRef.typeElement
    while (element is KtNullableType) element = element.innerType
    val user = element as? KtUserType ?: return null
    val text = user.text.substringBefore('<').filterNot { it.isWhitespace() }
    if (text.isEmpty()) return null
    if (user.qualifier == null) {
        val name = user.referencedName ?: return null
        if (resolver.isTypeParameterInScope(name, typeRef.textRange.startOffset)) return null
        if (service.isProjectTypeAlias(name)) return service.typeAliasChainTargetFqn(name, resolver.fileContext)
    }
    // A class's supertypes are resolved outside its body: its own nested classes are not in scope there
    // (`class MyClass : Base { interface Base }` names the outer `Base`), so the scope starts at its outer class.
    // Anything written in a supertype entry, the annotations on it included (`: @Anno(c) Base by d`), is in the
    // header. The walk stops at the first body, class or file, so a reference inside a body costs a step or two.
    var node: dev.ide.kotlin.syntax.psi.KtElement? = typeRef.parent
    var entry: dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry? = null
    while (node != null) {
        if (node is dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry) { entry = node; break }
        if (node is KtClassOrObject || node is dev.ide.kotlin.syntax.psi.KtClassBody || node is dev.ide.kotlin.syntax.psi.KtBlockExpression ||
            node is dev.ide.kotlin.syntax.psi.KtFile || node is dev.ide.kotlin.syntax.psi.KtDeclaration
        ) break
        node = node.parent
    }
    val header = entry?.getStrictParentOfType<KtClassOrObject>()
    val headerFqn = header?.let { enclosingClassFqnOf(it) }
    val fqn = (if (headerFqn != null && service.sourceClass(headerFqn)?.isLocal == false) service.headerTypeName(text, resolver.fileContext, headerFqn)
        else service.resolveTypeName(text, resolver.fileContext, enclosingClassFqnOf(typeRef))) ?: return null
    return fqn.takeIf { service.isKnownType(it) }
}

// --- FirFunctionParameterChecker.checkParameterTypes, and the parameter-type diagnostic K2 raises for a lambda
//     or anonymous function whose parameter type nothing gives ---

/**
 * VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE: a named function's or a constructor's parameter must declare its type;
 * a lambda's or an anonymous function's may leave it out only when an expected function type supplies it. The
 * expected type is judged only where there plainly is none: the initializer of a property with no declared type,
 * and a lambda written as a statement of a function's block body.
 */
internal fun valueParameterWithoutType(p: KtParameter): Diagnostic? {
    if (p.typeReference != null || p.destructuringDeclaration != null) return null
    val list = p.parent as? KtParameterList ?: return null
    val owner = list.parent
    val needsType = when (owner) {
        is KtNamedFunction -> owner.name != null || noExpectedType(owner)
        is KtPrimaryConstructor, is KtSecondaryConstructor -> true
        is KtFunctionLiteral -> (owner.parent as? KtLambdaExpression)?.let { noExpectedType(it) } == true
        else -> false
    }
    if (!needsType) return null
    return diagnostic(p, "Cannot infer a type for this parameter. Specify it explicitly.", KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED)
}

private fun noExpectedType(e: KtElement): Boolean {
    val parent = e.parent
    if (parent is KtProperty) {
        return parent.initializer === e && !parent.hasDelegate() &&
            (parent.typeReference == null || e is KtLambdaExpression && declaresPlainValueType(parent))
    }
    if (parent is KtBlockExpression) {
        if (parent.parent is KtNamedFunction && (parent.parent as KtNamedFunction).bodyBlockExpression === parent) return true
        // A lambda written as a statement that is not a block's last (its value) is never expected to be anything.
        return e is KtLambdaExpression && parent.statements.lastOrNull() !== e
    }
    // A lambda called on the spot (`{ a -> a }(1)`) is analyzed before the call that would type it.
    if (e is KtLambdaExpression && parent is dev.ide.kotlin.syntax.psi.KtCallExpression) return parent.calleeExpression === e
    return false
}

/** `val x: String = …`: a property declared as one of the builtin value types, which no function type or SAM
 *  interface can be, when this file declares no classifier of that name. */
private fun declaresPlainValueType(p: KtProperty): Boolean {
    val type = p.typeReference?.typeElement as? KtUserType ?: return false
    if (type.qualifier != null || type.typeArgumentList != null) return false
    val name = type.referencedName ?: return false
    if (name !in PLAIN_VALUE_TYPES) return false
    val file = p.containingKtFile
    return file.importDirectives.none { it.aliasName == name } &&
        file.declarations.none { (it is KtClassOrObject || it is dev.ide.kotlin.syntax.psi.KtTypeAlias) && (it as KtNamedDeclaration).name == name }
}

private val PLAIN_VALUE_TYPES = setOf("String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char")

// --- FirPrimaryConstructorSuperTypeChecker (interface branch) ---

/** SUPERTYPE_INITIALIZED_IN_INTERFACE: an interface has no constructor, so it cannot call a supertype's. */
internal fun supertypeInitializedInInterface(cls: KtClassOrObject): List<Diagnostic> {
    if (cls !is KtClass || !cls.isInterface()) return emptyList()
    return cls.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().map { entry ->
        diagnostic(entry.typeReference ?: entry, "Interfaces cannot initialize supertypes", KotlinDiagnosticCodes.SUPERTYPE_LIST)
    }
}

// --- FirSupertypesChecker ---

/**
 * The supertype list, entry by entry, as `FirSupertypesChecker` reads it. Written forms: a nullable supertype
 * (NULLABLE_SUPERTYPE), an extension or context function type (SUPERTYPE_IS_EXTENSION_OR_CONTEXT_FUNCTION_TYPE,
 * through a typealias of this file too), an annotation with a use-site target on it
 * (ANNOTATION_ON_SUPERCLASS_ERROR), a projection among its arguments
 * (PROJECTION_IN_IMMEDIATE_ARGUMENT_TO_SUPERTYPE). Resolved ones: a class named twice (SUPERTYPE_APPEARS_TWICE),
 * a second class (MANY_CLASSES_IN_SUPERTYPE_LIST), a class above an interface (INTERFACE_WITH_SUPERCLASS, `Any`
 * included when written out), `Enum` itself (CLASS_CANNOT_BE_EXTENDED_DIRECTLY), and `by` delegation to a class
 * (DELEGATION_NOT_TO_INTERFACE). A typealias to `Any` counts as no class, as in K2.
 */
internal fun KotlinSemanticChecks.supertypeList(cls: KtClassOrObject, resolver: KotlinResolver?): List<Diagnostic> {
    if (cls is KtEnumEntry) return emptyList()
    val isInterface = cls is KtClass && cls.isInterface()
    val out = ArrayList<Diagnostic>()
    val seen = HashSet<String>()
    var classAppeared = false
    for (entry in cls.superTypeListEntries) {
        val typeRef = entry.typeReference ?: continue
        val element = typeRef.typeElement
        if (element is KtNullableType) out += diagnostic(typeRef, "A supertype cannot be nullable", KotlinDiagnosticCodes.SUPERTYPE_LIST)
        if (isExtensionOrContextFunctionType(element, cls)) {
            out += diagnostic(typeRef, "Extension and context function types cannot be used as supertypes", KotlinDiagnosticCodes.SUPERTYPE_LIST)
        }
        typeRef.annotationEntries.filter { it.useSiteTarget != null }.forEach {
            out += diagnostic(it, "This annotation is not applicable to a supertype", KotlinDiagnosticCodes.SUPERTYPE_LIST)
        }
        var projected = false
        var part = element as? KtUserType
        while (part != null) {
            for (arg in part.typeArguments) {
                if (arg.projectionKind != dev.ide.kotlin.syntax.psi.KtProjectionKind.NONE) {
                    out += diagnostic(arg, "Projections are not allowed for immediate arguments of a supertype", KotlinDiagnosticCodes.SUPERTYPE_LIST)
                    projected = true
                }
            }
            part = part.qualifier
        }
        if (resolver == null) continue
        val fqn = classifierFqnOf(typeRef, resolver) ?: continue
        val isClassInterface = service.isInterfaceType(fqn) ?: continue
        val aliasToAny = fqn == "kotlin.Any" && (element as? KtUserType)?.referencedName?.let { service.isProjectTypeAlias(it) } == true
        if (!seen.add(fqn)) out += diagnostic(typeRef, "A supertype appears twice", KotlinDiagnosticCodes.SUPERTYPE_LIST)
        if (!isClassInterface) {
            if (classAppeared) {
                if (!aliasToAny) out += diagnostic(typeRef, "Only one class may appear in a supertype list", KotlinDiagnosticCodes.SUPERTYPE_LIST)
            } else if (!aliasToAny) {
                classAppeared = true
            }
            if (isInterface && !aliasToAny) out += diagnostic(typeRef, "An interface cannot inherit from a class", KotlinDiagnosticCodes.SUPERTYPE_LIST)
            if (entry is KtDelegatedSuperTypeEntry) out += diagnostic(typeRef, "Only interfaces can be delegated to", KotlinDiagnosticCodes.SUPERTYPE_LIST)
        }
        if (fqn == "kotlin.Enum" && !projected) out += diagnostic(typeRef, "Enum class cannot be extended directly", KotlinDiagnosticCodes.SUPERTYPE_LIST)
    }
    return out
}

/** An extension (`A.() -> B`) or context (`context(A) () -> B`) function type, written out or behind a
 *  non-generic typealias declared in this file. */
private fun isExtensionOrContextFunctionType(element: KtTypeElement?, from: KtElement): Boolean {
    var e = element
    while (e is KtNullableType) e = e.innerType
    if (e is KtFunctionType) return e.receiverTypeReference != null || e.text.trimStart().startsWith("context(")
    val user = e as? KtUserType ?: return false
    if (user.qualifier != null || user.typeArgumentList != null) return false
    val name = user.referencedName ?: return false
    val alias = from.containingFile.declarations.filterIsInstance<dev.ide.kotlin.syntax.psi.KtTypeAlias>()
        .singleOrNull { it.name == name && it.typeParameters.isEmpty() } ?: return false
    var target = alias.typeReference?.typeElement
    while (target is KtNullableType) target = target.innerType
    return target is KtFunctionType && (target.receiverTypeReference != null || target.text.trimStart().startsWith("context("))
}

// --- FirPublishedApiChecker ---

/**
 * NON_INTERNAL_PUBLISHED_API: `@PublishedApi` widens an `internal` declaration for inline bodies; on anything
 * else it means nothing. A parameter is exempt, as is an `override` that inherits its visibility.
 */
internal fun KotlinSemanticChecks.nonInternalPublishedApi(d: KtDeclaration, resolver: KotlinResolver): Diagnostic? {
    if (d is KtParameter || d is KtPropertyAccessor || d is KtTypeParameter) return null
    if (d is KtObjectDeclaration && d.isObjectLiteral()) return null
    val entry = d.annotationEntries.firstOrNull { it.shortName?.asString() == "PublishedApi" && it.useSiteTarget == null } ?: return null
    val written = entry.typeReference?.text?.filterNot { it.isWhitespace() }
    if (written != "PublishedApi" && written != "kotlin.PublishedApi") return null
    if (written == "PublishedApi" && service.resolveTypeName("PublishedApi", resolver.fileContext, enclosingClassFqnOf(d)) != "kotlin.PublishedApi") return null
    if (d.hasModifier(KtTokens.INTERNAL_KEYWORD)) return null
    val explicitVisibility = d.hasModifier(KtTokens.PUBLIC_KEYWORD) || d.hasModifier(KtTokens.PRIVATE_KEYWORD) || d.hasModifier(KtTokens.PROTECTED_KEYWORD)
    if (!explicitVisibility && d.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    return diagnostic(entry, "@PublishedApi annotation is only applicable to internal declarations", KotlinDiagnosticCodes.PUBLISHED_API)
}

// --- FirInlineDeclarationChecker.checkCanBeInlined / FirInlinePropertyChecker ---

/**
 * DECLARATION_CANT_BE_INLINED: an inline member must be effectively final, since a call cannot be inlined when an
 * override may replace the body. Private members and members of final classes are fine; an `open`, `abstract`
 * or `override` member (and any non-private interface member) of a class that can be subclassed is not.
 */
internal fun declarationCantBeInlined(d: KtDeclaration): Diagnostic? {
    val inlineModifier = when (d) {
        is KtNamedFunction -> d.modifierList?.getModifier(KtTokens.INLINE_KEYWORD)
        is KtProperty -> d.modifierList?.getModifier(KtTokens.INLINE_KEYWORD)
            ?: d.accessors.firstNotNullOfOrNull { it.modifierList?.getModifier(KtTokens.INLINE_KEYWORD) }
        else -> null
    } ?: return null
    val cls = containingClassOf(d) ?: return null
    if (d.hasModifier(KtTokens.PRIVATE_KEYWORD) || d.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
    // The effective visibility of a member of a private or local class is private too (or local), where the
    // compiler's own exemption rules differ in detail: left alone.
    var outer: KtElement? = cls
    while (outer != null) {
        if (outer is KtClassOrObject && (outer.hasModifier(KtTokens.PRIVATE_KEYWORD) || outer.hasModifier(KtTokens.EXPECT_KEYWORD))) return null
        if (outer is KtBlockExpression) return null
        outer = outer.parent
    }
    if (memberIsFinal(d, cls)) return null
    if (!classCanBeSubclassed(cls)) return null
    return diagnostic(inlineModifier, "'inline' modifier is not allowed on virtual members. Only private or final members can be inlined", KotlinDiagnosticCodes.INLINE_DECLARATION)
}

private fun memberIsFinal(d: KtDeclaration, cls: KtClassOrObject): Boolean {
    if (d.hasModifier(KtTokens.FINAL_KEYWORD)) return true
    if (d.hasModifier(KtTokens.OPEN_KEYWORD) || d.hasModifier(KtTokens.ABSTRACT_KEYWORD) || d.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
    return !(cls is KtClass && cls.isInterface())
}

/** Whether the class is open to subclassing: `open`, `abstract`, `sealed`, an interface or an enum (whose entries
 *  may subclass it). An object, an annotation class and a plain class are final. */
private fun classCanBeSubclassed(cls: KtClassOrObject): Boolean {
    if (cls !is KtClass) return false
    if (cls.isInterface() || cls.isEnum()) return true
    return cls.hasModifier(KtTokens.OPEN_KEYWORD) || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.hasModifier(KtTokens.SEALED_KEYWORD)
}

// --- FirInlineBodyNamedFunctionChecker / FirInlineBodyRegularClassChecker ---

/**
 * NOT_YET_SUPPORTED_IN_INLINE: a local function or a local class inside the body of an inline function that is
 * not itself local. An object between the two (an object literal, a nested object) ends the inline body; a
 * local inline function hands the question on to the inline function around it.
 */
internal fun notYetSupportedInInline(d: KtDeclaration): Diagnostic? {
    when (d) {
        is KtNamedFunction -> if (d.name == null) return null
        is KtClass -> {}
        else -> return null
    }
    if (!insideNonLocalInlineBody(d)) return null
    val what = if (d is KtNamedFunction) "Local functions" else "Local classes"
    val at: KtElement = (d as? KtNamedDeclaration)?.nameIdentifier ?: d
    return diagnostic(at, "$what are not yet supported in inline functions", KotlinDiagnosticCodes.INLINE_DECLARATION)
}

private fun insideNonLocalInlineBody(d: KtDeclaration): Boolean {
    var node: KtElement? = d.parent
    while (node != null) {
        when (node) {
            is KtObjectDeclaration, is KtFile -> return false
            is KtNamedFunction -> if (node.hasModifier(KtTokens.INLINE_KEYWORD) && node.parent !is KtBlockExpression) return true
            is KtPropertyAccessor -> {
                val property = node.property
                val inline = node.hasModifier(KtTokens.INLINE_KEYWORD) || property?.hasModifier(KtTokens.INLINE_KEYWORD) == true
                if (inline && property != null && property.parent !is KtBlockExpression) return true
            }
        }
        node = node.parent
    }
    return false
}

// --- FirPropertyTypeParametersChecker ---

/**
 * INCORRECT_TYPE_PARAMETER_OF_PROPERTY: a member or top-level property's type parameter must be reachable from its
 * receiver type (directly, or through the bounds of a type parameter that is), or a use could never infer it.
 */
internal fun incorrectTypeParameterOfProperty(p: KtProperty): Diagnostic? {
    if (p.parent is KtBlockExpression) return null
    val tps = p.typeParameters
    if (tps.isEmpty()) return null
    // Context parameters supply type parameters too; their shape is not modelled here.
    if (p.modifierList?.text?.contains("context(") == true) return null
    val byName = tps.filter { it.name != null }.associateBy { it.name!! }
    val used = HashSet<String>()
    val pending = ArrayDeque<KtElement>()
    p.receiverTypeReference?.let { pending += it }
    while (pending.isNotEmpty()) {
        forEachSimpleTypeName(pending.removeFirst()) { name ->
            val tp = byName[name]
            if (tp != null && used.add(name)) {
                tp.extendsBound?.let { pending += it }
                p.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == name }
                    .forEach { c -> c.boundTypeReference?.let { pending += it } }
            }
        }
    }
    val unused = tps.firstOrNull { it.name != null && it.name !in used } ?: return null
    return diagnostic(unused, "Type parameter of a property must be used in its receiver type", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
}

private fun forEachSimpleTypeName(root: KtElement, onName: (String) -> Unit) {
    fun rec(e: KtElement) {
        if (e is KtUserType && e.qualifier == null) e.referencedName?.let(onName)
        var c = e.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    rec(root)
}

// --- FirTypeParameterBoundsChecker.checkBoundUniqueness ---

/**
 * REPEATED_BOUND and ONLY_ONE_CLASS_BOUND_ALLOWED: a type parameter's bounds (its `: Bound` and its `where`
 * constraints) name each classifier once, and at most one of them is a class (`Any` included; interfaces are
 * free). Bounds that do not resolve take no part, as in K2.
 */
internal fun KotlinSemanticChecks.typeParameterBounds(tp: KtTypeParameter, resolver: KotlinResolver): List<Diagnostic> {
    val owner = (tp.parent?.parent as? KtTypeParameterListOwner) ?: return emptyList()
    val name = tp.name ?: return emptyList()
    val bounds = buildList {
        tp.extendsBound?.let { add(it) }
        owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == name }
            .forEach { c -> c.boundTypeReference?.let { add(it) } }
    }
    val out = ArrayList<Diagnostic>()
    // checkExtensionOrContextFunctionTypeBound
    for (bound in bounds) {
        if (bound.text.trimStart().startsWith("context(") || isExtensionOrContextFunctionType(bound.typeElement, bound)) {
            out += diagnostic(bound, "Extension and context function types cannot be used as upper bounds", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
        }
    }
    if (bounds.size < 2) return out
    // checkOnlyOneTypeParameterBound: a bound that is another type parameter must be the only bound.
    val distinct = bounds.distinctBy { it.text.filterNot { c -> c.isWhitespace() } }
    val (withParam, others) = distinct.partition { b ->
        val user = b.typeElement as? KtUserType
        user != null && user.qualifier == null && user.typeArgumentList == null &&
            user.referencedName?.let { resolver.isTypeParameterInScope(it, b.textRange.startOffset) } == true
    }
    if (withParam.size > 1 || (withParam.size == 1 && others.isNotEmpty())) {
        val inConstraint = { b: KtTypeReference -> b !== tp.extendsBound }
        val at: KtElement = if (distinct.size == 2) {
            val b = others.firstOrNull() ?: withParam.last()
            if (inConstraint(b)) b else tp
        } else tp
        out += diagnostic(at, "If a type parameter is bounded by another type parameter, it cannot have any other bounds", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
    }
    val seenKeys = HashSet<String>()
    val classes = HashSet<String>()
    for (bound in bounds) {
        val key = boundKey(bound, resolver) ?: continue
        if (!seenKeys.add(key)) {
            out += diagnostic(bound, "Repeated bound", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
            continue
        }
        val fqn = key.removePrefix("class:").takeIf { key.startsWith("class:") } ?: continue
        if (service.isInterfaceType(fqn) != false || isAnnotationClass(fqn)) continue
        if (classes.add(fqn) && classes.size > 1) {
            out += diagnostic(bound, "Only one of the upper bounds can be a class", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
        }
    }
    return out
}

/** A bound's identity for uniqueness: its class (type arguments and nullability aside, as K2 keys a class by its
 *  id), or a type parameter with its nullability; null when it does not resolve. */
private fun KotlinSemanticChecks.boundKey(bound: KtTypeReference, resolver: KotlinResolver): String? {
    var element = bound.typeElement
    var nullable = false
    while (element is KtNullableType) { nullable = true; element = element.innerType }
    val user = element as? KtUserType ?: return null
    if (user.qualifier == null) {
        val n = user.referencedName ?: return null
        if (resolver.isTypeParameterInScope(n, bound.textRange.startOffset)) return if (user.typeArgumentList == null) "tp:$n${if (nullable) "?" else ""}" else null
    }
    return classifierFqnOf(bound, resolver)?.let { "class:$it" }
}

private fun KotlinSemanticChecks.isAnnotationClass(fqn: String): Boolean = service.sourceClass(fqn)?.isAnnotation == true

// --- FirTopLevelPropertiesChecker / FirMemberPropertiesChecker: checkPropertyInitializer ---

/**
 * The initializer half of K2's `checkPropertyInitializer`: PROPERTY_INITIALIZER_IN_INTERFACE (an interface keeps no
 * state), PROPERTY_INITIALIZER_NO_BACKING_FIELD (accessors that never touch `field` leave the initializer nowhere
 * to go), EXTENSION_PROPERTY_WITH_BACKING_FIELD (an extension cannot store a value), and
 * EXTENSION_PROPERTY_MUST_HAVE_ACCESSORS_OR_BE_ABSTRACT for an extension that has nothing else either.
 */
internal fun propertyInitializerShape(p: KtProperty): Diagnostic? {
    if (p.parent is KtBlockExpression) return null
    if (p.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return null
    if (p.hasDelegate()) {
        val owner = containingClassOf(p)
        if (owner !is KtClass || !owner.isInterface() || owner.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
        return diagnostic(p.delegate ?: p, "Delegated properties are not allowed in interfaces", KotlinDiagnosticCodes.PROPERTY_SHAPE)
    }
    if (p.hasModifier(KtTokens.EXPECT_KEYWORD) || p.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return null
    var outer: KtElement? = p.parent
    while (outer != null) {
        if (outer is KtClassOrObject && (outer.hasModifier(KtTokens.EXPECT_KEYWORD) || outer.hasModifier(KtTokens.EXTERNAL_KEYWORD))) return null
        outer = outer.parent
    }
    val cls = containingClassOf(p)
    val inInterface = cls is KtClass && cls.isInterface()
    val initializer = p.initializer
    val backingField = declaredBackingField(p)
    if (inInterface && backingField && p.accessors.any { it.hasBody() }) {
        return diagnostic(p.nameIdentifier ?: p, "Property in an interface cannot have a backing field", KotlinDiagnosticCodes.PROPERTY_SHAPE)
    }
    if (initializer != null) {
        return when {
            inInterface -> diagnostic(initializer, "Property initializers are not allowed in interfaces", KotlinDiagnosticCodes.PROPERTY_SHAPE)
            !backingField -> diagnostic(initializer, "Initializer is not allowed here because this property has no backing field", KotlinDiagnosticCodes.PROPERTY_SHAPE)
            p.receiverTypeReference != null -> diagnostic(initializer, "Extension property cannot be initialized because it has no backing field", KotlinDiagnosticCodes.PROPERTY_SHAPE)
            else -> null
        }
    }
    if (!backingField || inInterface || p.hasModifier(KtTokens.LATEINIT_KEYWORD) || p.receiverTypeReference == null) return null
    val getterBody = p.getter?.hasBody() == true
    val setterBody = p.setter?.hasBody() == true
    if (getterBody && (!p.isVar || setterBody)) return null
    return diagnostic(p.nameIdentifier ?: p, "Extension property must have accessors or be abstract", KotlinDiagnosticCodes.PROPERTY_SHAPE)
}

/** K2's `FirProperty.hasBackingField` for a source property that is neither abstract nor delegated: a default
 *  accessor has one, and so does an accessor body that reads or writes `field`. */
private fun declaredBackingField(p: KtProperty): Boolean {
    val getter = p.getter ?: return true
    val setter = p.setter
    if (p.isVar && setter == null) return true
    if (setter != null && !setter.hasBody()) return true
    if (!getter.hasBody()) return true
    return p.accessors.any { accessorReferencesField(it) }
}

private fun accessorReferencesField(accessor: KtPropertyAccessor): Boolean {
    var found = false
    fun rec(e: KtElement) {
        if (found) return
        if (e is KtNameReferenceExpression && e.getReferencedName() == "field") {
            val parent = e.parent
            if (parent !is KtQualifiedExpression || parent.receiverExpression === e) { found = true; return }
        }
        var c = e.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    accessor.bodyExpression?.let { rec(it) }
    return found
}

/** Whether [d] is local in K2's sense: declared in a function body, or anywhere inside a local class or an object
 *  literal (whose members and nested classes are local too). */
private fun isLocalDeclaration(d: KtElement): Boolean {
    var node: KtElement? = d.parent
    while (node != null) {
        if (node is KtBlockExpression || node is KtObjectLiteralExpression) return true
        if (node is KtFile) return false
        node = node.parent
    }
    return false
}

// --- FirSealedSupertypeChecker.checkLocalDeclaration ---

/** SEALED_SUPERTYPE_IN_LOCAL_CLASS: a local class or an object literal cannot extend a sealed class or interface
 *  that is not itself local, since its inheritors must all be known where it is declared. */
internal fun KotlinSemanticChecks.sealedSupertypeInLocalClass(cls: KtClassOrObject, resolver: KotlinResolver): Diagnostic? {
    if (cls is KtEnumEntry || !isLocalDeclaration(cls)) return null
    for (entry in cls.superTypeListEntries) {
        val typeRef = entry.typeReference ?: continue
        val fqn = classifierFqnOf(typeRef, resolver) ?: continue
        val source = service.sourceClass(fqn)
        if (source?.isLocal == true) continue
        val sealed = source?.isSealed ?: (service.sealedSubclassesOf(fqn) != null)
        if (!sealed) continue
        val what = if (cls is KtObjectDeclaration && cls.isObjectLiteral()) "Anonymous object" else "Local class"
        return diagnostic(typeRef, "$what cannot extend a sealed class or interface", KotlinDiagnosticCodes.SUPERTYPE_LIST)
    }
    return null
}

// --- FirThrowableSubclassChecker ---

/**
 * GENERIC_THROWABLE_SUBCLASS and INNER_CLASS_OF_GENERIC_THROWABLE_SUBCLASS: a `catch` cannot tell instantiations of
 * a generic exception apart, so a Throwable subclass may not take type parameters, nor capture any: not as an
 * inner class of a generic class, and not as a local class or an object literal inside a generic declaration.
 */
internal fun KotlinSemanticChecks.genericThrowableSubclass(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    if (cls is KtEnumEntry || cls.superTypeListEntries.isEmpty()) return emptyList()
    val throwable = cls.superTypeListEntries.any { entry ->
        entry.typeReference?.let { classifierFqnOf(it, resolver) }?.let { isThrowableSubtype(it) } == true
    }
    if (!throwable) return emptyList()
    val out = ArrayList<Diagnostic>()
    val literal = cls is KtObjectDeclaration && cls.isObjectLiteral()
    val inner = cls is KtClass && cls.isInner()
    val local = isLocalDeclaration(cls)
    // An inner class's type parameters include its outer classes', which is how K2 sees it.
    val typeParameters = cls.typeParameters.isNotEmpty() || (inner && outerClassesGeneric(cls))
    val at: KtElement = cls.nameIdentifier ?: (cls as? KtObjectDeclaration)?.objectKeyword ?: cls
    if (typeParameters) {
        cls.typeParameters.firstOrNull()?.let { out += diagnostic(it, "Subclass of 'Throwable' cannot have generic parameters", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE) }
        if (inner || local || literal) {
            out += diagnostic(at, "Inner class of generic class extending 'Throwable' is prohibited", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
        }
    } else if (local && hasGenericOuterDeclaration(cls)) {
        out += diagnostic(at, "Inner class of generic class extending 'Throwable' is prohibited", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
    }
    return out
}

private fun outerClassesGeneric(cls: KtClassOrObject): Boolean {
    var current: KtClassOrObject = cls
    while (current is KtClass && current.isInner()) {
        val outer = containingClassOf(current) ?: return false
        if (outer.typeParameters.isNotEmpty()) return true
        current = outer
    }
    return false
}

private fun hasGenericOuterDeclaration(cls: KtClassOrObject): Boolean {
    var node: KtElement? = cls.parent
    while (node != null) {
        when (node) {
            is KtNamedFunction -> if (node.typeParameters.isNotEmpty()) return true
            is KtProperty -> if (node.typeParameters.isNotEmpty()) return true
            is KtClassOrObject -> {
                if (node.typeParameters.isNotEmpty()) return true
                val named = node !is KtObjectDeclaration || !node.isObjectLiteral()
                if (named && !isLocalDeclaration(node) && !(node is KtClass && node.isInner())) return false
            }
            is KtFile -> return false
        }
        node = node.parent
    }
    return false
}

private val THROWABLE_FQNS = setOf("kotlin.Throwable", "java.lang.Throwable")

private fun KotlinSemanticChecks.isThrowableSubtype(fqn: String): Boolean =
    fqn in THROWABLE_FQNS || service.supertypesOf(fqn).any { it.qualifiedName in THROWABLE_FQNS }

// --- FirFunctionParameterChecker.checkVarargParameters ---

/**
 * FORBIDDEN_VARARG_PARAMETER_TYPE: a vararg of `Nothing`, or of a value class other than the unsigned ones, has no
 * array type to collect into. Value classes are recognised in project source only.
 */
internal fun KotlinSemanticChecks.forbiddenVarargType(p: KtParameter, resolver: KotlinResolver): Diagnostic? {
    if (!p.isVarArg) return null
    val typeRef = p.typeReference ?: return null
    var element = typeRef.typeElement
    while (element is KtNullableType) element = element.innerType
    val user = element as? KtUserType ?: return null
    if (user.qualifier == null && user.referencedName == "Nothing") {
        if (service.resolveTypeName("Nothing", resolver.fileContext, enclosingClassFqnOf(p)) != "kotlin.Nothing") return null
        return diagnostic(p, "Forbidden vararg parameter type: Nothing", KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED)
    }
    val fqn = classifierFqnOf(typeRef, resolver) ?: return null
    if (!isSourceValueClass(fqn)) return null
    return diagnostic(p, "Forbidden vararg parameter type: ${user.referencedName}", KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED)
}

private fun KotlinSemanticChecks.isSourceValueClass(fqn: String): Boolean {
    val psi = (service.sourceClass(fqn)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClass ?: return false
    return psi.hasModifier(KtTokens.VALUE_KEYWORD) || psi.hasModifier(KtTokens.INLINE_KEYWORD)
}

// --- FirCommonConstructorDelegationIssuesChecker ---

/**
 * CYCLIC_CONSTRUCTOR_DELEGATION_CALL and PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED. Secondary constructors that
 * delegate around a loop never reach a superclass constructor; every constructor on the path into the loop is
 * reported at its delegation call. A class with a primary constructor must have every other one delegate to it,
 * directly or through another `this(...)`. A `this(...)` call is followed only when its target is plain: one
 * constructor of that arity, or one whose parameter types the literal arguments pick out.
 */
internal fun constructorDelegation(cls: KtClassOrObject): List<Diagnostic> {
    if (cls !is KtClass || cls.isInterface() || cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return emptyList()
    val secondaries = cls.secondaryConstructors
    if (secondaries.isEmpty()) return emptyList()
    val primary = cls.primaryConstructor
    val all: List<KtDeclaration> = listOfNotNull<KtDeclaration>(primary) + secondaries
    fun target(ctor: KtSecondaryConstructor): KtDeclaration? {
        val call = ctor.getDelegationCall()
        if (call.isImplicit || call.calleeExpression?.isThis != true) return null
        return delegationTarget(call, all, ctor)
    }
    val cyclic = LinkedHashSet<KtSecondaryConstructor>()
    for (start in secondaries) {
        val visited = LinkedHashSet<KtSecondaryConstructor>()
        var current: KtSecondaryConstructor = start
        while (true) {
            visited += current
            val next = target(current) as? KtSecondaryConstructor ?: break
            if (next in visited || next in cyclic) { cyclic += visited; break }
            current = next
        }
    }
    val out = ArrayList<Diagnostic>()
    for (ctor in cyclic) {
        out += diagnostic(ctor.getDelegationCall(), "There is a cycle in the delegation calls chain", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE)
    }
    if (primary != null) {
        for (ctor in secondaries) {
            if (ctor in cyclic) continue
            val call = ctor.getDelegationCall()
            if (!call.isImplicit && call.calleeExpression?.isThis == true) continue
            val at: KtElement = if (call.isImplicit) ctor.valueParameterList ?: ctor else call
            out += diagnostic(at, "Primary constructor call expected", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE)
        }
    }
    return out
}

private fun delegationTarget(call: KtConstructorDelegationCall, all: List<KtDeclaration>, from: KtSecondaryConstructor): KtDeclaration? {
    val args = call.valueArguments
    if (args.any { it.isSpread || it.getArgumentName() != null }) return null
    fun params(d: KtDeclaration): List<KtParameter> = when (d) {
        is KtPrimaryConstructor -> d.valueParameters
        is KtSecondaryConstructor -> d.valueParameters
        else -> emptyList()
    }
    if (all.any { d -> params(d).any { it.isVarArg } }) return null
    val byArity = all.filter { d ->
        val ps = params(d)
        args.size <= ps.size && ps.drop(args.size).all { it.hasDefaultValue() }
    }
    if (byArity.size <= 1) return byArity.singleOrNull()
    // A literal names its type; so does a parameter of the delegating constructor, by its declared type.
    val literalTypes = args.map { a ->
        val e = a.getArgumentExpression()
        literalTypeName(e)
            ?: (e as? KtNameReferenceExpression)?.getReferencedName()
                ?.let { n -> from.valueParameters.singleOrNull { it.name == n }?.typeReference?.text?.filterNot { c -> c.isWhitespace() } }
            ?: return null
    }
    return byArity.filter { d ->
        val ps = params(d)
        literalTypes.indices.all { i -> ps[i].typeReference?.text?.filterNot { it.isWhitespace() } == literalTypes[i] }
    }.singleOrNull()
}

/** The simple type name of a literal argument: `"s"` is a String, `1` an Int, `'c'` a Char, `1.0` a Double. */
private fun literalTypeName(e: KtExpression?): String? = when (e) {
    is KtStringTemplateExpression -> "String"
    is KtConstantExpression -> when (e.constantType) {
        KtNodeTypes.BOOLEAN_CONSTANT -> "Boolean"
        KtNodeTypes.CHARACTER_CONSTANT -> "Char"
        KtNodeTypes.FLOAT_CONSTANT -> if (e.text.endsWith("f") || e.text.endsWith("F")) "Float" else "Double"
        KtNodeTypes.INTEGER_CONSTANT -> when {
            e.text.endsWith("u") || e.text.endsWith("U") || e.text.endsWith("uL") || e.text.endsWith("UL") -> null
            e.text.endsWith("L") -> "Long"
            else -> "Int"
        }
        else -> null
    }
    else -> null
}

// --- FirAnnotationClassDeclarationChecker ---

/**
 * The shape of an annotation class: no supertypes (SUPERTYPES_FOR_ANNOTATION_CLASS), not local
 * (LOCAL_ANNOTATION_CLASS_ERROR), primary-constructor parameters that are `val`s (MISSING_VAL_ON_ANNOTATION_PARAMETER,
 * VAR_ANNOTATION_PARAMETER) of an allowed type (INVALID_TYPE_OF_ANNOTATION_MEMBER), and nothing in the body but
 * nested classes (ANNOTATION_CLASS_MEMBER).
 */
internal fun KotlinSemanticChecks.annotationClassShape(cls: KtClassOrObject, resolver: KotlinResolver?): List<Diagnostic> {
    if (cls !is KtClass || !cls.isAnnotation()) return emptyList()
    val out = ArrayList<Diagnostic>()
    val at: KtElement = cls.nameIdentifier ?: cls
    if (cls.parent is KtBlockExpression) out += diagnostic(at, "Annotation class cannot be local", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
    if (cls.superTypeListEntries.isNotEmpty()) out += diagnostic(at, "Annotation class cannot have supertypes", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
    for (p in cls.primaryConstructorParameters) {
        when {
            !p.hasValOrVar() -> out += diagnostic(p, "'val' keyword is missing on annotation parameter", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
            p.isMutable -> out += diagnostic(p, "An annotation parameter cannot be 'var'", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
        }
        if (resolver != null) invalidAnnotationMemberType(p, resolver)?.let { out += it }
    }
    for (d in cls.declarations) {
        if (d is KtClassOrObject) continue
        val target: KtElement = when (d) {
            is KtSecondaryConstructor -> d.valueParameterList ?: d
            else -> (d as? KtNamedDeclaration)?.nameIdentifier ?: d
        }
        out += diagnostic(target, "Members are prohibited in annotation classes", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
    }
    return out
}

private val ANNOTATION_MEMBER_SCALARS = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Char", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
    "kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte", "kotlin.String", "kotlin.reflect.KClass",
    "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray", "kotlin.ByteArray", "kotlin.CharArray", "kotlin.FloatArray",
    "kotlin.DoubleArray", "kotlin.BooleanArray", "kotlin.UIntArray", "kotlin.ULongArray", "kotlin.UShortArray", "kotlin.UByteArray",
)

/**
 * INVALID_TYPE_OF_ANNOTATION_MEMBER: an annotation parameter holds a primitive, an unsigned type, a String, a
 * KClass, an enum, another annotation, or an array of those (an `Array` only of String, KClass, enums or
 * annotations). Judged for project classes, the stdlib's own types and `java.lang.Class`; any other library type
 * is left alone, since whether it is an enum or an annotation is not known here.
 */
private fun KotlinSemanticChecks.invalidAnnotationMemberType(p: KtParameter, resolver: KotlinResolver): Diagnostic? {
    val typeRef = p.typeReference ?: return null
    if (typeRef.typeElement is KtNullableType) return null
    val user = typeRef.typeElement as? KtUserType ?: return null
    val fqn = classifierFqnOf(typeRef, resolver) ?: return null
    val invalid = if (fqn == "kotlin.Array") {
        val argRef = user.typeArguments.singleOrNull()?.typeReference ?: return null
        if (argRef.typeElement is KtNullableType) true
        else {
            val argFqn = classifierFqnOf(argRef, resolver) ?: return null
            if (argFqn == "kotlin.String" || argFqn == "kotlin.reflect.KClass") false
            else annotationMemberClassVerdict(argFqn, scalarsAllowed = false) ?: return null
        }
    } else {
        if (fqn in ANNOTATION_MEMBER_SCALARS) return null
        annotationMemberClassVerdict(fqn, scalarsAllowed = true) ?: return null
    }
    if (!invalid) return null
    return diagnostic(typeRef, "Invalid type of annotation member", KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE)
}

/** Whether a class is NOT an allowed annotation member (an enum or an annotation class is); null when unknown. */
private fun KotlinSemanticChecks.annotationMemberClassVerdict(fqn: String, scalarsAllowed: Boolean): Boolean? {
    service.sourceClass(fqn)?.let { rc -> return !(rc.isEnum || rc.isAnnotation) }
    if (fqn in ANNOTATION_MEMBER_SCALARS) return !scalarsAllowed
    if (fqn == "java.lang.Class") return true
    if (fqn.startsWith("kotlin.") && fqn.count { it == '.' } == 1) return true
    return null
}

// --- FirConstructorAllowedChecker ---

/**
 * CONSTRUCTOR_IN_OBJECT and CONSTRUCTOR_IN_INTERFACE: an object or an interface has no constructor to declare.
 * NON_PRIVATE_CONSTRUCTOR_IN_ENUM and NON_PRIVATE_OR_PROTECTED_CONSTRUCTOR_IN_SEALED: an enum's constructors are
 * private, and a sealed class's are protected or private.
 */
internal fun constructorAllowed(ctor: KtDeclaration): Diagnostic? {
    val cls: KtClassOrObject = when (ctor) {
        is KtPrimaryConstructor -> ctor.parent as? KtClassOrObject
        is KtSecondaryConstructor -> containingClassOf(ctor)
        else -> null
    } ?: return null
    return when {
        cls is KtObjectDeclaration || cls is KtEnumEntry ->
            diagnostic(ctor, "Constructors are not allowed for objects", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE)
        cls is KtClass && cls.isInterface() ->
            diagnostic(ctor, "An interface cannot have a constructor", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE)
        cls is KtClass && cls.isEnum() ->
            if (ctor.hasModifier(KtTokens.PUBLIC_KEYWORD) || ctor.hasModifier(KtTokens.INTERNAL_KEYWORD) || ctor.hasModifier(KtTokens.PROTECTED_KEYWORD))
                diagnostic(ctor, "Constructor must be private in enum class", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE) else null
        cls is KtClass && cls.isSealed() ->
            if (ctor.hasModifier(KtTokens.PUBLIC_KEYWORD) || ctor.hasModifier(KtTokens.INTERNAL_KEYWORD))
                diagnostic(ctor, "Constructor must be private or protected in sealed class", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE) else null
        else -> null
    }
}

// --- FirMethodOfAnyImplementedInInterfaceChecker ---

/** METHOD_OF_ANY_IMPLEMENTED_IN_INTERFACE: an interface cannot give `equals`, `hashCode` or `toString` a body,
 *  since the implementing class's own (from `Any`) always wins. */
internal fun methodOfAnyImplementedInInterface(f: KtNamedFunction): Diagnostic? {
    if (!f.hasBody() || f.receiverTypeReference != null) return null
    // A context parameter makes it a different function from the one `Any` declares.
    if (f.modifierList?.text?.contains("context") == true) return null
    val cls = containingClassOf(f) as? KtClass ?: return null
    if (!cls.isInterface()) return null
    val params = f.valueParameters
    val ofAny = when (f.name) {
        "toString", "hashCode" -> params.isEmpty()
        "equals" -> params.size == 1 && params[0].typeReference?.text?.filterNot { it.isWhitespace() }.let { it == "Any?" || it == "kotlin.Any?" }
        else -> false
    }
    if (!ofAny) return null
    return diagnostic(f.nameIdentifier ?: f, "An interface may not implement a method of 'Any'", KotlinDiagnosticCodes.FUNCTION_SHAPE)
}

// --- FirAnonymousFunctionParametersChecker / FirAnonymousFunctionTypeParametersChecker / FirFunctionNameChecker ---

/**
 * ANONYMOUS_FUNCTION_PARAMETER_WITH_DEFAULT_VALUE and TYPE_PARAMETERS_NOT_ALLOWED: an anonymous function takes
 * neither default values nor type parameters. FUNCTION_DECLARATION_WITH_NO_NAME: a member or top-level function
 * must be named. A nameless function in a block is left alone: whether it is an anonymous function or a local
 * declaration missing its name is the parser's call.
 */
internal fun functionShape(f: KtNamedFunction): List<Diagnostic> {
    if (f.name != null) return emptyList()
    if (f.parent is KtFile || f.parent is KtClassBody) {
        return listOf(diagnostic(f.funKeyword ?: f, "Function declaration must have a name", KotlinDiagnosticCodes.FUNCTION_SHAPE))
    }
    if (f.parent is KtBlockExpression) return emptyList()
    val out = ArrayList<Diagnostic>()
    f.typeParameterList?.let { out += diagnostic(it, "Type parameters are not allowed for anonymous functions", KotlinDiagnosticCodes.FUNCTION_SHAPE) }
    for (p in f.valueParameters) {
        if (p.hasDefaultValue()) out += diagnostic(p, "An anonymous function is not allowed to specify default values for its parameters", KotlinDiagnosticCodes.FUNCTION_SHAPE)
    }
    return out
}

// --- FirTypeParametersInObjectChecker (object literals) and FirLocalEntityNotAllowedChecker (nested objects) ---

/** TYPE_PARAMETERS_IN_OBJECT on an object literal (`object<T> { }`); a named object's is reported by
 *  [KotlinModifierChecks.classPlacement]. */
internal fun typeParametersInObjectLiteral(cls: KtClassOrObject): Diagnostic? {
    if (cls !is KtObjectDeclaration || !cls.isObjectLiteral()) return null
    val list = cls.typeParameterList ?: return null
    return diagnostic(list, "Type parameters are not allowed for objects", KotlinDiagnosticCodes.DECLARATION_SHAPE)
}

/** LOCAL_OBJECT_NOT_ALLOWED for a named object nested in a local class or an object literal, which is local
 *  itself; one declared straight in a function body is reported by [KotlinModifierChecks.classPlacement]. */
internal fun nestedLocalObject(cls: KtClassOrObject): Diagnostic? {
    if (cls !is KtObjectDeclaration || cls.isObjectLiteral() || cls.isCompanion()) return null
    if (cls.parent is KtBlockExpression || !isLocalDeclaration(cls)) return null
    return diagnostic(
        cls.nameIdentifier ?: cls.objectKeyword ?: cls,
        "Named object '${cls.name}' is a singleton and cannot be local. Try to use anonymous object instead", KotlinDiagnosticCodes.DECLARATION_SHAPE,
    )
}

// --- The type resolver's RECURSIVE_TYPEALIAS_EXPANSION ---

/**
 * RECURSIVE_TYPEALIAS_EXPANSION: a typealias whose expansion comes back to itself, directly (`typealias R = R`), in
 * a type argument (`typealias L = List<L>`), or through other typealiases (`typealias A = B`, `typealias B = A`),
 * never ends. Followed through the typealiases of the same file.
 */
internal fun recursiveTypeAlias(alias: dev.ide.kotlin.syntax.psi.KtTypeAlias): Diagnostic? {
    val name = alias.name ?: return null
    val target = alias.typeReference ?: return null
    val byName = HashMap<String, MutableList<dev.ide.kotlin.syntax.psi.KtTypeAlias>>()
    fun collect(e: KtElement) {
        if (e is dev.ide.kotlin.syntax.psi.KtTypeAlias) e.name?.let { byName.getOrPut(it) { ArrayList() } += e }
        var c = e.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(alias.containingFile)
    // Two aliases of one name in the file are a redeclaration; which one a use means is not judged here.
    if (byName.values.any { it.size > 1 }) return null
    val visited = HashSet<dev.ide.kotlin.syntax.psi.KtTypeAlias>()
    fun reachesSelf(t: dev.ide.kotlin.syntax.psi.KtTypeAlias): Boolean {
        if (!visited.add(t)) return false
        val own = t.typeParameters.mapNotNullTo(HashSet()) { it.name }
        var hit = false
        t.typeReference?.let { ref ->
            forEachSimpleTypeName(ref) { n ->
                if (hit || n in own) return@forEachSimpleTypeName
                if (n == name) { hit = true; return@forEachSimpleTypeName }
                byName[n]?.single()?.let { next -> if (reachesSelf(next)) hit = true }
            }
        }
        return hit
    }
    if (!reachesSelf(alias)) return null
    return diagnostic(target, "Recursive typealias in expansion of '$name'", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
}

// --- Inner-class type arguments: FirOuterClassArgumentsRequiredChecker, and the type resolver's
//     TYPE_ARGUMENTS_FOR_OUTER_CLASS_WHEN_NESTED_REFERENCED ---

/** The parts of a qualified user type, outermost qualifier first (`A<T>.B.C` gives `A<T>`, `A<T>.B`, the whole). */
private fun typeParts(user: KtUserType): List<KtUserType> {
    val parts = ArrayList<KtUserType>()
    var p: KtUserType? = user
    while (p != null) { parts += p; p = p.qualifier }
    return parts.asReversed()
}

/** The project class each part of [user] names, outermost first; null when a part does not resolve to a source
 *  class (a package segment, a library class, a type parameter, an alias). */
private fun KotlinSemanticChecks.sourceClassesOf(user: KtUserType, resolver: KotlinResolver): List<Pair<KtUserType, KtClassOrObject>>? {
    val parts = typeParts(user)
    val out = ArrayList<Pair<KtUserType, KtClassOrObject>>()
    var fqn: String? = null
    for (part in parts) {
        val name = part.referencedName ?: return null
        if (fqn == null) {
            if (part === parts.first() && resolver.isTypeParameterInScope(name, part.textRange.startOffset)) return null
            if (service.isProjectTypeAlias(name)) return null
            val candidate = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(part))
            if (candidate == null || !service.isKnownType(candidate)) {
                // A package segment: keep the dotted prefix and try again with the next part.
                if (part.typeArgumentList != null || out.isNotEmpty()) return null
                fqn = null
                continue
            }
            fqn = candidate
        } else {
            fqn = "$fqn.$name"
        }
        val cls = (service.sourceClass(fqn)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: return null
        out += part to cls
    }
    // A leading package path resolves through the last part alone.
    if (out.isEmpty()) {
        val whole = user.text.substringBefore('<').filterNot { it.isWhitespace() }
        if ('<' in user.text) return null
        val f = service.resolveTypeName(whole, resolver.fileContext, enclosingClassFqnOf(user)) ?: return null
        val cls = (service.sourceClass(f)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: return null
        out += user to cls
    }
    return out
}

/**
 * TYPE_ARGUMENTS_FOR_OUTER_CLASS_WHEN_NESTED_REFERENCED: `Outer<A>.Nested` gives type arguments to a class whose
 * next part is a nested class, not an inner one, which takes none of its outer's. Project classes only.
 */
internal fun KotlinSemanticChecks.typeArgumentsForOuterClass(user: KtUserType, resolver: KotlinResolver): Diagnostic? {
    if (user.parent is KtUserType || user.qualifier == null) return null
    val parts = sourceClassesOf(user, resolver) ?: return null
    for (i in 0 until parts.size - 1) {
        val (part, _) = parts[i]
        val next = parts[i + 1].second
        if (part.typeArgumentList == null) continue
        if (next is KtClass && next.isInner()) continue
        return diagnostic(part, "Type arguments for outer class are redundant when nested class is referenced", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
    }
    return null
}

/**
 * OUTER_CLASS_ARGUMENTS_REQUIRED: an inner class of a generic class carries its outer class's type parameters, so
 * naming it without them is only allowed where they are in scope: inside the outer class, reached through inner
 * classes, members and object literals, not through a nested class or object. Each part's written arguments are
 * matched to its own class; the first outer class whose parameters go unwritten and are out of scope is reported.
 * A use inside a class with supertypes is left alone: an inherited outer class's parameters may be in scope there.
 */
internal fun KotlinSemanticChecks.outerClassArgumentsRequired(user: KtUserType, resolver: KotlinResolver): Diagnostic? {
    if (user.parent is KtUserType) return null
    // A bare type in `is`/`as` takes its arguments from the value checked.
    val typeRef = user.parent?.let { if (it is KtNullableType) it.parent else it } as? KtTypeReference
    val holder = typeRef?.parent
    if (holder is dev.ide.kotlin.syntax.psi.KtIsExpression || holder is dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS ||
        holder is dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
    ) return null
    val parts = sourceClassesOf(user, resolver) ?: return null
    val (_, last) = parts.last()
    // The written arguments per class in the inner chain, innermost first.
    val written = HashMap<KtClassOrObject, Boolean>()
    for ((part, cls) in parts) written[cls] = part.typeArgumentList != null
    var current: KtClassOrObject = last
    while (current is KtClass && current.isInner()) {
        val outer = (current.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
        if (outer.typeParameters.isNotEmpty() && written[outer] != true) {
            return when (outerParametersInScope(user, outer)) {
                null -> null
                true -> { current = outer; continue }
                false -> diagnostic(user, "Type arguments should be specified for an outer class '${outer.name}'. Use full class name to specify them", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
            }
        }
        current = outer
    }
    return null
}

/** Whether [outer]'s type parameters are in scope at [use]; null when a class on the way inherits [outer] (whose
 *  arguments then come from that supertype) or cannot be told apart from one. */
private fun KotlinSemanticChecks.outerParametersInScope(use: KtElement, outer: KtClassOrObject): Boolean? {
    val outerFqn = outer.fqName?.asString() ?: return null
    var node: KtElement? = use.parent
    var static = false
    while (node != null) {
        if (node is KtClassOrObject) {
            if (node === outer) return !static
            if (node.superTypeListEntries.isNotEmpty()) {
                val fqn = node.fqName?.asString() ?: return null
                if (service.supertypesOf(fqn).any { it.qualifiedName == outerFqn } || service.hasUnresolvedSupertype(fqn)) return null
            }
            val literal = node is KtObjectDeclaration && node.isObjectLiteral()
            if (!literal && !(node is KtClass && node.isInner())) static = true
        }
        node = node.parent
    }
    return false
}

// --- FirImplicitNothingReturnTypeChecker ---

/**
 * IMPLICIT_NOTHING_RETURN_TYPE and IMPLICIT_NOTHING_PROPERTY_TYPE: a function or a (non-local) property whose type
 * would be inferred as `Nothing` must say so. Judged where the body plainly never completes: a `throw`, a `return`,
 * `null!!`, or a call of a function of this file that is declared `Nothing` or is such a function itself.
 */
internal fun implicitNothingType(d: KtDeclaration): Diagnostic? {
    if (d.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    val body: KtExpression = when (d) {
        is KtNamedFunction -> {
            if (d.name == null || d.typeReference != null || d.hasBlockBody()) return null
            d.bodyExpression ?: return null
        }
        is KtProperty -> {
            if (d.parent is KtBlockExpression || d.typeReference != null || d.hasDelegate()) return null
            d.initializer ?: d.getter?.takeIf { it.returnTypeReference == null && !it.hasBlockBody() }?.bodyExpression ?: return null
        }
        else -> return null
    }
    if (!certainlyNothing(body, HashSet())) return null
    val at: KtElement = (d as KtNamedDeclaration).nameIdentifier ?: d
    val what = if (d is KtNamedFunction) "return type" else "property type"
    return diagnostic(at, "'Nothing' $what needs to be specified explicitly", KotlinDiagnosticCodes.FUNCTION_SHAPE)
}

private fun certainlyNothing(e: KtExpression?, visiting: MutableSet<KtNamedFunction>): Boolean {
    var x = e
    while (x is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) x = x.expression
    return when (x) {
        is dev.ide.kotlin.syntax.psi.KtThrowExpression, is dev.ide.kotlin.syntax.psi.KtReturnExpression -> true
        is dev.ide.kotlin.syntax.psi.KtPostfixExpression ->
            x.operationReference.text == "!!" && x.baseExpression?.text?.trim() == "null"
        is dev.ide.kotlin.syntax.psi.KtCallExpression -> {
            if (runReturningNothing(x, visiting)) return true
            if (x.parent is KtQualifiedExpression || x.typeArgumentList != null || x.lambdaArguments.isNotEmpty()) return false
            val name = (x.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
            val candidates = x.containingFile.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name }
            val target = candidates.singleOrNull() ?: return false
            if (target.receiverTypeReference != null || target.valueParameters.size != x.valueArguments.size) return false
            // A local function of the same name would be the one called.
            if (localFunctionNamed(x, name)) return false
            val declared = target.typeReference?.text?.filterNot { it.isWhitespace() }
            when {
                declared != null -> declared == "Nothing" || declared == "kotlin.Nothing"
                target.hasBlockBody() || !visiting.add(target) -> false
                else -> certainlyNothing(target.bodyExpression, visiting)
            }
        }
        else -> false
    }
}

/**
 * `run { ...; throw e }` or `run { return x }` called with no receiver: the lambda's value is its last statement,
 * so when that never completes the call's type is `Nothing`. Not when the lambda can return a value through a
 * labeled `return@...`, or when this file declares its own `run`.
 */
private fun runReturningNothing(call: dev.ide.kotlin.syntax.psi.KtCallExpression, visiting: MutableSet<KtNamedFunction>): Boolean {
    if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "run") return false
    val q = call.parent as? KtQualifiedExpression
    if (q != null && q.selectorExpression === call) return false
    if (call.typeArgumentList != null || call.valueArguments.size != 1) return false
    val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return false
    if (lambda.valueParameters.isNotEmpty()) return false
    val body = lambda.bodyExpression ?: return false
    val last = body.statements.lastOrNull() ?: return false
    if (call.containingFile.declarations.any { it is KtNamedFunction && it.name == "run" }) return false
    if (localFunctionNamed(call, "run")) return false
    var labeled = false
    fun scan(e: KtElement) {
        if (labeled) return
        if (e is dev.ide.kotlin.syntax.psi.KtReturnExpression && e.getLabelName() != null) { labeled = true; return }
        var c = e.firstChild
        while (c != null) { scan(c); c = c.nextSibling }
    }
    scan(body)
    return !labeled && certainlyNothing(last, visiting)
}

private fun localFunctionNamed(from: KtElement, name: String): Boolean {
    var node: KtElement? = from.parent
    while (node != null) {
        if (node is KtBlockExpression && node.statements.any { it is KtNamedFunction && it.name == name }) return true
        if (node is KtClassBody && node.declarations.any { it is KtNamedFunction && it.name == name }) return true
        node = node.parent
    }
    return false
}

// --- FirReifiedTypeParameterChecker ---

/** REIFIED_TYPE_PARAMETER_NO_INLINE: only an inline function, or a property whose accessors are all inline, can have
 *  a reified type parameter; a class never can. */
internal fun reifiedTypeParameterNoInline(tp: KtTypeParameter): Diagnostic? {
    if (!tp.isReified) return null
    val forbidden = when (val owner = tp.parent?.parent) {
        is KtClass -> true
        is KtNamedFunction -> !owner.hasModifier(KtTokens.INLINE_KEYWORD)
        is KtProperty -> {
            val all = owner.hasModifier(KtTokens.INLINE_KEYWORD)
            val getter = all || owner.getter?.hasModifier(KtTokens.INLINE_KEYWORD) == true
            val setter = all || owner.setter?.hasModifier(KtTokens.INLINE_KEYWORD) == true
            !(getter && (!owner.isVar || setter))
        }
        else -> false
    }
    if (!forbidden) return null
    return diagnostic(tp, "Only type parameters of inline functions can be reified", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE)
}

// --- FirEnumClassSimpleChecker, FirManyCompanionObjectsChecker, FirAnonymousInitializerInInterfaceChecker ---

/**
 * TYPE_PARAMETERS_IN_ENUM (an enum class takes none), MANY_COMPANION_OBJECTS (a second companion), and
 * ANONYMOUS_INITIALIZER_IN_INTERFACE (an interface has no constructor for an `init` block to run in).
 */
internal fun classBodyShape(cls: KtClassOrObject): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    if (cls is KtClass && cls.isEnum()) {
        cls.typeParameters.firstOrNull()?.let { out += diagnostic(it, "Enum class cannot have type parameters", KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE) }
    }
    var companion = false
    for (d in cls.declarations) {
        if (d is KtObjectDeclaration && d.isCompanion()) {
            if (companion) out += diagnostic(d.objectKeyword ?: d, "Only one companion object is allowed per class", KotlinDiagnosticCodes.DECLARATION_FORM)
            companion = true
        }
        if (d is dev.ide.kotlin.syntax.psi.KtClassInitializer && cls is KtClass && cls.isInterface()) {
            out += diagnostic(d, "Anonymous initializers are not allowed in interfaces", KotlinDiagnosticCodes.DECLARATION_FORM)
        }
    }
    return out
}

// --- FirPropertyAccessorsTypesChecker (abstract accessors), FirMemberPropertiesChecker (private in interface) ---

/**
 * ABSTRACT_PROPERTY_WITH_GETTER / ABSTRACT_PROPERTY_WITH_SETTER: an `abstract` property has no accessor bodies.
 * PRIVATE_PROPERTY_IN_INTERFACE: a private interface property must give its getter a body, having no field.
 */
internal fun propertyAccessorShape(p: KtProperty): List<Diagnostic> {
    val cls = containingClassOf(p) ?: return emptyList()
    val out = ArrayList<Diagnostic>()
    val canHaveAbstract = cls is KtClass &&
        (cls.isInterface() || cls.isEnum() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.hasModifier(KtTokens.SEALED_KEYWORD))
    if (p.hasModifier(KtTokens.ABSTRACT_KEYWORD) && canHaveAbstract && !p.hasDelegate()) {
        p.getter?.takeIf { it.hasBody() }?.let { out += diagnostic(it, "An abstract property cannot have a getter body", KotlinDiagnosticCodes.PROPERTY_SHAPE) }
        p.setter?.takeIf { it.hasBody() }?.let { out += diagnostic(it, "An abstract property cannot have a setter body", KotlinDiagnosticCodes.PROPERTY_SHAPE) }
    }
    if (cls is KtClass && cls.isInterface() && p.hasModifier(KtTokens.PRIVATE_KEYWORD) && !p.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
        p.getter?.hasBody() != true
    ) {
        out += diagnostic(p.nameIdentifier ?: p, "Private property in interface must have a getter with a body", KotlinDiagnosticCodes.PROPERTY_SHAPE)
    }
    return out
}

// --- FirPrimaryConstructorSuperTypeChecker / FirCommonConstructorDelegationIssuesChecker, without a primary ---

/**
 * SUPERTYPE_INITIALIZED_WITHOUT_PRIMARY_CONSTRUCTOR: a class with only secondary constructors calls its superclass
 * constructor from them, not from the supertype list. EXPLICIT_DELEGATION_CALL_REQUIRED: such a constructor that
 * delegates nowhere calls the superclass's no-argument constructor, so one must exist; judged for a project
 * superclass whose every constructor needs arguments.
 */
internal fun KotlinSemanticChecks.withoutPrimaryConstructor(cls: KtClassOrObject, resolver: KotlinResolver?): List<Diagnostic> {
    if (cls !is KtClass || cls.isInterface() || cls.primaryConstructor != null || cls.secondaryConstructors.isEmpty()) return emptyList()
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return emptyList()
    val out = ArrayList<Diagnostic>()
    for (entry in cls.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>()) {
        out += diagnostic(entry, "Supertype initialization is impossible without a primary constructor", KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE)
    }
    if (resolver == null || cls.isEnum()) return out
    val superclass = cls.superTypeListEntries.filterIsInstance<dev.ide.kotlin.syntax.psi.KtSuperTypeEntry>().firstNotNullOfOrNull { e ->
        e.typeReference?.let { classifierFqnOf(it, resolver) }
            ?.let { (service.sourceClass(it)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClass }
            ?.takeIf { !it.isInterface() }
    } ?: return out
    if (!everyConstructorNeedsArguments(superclass)) return out
    for (ctor in cls.secondaryConstructors) {
        if (!ctor.getDelegationCall().isImplicit) continue
        out += diagnostic(
            ctor.valueParameterList ?: ctor,
            "Explicit 'this' or 'super' call is required. There is no constructor in the superclass that can be called without arguments",
            KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE,
        )
    }
    return out
}

private fun everyConstructorNeedsArguments(cls: KtClass): Boolean {
    fun needs(params: List<KtParameter>) = params.any { !it.hasDefaultValue() && !it.isVarArg }
    val primary = cls.primaryConstructor
    if (primary == null && cls.secondaryConstructors.isEmpty()) return false
    if (primary != null && !needs(primary.valueParameters)) return false
    return cls.secondaryConstructors.all { needs(it.valueParameters) }
}

// --- FirAmbiguousAnonymousTypeChecker ---

/**
 * AMBIGUOUS_ANONYMOUS_TYPE_INFERRED: a declaration that is not private (or is private and inline) has its object
 * literal type approximated to the object's supertype, and with two or more there is no single one to choose. Judged
 * where the inferred type is the object literal itself: an expression body, an initializer, a getter's expression.
 */
internal fun ambiguousAnonymousType(d: KtDeclaration): Diagnostic? {
    if (isLocalDeclaration(d)) return null
    val (expression, at) = when (d) {
        is KtNamedFunction -> {
            if (d.typeReference != null || d.hasBlockBody()) return null
            if (d.hasModifier(KtTokens.PRIVATE_KEYWORD) && !d.hasModifier(KtTokens.INLINE_KEYWORD)) return null
            d.bodyExpression to (d.nameIdentifier ?: d)
        }
        is KtProperty -> {
            if (d.typeReference != null) return null
            if (d.hasModifier(KtTokens.PRIVATE_KEYWORD) && !d.hasModifier(KtTokens.INLINE_KEYWORD)) return null
            val getter = d.getter
            when {
                // `by lazy { object : A, B {} }`: the delegate's `getValue` returns the lambda's value, the object.
                d.hasDelegate() -> lazyValue(d.delegateExpression) to (d.nameIdentifier ?: d)
                d.initializer != null -> d.initializer to (d.nameIdentifier ?: d)
                getter != null && getter.returnTypeReference == null && !getter.hasBlockBody() ->
                    getter.bodyExpression to getter.namePlaceholder
                else -> return null
            }
        }
        else -> return null
    }
    var e = expression
    while (e is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) e = e.expression
    val literal = (e as? KtObjectLiteralExpression)?.objectDeclaration ?: return null
    if (literal.superTypeListEntries.size < 2) return null
    return diagnostic(at, "Right-hand side has anonymous type. Please specify type explicitly", KotlinDiagnosticCodes.FUNCTION_SHAPE)
}

/** The value a `lazy { ...; value }` delegate (called bare, with only its lambda) gives: the lambda's last
 *  statement. Null for any other delegate, or when this file declares its own `lazy`. */
private fun lazyValue(delegate: KtExpression?): KtExpression? {
    val call = delegate as? dev.ide.kotlin.syntax.psi.KtCallExpression ?: return null
    if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "lazy") return null
    if (call.valueArguments.size != 1) return null
    if (call.containingFile.declarations.any { it is KtNamedFunction && it.name == "lazy" }) return null
    val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return null
    return lambda.bodyExpression?.statements?.lastOrNull()
}
