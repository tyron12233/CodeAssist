package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeElement
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.calleeFunctionOf

/**
 * A sixth round of rules ported from the K2 compiler's checkers (`compiler/fir/checkers`, Apache 2.0) and from
 * the diagnostics its resolution attaches. Each function names the K2 code and checker it follows; where K2
 * decides on resolution this editor does not have, the rule keeps to the cases it can decide and reports
 * nothing on the rest. Registered from [KotlinSemanticChecks.checkers], after the earlier rounds.
 */
internal fun KotlinSemanticChecks.round6PortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallExpression::class) { psi ->
        reportAll(projectionOnNonClassTypeArgument(psi as KtCallExpression))
        report(enumConstructorCall(psi))
        report(protectedConstructorNotInSuperCall(psi))
        if (resolveReady) report(recursionInInline(psi, resolver))
    },
    KotlinChecker(KtSuperExpression::class) { psi ->
        report(superCallFromPublicInline(psi as KtSuperExpression))
        report(notASupertype(psi))
    },
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        report(uninitializedEnumEntry(psi as KtNameReferenceExpression))
        report(typeParameterAsExpression(psi))
    },
    KotlinChecker(KtClassOrObject::class) { psi ->
        reportAll(funInterfaceShape(psi as KtClassOrObject))
        report(finiteBoundsViolation(psi))
        report(valueClassRecursive(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtTypeParameter::class) { psi ->
        report(upperBoundIsArray(psi as dev.ide.kotlin.syntax.psi.KtTypeParameter))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtIntersectionType::class) { psi ->
        reportAll(definitelyNotNullComponents(psi as dev.ide.kotlin.syntax.psi.KtIntersectionType))
    },
    KotlinChecker(KtProperty::class) { psi ->
        report(constValType(psi as KtProperty))
        reportAll(accessorVisibility(psi))
        report(localVariableWithTypeParameters(psi))
        if (resolveReady) report(wrongSetterParameterType(psi, resolver))
        reportAll(accessorForDelegatedProperty(psi))
        report(varOverriddenByVal(psi))
        reportAll(delegateSpecialFunctionMissing(psi))
        if (resolveReady) reportAll(delegateSpecialFunctionNoneApplicable(psi, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtBinaryExpression::class) { psi ->
        if (resolveReady) report(assignmentOperatorReturningValue(psi as dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver))
        report(capturedValInitialization(psi as dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver.takeIf { resolveReady }))
        if (resolveReady) report(assignOperatorAmbiguity(psi, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry::class) { psi ->
        report(functionTypeSupertypeCall(psi as dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry))
    },
    KotlinChecker(KtEnumEntry::class) { psi ->
        report(abstractMemberNotImplementedByEnumEntry(psi as KtEnumEntry))
    },
    KotlinChecker(KtNamedFunction::class) { psi ->
        reportAll(reifiedTypeParameterInOverride(psi as KtNamedFunction))
        report(localInlineFunction(psi))
        report(anonymousSuspendFunction(psi))
        report(anonymousFunctionWithoutBody(psi))
        reportAll(nullableInlineParameter(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtAnnotationEntry::class) { psi ->
        report(illegalJvmName(psi as dev.ide.kotlin.syntax.psi.KtAnnotationEntry))
        report(inapplicableFileTarget(psi))
        report(propertyUseSiteTarget(psi))
        report(wrongExtensionFunctionType(psi))
        report(illegalKotlinVersionString(psi))
        report(jvmExposeBoxedPrivate(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtAnnotation::class) { psi ->
        report(inapplicableFileTarget(psi))
        report(propertyUseSiteTarget(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtPrimaryConstructor::class) { psi ->
        report(missingConstructorKeyword(psi as dev.ide.kotlin.syntax.psi.KtPrimaryConstructor))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtPostfixExpression::class) { psi ->
        if (resolveReady) report(incDecReturningUnit(psi as KtExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtPrefixExpression::class) { psi ->
        if (resolveReady) report(incDecReturningUnit(psi as KtExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression::class) { psi ->
        if (resolveReady) report(extensionInClassReference(psi as dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtWhenExpression::class) { psi ->
        report(illegalWhenSubject(psi as dev.ide.kotlin.syntax.psi.KtWhenExpression))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtParameter::class) { psi ->
        report(annotationDefaultNotConstant(psi as dev.ide.kotlin.syntax.psi.KtParameter))
        reportAll(illegalInlineParameterModifier(psi))
        report(underscoreIsReserved(psi))
        report(varOverriddenByValParameter(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtClassLiteralExpression::class) { psi ->
        report(emptyLhsClassLiteral(psi as dev.ide.kotlin.syntax.psi.KtClassLiteralExpression))
    },
    KotlinChecker(KtTypeAlias::class) { psi ->
        report(typeAliasExpandsToNoClass(psi as KtTypeAlias))
        reportAll(boundOnTypeAliasParameter(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtLabeledExpression::class) { psi ->
        report(underscoreIsReserved(psi))
    },
    KotlinChecker(KtUserType::class) { psi ->
        if (resolveReady) reportAll(conflictingProjectionInTypeAliasExpansion(psi as KtUserType, resolver))
        report(typeArgumentsOnTypeParameter(psi as KtUserType))
    },
    KotlinChecker(KtDeclaration::class) { psi ->
        reportAll(privateWithOpenOrAbstract(psi as KtDeclaration))
    },
    KotlinChecker(KtTypeProjection::class) { psi ->
        reportAll(inAndOutProjection(psi as KtTypeProjection))
    },
    KotlinChecker(KtEnumEntry::class) { psi ->
        reportAll(enumEntryNamedLikeEnumMember(psi as KtEnumEntry))
    },
    KotlinChecker(KtReturnExpression::class) { psi ->
        report(returnInExpressionBodyWithImplicitType(psi as KtReturnExpression, resolver.takeIf { resolveReady }))
    },
)

/** The K2 names each code of this file stands for, merged into [KotlinSuppression.K2_NAMES]. */
internal val ROUND6_K2_NAMES: Map<String, Set<String>> = mapOf(
    KotlinDiagnosticCodes.PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT to setOf("PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT"),
    KotlinDiagnosticCodes.SUPER_CALL_FROM_PUBLIC_INLINE to setOf("SUPER_CALL_FROM_PUBLIC_INLINE"),
    KotlinDiagnosticCodes.CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION to setOf("CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION"),
    KotlinDiagnosticCodes.RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE to setOf("RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE"),
    KotlinDiagnosticCodes.UNINITIALIZED_ENUM_ENTRY to setOf("UNINITIALIZED_ENUM_ENTRY"),
    KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE to setOf(
        "FUN_INTERFACE_WRONG_COUNT_OF_ABSTRACT_MEMBERS", "FUN_INTERFACE_CANNOT_HAVE_ABSTRACT_PROPERTIES",
        "FUN_INTERFACE_ABSTRACT_METHOD_WITH_TYPE_PARAMETERS", "FUN_INTERFACE_ABSTRACT_METHOD_WITH_DEFAULT_VALUE",
    ),
    KotlinDiagnosticCodes.UPPER_BOUND_CANNOT_BE_ARRAY to setOf("UPPER_BOUND_CANNOT_BE_ARRAY"),
    KotlinDiagnosticCodes.DEFINITELY_NOT_NULL_COMPONENT to setOf("INCORRECT_LEFT_COMPONENT_OF_INTERSECTION", "INCORRECT_RIGHT_COMPONENT_OF_INTERSECTION"),
    KotlinDiagnosticCodes.FINITE_BOUNDS_VIOLATION to setOf("FINITE_BOUNDS_VIOLATION"),
    KotlinDiagnosticCodes.NOT_A_SUPERTYPE to setOf("NOT_A_SUPERTYPE"),
    KotlinDiagnosticCodes.TYPE_PARAMETER_IS_NOT_AN_EXPRESSION to setOf("TYPE_PARAMETER_IS_NOT_AN_EXPRESSION"),
    KotlinDiagnosticCodes.REIFIED_TYPE_PARAMETER_IN_OVERRIDE to setOf("REIFIED_TYPE_PARAMETER_IN_OVERRIDE"),
    KotlinDiagnosticCodes.ILLEGAL_JVM_NAME to setOf("ILLEGAL_JVM_NAME"),
    KotlinDiagnosticCodes.MISSING_CONSTRUCTOR_KEYWORD to setOf("MISSING_CONSTRUCTOR_KEYWORD"),
    KotlinDiagnosticCodes.INAPPLICABLE_FILE_TARGET to setOf("INAPPLICABLE_FILE_TARGET"),
    KotlinDiagnosticCodes.INC_DEC_SHOULD_NOT_RETURN_UNIT to setOf("INC_DEC_SHOULD_NOT_RETURN_UNIT"),
    KotlinDiagnosticCodes.EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED to setOf("EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED"),
    KotlinDiagnosticCodes.ACCESSOR_VISIBILITY to setOf("GETTER_VISIBILITY_DIFFERS_FROM_PROPERTY_VISIBILITY", "SETTER_VISIBILITY_INCONSISTENT_WITH_PROPERTY_VISIBILITY"),
    KotlinDiagnosticCodes.LOCAL_DECLARATION_SHAPE to setOf("LOCAL_VARIABLE_WITH_TYPE_PARAMETERS", "NOT_YET_SUPPORTED_LOCAL_INLINE_FUNCTION"),
    KotlinDiagnosticCodes.ILLEGAL_DECLARATION_IN_WHEN_SUBJECT to setOf("ILLEGAL_DECLARATION_IN_WHEN_SUBJECT"),
    KotlinDiagnosticCodes.ENUM_CLASS_CONSTRUCTOR_CALL to setOf("ENUM_CLASS_CONSTRUCTOR_CALL"),
    KotlinDiagnosticCodes.RECURSION_IN_INLINE to setOf("RECURSION_IN_INLINE"),
    KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_BE_RECURSIVE to setOf("VALUE_CLASS_CANNOT_BE_RECURSIVE"),
    KotlinDiagnosticCodes.ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT to setOf("ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT"),
    KotlinDiagnosticCodes.ANONYMOUS_SUSPEND_FUNCTION to setOf("ANONYMOUS_SUSPEND_FUNCTION"),
    KotlinDiagnosticCodes.ILLEGAL_INLINE_PARAMETER_MODIFIER to setOf("ILLEGAL_INLINE_PARAMETER_MODIFIER"),
    KotlinDiagnosticCodes.PROPERTY_USE_SITE_TARGET to setOf(
        "INAPPLICABLE_TARGET_ON_PROPERTY", "INAPPLICABLE_TARGET_PROPERTY_IMMUTABLE", "INAPPLICABLE_TARGET_PROPERTY_HAS_NO_DELEGATE",
    ),
    KotlinDiagnosticCodes.ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT to setOf("ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT"),
    KotlinDiagnosticCodes.WRONG_SETTER_PARAMETER_TYPE to setOf("WRONG_SETTER_PARAMETER_TYPE"),
    KotlinDiagnosticCodes.ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY to setOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY"),
    KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS to setOf("INCOMPATIBLE_MODIFIERS"),
    KotlinDiagnosticCodes.UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS to setOf("UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS"),
    KotlinDiagnosticCodes.TYPEALIAS_SHOULD_EXPAND_TO_CLASS to setOf("TYPEALIAS_SHOULD_EXPAND_TO_CLASS"),
    KotlinDiagnosticCodes.BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED to setOf("BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED"),
    KotlinDiagnosticCodes.NON_MEMBER_FUNCTION_NO_BODY to setOf("NON_MEMBER_FUNCTION_NO_BODY"),
    KotlinDiagnosticCodes.ACCESSOR_FOR_DELEGATED_PROPERTY to setOf("ACCESSOR_FOR_DELEGATED_PROPERTY"),
    KotlinDiagnosticCodes.NULLABLE_INLINE_PARAMETER to setOf("NULLABLE_INLINE_PARAMETER"),
    KotlinDiagnosticCodes.TYPE_ARGUMENTS_NOT_ALLOWED to setOf("TYPE_ARGUMENTS_NOT_ALLOWED"),
    KotlinDiagnosticCodes.UNDERSCORE_IS_RESERVED to setOf("UNDERSCORE_IS_RESERVED"),
    KotlinDiagnosticCodes.WRONG_EXTENSION_FUNCTION_TYPE to setOf("WRONG_EXTENSION_FUNCTION_TYPE"),
    KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL to setOf("VAR_OVERRIDDEN_BY_VAL"),
    KotlinDiagnosticCodes.ILLEGAL_KOTLIN_VERSION_STRING_VALUE to setOf("ILLEGAL_KOTLIN_VERSION_STRING_VALUE"),
    KotlinDiagnosticCodes.JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE to setOf("JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE"),
    KotlinDiagnosticCodes.NO_CONSTRUCTOR to setOf("NO_CONSTRUCTOR"),
    KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_MISSING to setOf("DELEGATE_SPECIAL_FUNCTION_MISSING"),
    KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE to setOf("DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE"),
    KotlinDiagnosticCodes.CAPTURED_VAL_INITIALIZATION to setOf("CAPTURED_VAL_INITIALIZATION"),
    KotlinDiagnosticCodes.ASSIGN_OPERATOR_AMBIGUITY to setOf("ASSIGN_OPERATOR_AMBIGUITY"),
    KotlinDiagnosticCodes.PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL to setOf("PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL"),
)

private fun error(at: KtElement, message: String, code: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

// --- FirProjectionsOnNonClassTypeArgumentChecker ---

/**
 * PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT: a call's explicit type arguments (`foo<*>()`, `A<in Int>()`) name the
 * types its type parameters take, so a star or a variance projection means nothing there. Reported on the star,
 * or on the `in`/`out` keyword.
 */
internal fun projectionOnNonClassTypeArgument(call: KtCallExpression): List<Diagnostic> {
    // `Nested<*>::member` parses its left side as a call with no arguments; a reference's left side is a type.
    if (call.valueArgumentList == null && call.lambdaArguments.isEmpty()) return emptyList()
    val args = call.typeArguments
    if (args.isEmpty()) return emptyList()
    val out = ArrayList<Diagnostic>()
    for (proj in args) {
        val at: KtElement = when (proj.projectionKind) {
            KtProjectionKind.STAR -> proj
            KtProjectionKind.IN -> proj.modifierList?.getModifier(KtTokens.IN_KEYWORD) ?: proj
            KtProjectionKind.OUT -> proj.modifierList?.getModifier(KtTokens.OUT_KEYWORD) ?: proj
            else -> continue
        }
        out += error(at, "Projections are not allowed on type arguments of functions and properties", KotlinDiagnosticCodes.PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT)
    }
    return out
}

// --- FirInlineDeclarationChecker.checkSuperCalls ---

/**
 * SUPER_CALL_FROM_PUBLIC_INLINE: `super.member` in the body of a public-API inline function (or inline property
 * accessor), lambdas and object literals in that body included. The call is copied into callers, which cannot
 * make a non-virtual call into another class. A `super` of an object literal or an enum entry's body is local
 * to the inlined code and stays allowed, as does a member the file declares non-public (another error).
 */
internal fun superCallFromPublicInline(sup: KtSuperExpression): Diagnostic? {
    val q = sup.parent as? KtQualifiedExpression ?: return null
    if (q.receiverExpression !== sup) return null
    // The member or top-level function (or accessor) whose body holds the call.
    var node: KtElement? = sup.parent
    var host: KtDeclaration? = null
    while (node != null && node !is KtFile) {
        when (node) {
            is KtNamedFunction -> {
                if (isMemberOrTopLevel(node)) { host = node; break }
                // A local named function is a body of its own; an anonymous function or a member of an object
                // literal is part of the enclosing inline body.
                if (node.name != null && !isObjectLiteralMember(node)) return null
            }
            is KtPropertyAccessor -> {
                val prop = node.property ?: return null
                if (isMemberOrTopLevel(prop)) { host = node; break }
                if (!isObjectLiteralMember(prop)) return null
            }
            is KtClassOrObject -> if (!(node is KtObjectDeclaration && node.isObjectLiteral())) return null
            else -> {}
        }
        node = node.parent
    }
    val owner: KtDeclaration = when (host) {
        is KtNamedFunction -> host.takeIf { it.hasModifier(KtTokens.INLINE_KEYWORD) } ?: return null
        is KtPropertyAccessor -> {
            val prop = host.property ?: return null
            if (!host.hasModifier(KtTokens.INLINE_KEYWORD) && !prop.hasModifier(KtTokens.INLINE_KEYWORD)) return null
            prop
        }
        else -> return null
    }
    if (!KotlinInlineChecks.isPublicApi(owner)) return null
    // The class whose supertype the call goes to: the labeled one, else the nearest enclosing class.
    val label = sup.getLabelName()
    var cls: KtElement? = sup.parent
    while (cls != null && !(cls is KtClassOrObject && (label == null || cls.name == label))) cls = cls.parent
    val target = cls as? KtClassOrObject ?: return null
    if (target is KtEnumEntry || (target is KtObjectDeclaration && target.isObjectLiteral())) return null
    // A member this file declares non-public is NON_PUBLIC_CALL_FROM_PUBLIC_INLINE's business, not this one's.
    val name = when (val sel = q.selectorExpression) {
        is KtNameReferenceExpression -> sel.getReferencedName()
        is KtCallExpression -> (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        else -> null
    } ?: return null
    if (nonPublicDeclarationNamed(sup.containingKtFile, name)) return null
    return error(sup, "Accessing super members from public-API inline function is deprecated", KotlinDiagnosticCodes.SUPER_CALL_FROM_PUBLIC_INLINE)
}

/** A function or property declared directly in a file or in the body of a named class (not an object literal or
 *  an enum entry's body). */
private fun isMemberOrTopLevel(d: KtDeclaration): Boolean {
    val parent = d.parent
    if (parent is KtFile) return true
    val cls = (parent as? KtClassBody)?.parent as? KtClassOrObject ?: return false
    return cls !is KtEnumEntry && !(cls is KtObjectDeclaration && cls.isObjectLiteral())
}

private fun isObjectLiteralMember(d: KtDeclaration): Boolean {
    val cls = (d.parent as? KtClassBody)?.parent
    return cls is KtObjectDeclaration && cls.isObjectLiteral()
}

private fun nonPublicDeclarationNamed(file: KtFile, name: String): Boolean {
    var found = false
    fun visit(e: KtElement) {
        if (found) return
        if (e is KtDeclaration && (e is KtNamedFunction || e is KtProperty) && (e as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name) {
            if (e.hasModifier(KtTokens.PRIVATE_KEYWORD) ||
                (e.hasModifier(KtTokens.INTERNAL_KEYWORD) && e.annotationEntries.none { it.shortName?.asString() == "PublishedApi" })
            ) { found = true; return }
        }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    return found
}

// --- ProjectionRelationCheckerImpl (typealias expansion) ---

/**
 * CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION: `In1<out A>` where `typealias In1<T> = In<T>` and `class In<in T>`.
 * The projection written on the alias's argument lands, through the expansion, on a class type parameter whose
 * declared variance it contradicts, or meets an opposite projection the alias itself writes (`In<out T>` used as
 * `In3<in Int>`). Followed through this file's aliases (aliases of aliases included) into every class type the
 * expansion builds; a class whose variance is unknown, or an alias of another file, reports nothing. Reported on
 * the `in`/`out` keyword of the use-site argument.
 */
internal fun KotlinSemanticChecks.conflictingProjectionInTypeAliasExpansion(userType: KtUserType, resolver: KotlinResolver): List<Diagnostic> {
    if (userType.parent is KtUserType || userType.qualifier != null) return emptyList()
    val args = userType.typeArgumentList?.arguments ?: return emptyList()
    if (args.none { it.projectionKind == KtProjectionKind.IN || it.projectionKind == KtProjectionKind.OUT }) return emptyList()
    val name = userType.referencedName ?: return emptyList()
    val file = userType.containingFile as? KtFile ?: return emptyList()
    if (resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return emptyList()
    val aliases = file.declarations.filterIsInstance<KtTypeAlias>().groupBy { it.name }
    val alias = aliases[name]?.singleOrNull() ?: return emptyList()
    if (alias.typeParameters.size != args.size) return emptyList()
    val out = ArrayList<Diagnostic>()
    args.forEachIndexed { i, proj ->
        val use = proj.projectionKind
        if (use != KtProjectionKind.IN && use != KtProjectionKind.OUT) return@forEachIndexed
        val param = alias.typeParameters[i].name ?: return@forEachIndexed
        if (conflictsInExpansion(alias, mapOf(param to use), aliases, resolver, depth = 0)) {
            val kw = proj.modifierList?.getModifier(if (use == KtProjectionKind.IN) KtTokens.IN_KEYWORD else KtTokens.OUT_KEYWORD) ?: proj
            out += error(kw, "Conflicting projection in type alias expansion", KotlinDiagnosticCodes.CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION)
        }
    }
    return out
}

/** Marks a slot whose two projections disagree (`out` written on an `in` one). */
private val CONFLICT: KtProjectionKind? = null

/** Whether the projections [projected] (alias type parameter -> use-site projection) conflict anywhere in
 *  [alias]'s expansion. */
private fun KotlinSemanticChecks.conflictsInExpansion(
    alias: KtTypeAlias, projected: Map<String, KtProjectionKind>, aliases: Map<String?, List<KtTypeAlias>>,
    resolver: KotlinResolver, depth: Int,
): Boolean {
    if (depth > 5) return false
    val rhs = alias.typeReference?.typeElement ?: return false
    return conflictsIn(rhs, projected, aliases, resolver, depth)
}

private fun KotlinSemanticChecks.conflictsIn(
    element: KtTypeElement?, projected: Map<String, KtProjectionKind>, aliases: Map<String?, List<KtTypeAlias>>,
    resolver: KotlinResolver, depth: Int,
): Boolean {
    val type = when (element) {
        is KtNullableType -> return conflictsIn(element.innerType, projected, aliases, resolver, depth)
        is KtUserType -> element
        else -> return false // function, intersection and other types: not followed
    }
    val args = type.typeArgumentList?.arguments.orEmpty()
    if (args.isEmpty()) return false
    // What reaches each slot of this type: the projection of an alias parameter written there, combined with the
    // projection the alias writes on it.
    val slots = args.map { arg -> slotProjection(arg, projected) }
    val name = type.referencedName ?: return false
    val inner = if (type.qualifier == null) aliases[name]?.singleOrNull() else null
    if (inner != null) {
        if (inner.typeParameters.size != args.size) return false
        val next = HashMap<String, KtProjectionKind>()
        for ((j, s) in slots.withIndex()) {
            if (s == null) continue
            if (s.second == CONFLICT) return true
            val p = inner.typeParameters[j].name ?: continue
            next[p] = s.second!!
        }
        if (next.isNotEmpty() && conflictsInExpansion(inner, next, aliases, resolver, depth + 1)) return true
    } else if (slots.any { it != null }) {
        val declared = if (type.qualifier == null) declaredVarianceOf(type, name, resolver) else null
        for ((j, s) in slots.withIndex()) {
            if (s == null) continue
            val kind = s.second ?: return true
            val decl = declared?.getOrNull(j) ?: continue
            if (decl == "in" && kind == KtProjectionKind.OUT || decl == "out" && kind == KtProjectionKind.IN) return true
        }
    }
    // A parameter nested deeper (`Generic<In<T>>`) carries the use-site projection into the inner class type.
    for (arg in args) {
        if (arg.projectionKind == KtProjectionKind.STAR) continue
        val el = arg.typeReference?.typeElement
        if (el is KtUserType && el.typeArgumentList == null) continue
        if (conflictsIn(el, projected, aliases, resolver, depth)) return true
    }
    return false
}

/** For a type argument that is exactly one projected alias parameter: (that, the resulting projection), with
 *  [CONFLICT] as the projection when the written one is the opposite. Null for any other argument. */
private fun slotProjection(arg: KtTypeProjection, projected: Map<String, KtProjectionKind>): Pair<KtTypeProjection, KtProjectionKind?>? {
    val written = arg.projectionKind
    if (written == KtProjectionKind.STAR) return null
    val el = arg.typeReference?.typeElement as? KtUserType ?: return null
    if (el.qualifier != null || el.typeArgumentList != null) return null
    val use = projected[el.referencedName ?: return null] ?: return null
    return when (written) {
        KtProjectionKind.NONE, use -> arg to use
        else -> arg to CONFLICT
    }
}

// --- FirModifierChecker (ModifierCheckerHelpers' compatibility table) ---

/**
 * INCOMPATIBLE_MODIFIERS for the pairs the table registers as compatible for classes only: `private` with `open`
 * or `abstract` on a function, property, accessor or parameter. A private member cannot be overridden, so it can
 * be neither. Both modifiers are reported, as K2 does.
 */
internal fun privateWithOpenOrAbstract(decl: KtDeclaration): List<Diagnostic> {
    if (decl !is KtNamedFunction && decl !is KtProperty && decl !is KtParameter && decl !is KtPropertyAccessor) return emptyList()
    val ml = decl.modifierList ?: return emptyList()
    val private = ml.getModifier(KtTokens.PRIVATE_KEYWORD) ?: return emptyList()
    val other = ml.getModifier(KtTokens.ABSTRACT_KEYWORD) ?: ml.getModifier(KtTokens.OPEN_KEYWORD) ?: return emptyList()
    return listOf(
        error(private, "Modifier 'private' is incompatible with '${other.text}'", KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS),
        error(other, "Modifier '${other.text}' is incompatible with 'private'", KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS),
    )
}

/** INCOMPATIBLE_MODIFIERS on a type argument written `in out T`: a projection has one variance. */
internal fun inAndOutProjection(proj: KtTypeProjection): List<Diagnostic> {
    val ml = proj.modifierList ?: return emptyList()
    val inKw = ml.getModifier(KtTokens.IN_KEYWORD) ?: return emptyList()
    val outKw = ml.getModifier(KtTokens.OUT_KEYWORD) ?: return emptyList()
    return listOf(
        error(inKw, "Modifier 'in' is incompatible with 'out'", KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS),
        error(outKw, "Modifier 'out' is incompatible with 'in'", KotlinDiagnosticCodes.INCOMPATIBLE_MODIFIERS),
    )
}

// --- FirConflictsChecker (enum entries against Enum's members) ---

/**
 * REDECLARATION: an enum entry named `name` or `ordinal` clashes with the property every enum class inherits
 * from `Enum`. K2 reports the entry and the enum class.
 */
internal fun enumEntryNamedLikeEnumMember(entry: KtEnumEntry): List<Diagnostic> {
    val name = entry.name ?: return emptyList()
    if (name != "name" && name != "ordinal") return emptyList()
    val at = entry.nameIdentifier ?: return emptyList()
    val message = "Conflicting declarations: enum entry '$name' and 'Enum.$name'"
    val out = arrayListOf(error(at, message, KotlinDiagnosticCodes.CONFLICTING_DECLARATION))
    val cls = (entry.parent as? KtClassBody)?.parent as? KtClass
    cls?.nameIdentifier?.let { out += error(it, message, KotlinDiagnosticCodes.CONFLICTING_DECLARATION) }
    return out
}

// --- FirReturnSyntaxAndLabelChecker (expression bodies) ---

/**
 * RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE: a `return` leaving a named function that has an
 * expression body and no declared return type (`fun f() = run { return 1 }`), whose type the return would have
 * to take part in inferring. Reported only when the way out crosses nothing but lambdas not shown to be left
 * un-inlined; a local function, a class or a stored lambda on the way is RETURN_NOT_ALLOWED instead.
 */
internal fun KotlinSemanticChecks.returnInExpressionBodyWithImplicitType(ret: KtReturnExpression, resolver: KotlinResolver?): Diagnostic? {
    if (ret.containingFile.name.endsWith(".kts")) return null
    val target = KotlinStructuralChecks.returnTarget(ret) as? KtNamedFunction ?: return null
    if (target.name == null || target.hasBlockBody() || target.typeReference != null) return null
    if (target.bodyExpression == null) return null
    var node = ret.parent
    while (node != null && node !== target) {
        when (node) {
            is KtFunctionLiteral -> {
                val lambda = node.parent as? KtLambdaExpression ?: return null
                if (lambdaNotInlined(lambda, resolver)) return null
            }
            is KtNamedFunction, is KtPropertyAccessor, is KtClassOrObject, is KtParameter, is KtProperty -> return null
            else -> {}
        }
        node = node.parent
    }
    if (node !== target) return null
    val start = ret.textRange.startOffset
    val end = ret.getTargetLabel()?.textRange?.endOffset ?: (start + "return".length)
    return Diagnostic(
        TextRange(start, end), Severity.ERROR,
        "Returns are prohibited in functions with an expression body and an implicit return type. Specify the return type explicitly.",
        KotlinDiagnosticCodes.RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE,
    )
}

// --- FirUninitializedEnumChecker ---

/**
 * UNINITIALIZED_ENUM_ENTRY: an enum entry read while the entries are still being built. A member property's
 * initializer or an `init` block of the enum class runs inside every entry's constructor, before any entry is
 * stored, so every entry is uninitialized there (`val x = when (this) { A -> 1 }`). An entry's own constructor
 * arguments and the `init` blocks of its body run before that entry and the ones after it exist (`A1(A2)`).
 * Read by its bare name or through the enum's simple name (`E.A`); a lazy (delegated) property, a function or
 * accessor body, and anything in a nested class run later and are left alone.
 */
internal fun uninitializedEnumEntry(ref: KtNameReferenceExpression): Diagnostic? {
    val name = ref.getReferencedName()
    val parent = ref.parent
    if (parent is KtUserType || parent is dev.ide.kotlin.syntax.psi.KtValueArgumentName) return null
    if (parent is KtCallExpression && parent.calleeExpression === ref) return null
    var qualifier: String? = null
    if (parent is KtQualifiedExpression) {
        if (parent.receiverExpression === ref) {
            // `A.x`: the bare entry as a receiver is still a read of it.
        } else {
            if (parent !is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression) return null
            qualifier = (parent.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        }
    }
    // The context the read runs in, walking out from it.
    var node: KtElement = ref
    var cameFrom: KtElement = ref
    while (true) {
        val p = node.parent ?: return null
        cameFrom = node
        node = p
        when (node) {
            is KtFile, is KtNamedFunction, is KtPropertyAccessor, is KtObjectDeclaration -> return null
            is dev.ide.kotlin.syntax.psi.KtClassInitializer -> break
            is KtProperty -> if (node.parent is KtClassBody) break
            is KtEnumEntry -> if (cameFrom is dev.ide.kotlin.syntax.psi.KtInitializerList) break else return null
            is KtClassOrObject -> return null
            else -> {}
        }
    }
    val holder = node
    // The class body the context belongs to, and the entry whose body or arguments it is in, if any.
    val entry: KtEnumEntry?
    val enumClass: KtClass
    when (holder) {
        is KtEnumEntry -> {
            entry = holder
            enumClass = (holder.parent as? KtClassBody)?.parent as? KtClass ?: return null
        }
        else -> {
            val owner = (holder.parent as? KtClassBody)?.parent ?: return null
            if (owner is KtEnumEntry) {
                if (holder !is dev.ide.kotlin.syntax.psi.KtClassInitializer) return null // a property of an entry's body
                entry = owner
                enumClass = (owner.parent as? KtClassBody)?.parent as? KtClass ?: return null
            } else {
                entry = null
                enumClass = owner as? KtClass ?: return null
            }
        }
    }
    if (!enumClass.isEnum() || enumClass.parent !is KtFile && enumClass.parent !is KtClassBody) return null
    if (holder is KtProperty && (holder.hasDelegate() || holder.initializer == null)) return null
    if (qualifier != null && qualifier != enumClass.name) return null
    val entries = enumClass.declarations.filterIsInstance<KtEnumEntry>()
    val index = entries.indexOfFirst { it.name == name }
    if (index < 0) return null
    if (entry != null && index < entries.indexOf(entry)) return null
    // A lambda among an entry's arguments may be stored and run once every entry exists.
    if (holder is KtEnumEntry && generateSequence(ref.parent) { it.parent }.takeWhile { it !== holder }.any { it is KtFunctionLiteral }) return null
    // A bare name could be something nearer: a constructor parameter, a member, or a local or lambda parameter.
    if (qualifier == null) {
        if (enumClass.primaryConstructorParameters.any { it.name == name }) return null
        if (enumClass.declarations.any { it !is KtEnumEntry && (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
        if (entry != null && entry.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
        var n: KtElement? = ref.parent
        while (n != null && n !== holder) {
            when (n) {
                is dev.ide.kotlin.syntax.psi.KtBlockExpression -> if (n.statements.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name || it is dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration }) return null
                is KtFunctionLiteral -> if (n.valueParameters.isNotEmpty() || name == "it") return null
                else -> {}
            }
            n = n.parent
        }
    }
    return error(ref, "Enum entry '$name' is uninitialized here", KotlinDiagnosticCodes.UNINITIALIZED_ENUM_ENTRY)
}

// --- FirFunInterfaceDeclarationChecker ---

/**
 * The shape of a `fun interface`: exactly one abstract function (FUN_INTERFACE_WRONG_COUNT_OF_ABSTRACT_MEMBERS),
 * no abstract property (FUN_INTERFACE_CANNOT_HAVE_ABSTRACT_PROPERTIES), and a single abstract function that is
 * neither generic (FUN_INTERFACE_ABSTRACT_METHOD_WITH_TYPE_PARAMETERS) nor declares default values
 * (FUN_INTERFACE_ABSTRACT_METHOD_WITH_DEFAULT_VALUE). Judged only when every supertype is an interface of this
 * file, so the whole member set is in view; a member body or initializer makes it concrete, and the nearest
 * declaration of a name and arity wins over the one it overrides.
 */
internal fun funInterfaceShape(cls: KtClassOrObject): List<Diagnostic> {
    if (cls !is KtClass || !cls.isInterface() || !cls.hasModifier(KtTokens.FUN_KEYWORD)) return emptyList()
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.parent !is KtFile && cls.parent !is KtClassBody) return emptyList()
    val funKw = cls.modifierList?.getModifier(KtTokens.FUN_KEYWORD) ?: return emptyList()
    val file = cls.containingKtFile
    val interfaces = HashMap<String, KtClass>()
    var ambiguous = false
    fun collect(e: KtElement) {
        if (e is KtClass && e !is KtEnumEntry) e.name?.let { if (interfaces.put(it, e) != null) ambiguous = true }
        var c = e.firstChild
        while (c != null) { if (c !is dev.ide.kotlin.syntax.psi.KtBlockExpression) collect(c); c = c.nextSibling }
    }
    collect(file)
    if (ambiguous) return emptyList()
    // Members by key (name and arity for functions, name for properties), the most derived declaration first.
    val functions = LinkedHashMap<String, KtNamedFunction>()
    val properties = LinkedHashMap<String, KtProperty>()
    val visited = HashSet<KtClass>()
    val queue = ArrayDeque<KtClass>().apply { add(cls) }
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (!visited.add(c)) continue
        if (!c.isInterface()) return emptyList()
        for (d in c.declarations) when (d) {
            is KtNamedFunction -> {
                val name = d.name ?: return emptyList()
                if (name in ANY_MEMBER_NAMES) return emptyList()
                functions.getOrPut("$name/${d.valueParameters.size}/${d.receiverTypeReference != null}") { d }
            }
            is KtProperty -> properties.getOrPut((d.name ?: return emptyList()) + "/${d.receiverTypeReference != null}") { d }
            else -> {}
        }
        for (entry in c.superTypeListEntries) {
            val type = entry.typeReference?.typeElement as? KtUserType ?: return emptyList()
            if (type.qualifier != null) return emptyList()
            val name = type.referencedName ?: return emptyList()
            if (name == "Any") continue
            val sup = interfaces[name] ?: return emptyList()
            if (c.typeParameters.any { it.name == name }) return emptyList()
            queue.add(sup)
        }
    }
    val out = ArrayList<Diagnostic>()
    for (p in properties.values) {
        if (p.initializer != null || p.getter != null || p.hasDelegate()) continue
        val own = p.parent?.parent === cls
        out += error(if (own) p.valOrVarKeyword else funKw, "Fun interfaces cannot have abstract properties", KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE)
    }
    val abstract = functions.values.filter { it.bodyExpression == null }
    if (abstract.size != 1) {
        out += error(funKw, "Fun interfaces must have exactly one abstract method", KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE)
        return out
    }
    val fn = abstract.single()
    val own = fn.parent?.parent === cls
    fn.typeParameterList?.let {
        out += error(if (own) it else funKw, "Single abstract member cannot declare type parameters", KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE)
    }
    for (p in fn.valueParameters) {
        if (p.defaultValue != null) {
            out += error(if (own) p else funKw, "Single abstract member cannot declare default values", KotlinDiagnosticCodes.FUN_INTERFACE_SHAPE)
        }
    }
    return out
}

private val ANY_MEMBER_NAMES = setOf("equals", "hashCode", "toString")

// --- FirUpperBoundsChecker (JVM) ---

/** UPPER_BOUND_CANNOT_BE_ARRAY: a type parameter bounded by an array type (`A : Array<Any>`, `T : IntArray`),
 *  written on the parameter or in a `where` clause. */
internal fun upperBoundIsArray(tp: dev.ide.kotlin.syntax.psi.KtTypeParameter): Diagnostic? {
    val name = tp.name ?: return null
    val owner = tp.parent?.parent as? dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner ?: return null
    val bounds = listOfNotNull(tp.extendsBound) +
        owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == name }.mapNotNull { it.boundTypeReference }
    val file = tp.containingKtFile
    val isArray = bounds.any { ref ->
        var el = ref.typeElement
        while (el is KtNullableType) el = el.innerType
        val user = el as? KtUserType ?: return@any false
        val q = user.qualifier?.text
        val n = user.referencedName ?: return@any false
        (q == null || q == "kotlin") && n in ARRAY_TYPE_NAMES && (q != null || !declaresClassifier(file, n))
    }
    return if (isArray) error(tp, "Upper bound of a type parameter cannot be an array", KotlinDiagnosticCodes.UPPER_BOUND_CANNOT_BE_ARRAY) else null
}

private val ARRAY_TYPE_NAMES = setOf(
    "Array", "IntArray", "LongArray", "ShortArray", "ByteArray", "CharArray", "BooleanArray", "FloatArray", "DoubleArray",
    "UIntArray", "ULongArray", "UShortArray", "UByteArray",
)

/** Whether [file] declares (anywhere) or imports under that name a classifier called [name]. */
private fun declaresClassifier(file: KtFile, name: String): Boolean {
    if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return true
    var found = false
    fun visit(e: KtElement) {
        if (found) return
        if ((e is KtClassOrObject || e is KtTypeAlias) && (e as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == name) { found = true; return }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    return found
}

// --- FirDefinitelyNotNullableChecker ---

/**
 * `T & Any`, the definitely-non-nullable type: its left side must be a type parameter that can be null
 * (INCORRECT_LEFT_COMPONENT_OF_INTERSECTION: `String & Any`, `F? & Any`, `(F & Any) & Any`, or a `T` bounded by a
 * non-null type), and its right side `Any` itself (INCORRECT_RIGHT_COMPONENT_OF_INTERSECTION). A type parameter
 * bounded by another type parameter or by a typealias is given the benefit of the doubt.
 */
internal fun KotlinSemanticChecks.definitelyNotNullComponents(t: dev.ide.kotlin.syntax.psi.KtIntersectionType): List<Diagnostic> {
    val parts = t.children.filterIsInstance<dev.ide.kotlin.syntax.psi.KtTypeReference>()
    if (parts.size != 2) return emptyList()
    val (left, right) = parts
    val out = ArrayList<Diagnostic>()
    when (leftIsNullableTypeParameter(left)) {
        false -> out += error(left, "Incorrect left component of intersection: must be a type parameter with a nullable upper bound", KotlinDiagnosticCodes.DEFINITELY_NOT_NULL_COMPONENT)
        else -> {}
    }
    val r = right.typeElement
    if (r is KtUserType && r.qualifier == null && r.referencedName == "Any" && declaresClassifier(t.containingKtFile, "Any")) return emptyList()
    // A typealias (`typealias Anything = Any`) may stand for `Any`.
    if (r is KtUserType && r.referencedName != "Any" &&
        (service.isProjectTypeAlias(r.referencedName ?: return out) || declaresClassifier(t.containingKtFile, r.referencedName!!))) return out
    val rightIsAny = r is KtUserType && r.referencedName == "Any" && r.typeArgumentList == null &&
        r.qualifier.let { it == null || it.text == "kotlin" }
    if (!rightIsAny) out += error(right, "Incorrect right component of intersection: must be 'Any'", KotlinDiagnosticCodes.DEFINITELY_NOT_NULL_COMPONENT)
    return out
}

/** true / false, or null when undecidable. */
private fun KotlinSemanticChecks.leftIsNullableTypeParameter(ref: dev.ide.kotlin.syntax.psi.KtTypeReference): Boolean? {
    val el = ref.typeElement
    if (el !is KtUserType) return false // `F?`, `(F & Any)`, a function type
    if (el.qualifier != null || el.typeArgumentList != null) return false
    val name = el.referencedName ?: return null
    val tp = typeParameterInScope(ref, name) ?: run {
        // A class (`String`, `Any`) is never a valid left side; an unknown name is another error.
        return if (service.isProjectTypeAlias(name)) null else false
    }
    val owner = tp.parent?.parent as? dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner ?: return null
    val bounds = listOfNotNull(tp.extendsBound) +
        owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == name }.mapNotNull { it.boundTypeReference }
    for (b in bounds) {
        when (val be = b.typeElement) {
            is KtNullableType -> {}
            is dev.ide.kotlin.syntax.psi.KtIntersectionType -> return null
            is KtUserType -> {
                val bn = be.referencedName ?: return null
                if (be.qualifier == null && (typeParameterInScope(b, bn) != null || service.isProjectTypeAlias(bn))) return null
                return false
            }
            else -> return false
        }
    }
    return true
}

/** The type parameter named [name] that is in scope at [at]: the nearest declaring owner, not crossing a nested
 *  class or object that would hide an outer class's parameters. */
private fun typeParameterInScope(at: KtElement, name: String): dev.ide.kotlin.syntax.psi.KtTypeParameter? {
    var node: KtElement? = at.parent
    while (node != null && node !is KtFile) {
        if (node is dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner) {
            node.typeParameters.firstOrNull { it.name == name }?.let { return it }
        }
        if (node is KtClassOrObject && !(node is KtClass && node.hasModifier(KtTokens.INNER_KEYWORD))) return null
        node = node.parent
    }
    return null
}

// --- FirFiniteBoundRestrictionChecker ---

/**
 * FINITE_BOUNDS_VIOLATION: the type parameters' bounds expand forever. An edge runs from a type parameter to
 * the parameter of a generic class that its bounds (and their arguments, and the bounds of parameters they name)
 * reach through a star or `in`/`out` projection; a cycle is the violation, reported on the class's first type
 * parameter on one. Classes of this file only: a library class's parameters never lead back here.
 */
internal fun finiteBoundsViolation(cls: KtClassOrObject): Diagnostic? {
    if (cls !is KtClass || cls.typeParameters.isEmpty()) return null
    // A cycle through this class's parameters leaves one of them by a bound; with none, there is nothing to walk.
    if (cls.typeParameters.all { it.extendsBound == null } && cls.typeConstraints.isEmpty()) return null
    val file = cls.containingKtFile
    val classes = HashMap<String, KtClass>()
    val ambiguous = HashSet<String>()
    fun collect(e: KtElement) {
        if (e is KtClass && e !is KtEnumEntry) e.name?.let { if (classes.put(it, e) != null) ambiguous += it }
        if (e is KtTypeAlias) e.name?.let { ambiguous += it }
        var c = e.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(file)
    val edges = HashMap<dev.ide.kotlin.syntax.psi.KtTypeParameter, MutableSet<dev.ide.kotlin.syntax.psi.KtTypeParameter>>()
    val visitedClasses = HashSet<KtClass>()
    fun boundsOf(tp: dev.ide.kotlin.syntax.psi.KtTypeParameter): List<dev.ide.kotlin.syntax.psi.KtTypeReference> {
        val owner = tp.parent?.parent as? dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner ?: return emptyList()
        return listOfNotNull(tp.extendsBound) +
            owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == tp.name }.mapNotNull { it.boundTypeReference }
    }
    fun ownerParams(tp: dev.ide.kotlin.syntax.psi.KtTypeParameter): List<dev.ide.kotlin.syntax.psi.KtTypeParameter> =
        (tp.parent?.parent as? dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner)?.typeParameters.orEmpty()
    fun visit(tp: dev.ide.kotlin.syntax.psi.KtTypeParameter) {
        val siblings = ownerParams(tp)
        // The closure of upper bounds: a bound naming a sibling parameter brings in that parameter's bounds.
        val closure = ArrayList<dev.ide.kotlin.syntax.psi.KtTypeReference>()
        val seenParams = hashSetOf(tp)
        val work = ArrayDeque(boundsOf(tp))
        while (work.isNotEmpty()) {
            val b = work.removeFirst()
            closure += b
            val be = (b.typeElement as? KtNullableType)?.innerType ?: b.typeElement
            if (be is KtUserType && be.qualifier == null && be.typeArgumentList == null) {
                siblings.firstOrNull { it.name == be.referencedName }?.let { if (seenParams.add(it)) work.addAll(boundsOf(it)) }
            }
        }
        // Every class type among the bounds and their arguments.
        fun constituents(el: KtTypeElement?, sink: MutableList<KtUserType>) {
            when (el) {
                is KtNullableType -> constituents(el.innerType, sink)
                is KtUserType -> {
                    sink += el
                    el.typeArgumentList?.arguments?.forEach { constituents(it.typeReference?.typeElement, sink) }
                }
                else -> {}
            }
        }
        val types = ArrayList<KtUserType>()
        closure.forEach { constituents(it.typeElement, types) }
        for (type in types) {
            if (type.qualifier != null) continue
            val name = type.referencedName ?: continue
            if (siblings.any { it.name == name } || name in ambiguous) continue
            val target = classes[name] ?: continue
            val params = target.typeParameters
            if (visitedClasses.add(target)) params.forEach { visit(it) }
            val args = type.typeArgumentList?.arguments.orEmpty()
            if (params.size != args.size) continue
            for (i in params.indices) {
                if (args[i].projectionKind != KtProjectionKind.NONE) {
                    edges.getOrPut(tp) { HashSet() }.add(params[i])
                    edges.getOrPut(params[i]) { HashSet() }
                }
            }
        }
    }
    visitedClasses += cls
    cls.typeParameters.forEach { visit(it) }
    fun inCycle(start: dev.ide.kotlin.syntax.psi.KtTypeParameter): Boolean {
        val seen = HashSet<dev.ide.kotlin.syntax.psi.KtTypeParameter>()
        val stack = ArrayDeque(edges[start].orEmpty())
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n === start) return true
            if (seen.add(n)) stack.addAll(edges[n].orEmpty())
        }
        return false
    }
    val first = cls.typeParameters.firstOrNull { inCycle(it) } ?: return null
    return error(first, "This type parameter violates the Finite Bound Restriction", KotlinDiagnosticCodes.FINITE_BOUNDS_VIOLATION)
}

// --- FirConstPropertyChecker (type) ---

/**
 * TYPE_CANT_BE_USED_FOR_CONST_VAL: a `const val` whose type is not a primitive, an unsigned type or `String`,
 * declared (`const val e: MyEnum = MyEnum.A`, `String?`, `Array<String>`) or inferred from a range
 * (`const val r = 1..2`, `1.rangeTo(2)`). Only where the initializer is otherwise constant-looking, so a call
 * reported as a non-constant initializer is not reported twice.
 */
internal fun constValType(prop: KtProperty): Diagnostic? {
    val kw = prop.modifierList?.getModifier(KtTokens.CONST_KEYWORD) ?: return null
    if (prop.isVar || prop.parent is dev.ide.kotlin.syntax.psi.KtBlockExpression || prop.receiverTypeReference != null) return null
    if (prop.getter != null || prop.hasDelegate()) return null
    val holder = (prop.parent as? KtClassBody)?.parent
    if (holder != null && !(holder is KtObjectDeclaration && !holder.isObjectLiteral())) return null
    val init = prop.initializer?.let { dev.ide.lang.kotlin.parse.unwrapParen(it) } ?: return null
    if (init !is dev.ide.kotlin.syntax.psi.KtConstantExpression && init !is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression &&
        init !is KtNameReferenceExpression && init !is KtQualifiedExpression && init !is dev.ide.kotlin.syntax.psi.KtBinaryExpression &&
        init !is dev.ide.kotlin.syntax.psi.KtPrefixExpression
    ) return null
    val file = prop.containingKtFile
    val typeRef = prop.typeReference
    val bad = if (typeRef != null) {
        when (val el = typeRef.typeElement) {
            is KtNullableType -> true
            is KtUserType -> {
                val name = el.referencedName
                val q = el.qualifier?.text
                when {
                    name == null -> false
                    q != null && q != "kotlin" -> false
                    name in CONST_TYPE_NAMES -> false
                    el.typeArgumentList != null -> true
                    q == null && (file.declarations.any { it is KtTypeAlias && it.name == name } || file.importDirectives.any { it.aliasName == name }) -> false
                    q == null && fileDeclaresClass(file, name) -> true
                    name in NON_CONST_LIBRARY_TYPES -> true
                    else -> false
                }
            }
            else -> true
        }
    } else {
        isRangeExpression(init)
    }
    return if (bad) error(kw, "Const 'val' has type that cannot be used for a constant", KotlinDiagnosticCodes.CONST_MISUSE) else null
}

private val CONST_TYPE_NAMES = setOf("Int", "Long", "Short", "Byte", "Double", "Float", "Char", "Boolean", "String", "UInt", "ULong", "UShort", "UByte")
private val NON_CONST_LIBRARY_TYPES = setOf(
    "Any", "Unit", "Nothing", "Number", "CharSequence", "Comparable", "IntRange", "LongRange", "CharRange",
    "IntArray", "LongArray", "ShortArray", "ByteArray", "CharArray", "BooleanArray", "FloatArray", "DoubleArray",
)

private fun fileDeclaresClass(file: KtFile, name: String): Boolean {
    var found = false
    fun visit(e: KtElement) {
        if (found) return
        if (e is KtClassOrObject && e.name == name) { found = true; return }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    return found
}

/** `a..b`, `a..<b`, `a.rangeTo(b)`, `a.rangeUntil(b)` over operands that are literals or names. */
private fun isRangeExpression(e: KtElement): Boolean {
    fun simple(x: KtElement?): Boolean {
        val u = (x as? KtExpression)?.let { dev.ide.lang.kotlin.parse.unwrapParen(it) }
        return u is dev.ide.kotlin.syntax.psi.KtConstantExpression || u is KtNameReferenceExpression
    }
    return when (e) {
        is dev.ide.kotlin.syntax.psi.KtBinaryExpression ->
            (e.operationToken == KtTokens.RANGE || e.operationToken == KtTokens.RANGE_UNTIL) &&
                e.left?.let { simple(it) } == true && e.right?.let { simple(it) } == true
        is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> {
            val call = e.selectorExpression as? KtCallExpression ?: return false
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            (name == "rangeTo" || name == "rangeUntil") && call.valueArguments.size == 1 &&
                simple(e.receiverExpression) && simple(call.valueArguments.single().getArgumentExpression())
        }
        else -> false
    }
}

// --- FirSuperReferenceChecker / super qualifier resolution ---

/**
 * NOT_A_SUPERTYPE: `super<X>` where `X` is not one of the class's own supertypes (`super<String>`, `super<E>` for
 * a type parameter, `super<C>` from an inner class that does not extend `C`, `super<G<String>>` over `G<Int>`).
 * Supertypes and the qualifier are compared by class name after following this file's typealiases, and by their
 * type arguments as written when both sides have them. A class whose supertypes are not all plain class types
 * (a function type among them), or that writes none, is left alone.
 */
internal fun notASupertype(sup: KtSuperExpression): Diagnostic? {
    val qualifier = sup.superTypeQualifier?.takeIf { it.text.isNotBlank() } ?: return null
    val label = sup.getLabelName()
    // The class whose body holds the call: a `super` in a class header (`class Inner : A(super<B>.f())`) belongs
    // to the class around it.
    var n: KtElement? = sup.parent
    var from: KtElement = sup
    while (n != null && !(n is KtClassOrObject && from is KtClassBody && (label == null || n.name == label))) { from = n; n = n.parent }
    val cls = n as? KtClassOrObject ?: return null
    if (cls is KtEnumEntry) return null
    val entries = cls.superTypeListEntries
    if (entries.isEmpty()) return null
    val file = sup.containingKtFile
    if (file.importDirectives.any { it.aliasName != null }) return null
    val aliases = HashMap<String, KtTypeAlias>()
    val dup = HashSet<String>()
    fun collect(e: KtElement) {
        if (e is KtTypeAlias) e.name?.let { if (aliases.put(it, e) != null) dup += it }
        var c = e.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(file)
    if (dup.isNotEmpty()) return null
    val supers = entries.map { expandHead(it.typeReference?.typeElement, aliases) ?: return null }
    val q = qualifier.typeElement
    if (q !is KtUserType) return error(qualifier, "Not an immediate supertype", KotlinDiagnosticCodes.NOT_A_SUPERTYPE)
    val (qHead, qArgs) = expandHead(q, aliases) ?: return null
    if (qHead == "Any") return null
    val match = supers.any { (head, args) -> head == qHead && (qArgs == null || args == null || args == qArgs || (qArgs + args).any { "." in it }) }
    if (match) return null
    return error(qualifier, "Not an immediate supertype", KotlinDiagnosticCodes.NOT_A_SUPERTYPE)
}

/** The class name a type names after this file's typealiases, with its type arguments as written (null when they
 *  are not written or cannot be followed through an alias); null for anything but a class type. */
private fun expandHead(el: KtTypeElement?, aliases: Map<String, KtTypeAlias>): Pair<String, List<String>?>? {
    var type = el as? KtUserType ?: return null
    var args: List<String>? = argTexts(type)
    for (step in 0 until 5) {
        val name = type.referencedName ?: return null
        val alias = (if (type.qualifier == null) aliases[name] else null) ?: return name to args
        val rhs = alias.typeReference?.typeElement as? KtUserType ?: return null
        val params = alias.typeParameters.mapNotNull { it.name }
        val rhsArgs = argTexts(rhs)
        val useArgs = args
        args = when {
            rhsArgs == null -> null
            params.isEmpty() -> rhsArgs
            useArgs == null || useArgs.size != params.size -> null
            rhsArgs.any { a -> a !in params && params.any { p -> mentionsName(a, p) } } -> null
            else -> {
                val sub = params.zip(useArgs).toMap()
                rhsArgs.map { sub[it] ?: it }
            }
        }
        type = rhs
    }
    return null
}

private fun argTexts(type: KtUserType): List<String>? =
    type.typeArgumentList?.arguments?.map { it.text.filterNot(Char::isWhitespace) }

/** Whether [text] mentions the identifier [name] as a whole word. */
private fun mentionsName(text: String, name: String): Boolean {
    var i = text.indexOf(name)
    while (i >= 0) {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + name.length)
        if ((before == null || !before.isLetterOrDigit() && before != '_') && (after == null || !after.isLetterOrDigit() && after != '_')) return true
        i = text.indexOf(name, i + 1)
    }
    return false
}

// --- type parameters in expression position ---

/**
 * TYPE_PARAMETER_IS_NOT_AN_EXPRESSION: a type parameter's name read as a value (`val x = T`, `T()`, `1 + T`,
 * `"$T"`). Only where nothing else of that name could be meant: the file declares no value, function, class or
 * label of the name and imports none.
 */
internal fun typeParameterAsExpression(ref: KtNameReferenceExpression): Diagnostic? {
    val name = ref.getReferencedName()
    if (name.isEmpty() || !name[0].isUpperCase()) return null
    when (val parent = ref.parent) {
        is KtUserType, is dev.ide.kotlin.syntax.psi.KtValueArgumentName, is dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression,
        is dev.ide.kotlin.syntax.psi.KtClassLiteralExpression, is dev.ide.kotlin.syntax.psi.KtTypeConstraint, is KtQualifiedExpression -> return null
        is KtCallExpression -> {
            val grand = parent.parent
            if (grand is KtQualifiedExpression && grand.selectorExpression === parent) return null
        }
        else -> {}
    }
    typeParameterInScope(ref, name) ?: return null
    val file = ref.containingKtFile
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return null
    var clash = false
    fun visit(e: KtElement) {
        if (clash) return
        if (e is dev.ide.kotlin.syntax.psi.KtNamedDeclaration && e !is dev.ide.kotlin.syntax.psi.KtTypeParameter && e.name == name) { clash = true; return }
        if (e is dev.ide.kotlin.syntax.psi.KtLabeledExpression && e.getLabelName() == name) { clash = true; return }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    if (clash) return null
    return error(ref, "Type parameter '$name' is not an expression", KotlinDiagnosticCodes.TYPE_PARAMETER_IS_NOT_AN_EXPRESSION)
}

// --- FirInlineDeclarationChecker (reified parameters of overrides) ---

/** REIFIED_TYPE_PARAMETER_IN_OVERRIDE: an `override` cannot make a type parameter `reified`, since a call through
 *  the overridden member passes no type. Reported on the `reified` keyword. */
internal fun reifiedTypeParameterInOverride(fn: KtNamedFunction): List<Diagnostic> {
    if (!fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return emptyList()
    val cls = (fn.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return emptyList()
    if (cls.superTypeListEntries.isEmpty() && cls !is KtEnumEntry) return emptyList()
    return fn.typeParameters.mapNotNull { tp ->
        tp.modifierList?.getModifier(KtTokens.REIFIED_KEYWORD)?.let {
            error(it, "Override by a function with reified type parameter", KotlinDiagnosticCodes.REIFIED_TYPE_PARAMETER_IN_OVERRIDE)
        }
    }
}

// --- FirJvmNameChecker / FirJvmExposeBoxedChecker ---

/** ILLEGAL_JVM_NAME: `@JvmName("with.dot")` (or `@JvmExposeBoxed`) naming something the JVM cannot call a method:
 *  empty, or holding `.`, `;`, `[`, `/`, `<`, `>`, `:` or `\`. */
internal fun illegalJvmName(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
    val short = entry.shortName?.asString() ?: return null
    if (short != "JvmName" && short != "JvmExposeBoxed") return null
    if (fileDeclaresClass(entry.containingKtFile, short)) return null
    val arg = entry.valueArguments.singleOrNull() ?: return null
    val str = arg.getArgumentExpression() as? dev.ide.kotlin.syntax.psi.KtStringTemplateExpression ?: return null
    if (str.entries.any { it !is dev.ide.kotlin.syntax.psi.KtLiteralStringTemplateEntry }) return null
    val value = str.entries.joinToString("") { it.text }
    if (short == "JvmExposeBoxed" && value.isEmpty()) return null
    if (value.isNotEmpty() && value.none { it in ILLEGAL_JVM_NAME_CHARS }) return null
    return error(entry, "Illegal JVM name", KotlinDiagnosticCodes.ILLEGAL_JVM_NAME)
}

private val ILLEGAL_JVM_NAME_CHARS = setOf('.', ';', '[', '/', '<', '>', ':', '\\')

// --- FirMissingConstructorKeywordSyntaxChecker ---

/** MISSING_CONSTRUCTOR_KEYWORD: a primary constructor with modifiers or annotations but no `constructor` keyword
 *  (`class A private (val x: Int)`). */
internal fun missingConstructorKeyword(ctor: dev.ide.kotlin.syntax.psi.KtPrimaryConstructor): Diagnostic? {
    if (ctor.modifierList == null) return null
    var c = ctor.firstChild
    while (c != null) {
        if (c.elementType == KtTokens.CONSTRUCTOR_KEYWORD) return null
        c = c.nextSibling
    }
    return error(ctor, "Use 'constructor' keyword after modifiers of primary constructor", KotlinDiagnosticCodes.MISSING_CONSTRUCTOR_KEYWORD)
}

// --- FirAnnotationChecker (file use-site target) ---

/** INAPPLICABLE_FILE_TARGET: `@file:` on an annotation that is not among the file's own, which come before the
 *  `package` directive; anywhere else it lands on a declaration. Reported on the `file` target. */
internal fun inapplicableFileTarget(e: KtElement): Diagnostic? {
    if (e !is dev.ide.kotlin.syntax.psi.KtAnnotationEntry && e !is dev.ide.kotlin.syntax.psi.KtAnnotation) return null
    // On a type, `@file:` is a use-site target the type cannot have: WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET.
    if ((e.parent as? dev.ide.kotlin.syntax.psi.KtModifierList)?.parent is dev.ide.kotlin.syntax.psi.KtTypeReference) return null
    var p = e.parent
    while (p != null && p !is KtFile) {
        if (p is dev.ide.kotlin.syntax.psi.KtFileAnnotationList) return null
        if (p is dev.ide.kotlin.syntax.psi.KtAnnotation) return null // the group carries the target
        p = p.parent
    }
    var c = e.firstChild
    while (c != null) {
        if (c is dev.ide.kotlin.syntax.psi.KtAnnotationUseSiteTarget) {
            return if (c.text.trim() == "file") error(c, "'@file:' annotations can only be applied before package declaration", KotlinDiagnosticCodes.INAPPLICABLE_FILE_TARGET) else null
        }
        if (c is dev.ide.kotlin.syntax.psi.KtAnnotationEntry) return null
        c = c.nextSibling
    }
    return null
}

// --- FirIncDecChecker-style operator result ---

/**
 * INC_DEC_SHOULD_NOT_RETURN_UNIT: `x++` / `--x` resolving to an `inc`/`dec` of a project class that returns
 * `Unit`, whose result would be assigned back to `x`. Judged only when every candidate is a member of that class
 * declared in source and no extension of the name competes.
 */
internal fun KotlinSemanticChecks.incDecReturningUnit(e: KtExpression, resolver: KotlinResolver): Diagnostic? {
    val (token, operand, opRef) = when (e) {
        is dev.ide.kotlin.syntax.psi.KtPostfixExpression -> Triple(e.operationToken, e.baseExpression, e.operationReference)
        is dev.ide.kotlin.syntax.psi.KtPrefixExpression -> Triple(e.operationToken, e.baseExpression, e.operationReference)
        else -> return null
    }
    val name = when (token) {
        KtTokens.PLUSPLUS -> "inc"
        KtTokens.MINUSMINUS -> "dec"
        else -> return null
    }
    operand ?: return null
    val t = resolver.inferType(operand) ?: return null
    if (t.isTypeParameter || t.nullable) return null
    val fqn = t.qualifiedName
    if (service.sourceClass(fqn) == null || !service.supertypeClosureKnown(fqn)) return null
    val members = service.ownAndInheritedMembers(fqn).filter { it.name == name }
    if (members.isEmpty() || members.any { it.kind != dev.ide.lang.resolve.SymbolKind.METHOD }) return null
    val candidates = members.filter { it.paramTypes.isEmpty() }
    if (candidates.isEmpty()) return null
    for (c in candidates) {
        if (!c.origin.fromSource) return null
        val fn = (c.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction ?: return null
        if (!fn.hasModifier(KtTokens.OPERATOR_KEYWORD) || fn.receiverTypeReference != null) return null
        val declared = fn.typeReference?.text?.filterNot(Char::isWhitespace)
        val returnsUnit = declared == "Unit" || declared == "kotlin.Unit" || (declared == null && fn.hasBlockBody())
        if (!returnsUnit) return null
    }
    if (service.extensionsFor(fqn, t.typeArguments, namePrefix = name, exactName = true).any { it.name == name }) return null
    return error(opRef, "Functions inc(), dec() shouldn't return Unit to be used by operators ++, --", KotlinDiagnosticCodes.INC_DEC_SHOULD_NOT_RETURN_UNIT)
}

// --- callable references to member extensions ---

/**
 * EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED: `Int::extInt` inside a class that declares `fun Int.extInt()` as a
 * member. A reference cannot bind the class's own instance as well as the receiver. Only when the name is a
 * member extension of an enclosing class and nothing else of that name is in view: no top-level declaration or
 * import of it, and no member of that name on the written receiver class.
 */
internal fun KotlinSemanticChecks.extensionInClassReference(ref: dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val receiver = ref.receiverExpression ?: return null
    val callee = ref.callableReference ?: return null
    val name = callee.getReferencedName()
    val receiverName = when (receiver) {
        is KtNameReferenceExpression -> receiver.getReferencedName()
        // `B<*>::extensionFun` parses as an argument-less call carrying the type arguments.
        is dev.ide.kotlin.syntax.psi.KtCallExpression ->
            (receiver.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.takeIf { receiver.valueArgumentList == null }
                ?: return null
        else -> return null
    }
    var declaringClass: KtClassOrObject? = null
    var n: KtElement? = ref.parent
    while (n != null && n !is KtFile) {
        if (n is KtClassOrObject) {
            val named = n.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
            if (named.isNotEmpty()) {
                // Member extension functions and properties alike (`fun B.extensionFun()`, `val B.extensionVal`).
                if (named.any { (it as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration)?.receiverTypeReference == null }) return null
                declaringClass = n
                break
            }
        }
        n = n.parent
    }
    declaringClass ?: return null
    val file = ref.containingKtFile
    if (file.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return null
    // The receiver must be a class this can see the members of.
    if (resolver.isTypeParameterInScope(receiverName, ref.textRange.startOffset)) return null
    val fqn = service.resolveTypeName(receiverName, resolver.fileContext, dev.ide.lang.kotlin.resolve.enclosingClassFqnOf(ref)) ?: return null
    if (!service.isKnownType(fqn) || !service.supertypeClosureKnown(fqn)) return null
    // A member of the receiver class wins over the enclosing class's member extension; the extension itself, when
    // the receiver is that class, is not such a member.
    if (service.ownAndInheritedMembers(fqn).any { it.name == name && it.receiverTypeFqn == null }) return null
    return error(callee, "'$name' is a member and an extension at the same time. References to such elements are not allowed", KotlinDiagnosticCodes.EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED)
}

// --- FirPropertyAccessorsVisibilityChecker-style accessor visibility ---

private fun visibilityKeyword(ml: dev.ide.kotlin.syntax.psi.KtModifierList?): KtElement? =
    listOf(KtTokens.PRIVATE_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.PUBLIC_KEYWORD)
        .firstNotNullOfOrNull { t -> ml?.getModifier(t) }

/**
 * GETTER_VISIBILITY_DIFFERS_FROM_PROPERTY_VISIBILITY: a getter written with a visibility other than its property's;
 * SETTER_VISIBILITY_INCONSISTENT_WITH_PROPERTY_VISIBILITY: a setter more visible than its property, or visible
 * to a different audience (`protected var` with an `internal set`). An `override` that leaves its own
 * visibility to what it overrides is not judged.
 */
internal fun accessorVisibility(prop: KtProperty): List<Diagnostic> {
    if (prop.accessors.isEmpty()) return emptyList()
    val propVis = visibilityKeyword(prop.modifierList)?.text.orEmpty()
    if (propVis.isEmpty() && prop.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return emptyList()
    if (prop.parent is dev.ide.kotlin.syntax.psi.KtBlockExpression) return emptyList()
    val property = propVis.ifEmpty { "public" }
    val out = ArrayList<Diagnostic>()
    for (acc in prop.accessors) {
        val kw = visibilityKeyword(acc.modifierList) ?: continue
        val vis = kw.text
        if (acc.isGetter) {
            if (vis != property) out += error(kw, "Getter visibility must be the same as property visibility", KotlinDiagnosticCodes.ACCESSOR_VISIBILITY)
        } else {
            val bad = when (property) {
                "public" -> false
                "private" -> vis != "private"
                "protected" -> vis == "internal" || vis == "public"
                "internal" -> vis == "protected" || vis == "public"
                else -> false
            }
            if (bad) out += error(kw, "Setter visibility must be the same as or less permissive than property visibility", KotlinDiagnosticCodes.ACCESSOR_VISIBILITY)
        }
    }
    return out
}

// --- local declarations ---

/** LOCAL_VARIABLE_WITH_TYPE_PARAMETERS: `val <T> a = 0` inside a function body. */
internal fun localVariableWithTypeParameters(prop: KtProperty): Diagnostic? {
    if (prop.parent !is dev.ide.kotlin.syntax.psi.KtBlockExpression) return null
    val list = prop.typeParameterList ?: return null
    return error(list, "Local variables cannot have type parameters", KotlinDiagnosticCodes.LOCAL_DECLARATION_SHAPE)
}

/** NOT_YET_SUPPORTED_LOCAL_INLINE_FUNCTION: an `inline` function declared in a block. */
internal fun localInlineFunction(fn: KtNamedFunction): Diagnostic? {
    if (fn.parent !is dev.ide.kotlin.syntax.psi.KtBlockExpression) return null
    val kw = fn.modifierList?.getModifier(KtTokens.INLINE_KEYWORD) ?: return null
    return error(kw, "Local inline functions are not yet supported", KotlinDiagnosticCodes.LOCAL_DECLARATION_SHAPE)
}

/** ILLEGAL_DECLARATION_IN_WHEN_SUBJECT: a `when` subject declared as anything but `val name = value`: a `var`, no
 *  initializer, a delegate, or a destructuring. */
internal fun illegalWhenSubject(w: dev.ide.kotlin.syntax.psi.KtWhenExpression): Diagnostic? {
    var c = w.firstChild
    while (c != null && c !is dev.ide.kotlin.syntax.psi.KtWhenEntry) {
        when (c) {
            is KtProperty -> {
                if (c.receiverTypeReference != null) return null
                val bad = c.isVar || c.initializer == null || c.hasDelegate()
                return if (bad) error(c, "Illegal variable declaration in 'when' subject", KotlinDiagnosticCodes.ILLEGAL_DECLARATION_IN_WHEN_SUBJECT) else null
            }
            is dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration ->
                return error(c, "Illegal variable declaration in 'when' subject", KotlinDiagnosticCodes.ILLEGAL_DECLARATION_IN_WHEN_SUBJECT)
            else -> {}
        }
        c = c.nextSibling
    }
    return null
}

// --- enum constructors ---

/** ENUM_CLASS_CONSTRUCTOR_CALL: `A(10)` constructing an enum class of this file (directly, through `Outer.A`, or
 *  through a typealias of this file), which only its entries may do. */
internal fun enumConstructorCall(call: KtCallExpression): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val file = call.containingKtFile
    // Cheap first: an enum or typealias of that name among the top-level declarations, the members of a
    // top-level class, or the members of a class around the call. Most calls stop here.
    fun candidate(d: KtDeclaration) = (d is KtClass && d.isEnum() || d is KtTypeAlias) && (d as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == name
    val plausible = file.declarations.any { d -> candidate(d) || (d is KtClassOrObject && d.declarations.any(::candidate)) } ||
        generateSequence(call.parent) { it.parent }.filterIsInstance<KtClassOrObject>().any { c -> c.declarations.any(::candidate) }
    if (!plausible) return null
    val classes = ArrayList<KtClassOrObject>()
    val others = ArrayList<KtElement>()
    fun visit(e: KtElement) {
        if (e is dev.ide.kotlin.syntax.psi.KtNamedDeclaration && e.name == name) {
            if (e is KtClassOrObject && e !is KtEnumEntry) classes += e else if (e !is dev.ide.kotlin.syntax.psi.KtTypeParameter) others += e
        }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return null
    val q = call.parent as? KtQualifiedExpression
    if (q != null && q.selectorExpression === call) {
        // `Outer.A()`: the receiver must name the class that declares the enum.
        val outer = (q.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val enum = classes.singleOrNull() as? KtClass ?: return null
        if (!enum.isEnum() || others.isNotEmpty()) return null
        val holder = (enum.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
        if (holder.name != outer) return null
        return error(call, "Enum types cannot be instantiated", KotlinDiagnosticCodes.ENUM_CLASS_CONSTRUCTOR_CALL)
    }
    val target: KtClass = when {
        classes.size == 1 && others.isEmpty() -> classes.single() as? KtClass ?: return null
        classes.isEmpty() && others.size == 1 && others.single() is KtTypeAlias -> {
            val rhs = (others.single() as KtTypeAlias).typeReference?.typeElement as? KtUserType ?: return null
            if (rhs.qualifier != null) return null
            val aliased = rhs.referencedName ?: return null
            val found = ArrayList<KtClassOrObject>()
            fun find(e: KtElement) {
                if (e is KtClassOrObject && e !is KtEnumEntry && e.name == aliased) found += e
                var c = e.firstChild
                while (c != null) { find(c); c = c.nextSibling }
            }
            find(file)
            found.singleOrNull() as? KtClass ?: return null
        }
        else -> return null
    }
    if (!target.isEnum()) return null
    // `E(1)` may be its companion's `invoke`.
    if (target.declarations.any { it is KtObjectDeclaration && it.isCompanion() }) return null
    if (declaresFunctionNamed(file, "invoke")) return null
    // A nested enum is reached bare only from inside its holder; elsewhere the name means something else.
    if (target.parent !is KtFile) {
        val holder = (target.parent as? KtClassBody)?.parent ?: return null
        if (generateSequence(call.parent) { it.parent }.none { it === holder }) return null
    }
    return error(call, "Enum types cannot be instantiated", KotlinDiagnosticCodes.ENUM_CLASS_CONSTRUCTOR_CALL)
}

// --- FirInlineDeclarationChecker.checkRecursion ---

/** RECURSION_IN_INLINE: an inline function calling itself, whose body would be inlined into itself forever.
 *  Only a call the resolver binds to the very declaration that encloses it. */
internal fun recursionInInline(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    var fn: KtElement? = call.parent
    while (fn != null && !(fn is KtNamedFunction && fn.name != null && (fn.parent is KtFile || fn.parent is KtClassBody))) {
        if (fn is KtClassOrObject) return null
        fn = fn.parent
    }
    val host = fn as? KtNamedFunction ?: return null
    if (host.name != name || !host.hasModifier(KtTokens.INLINE_KEYWORD)) return null
    val target = runCatching { resolver.calleeFunctionOf(call) }.getOrNull() ?: return null
    val psi = (target.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi ?: return null
    if (psi !== host) return null
    return error(callee, "Inline function '$name' cannot be recursive", KotlinDiagnosticCodes.RECURSION_IN_INLINE)
}

// --- FirInlineClassDeclarationChecker (recursion) ---

/** VALUE_CLASS_CANNOT_BE_RECURSIVE: a value class whose underlying property's type (nullable or not) is a value
 *  class of this file that leads back to it, so it would have to contain itself. Reported on that type. */
internal fun valueClassRecursive(cls: KtClassOrObject): Diagnostic? {
    if (cls !is KtClass || !isValueClass(cls)) return null
    val file = cls.containingKtFile
    val byName = HashMap<String, KtClass>()
    val dup = HashSet<String>()
    fun collect(e: KtElement) {
        if (e is KtClassOrObject && e !is KtEnumEntry) e.name?.let { if (byName.put(it, e as? KtClass ?: return@let) != null) dup += it }
        if (e is KtTypeAlias) e.name?.let { dup += it }
        var c = e.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(file)
    fun underlying(c: KtClass): Pair<dev.ide.kotlin.syntax.psi.KtTypeReference, KtClass?>? {
        val ref = c.primaryConstructorParameters.singleOrNull()?.typeReference ?: return null
        var el = ref.typeElement
        while (el is KtNullableType) el = el.innerType
        val user = el as? KtUserType ?: return ref to null
        if (user.qualifier != null || user.typeArgumentList != null) return ref to null
        val n = user.referencedName ?: return ref to null
        if (n in dup || c.typeParameters.any { it.name == n }) return ref to null
        val next = byName[n]?.takeIf { isValueClass(it) }
        return ref to next
    }
    val (ref, first) = underlying(cls) ?: return null
    var current = first
    val seen = HashSet<KtClass>()
    while (current != null && seen.add(current)) {
        if (current === cls) return error(ref, "Value class cannot be recursive", KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_BE_RECURSIVE)
        current = underlying(current)?.second
    }
    return null
}

private fun isValueClass(c: KtClass): Boolean = c.hasModifier(KtTokens.VALUE_KEYWORD) || c.hasModifier(KtTokens.INLINE_KEYWORD)

// --- FirAnnotationClassDeclarationChecker (default values) ---

/**
 * ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT: an annotation class parameter whose default value reads a
 * non-`const` property or calls a function of this file (`= iVal`, `= 1 + iFun()`, `= arrayOf(*x)`). Only names
 * this file declares at the top level or in a named object are judged; anything else may be a constant.
 */
internal fun annotationDefaultNotConstant(p: dev.ide.kotlin.syntax.psi.KtParameter): Diagnostic? {
    val ctor = p.parent?.parent as? dev.ide.kotlin.syntax.psi.KtPrimaryConstructor ?: return null
    val cls = ctor.parent as? KtClass ?: return null
    if (!cls.isAnnotation()) return null
    val default = p.defaultValue ?: return null
    val file = p.containingKtFile
    val topLevel = file.declarations
    var bad = false
    fun nonConstProperty(d: KtElement?): Boolean = d is KtProperty && !d.hasModifier(KtTokens.CONST_KEYWORD)
    fun visit(e: KtElement) {
        if (bad) return
        when (e) {
            is dev.ide.kotlin.syntax.psi.KtLambdaExpression, is KtObjectDeclaration -> return
            is KtNameReferenceExpression -> {
                val name = e.getReferencedName()
                val parent = e.parent
                if (parent is KtUserType || parent is dev.ide.kotlin.syntax.psi.KtValueArgumentName) return
                if (parent is KtCallExpression && parent.calleeExpression === e) {
                    val fns = topLevel.filter { it is KtNamedFunction && it.name == name }
                    if (parent.parent !is KtQualifiedExpression && fns.isNotEmpty() &&
                        topLevel.none { it !is KtNamedFunction && (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
                    ) bad = true
                    return
                }
                if (parent is KtQualifiedExpression && parent.selectorExpression === e) {
                    val owner = (parent.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
                    val obj = topLevel.filterIsInstance<KtObjectDeclaration>().singleOrNull { it.name == owner } ?: return
                    if (topLevel.count { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == owner } != 1) return
                    val member = obj.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
                    if (member.size == 1 && nonConstProperty(member.single())) bad = true
                    return
                }
                if (parent is KtQualifiedExpression) return
                if (cls.primaryConstructorParameters.any { it.name == name }) return
                val decls = topLevel.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
                if (decls.size == 1 && nonConstProperty(decls.single()) && (decls.single() as KtProperty).receiverTypeReference == null) bad = true
                return
            }
            else -> {}
        }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(default)
    if (!bad) return null
    if (file.importDirectives.any { it.isAllUnder }) return null
    return error(default, "Default value of annotation parameter must be a compile-time constant", KotlinDiagnosticCodes.ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT)
}

// --- anonymous functions ---

/** ANONYMOUS_SUSPEND_FUNCTION: `suspend fun() {}`, an anonymous function cannot be declared `suspend`. */
internal fun anonymousSuspendFunction(fn: KtNamedFunction): Diagnostic? {
    if (fn.name != null) return null
    val kw = fn.modifierList?.getModifier(KtTokens.SUSPEND_KEYWORD) ?: return null
    return error(kw, "Anonymous suspend functions are prohibited", KotlinDiagnosticCodes.ANONYMOUS_SUSPEND_FUNCTION)
}

// --- FirInlineDeclarationChecker (parameter modifiers) ---

/**
 * ILLEGAL_INLINE_PARAMETER_MODIFIER: `noinline` / `crossinline` on a parameter of a function that is not `inline`,
 * or on one whose type is not a function type (`noinline x: Int`, a class implementing a function type). A
 * typealias or a type parameter as the type is given the benefit of the doubt.
 */
internal fun illegalInlineParameterModifier(p: dev.ide.kotlin.syntax.psi.KtParameter): List<Diagnostic> {
    val ml = p.modifierList ?: return emptyList()
    val mods = listOfNotNull(ml.getModifier(KtTokens.NOINLINE_KEYWORD), ml.getModifier(KtTokens.CROSSINLINE_KEYWORD))
    if (mods.isEmpty() || mods.size == 2) return emptyList() // both together is INCOMPATIBLE_MODIFIERS
    val fn = p.parent?.parent as? KtNamedFunction ?: return emptyList()
    if (fn.hasModifier(KtTokens.EXPECT_KEYWORD) || fn.hasModifier(KtTokens.ACTUAL_KEYWORD) || fn.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return emptyList()
    val illegal = if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) true else {
        var el = p.typeReference?.typeElement
        while (el is KtNullableType) el = el.innerType
        when (el) {
            is dev.ide.kotlin.syntax.psi.KtFunctionType -> false
            is KtUserType -> {
                val name = el.referencedName
                when {
                    name == null || el.qualifier != null -> false
                    FUNCTION_TYPE_NAME.matches(name) -> false
                    fn.typeParameters.any { it.name == name } -> false
                    p.containingKtFile.declarations.any { it is KtTypeAlias && it.name == name } -> false
                    // A class of this file, or a builtin value type nothing here redeclares, is no function type.
                    fileDeclaresClass(p.containingKtFile, name) -> true
                    name in PLAIN_VALUE_TYPE_NAMES && !declaresClassifier(p.containingKtFile, name) -> true
                    else -> false
                }
            }
            else -> false
        }
    }
    if (!illegal) return emptyList()
    return mods.map { error(it, "Modifier '${it.text}' is allowed only for function parameters of an inline function", KotlinDiagnosticCodes.ILLEGAL_INLINE_PARAMETER_MODIFIER) }
}

private val PLAIN_VALUE_TYPE_NAMES = setOf("String", "Int", "Long", "Short", "Byte", "Double", "Float", "Char", "Boolean", "Any", "Unit", "Number")

private val FUNCTION_TYPE_NAME = Regex("""(Suspend)?Function\d+|KFunction\d*|KSuspendFunction\d+""")

// --- FirAnnotationChecker (property use-site targets) ---

/**
 * The property use-site targets put where they cannot apply: `@get:`/`@set:`/`@setparam:`/`@property:` on
 * anything but a member or top-level property (INAPPLICABLE_TARGET_ON_PROPERTY: an accessor, a function, a class,
 * a local variable, a plain parameter), `@set:`/`@setparam:` on a `val` (INAPPLICABLE_TARGET_PROPERTY_IMMUTABLE),
 * and `@delegate:` on a property with no delegate (INAPPLICABLE_TARGET_PROPERTY_HAS_NO_DELEGATE).
 */
internal fun propertyUseSiteTarget(e: KtElement): Diagnostic? {
    if (e !is dev.ide.kotlin.syntax.psi.KtAnnotationEntry && e !is dev.ide.kotlin.syntax.psi.KtAnnotation) return null
    if (e is dev.ide.kotlin.syntax.psi.KtAnnotationEntry && e.parent is dev.ide.kotlin.syntax.psi.KtAnnotation) return null
    var target: String? = null
    var c = e.firstChild
    while (c != null) {
        if (c is dev.ide.kotlin.syntax.psi.KtAnnotationUseSiteTarget) { target = c.text.trim(); break }
        if (c is dev.ide.kotlin.syntax.psi.KtAnnotationEntry) break
        c = c.nextSibling
    }
    target ?: return null
    if (target !in PROPERTY_TARGETS) return null
    val ml = e.parent as? dev.ide.kotlin.syntax.psi.KtModifierList ?: return null
    val owner = ml.parent
    val ctorProperty = owner is dev.ide.kotlin.syntax.psi.KtParameter && owner.valOrVarKeyword != null &&
        owner.parent?.parent is dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
    val memberProperty = owner is KtProperty && owner.parent !is dev.ide.kotlin.syntax.psi.KtBlockExpression
    val isProperty = ctorProperty || memberProperty
    if (!isProperty) {
        if (target == "delegate" || target == "field") return null // WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET's business
        val known = owner is KtNamedFunction || owner is KtClassOrObject || owner is KtPropertyAccessor ||
            (owner is KtProperty) || (owner is dev.ide.kotlin.syntax.psi.KtParameter && owner.parent?.parent !is KtPropertyAccessor) ||
            owner is dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
        if (!known || owner is KtEnumEntry) return null
        return error(e, "'@$target:' annotations could be applied only to property declarations", KotlinDiagnosticCodes.PROPERTY_USE_SITE_TARGET)
    }
    when (target) {
        "set", "setparam" -> {
            val isVar = if (owner is KtProperty) owner.isVar else (owner as dev.ide.kotlin.syntax.psi.KtParameter).valOrVarKeyword?.text == "var"
            val lateinit = (owner as? KtProperty)?.hasModifier(KtTokens.LATEINIT_KEYWORD) == true
            if (!isVar && !lateinit) {
                return error(e, "'@$target:' annotations could be applied only to mutable properties", KotlinDiagnosticCodes.PROPERTY_USE_SITE_TARGET)
            }
        }
        "delegate" -> {
            if (owner is dev.ide.kotlin.syntax.psi.KtParameter || (owner as KtProperty).hasDelegate().not()) {
                return error(e, "'@delegate:' annotations could be applied only to delegated properties", KotlinDiagnosticCodes.PROPERTY_USE_SITE_TARGET)
            }
        }
    }
    return null
}

private val PROPERTY_TARGETS = setOf("get", "set", "setparam", "property", "delegate", "field")

// --- assignment operators ---

/**
 * ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT: `c += x` resolving to a `plusAssign` (or another `xAssign`) of a project
 * class declared to return something other than `Unit`. Judged only when the class offers no `plus` the compiler
 * could pick instead, every candidate is its own member declared in source, and no extension competes.
 */
internal fun KotlinSemanticChecks.assignmentOperatorReturningValue(e: dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val (assignName, plainName) = when (e.operationToken) {
        KtTokens.PLUSEQ -> "plusAssign" to "plus"
        KtTokens.MINUSEQ -> "minusAssign" to "minus"
        KtTokens.MULTEQ -> "timesAssign" to "times"
        KtTokens.DIVEQ -> "divAssign" to "div"
        KtTokens.PERCEQ -> "remAssign" to "rem"
        else -> return null
    }
    val left = e.left ?: return null
    e.right ?: return null
    val t = resolver.inferType(left) ?: return null
    if (t.isTypeParameter || t.nullable) return null
    val fqn = t.qualifiedName
    if (service.sourceClass(fqn) == null || !service.supertypeClosureKnown(fqn)) return null
    val members = service.ownAndInheritedMembers(fqn)
    if (members.any { it.name == plainName }) return null
    val candidates = members.filter { it.name == assignName }
    if (candidates.isEmpty() || candidates.any { it.kind != dev.ide.lang.resolve.SymbolKind.METHOD || it.paramTypes.size != 1 }) return null
    for (c in candidates) {
        if (!c.origin.fromSource) return null
        val fn = (c.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction ?: return null
        if (fn.receiverTypeReference != null || !fn.hasModifier(KtTokens.OPERATOR_KEYWORD)) return null
        val declared = fn.typeReference?.text?.filterNot(Char::isWhitespace) ?: return null
        if (declared == "Unit" || declared == "kotlin.Unit") return null
    }
    for (n in listOf(assignName, plainName)) {
        if (service.extensionsFor(fqn, t.typeArguments, namePrefix = n, exactName = true).any { it.name == n }) return null
    }
    return error(e.operationReference, "Function '$assignName' should return Unit to be used by corresponding operator", KotlinDiagnosticCodes.ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT)
}

// --- FirPropertyAccessorsTypesChecker (setter parameter) ---

/** WRONG_SETTER_PARAMETER_TYPE: a setter parameter written with a type other than its property's
 *  (`var x: Int … set(value: Long)`). Both types must be written and resolve; an annotation on either is ignored. */
internal fun KotlinSemanticChecks.wrongSetterParameterType(prop: KtProperty, resolver: KotlinResolver): Diagnostic? {
    val propType = prop.typeReference ?: return null
    val setter = prop.accessors.firstOrNull { !it.isGetter } ?: return null
    val param = setter.valueParameters.singleOrNull() ?: return null
    val paramType = param.typeReference ?: return null
    val a = propType.typeElement?.text?.filterNot(Char::isWhitespace) ?: return null
    val b = paramType.typeElement?.text?.filterNot(Char::isWhitespace) ?: return null
    if (a == b) return null
    val ctx = dev.ide.lang.kotlin.resolve.enclosingClassFqnOf(prop)
    val ta = service.typeFromText(propType.typeElement!!.text, resolver.fileContext, ctx) ?: return null
    val tb = service.typeFromText(paramType.typeElement!!.text, resolver.fileContext, ctx) ?: return null
    if (ta.isTypeParameter || tb.isTypeParameter) return null
    if (!service.isKnownType(ta.qualifiedName) || !service.isKnownType(tb.qualifiedName)) return null
    if (renderType(ta) == renderType(tb)) return null
    return error(paramType, "Setter parameter type must be equal to the type of the property, i.e. '${renderType(ta)}'", KotlinDiagnosticCodes.WRONG_SETTER_PARAMETER_TYPE)
}

// --- FirEnumEntriesChecker-style abstract members ---

/**
 * ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY: an entry of an enum class that has abstract members (declared in
 * it, or left abstract from an interface of this file it implements) without a body overriding each of them.
 */
internal fun abstractMemberNotImplementedByEnumEntry(entry: KtEnumEntry): Diagnostic? {
    val enum = (entry.parent as? KtClassBody)?.parent as? KtClass ?: return null
    if (!enum.isEnum()) return null
    val file = entry.containingKtFile
    val abstract = LinkedHashSet<String>()
    val concrete = HashSet<String>()
    fun key(d: KtDeclaration): String? = when (d) {
        is KtNamedFunction -> d.name?.let { "f:$it/${d.valueParameters.size}" }
        is KtProperty -> d.name?.let { "p:$it" }
        else -> null
    }
    for (d in enum.declarations) {
        if (d is KtEnumEntry) continue
        val k = key(d) ?: continue
        if (d.hasModifier(KtTokens.ABSTRACT_KEYWORD)) abstract += k else concrete += k
    }
    for (entryRef in enum.superTypeListEntries) {
        val type = entryRef.typeReference?.typeElement as? KtUserType ?: return null
        val name = type.referencedName ?: return null
        val iface = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name }
        if (iface == null || !iface.isInterface() || iface.superTypeListEntries.isNotEmpty()) return null
        for (d in iface.declarations) {
            val k = key(d) ?: continue
            val isAbstract = when (d) {
                is KtNamedFunction -> d.bodyExpression == null
                is KtProperty -> d.initializer == null && d.getter == null && !d.hasDelegate()
                else -> false
            }
            if (isAbstract && k !in concrete) abstract += k
        }
    }
    if (abstract.isEmpty()) return null
    val implemented = entry.declarations.mapNotNull { if (it.hasModifier(KtTokens.OVERRIDE_KEYWORD)) key(it) else null }.toSet()
    if (abstract.all { it in implemented }) return null
    val at = entry.nameIdentifier ?: return null
    return error(at, "Enum entry '${entry.name}' does not implement all abstract members of its enum class", KotlinDiagnosticCodes.ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY)
}

// --- small syntactic rules ---

/** UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS: `::class` with nothing on its left. */
internal fun emptyLhsClassLiteral(e: dev.ide.kotlin.syntax.psi.KtClassLiteralExpression): Diagnostic? {
    if (e.receiverExpression != null) return null
    return error(e, "Class literals with empty left hand side are not yet supported", KotlinDiagnosticCodes.UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS)
}

/** TYPEALIAS_SHOULD_EXPAND_TO_CLASS: a typealias whose right side is a type parameter (`typealias X<T> = T`,
 *  `= T?`) or `dynamic`, which names no class to expand to. */
internal fun typeAliasExpandsToNoClass(alias: KtTypeAlias): Diagnostic? {
    val ref = alias.typeReference ?: return null
    var el = ref.typeElement
    while (el is KtNullableType) el = el.innerType
    val bad = when (el) {
        is dev.ide.kotlin.syntax.psi.KtDynamicType -> true
        is KtUserType -> {
            val name = el.referencedName
            el.qualifier == null && el.typeArgumentList == null && name != null &&
                (alias.typeParameters.any { it.name == name } || typeParameterInScope(alias, name) != null)
        }
        else -> false
    }
    return if (bad) error(ref, "Type alias expands to a type that is not a class", KotlinDiagnosticCodes.TYPEALIAS_SHOULD_EXPAND_TO_CLASS) else null
}

/** BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED: `typealias A<T : Number> = …`, a typealias parameter takes no bound. */
internal fun boundOnTypeAliasParameter(alias: KtTypeAlias): List<Diagnostic> =
    alias.typeParameters.mapNotNull { tp ->
        tp.extendsBound?.let { error(it, "Bounds are not allowed on type alias parameters", KotlinDiagnosticCodes.BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED) }
    }

/** NON_MEMBER_FUNCTION_NO_BODY for an anonymous function written without a body (`val f = fun ()`). */
internal fun anonymousFunctionWithoutBody(fn: KtNamedFunction): Diagnostic? {
    if (fn.name != null || fn.bodyExpression != null) return null
    if (fn.parent is KtClassBody || fn.parent is KtFile) return null
    return error(fn, "Function 'anonymous' must have a body", KotlinDiagnosticCodes.NON_MEMBER_FUNCTION_NO_BODY)
}

/** ACCESSOR_FOR_DELEGATED_PROPERTY: a delegated property with a getter or setter body of its own, which the
 *  delegate's `getValue`/`setValue` already are. */
internal fun accessorForDelegatedProperty(prop: KtProperty): List<Diagnostic> {
    if (!prop.hasDelegate()) return emptyList()
    return prop.accessors.filter { it.bodyExpression != null }.map {
        error(it, "Delegated property cannot have accessors with non-default implementations", KotlinDiagnosticCodes.ACCESSOR_FOR_DELEGATED_PROPERTY)
    }
}

/** NULLABLE_INLINE_PARAMETER: an inline function's parameter of a nullable function type that is not `noinline`
 *  (`p: (() -> Unit)?`, `Function1<Int, Int>?`, or a typealias of this file to one). */
internal fun nullableInlineParameter(fn: KtNamedFunction): List<Diagnostic> {
    if (!fn.hasModifier(KtTokens.INLINE_KEYWORD) || fn.name == null) return emptyList()
    val file = fn.containingKtFile
    fun nullableFunctionType(el: KtTypeElement?, depth: Int): Boolean {
        if (depth > 3) return false
        if (el is KtNullableType) {
            // `(() -> Unit)?`: the parentheses come before the type among the children.
            fun innerOf(n: KtNullableType): KtTypeElement? = n.innerType ?: n.children.firstOrNull { it is KtTypeElement } as? KtTypeElement
            var inner = innerOf(el)
            while (inner is KtNullableType) inner = innerOf(inner)
            return when (inner) {
                is dev.ide.kotlin.syntax.psi.KtFunctionType -> true
                is KtUserType -> inner.qualifier == null && inner.referencedName?.let { FUNCTION_N_NAME.matches(it) } == true &&
                    !declaresClassifier(file, inner.referencedName!!)
                else -> false
            }
        }
        val user = el as? KtUserType ?: return false
        if (user.qualifier != null || user.typeArgumentList != null) return false
        val alias = file.declarations.filterIsInstance<KtTypeAlias>().singleOrNull { it.name == user.referencedName } ?: return false
        if (alias.typeParameters.isNotEmpty()) return false
        return nullableFunctionType(alias.typeReference?.typeElement, depth + 1)
    }
    return fn.valueParameters.filter { p ->
        !p.hasModifier(KtTokens.NOINLINE_KEYWORD) && !p.isVarArg && nullableFunctionType(p.typeReference?.typeElement, 0)
    }.map { error(it, "Inline parameter '${it.name}' must not be nullable. Add 'noinline' modifier to the parameter declaration or make its type non-nullable.", KotlinDiagnosticCodes.NULLABLE_INLINE_PARAMETER) }
}

private val FUNCTION_N_NAME = Regex("""Function\d+""")

/** TYPE_ARGUMENTS_NOT_ALLOWED: type arguments on a type parameter (`T<Int>`), which takes none. */
internal fun typeArgumentsOnTypeParameter(type: KtUserType): Diagnostic? {
    if (type.qualifier != null || type.parent is KtUserType) return null
    val args = type.typeArgumentList ?: return null
    val name = type.referencedName ?: return null
    typeParameterInScope(type, name) ?: return null
    if (declaresClassifier(type.containingKtFile, name)) return null
    return error(args, "Type arguments are not allowed for type parameters", KotlinDiagnosticCodes.TYPE_ARGUMENTS_NOT_ALLOWED)
}

/** UNDERSCORE_IS_RESERVED: `_` (or `__`) as a label, or as a parameter name of a named function, where an
 *  underscore-only name is reserved. */
internal fun underscoreIsReserved(e: KtElement): Diagnostic? {
    val at: KtElement = when (e) {
        is dev.ide.kotlin.syntax.psi.KtLabeledExpression -> {
            val label = e.getLabelName() ?: return null
            if (label.isEmpty() || label.any { it != '_' }) return null
            e.labelQualifier ?: return null
        }
        is dev.ide.kotlin.syntax.psi.KtParameter -> {
            val name = e.nameIdentifier ?: return null
            if (name.text.isEmpty() || name.text.any { it != '_' }) return null
            val fn = e.parent?.parent as? KtNamedFunction ?: return null
            if (fn.name == null || e.parent !is dev.ide.kotlin.syntax.psi.KtParameterList) return null
            name
        }
        else -> return null
    }
    return error(at, "Names _, __, ___, ..., are reserved in Kotlin", KotlinDiagnosticCodes.UNDERSCORE_IS_RESERVED)
}

/** WRONG_EXTENSION_FUNCTION_TYPE: `@ExtensionFunctionType` on a type that has no parameter to become the receiver
 *  (`() -> Int`, `Function0<Int>`) or is no function type at all (`Int`). */
internal fun wrongExtensionFunctionType(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
    if (entry.shortName?.asString() != "ExtensionFunctionType") return null
    if (fileDeclaresClass(entry.containingKtFile, "ExtensionFunctionType")) return null
    val ml = entry.parent as? dev.ide.kotlin.syntax.psi.KtModifierList ?: return null
    val ref = ml.parent as? dev.ide.kotlin.syntax.psi.KtTypeReference ?: return null
    val bad = when (val el = ref.typeElement) {
        is dev.ide.kotlin.syntax.psi.KtFunctionType -> el.receiverTypeReference == null && el.parameters.isEmpty() && el.text.trimStart().startsWith("(")
        is KtUserType -> {
            val name = el.referencedName ?: return null
            if (el.qualifier != null || declaresClassifier(entry.containingKtFile, name)) return null
            name == "Function0" || name in PLAIN_VALUE_TYPE_NAMES
        }
        else -> false
    }
    return if (bad) error(entry, "ExtensionFunctionType is only applicable to function types with at least one parameter", KotlinDiagnosticCodes.WRONG_EXTENSION_FUNCTION_TYPE) else null
}

/** VAR_OVERRIDDEN_BY_VAL: `override val p` where a direct supertype of this file declares `var p`. */
internal fun varOverriddenByVal(prop: KtProperty): Diagnostic? {
    if (prop.isVar || !prop.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    val cls = (prop.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
    return varOverriddenByValIn(cls, prop.name ?: return null, prop.receiverTypeReference != null, prop.valOrVarKeyword)
}

/** The same for a primary-constructor `override val p`. */
internal fun varOverriddenByValParameter(p: dev.ide.kotlin.syntax.psi.KtParameter): Diagnostic? {
    val kw = p.valOrVarKeyword ?: return null
    if (kw.text != "val" || !p.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    val cls = (p.parent?.parent as? dev.ide.kotlin.syntax.psi.KtPrimaryConstructor)?.parent as? KtClassOrObject ?: return null
    return varOverriddenByValIn(cls, p.name ?: return null, false, kw)
}

private fun varOverriddenByValIn(cls: KtClassOrObject, name: String, extension: Boolean, at: KtElement): Diagnostic? {
    if (extension) return null
    val file = cls.containingKtFile
    for (entry in cls.superTypeListEntries) {
        val type = entry.typeReference?.typeElement as? KtUserType ?: continue
        if (type.qualifier != null) continue
        val sup = file.declarations.filterIsInstance<KtClassOrObject>().filter { it.name == type.referencedName }.singleOrNull() ?: continue
        val members = sup.declarations.filterIsInstance<KtProperty>().filter { it.name == name && it.receiverTypeReference == null } +
            ((sup as? KtClass)?.primaryConstructorParameters.orEmpty().filter { it.name == name && it.valOrVarKeyword != null })
        if (members.any { (it is KtProperty && it.isVar) || (it is dev.ide.kotlin.syntax.psi.KtParameter && it.valOrVarKeyword?.text == "var") }) {
            return error(at, "Var-property cannot be overridden by val-property", KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL)
        }
    }
    return null
}

// --- FirAnnotationExpressionChecker (version strings) ---

/** ILLEGAL_KOTLIN_VERSION_STRING_VALUE: `@SinceKotlin("1")` / `@RequireKotlin("1.0-beta")`, a version that is not
 *  `major.minor` or `major.minor.patch` written without leading zeros. */
internal fun illegalKotlinVersionString(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
    val short = entry.shortName?.asString() ?: return null
    if (short != "SinceKotlin" && short != "RequireKotlin") return null
    if (fileDeclaresClass(entry.containingKtFile, short)) return null
    val arg = entry.valueArguments.firstOrNull { it.getArgumentName()?.text?.trim().let { n -> n == null || n == "version" } } ?: return null
    if (arg.getArgumentName() == null && entry.valueArguments.indexOf(arg) != 0) return null
    val str = arg.getArgumentExpression() as? dev.ide.kotlin.syntax.psi.KtStringTemplateExpression ?: return null
    if (str.entries.any { it !is dev.ide.kotlin.syntax.psi.KtLiteralStringTemplateEntry }) return null
    val value = str.entries.joinToString("") { it.text }
    if (VERSION_REGEX.matches(value)) return null
    return error(str, "Invalid version: it should consist of up to three non-negative integers separated by dots", KotlinDiagnosticCodes.ILLEGAL_KOTLIN_VERSION_STRING_VALUE)
}

private val VERSION_REGEX = "(0|[1-9][0-9]*)".let { n -> Regex("$n\\.$n(\\.$n)?") }

// --- FirJvmExposeBoxedChecker (visibility) ---

/**
 * JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE: `@JvmExposeBoxed` on something no other class can call: a private
 * function, class, constructor or accessor (`@get:`/`@set:` reach the accessor), or anything inside a private class.
 */
internal fun jvmExposeBoxedPrivate(entry: dev.ide.kotlin.syntax.psi.KtAnnotationEntry): Diagnostic? {
    if (entry.shortName?.asString() != "JvmExposeBoxed") return null
    if (fileDeclaresClass(entry.containingKtFile, "JvmExposeBoxed")) return null
    val ml = entry.parent as? dev.ide.kotlin.syntax.psi.KtModifierList ?: return null
    val owner = ml.parent as? KtDeclaration ?: return null
    val target = entry.useSiteTarget?.trim()
    fun private(d: dev.ide.kotlin.syntax.psi.KtModifierListOwner?) = d?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true
    val itself = when (owner) {
        is KtProperty -> when (target) {
            "get" -> private(owner) || private(owner.getter)
            "set" -> private(owner) || private(owner.accessors.firstOrNull { !it.isGetter })
            else -> return null
        }
        is KtPropertyAccessor -> private(owner) || private(owner.property)
        is KtNamedFunction, is KtClassOrObject, is dev.ide.kotlin.syntax.psi.KtPrimaryConstructor,
        is dev.ide.kotlin.syntax.psi.KtSecondaryConstructor -> private(owner)
        else -> return null
    }
    // An enclosing private class hides everything in it.
    val inPrivateClass = generateSequence(owner.parent) { it.parent }.any { it is KtClassOrObject && private(it) }
    if (!itself && !inPrivateClass) return null
    if (generateSequence(owner.parent) { it.parent }.any { it is dev.ide.kotlin.syntax.psi.KtBlockExpression }) return null
    return error(entry, "'@JvmExposeBoxed' cannot be applied to private declarations", KotlinDiagnosticCodes.JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE)
}

// --- supertype calls on function types ---

/** NO_CONSTRUCTOR: a supertype entry that calls a function type (`() -> Int()`, `Function0<Int>()`,
 *  `SuspendFunction0<Int>()`): those are interfaces, with no constructor to call. Reported on the argument list. */
internal fun functionTypeSupertypeCall(entry: dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry): Diagnostic? {
    val args = entry.valueArgumentList ?: return null
    val ref = entry.typeReference ?: return null
    val el: Any? = ref.typeElement ?: ref.children.firstOrNull { it is KtTypeElement }
    val isFunction = when (el) {
        is dev.ide.kotlin.syntax.psi.KtFunctionType -> true
        is KtUserType -> {
            val name = el.referencedName ?: return null
            val q = el.qualifier?.text?.filterNot(Char::isWhitespace)
            SUPERTYPE_FUNCTION_NAME.matches(name) && (q == null || q == "kotlin" || q == "kotlin.coroutines") &&
                (q != null || !declaresClassifier(entry.containingKtFile, name))
        }
        else -> ref.text.contains("->") && ref.text.trim().let { it.startsWith("(") || it.startsWith("suspend") }
    }
    if (!isFunction) return null
    return error(args, "This class does not have a constructor", KotlinDiagnosticCodes.NO_CONSTRUCTOR)
}

private val SUPERTYPE_FUNCTION_NAME = Regex("""(Suspend)?Function\d+""")

// --- delegates without their operators ---

/**
 * DELEGATE_SPECIAL_FUNCTION_MISSING: `val a by A()` where `A` is a class of this file with no `getValue` (and, for a
 * `var`, no `setValue`) of its own, and nothing else could supply one: it has no supertypes and no
 * `provideDelegate`, and the file declares no extension of those names and star-imports nothing. Reported on `by`.
 */
internal fun delegateSpecialFunctionMissing(prop: KtProperty): List<Diagnostic> {
    val delegate = prop.delegate ?: return emptyList()
    val call = delegate.expression as? KtCallExpression ?: return emptyList()
    if (call.typeArgumentList != null) return emptyList()
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    val file = prop.containingKtFile
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return emptyList()
    if (file.declarations.none { it is KtClass && it.name == name }) return emptyList()
    val named = ArrayList<KtElement>()
    var operatorExtensions = false
    fun visit(e: KtElement) {
        if (e is dev.ide.kotlin.syntax.psi.KtNamedDeclaration && e.name == name && e !is dev.ide.kotlin.syntax.psi.KtTypeParameter) named += e
        if (e is KtNamedFunction && e.receiverTypeReference != null && e.name in DELEGATE_OPERATOR_NAMES) operatorExtensions = true
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    if (operatorExtensions) return emptyList()
    val cls = named.singleOrNull() as? KtClass ?: return emptyList()
    if (cls.isInterface() || cls.isEnum() || cls.superTypeListEntries.isNotEmpty() || cls is KtEnumEntry) return emptyList()
    val members = cls.declarations.mapNotNull { (it as? KtNamedFunction)?.name }.toSet()
    if ("provideDelegate" in members) return emptyList()
    val by = generateSequence(delegate.firstChild) { it.nextSibling }.firstOrNull { it.elementType == KtTokens.BY_KEYWORD } ?: delegate
    val out = ArrayList<Diagnostic>()
    if ("getValue" !in members) out += error(by, "Missing 'getValue' method on delegate of type '$name'", KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_MISSING)
    if (prop.isVar && "setValue" !in members) out += error(by, "Missing 'setValue' method on delegate of type '$name'", KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_MISSING)
    return out
}

private val DELEGATE_OPERATOR_NAMES = setOf("getValue", "setValue", "provideDelegate")

/**
 * DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE: `val b: Int by Delegate()` where the delegate class (one of this file,
 * as for [delegateSpecialFunctionMissing]) declares `getValue` (or, for a `var`, `setValue`) but no declaration of
 * it can take the call the property makes: a wrong parameter count, a `thisRef` parameter its owner is not (a
 * top-level or local property passes `null`), or a `setValue` value parameter the property's type does not fit.
 * Judged only where every parameter type is known; a generic function or class is left to the compiler.
 */
internal fun KotlinSemanticChecks.delegateSpecialFunctionNoneApplicable(prop: KtProperty, resolver: KotlinResolver): List<Diagnostic> {
    val delegate = prop.delegate ?: return emptyList()
    val call = delegate.expression as? KtCallExpression ?: return emptyList()
    if (call.typeArgumentList != null) return emptyList()
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    val file = prop.containingKtFile
    if (file.importDirectives.any { it.isAllUnder || (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return emptyList()
    val cls = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name } ?: return emptyList()
    if (cls.isInterface() || cls.isEnum() || cls.superTypeListEntries.isNotEmpty() || cls.typeParameters.isNotEmpty()) return emptyList()
    var extensions = false
    fun visit(e: KtElement) {
        if (e is KtNamedFunction && e.receiverTypeReference != null && e.name in DELEGATE_OPERATOR_NAMES) extensions = true
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    if (extensions) return emptyList()
    val functions = cls.declarations.filterIsInstance<KtNamedFunction>()
    if (functions.any { it.name == "provideDelegate" }) return emptyList()
    if (prop.typeReference == null) return emptyList()
    // What the property passes as `thisRef`: its class's instance, its extension receiver, or `null` at top level.
    val owner = prop.parent?.parent as? KtClassOrObject
    val thisRef: dev.ide.lang.kotlin.symbols.KotlinType? = when {
        prop.receiverTypeReference != null -> service.typeFromText(prop.receiverTypeReference!!.text, resolver.fileContext, owner?.fqName?.asString()) ?: return emptyList()
        owner != null -> owner.fqName?.asString()?.let { dev.ide.lang.kotlin.symbols.KotlinType(it) } ?: return emptyList()
        prop.parent is dev.ide.kotlin.syntax.psi.KtFile -> null
        else -> return emptyList()
    }
    if (owner != null && (owner.typeParameters.isNotEmpty() || owner !is KtClass && owner !is dev.ide.kotlin.syntax.psi.KtObjectDeclaration)) return emptyList()
    val propType = service.typeFromText(prop.typeReference!!.text, resolver.fileContext, owner?.fqName?.asString()) ?: return emptyList()
    val subtyping = KotlinStrictSubtyping(service)
    fun type(p: dev.ide.kotlin.syntax.psi.KtParameter): dev.ide.lang.kotlin.symbols.KotlinType? =
        p.typeReference?.text?.let { service.typeFromText(it, resolver.fileContext, cls.fqName?.asString()) }
    /** Whether [fn] can take the call: true, false, or null when it cannot be judged. */
    fun applicable(fn: KtNamedFunction, arity: Int): Boolean? {
        if (fn.typeParameters.isNotEmpty() || fn.receiverTypeReference != null) return null
        val params = fn.valueParameters
        if (params.any { it.isVarArg || it.hasDefaultValue() }) return null
        if (params.size != arity) return false
        val t = type(params[0]) ?: return null
        if (t.isTypeParameter) return null
        // A top-level property passes `null`, which only a nullable parameter takes.
        val thisFits = if (thisRef == null) t.nullable else subtyping.subtype(thisRef, t)
        if (thisFits == false) return false
        // The property parameter is a `KProperty<*>` (or something above it); any other shape is not judged.
        val pp = params[1].typeReference?.text?.replace(" ", "") ?: return null
        if (pp !in PROPERTY_PARAMETER_TYPES) return null
        if (arity == 3) {
            val v = type(params[2]) ?: return null
            if (v.isTypeParameter) return null
            if (subtyping.subtype(propType, v) == false) return false
            if (subtyping.subtype(propType, v) == null) return null
        }
        return if (thisFits == null) null else true
    }
    val by = generateSequence(delegate.firstChild) { it.nextSibling }.firstOrNull { it.elementType == KtTokens.BY_KEYWORD } ?: delegate
    val out = ArrayList<Diagnostic>()
    for ((fnName, arity) in listOfNotNull("getValue" to 2, ("setValue" to 3).takeIf { prop.isVar })) {
        val candidates = functions.filter { it.name == fnName }
        if (candidates.isEmpty()) continue
        val verdicts = candidates.map { applicable(it, arity) }
        if (verdicts.all { it == false }) out += error(
            by, "None of the '$fnName' functions of '$name' can be called for this property",
            KotlinDiagnosticCodes.DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE,
        )
    }
    return out
}

private val PROPERTY_PARAMETER_TYPES = setOf("KProperty<*>", "kotlin.reflect.KProperty<*>", "Any?", "Any")

// --- FirPropertyInitializationAnalyzer (captured values) ---

/**
 * CAPTURED_VAL_INITIALIZATION: `x = …` assigning a local `val` declared (without initializer) outside the lambda,
 * local function or local class the assignment sits in, which may run any number of times or never. Lambdas
 * handed to a project function that declares no contract count; one handed to a library function (which may
 * promise to call it in place, as `run` does) is left alone.
 */
internal fun KotlinSemanticChecks.capturedValInitialization(e: dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver: KotlinResolver?): Diagnostic? {
    if (e.operationToken != KtTokens.EQ) return null
    val ref = e.left as? KtNameReferenceExpression ?: return null
    val name = ref.getReferencedName()
    var crossed = false
    var node: KtElement = e
    while (true) {
        val p = node.parent ?: return null
        when (p) {
            is KtFile -> return null
            is KtClassBody -> return null
            is dev.ide.kotlin.syntax.psi.KtBlockExpression -> {
                val decl = p.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= e.textRange.startOffset } as KtProperty?
                if (decl != null) {
                    if (!crossed || decl.isVar || decl.initializer != null || decl.hasDelegate() || decl.receiverTypeReference != null) return null
                    return error(ref, "Captured values initialization is forbidden due to possible reassignment", KotlinDiagnosticCodes.CAPTURED_VAL_INITIALIZATION)
                }
                if (p.statements.any { it is dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration }) return null
            }
            is KtFunctionLiteral -> {
                if (p.valueParameters.any { it.name == name } || (name == "it" && p.valueParameters.isEmpty())) return null
                val lambda = p.parent as? dev.ide.kotlin.syntax.psi.KtLambdaExpression ?: return null
                if (!lambdaMayRunOutOfPlace(lambda, resolver)) return null
                crossed = true
            }
            is KtNamedFunction -> {
                if (p.valueParameters.any { it.name == name }) return null
                if (p.parent !is dev.ide.kotlin.syntax.psi.KtBlockExpression) return null
                crossed = true
            }
            is KtClassOrObject -> return null
            is dev.ide.kotlin.syntax.psi.KtForExpression -> if (p.loopParameter?.name == name) return null
            is dev.ide.kotlin.syntax.psi.KtCatchClause -> if (p.catchParameter?.name == name) return null
            else -> {}
        }
        node = p
    }
}

/** Whether nothing promises [lambda] runs in place exactly once: it is not passed to a call at all, or the call
 *  resolves to a project function whose body declares no contract. */
private fun KotlinSemanticChecks.lambdaMayRunOutOfPlace(lambda: dev.ide.kotlin.syntax.psi.KtLambdaExpression, resolver: KotlinResolver?): Boolean {
    var holder: KtElement = lambda
    while (holder.parent is dev.ide.kotlin.syntax.psi.KtLabeledExpression || holder.parent is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) holder = holder.parent!!
    val arg = holder.parent as? dev.ide.kotlin.syntax.psi.KtValueArgument ?: return false
    val call = (if (arg is dev.ide.kotlin.syntax.psi.KtLambdaArgument) arg.parent else arg.parent?.parent) as? KtCallExpression ?: return false
    resolver ?: return false
    val callee = runCatching { resolver.calleeFunctionOf(call) }.getOrNull() ?: return false
    if (!callee.origin.fromSource) return false
    val fn = (callee.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction ?: return false
    val body = fn.bodyExpression ?: return false
    return !body.text.contains("contract")
}

// --- FirExpressionsResolveTransformer (augmented assignment ambiguity) ---

/**
 * ASSIGN_OPERATOR_AMBIGUITY: `v += x` where both `v.plusAssign(x)` and `v = v + x` apply: `v` is a `var` (or an
 * indexed element `a[i]` whose class also has `set`), and its class of this file offers an operator `plusAssign`
 * and an operator `plus` returning that class, taking the same parameter type. Judged only when each operator
 * has exactly one candidate, declared in this file as a member or as an extension on the class by name.
 */
internal fun KotlinSemanticChecks.assignOperatorAmbiguity(e: dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val (assignName, plainName) = when (e.operationToken) {
        KtTokens.PLUSEQ -> "plusAssign" to "plus"
        KtTokens.MINUSEQ -> "minusAssign" to "minus"
        KtTokens.MULTEQ -> "timesAssign" to "times"
        KtTokens.DIVEQ -> "divAssign" to "div"
        KtTokens.PERCEQ -> "remAssign" to "rem"
        else -> return null
    }
    val left = e.left ?: return null
    e.right ?: return null
    val file = e.containingKtFile
    if (file.importDirectives.isNotEmpty()) return null
    // Only a class of this project can be judged; settle that before walking the file.
    val probe = (dev.ide.lang.kotlin.parse.unwrapParen(left) as? dev.ide.kotlin.syntax.psi.KtArrayAccessExpression)?.arrayExpression ?: left
    val probeType = resolver.inferType(probe) ?: return null
    if (service.sourceClass(probeType.qualifiedName) == null) return null
    val classes = HashMap<String, KtClass>()
    val dup = HashSet<String>()
    val extensions = ArrayList<KtNamedFunction>()
    fun collect(n: KtElement) {
        if (n is KtClass && n !is KtEnumEntry) n.name?.let { if (classes.put(it, n) != null) dup += it }
        if (n is KtTypeAlias) n.name?.let { dup += it }
        if (n is KtNamedFunction && n.receiverTypeReference != null) extensions += n
        var c = n.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(file)
    fun operators(cls: KtClass, name: String): List<KtNamedFunction> =
        cls.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name && it.receiverTypeReference == null } +
            extensions.filter { it.name == name }
    /** Whether a class named [typeName] offers both operators in the ambiguous way. */
    fun bothApply(typeName: String): Boolean {
        if (typeName in dup) return false
        val cls = classes[typeName] ?: return false
        if (cls.superTypeListEntries.isNotEmpty() || cls.typeParameters.isNotEmpty()) return false
        val assign = operators(cls, assignName).singleOrNull() ?: return false
        val plain = operators(cls, plainName).singleOrNull() ?: return false
        for (f in listOf(assign, plain)) {
            if (!f.hasModifier(KtTokens.OPERATOR_KEYWORD) || f.valueParameters.size != 1 || f.typeParameters.isNotEmpty()) return false
            f.receiverTypeReference?.let { r -> if (r.text.filterNot(Char::isWhitespace) != typeName) return false }
        }
        val pa = assign.valueParameters.single().typeReference?.text?.filterNot(Char::isWhitespace) ?: return false
        val pp = plain.valueParameters.single().typeReference?.text?.filterNot(Char::isWhitespace) ?: return false
        if (pa != pp) return false
        val assignReturn = assign.typeReference?.text?.filterNot(Char::isWhitespace)
        if (assignReturn != null && assignReturn != "Unit") return false
        if (assignReturn == null && !assign.hasBlockBody()) return false
        return plain.typeReference?.text?.filterNot(Char::isWhitespace) == typeName
    }
    val typeName: String = when (val l = dev.ide.lang.kotlin.parse.unwrapParen(left)) {
        is KtNameReferenceExpression -> {
            // A local `var` with its class written on it.
            val name = l.getReferencedName()
            var decl: KtProperty? = null
            var n: KtElement? = e.parent
            while (n != null && n !is KtFile && decl == null) {
                if (n is KtClassOrObject || n is KtFunctionLiteral) return null
                if (n is KtNamedFunction && n.valueParameters.any { it.name == name }) return null
                if (n is dev.ide.kotlin.syntax.psi.KtBlockExpression) {
                    decl = n.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= e.textRange.startOffset } as KtProperty?
                }
                n = n.parent
            }
            val d = decl ?: return null
            if (!d.isVar) return null
            val t = resolver.inferType(l) ?: return null
            if (t.nullable || t.isTypeParameter || t.typeArguments.isNotEmpty()) return null
            t.qualifiedName.substringAfterLast('.')
        }
        is dev.ide.kotlin.syntax.psi.KtArrayAccessExpression -> {
            val array = l.arrayExpression ?: return null
            if (l.indexExpressions.size != 1) return null
            val t = resolver.inferType(array) ?: return null
            if (t.nullable || t.isTypeParameter || t.typeArguments.isNotEmpty()) return null
            val holder = classes[t.qualifiedName.substringAfterLast('.')] ?: return null
            if (holder.superTypeListEntries.isNotEmpty() || holder.name in dup) return null
            val get = holder.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == "get" }.singleOrNull() ?: return null
            val set = holder.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == "set" }.singleOrNull() ?: return null
            if (extensions.any { it.name == "get" || it.name == "set" }) return null
            if (get.valueParameters.size != 1 || set.valueParameters.size != 2) return null
            if (!get.hasModifier(KtTokens.OPERATOR_KEYWORD) || !set.hasModifier(KtTokens.OPERATOR_KEYWORD)) return null
            val element = get.typeReference?.text?.filterNot(Char::isWhitespace) ?: return null
            if (set.valueParameters[1].typeReference?.text?.filterNot(Char::isWhitespace) != element) return null
            element
        }
        else -> return null
    }
    if (!bothApply(typeName)) return null
    return error(e.operationReference, "Assignment operators ambiguity: both '$plainName' and '$assignName' apply", KotlinDiagnosticCodes.ASSIGN_OPERATOR_AMBIGUITY)
}

// --- protected constructors ---

/**
 * PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL: `A()` from inside a subclass of `A` calling a `protected` constructor,
 * which only a supertype call or a delegation call may do. The class and its constructors are this file's; the
 * constructor is the only one of that arity, or the only one whose parameter types the literal arguments match.
 */
internal fun protectedConstructorNotInSuperCall(call: KtCallExpression): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null
    if (call.typeArgumentList != null) return null
    val name = callee.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val file = call.containingKtFile
    if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.')) == name }) return null
    val top = file.declarations
    val named = top.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
    val cls: KtClass = when (val d = named.singleOrNull()) {
        is KtClass -> d
        is KtTypeAlias -> {
            val rhs = d.typeReference?.typeElement as? KtUserType ?: return null
            if (rhs.qualifier != null || rhs.typeArgumentList != null) return null
            top.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == rhs.referencedName }.singleOrNull() as? KtClass ?: return null
        }
        else -> return null
    }
    if (cls.isInterface() || cls.isEnum() || cls.typeParameters.isNotEmpty()) return null
    val args = call.valueArguments
    if (args.any { it.getArgumentName() != null || it.isSpread }) return null
    val ctors: List<KtDeclaration> = listOfNotNull<KtDeclaration>(cls.primaryConstructor) + cls.secondaryConstructors
    fun params(d: KtDeclaration): List<dev.ide.kotlin.syntax.psi.KtParameter> = when (d) {
        is dev.ide.kotlin.syntax.psi.KtPrimaryConstructor -> d.valueParameters
        is dev.ide.kotlin.syntax.psi.KtSecondaryConstructor -> d.valueParameters
        else -> emptyList()
    }
    if (ctors.any { c -> params(c).any { it.isVarArg || it.defaultValue != null } }) return null
    var candidates = ctors.filter { params(it).size == args.size }
    if (candidates.size > 1) {
        val types = args.map { literalArgumentType(it.getArgumentExpression()) ?: return null }
        candidates = candidates.filter { c -> params(c).map { it.typeReference?.text?.filterNot(Char::isWhitespace) } == types }
    }
    val ctor = candidates.singleOrNull() ?: return null
    if (!ctor.hasModifier(KtTokens.PROTECTED_KEYWORD)) return null
    // `A(1)` may be a companion's `invoke`.
    if (cls.declarations.any { it is KtObjectDeclaration && it.isCompanion() } || declaresFunctionNamed(file, "invoke")) return null
    // Inside a subclass (by a direct supertype naming the class or the alias), not inside the class itself.
    val inSubclass = generateSequence(call.parent) { it.parent }.filterIsInstance<KtClassOrObject>().any { c ->
        c !== cls && c.superTypeListEntries.any { e ->
            val t = e.typeReference?.typeElement as? KtUserType
            t != null && t.qualifier == null && (t.referencedName == name || t.referencedName == cls.name)
        }
    }
    if (!inSubclass) return null
    if (generateSequence(call.parent) { it.parent }.any { it === cls }) return null
    return error(callee, "Protected constructor of '${cls.name}' can only be called from a super call", KotlinDiagnosticCodes.PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL)
}

/** The written type name of a literal argument: `1` Int, `1.0` Double, `""` String, `'c'` Char, `true` Boolean. */
private fun literalArgumentType(e: KtExpression?): String? = when (e) {
    is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression -> "String"
    is dev.ide.kotlin.syntax.psi.KtConstantExpression -> {
        val t = e.text
        when {
            t == "true" || t == "false" -> "Boolean"
            t.startsWith("'") -> "Char"
            t.endsWith("L") -> "Long"
            t.endsWith("f") || t.endsWith("F") -> "Float"
            t.endsWith("u") || t.endsWith("U") || t.endsWith("uL") || t.endsWith("UL") -> null
            t.contains('.') || (t.contains('e') || t.contains('E')) && !t.startsWith("0x") && !t.startsWith("0X") -> "Double"
            t == "null" -> null
            else -> "Int"
        }
    }
    else -> null
}

/** Whether [file] declares a function named [name] anywhere. */
private fun declaresFunctionNamed(file: KtFile, name: String): Boolean {
    var found = false
    fun visit(e: KtElement) {
        if (found) return
        if (e is KtNamedFunction && e.name == name) { found = true; return }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(file)
    return found
}
