package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtIntersectionType
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeElement
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.flowProvenTypes

import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.resolve.SymbolKind

/**
 * An eighth round of rules ported from the K2 compiler's checkers (`compiler/fir/checkers`, Apache 2.0) and from
 * the diagnostics its resolution attaches. Each function names the K2 code and checker it follows; where K2
 * decides on resolution this editor does not have, the rule keeps to the cases it can decide and reports
 * nothing on the rest. Registered from [KotlinSemanticChecks.checkers], after the earlier rounds.
 */
internal fun KotlinSemanticChecks.round8PortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        report(valReassignmentViaBackingField(psi as KtNameReferenceExpression, resolver))
        report(inlineParameterInInlineDefault(psi, resolver))
    },
    KotlinChecker(KtAnnotationEntry::class) { psi ->
        KotlinPerf.span("sem.r8.annotation") {
            if (resolveReady) report(annotationNotAClass(psi as KtAnnotationEntry, resolver))
            reportAll(annotationArgumentShapes(psi as KtAnnotationEntry, resolver))
            report(deprecatedSinceKotlinOutsideKotlin(psi))
        }
    },
    KotlinChecker(KtWhenExpression::class) { psi -> reportAll(elseMisplacedInWhen(psi as KtWhenExpression)) },
    KotlinChecker(KtNamedFunction::class) { psi ->
        psi as KtNamedFunction
        report(dataObjectCustomEqualsOrHashCode(psi))
        report(privateFunctionWithNoBody(psi))
        report(deprecatedTypeParameterSyntax(psi))
        if (resolveReady) report(abbreviatedNothingType(psi, resolver))
        if (resolveReady) reportAll(multipleInheritedDefaults(psi, resolver))
        report(functionDelegateMemberNameClash(psi))
        reportAll(cyclicTypeBounds(psi))
        report(externalDeclarationShape(psi))
        report(expectedDeclarationShape(psi))
    },
    KotlinChecker(KtPropertyAccessor::class) { psi ->
        psi as KtPropertyAccessor
        report(externalDeclarationShape(psi))
        report(valWithSetter(psi))
        if (resolveReady) report(wrongAccessorReturnType(psi, resolver))
    },
    KotlinChecker(KtSuperTypeListEntry::class) { psi ->
        report(supertypeNotAClassOrInterface(psi as KtSuperTypeListEntry))
        if (resolveReady) report(sealedInheritorInDifferentPackage(psi, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtCallExpression::class) { psi ->
        if (resolveReady) reportAll(reifiedArgumentShapes(psi as dev.ide.kotlin.syntax.psi.KtCallExpression, resolver))
    },
    KotlinChecker(KtProperty::class) { psi ->
        psi as KtProperty
        if (resolveReady) report(abbreviatedNothingType(psi, resolver))
        report(delegateUsesExtensionTypeParameter(psi))
        report(expectedDeclarationShape(psi))
        report(functionDelegateMemberNameClash(psi))
        reportAll(cyclicTypeBounds(psi))
    },
    KotlinChecker(KtCallableReferenceExpression::class) { psi ->

        report(callableReferenceLhsNotAClass(psi as KtCallableReferenceExpression, resolver))
        if (resolveReady) report(constructorLikeReference(psi, resolver))
        if (resolveReady) report(safeCallableReferenceCall(psi, resolver))
        if (resolveReady) report(unsafeCallableReference(psi, resolver))
        reportAll(placeholderInCallableReferenceQualifier(psi))


    },
    KotlinChecker(KtDotQualifiedExpression::class) { psi -> report(typeParameterOnLhsOfDot(psi as KtDotQualifiedExpression, resolver)) },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtForExpression::class) { psi ->
        if (resolveReady) reportAll(forLoopConventions(psi as dev.ide.kotlin.syntax.psi.KtForExpression, resolver))
    },
    KotlinChecker(KtCatchClause::class) { psi -> reportAll(catchParameterShape(psi as KtCatchClause, resolver.takeIf { resolveReady })) },
    KotlinChecker(KtClassOrObject::class) { psi ->
        psi as KtClassOrObject
        if (resolveReady) reportAll(delegatedMemberClashes(psi, resolver))
        if (resolveReady) reportAll(cyclicInheritance(psi, resolver))
        if (resolveReady) reportAll(inheritedImplementationWeakensAccess(psi, resolver))

        if (psi is KtClass) reportAll(cyclicTypeBounds(psi))
        report(expectAndActualTogether(psi))
    },
    KotlinChecker(KtClass::class) { psi ->
        report(sealedFunInterface(psi as KtClass))
        report(restrictedRetentionForExpressionAnnotation(psi))
    },
    KotlinChecker(KtParameter::class) { psi ->
        report(abstractPropertyInPrimaryConstructor(psi as KtParameter))
        report(invalidInlineDefault(psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtTypeAlias::class) { psi ->
        report(typeAliasCapturesOuterTypeParameters(psi as dev.ide.kotlin.syntax.psi.KtTypeAlias))
    },
    KotlinChecker(KtSuperExpression::class) { psi ->
        if (resolveReady) report(superclassFromInterface(psi as KtSuperExpression, resolver))
        report(ambiguousLabel(psi as KtSuperExpression))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtThisExpression::class) { psi ->
        report(ambiguousLabel(psi as dev.ide.kotlin.syntax.psi.KtThisExpression))
    },
    KotlinChecker(KtSafeQualifiedExpression::class) { psi -> report(safeCallOnSuper(psi as KtSafeQualifiedExpression)) },
    KotlinChecker(KtNullableType::class) { psi -> report(nullableOnDefinitelyNotNullable(psi as KtNullableType)) },
)

/** The K2 names each code of this file stands for, merged into [KotlinSuppression.K2_NAMES]. */
internal val ROUND8_K2_NAMES: Map<String, Set<String>> = mapOf(
    KotlinDiagnosticCodes.VAL_REASSIGNMENT_VIA_BACKING_FIELD to setOf("VAL_REASSIGNMENT_VIA_BACKING_FIELD_ERROR"),
    KotlinDiagnosticCodes.NOT_AN_ANNOTATION_CLASS to setOf("NOT_AN_ANNOTATION_CLASS"),
    KotlinDiagnosticCodes.NOT_A_CLASS to setOf("NOT_A_CLASS"),
    KotlinDiagnosticCodes.ELSE_MISPLACED_IN_WHEN to setOf("ELSE_MISPLACED_IN_WHEN"),
    KotlinDiagnosticCodes.DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE to setOf("DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE"),
    KotlinDiagnosticCodes.PRIVATE_FUNCTION_WITH_NO_BODY to setOf("PRIVATE_FUNCTION_WITH_NO_BODY"),
    KotlinDiagnosticCodes.DEPRECATED_TYPE_PARAMETER_SYNTAX to setOf("DEPRECATED_TYPE_PARAMETER_SYNTAX"),
    KotlinDiagnosticCodes.VAL_WITH_SETTER to setOf("VAL_WITH_SETTER"),
    KotlinDiagnosticCodes.WRONG_GETTER_RETURN_TYPE to setOf("WRONG_GETTER_RETURN_TYPE"),
    KotlinDiagnosticCodes.WRONG_SETTER_RETURN_TYPE to setOf("WRONG_SETTER_RETURN_TYPE"),
    KotlinDiagnosticCodes.SUPERTYPE_NOT_A_CLASS_OR_INTERFACE to setOf("SUPERTYPE_NOT_A_CLASS_OR_INTERFACE"),
    KotlinDiagnosticCodes.CALLABLE_REFERENCE_LHS_NOT_A_CLASS to setOf("CALLABLE_REFERENCE_LHS_NOT_A_CLASS"),
    KotlinDiagnosticCodes.TYPE_PARAMETER_ON_LHS_OF_DOT to setOf("TYPE_PARAMETER_ON_LHS_OF_DOT"),
    KotlinDiagnosticCodes.CATCH_PARAMETER_WITH_DEFAULT_VALUE to setOf("CATCH_PARAMETER_WITH_DEFAULT_VALUE"),
    KotlinDiagnosticCodes.TYPE_PARAMETER_IN_CATCH_CLAUSE to setOf("TYPE_PARAMETER_IN_CATCH_CLAUSE"),
    KotlinDiagnosticCodes.THROWABLE_TYPE_MISMATCH to setOf("THROWABLE_TYPE_MISMATCH"),
    KotlinDiagnosticCodes.UNSUPPORTED_SEALED_FUN_INTERFACE to setOf("UNSUPPORTED_SEALED_FUN_INTERFACE"),
    KotlinDiagnosticCodes.ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS to setOf("ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS"),
    KotlinDiagnosticCodes.UNEXPECTED_SAFE_CALL to setOf("UNEXPECTED_SAFE_CALL"),
    KotlinDiagnosticCodes.NULLABLE_ON_DEFINITELY_NOT_NULLABLE to setOf("NULLABLE_ON_DEFINITELY_NOT_NULLABLE"),
    KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED to setOf("ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED"),
    KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR to setOf("ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR"),
    KotlinDiagnosticCodes.TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR to setOf("TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR"),
    KotlinDiagnosticCodes.DEFINITELY_NON_NULLABLE_AS_REIFIED to setOf("DEFINITELY_NON_NULLABLE_AS_REIFIED"),
    KotlinDiagnosticCodes.RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR to setOf("RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR"),
    KotlinDiagnosticCodes.CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR to setOf("CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR"),
    KotlinDiagnosticCodes.JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE to setOf("JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE"),
    KotlinDiagnosticCodes.ABBREVIATED_NOTHING_RETURN_TYPE to setOf("ABBREVIATED_NOTHING_RETURN_TYPE"),
    KotlinDiagnosticCodes.ABBREVIATED_NOTHING_PROPERTY_TYPE to setOf("ABBREVIATED_NOTHING_PROPERTY_TYPE"),
    KotlinDiagnosticCodes.PACKAGE_CONFLICTS_WITH_CLASSIFIER to setOf("PACKAGE_CONFLICTS_WITH_CLASSIFIER"),
    KotlinDiagnosticCodes.SEALED_INHERITOR_IN_DIFFERENT_PACKAGE to setOf("SEALED_INHERITOR_IN_DIFFERENT_PACKAGE"),
    KotlinDiagnosticCodes.OVERRIDING_FINAL_MEMBER_BY_DELEGATION to setOf("OVERRIDING_FINAL_MEMBER_BY_DELEGATION"),
    KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION to setOf("VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION"),
    KotlinDiagnosticCodes.MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES to setOf("MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES"),
    KotlinDiagnosticCodes.SAFE_CALLABLE_REFERENCE_CALL to setOf("SAFE_CALLABLE_REFERENCE_CALL"),
    KotlinDiagnosticCodes.UNSAFE_CALLABLE_REFERENCE to setOf("UNSAFE_CALLABLE_REFERENCE"),
    KotlinDiagnosticCodes.INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE to setOf("INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE"),
    KotlinDiagnosticCodes.NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE to setOf("NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE"),
    KotlinDiagnosticCodes.TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS to setOf("TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS"),
    KotlinDiagnosticCodes.DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR to setOf("DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR"),
    KotlinDiagnosticCodes.SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE to setOf("SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE"),
    KotlinDiagnosticCodes.AMBIGUOUS_LABEL to setOf("AMBIGUOUS_LABEL"),
    KotlinDiagnosticCodes.FUNCTION_DELEGATE_MEMBER_NAME_CLASH to setOf("FUNCTION_DELEGATE_MEMBER_NAME_CLASH"),
    KotlinDiagnosticCodes.CANNOT_BE_IMPORTED to setOf("CANNOT_BE_IMPORTED"),
    KotlinDiagnosticCodes.CYCLIC_GENERIC_UPPER_BOUND to setOf("CYCLIC_GENERIC_UPPER_BOUND"),
    KotlinDiagnosticCodes.CYCLIC_INHERITANCE_HIERARCHY to setOf("CYCLIC_INHERITANCE_HIERARCHY"),
    KotlinDiagnosticCodes.IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL to setOf("IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR"),
    KotlinDiagnosticCodes.ITERATOR_ON_NULLABLE to setOf("ITERATOR_ON_NULLABLE"),
    KotlinDiagnosticCodes.HAS_NEXT_MISSING to setOf("HAS_NEXT_MISSING"),
    KotlinDiagnosticCodes.NEXT_MISSING to setOf("NEXT_MISSING"),
    KotlinDiagnosticCodes.HAS_NEXT_FUNCTION_TYPE_MISMATCH to setOf("HAS_NEXT_FUNCTION_TYPE_MISMATCH"),
    KotlinDiagnosticCodes.TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE to setOf("TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE"),
    KotlinDiagnosticCodes.EXTERNAL_DECLARATION_IN_INTERFACE to setOf("EXTERNAL_DECLARATION_IN_INTERFACE"),
    KotlinDiagnosticCodes.EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT to setOf("EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT"),
    KotlinDiagnosticCodes.EXTERNAL_DECLARATION_CANNOT_HAVE_BODY to setOf("EXTERNAL_DECLARATION_CANNOT_HAVE_BODY"),
    KotlinDiagnosticCodes.EXPECTED_DECLARATION_WITH_BODY to setOf("EXPECTED_DECLARATION_WITH_BODY"),
    KotlinDiagnosticCodes.EXPECTED_PROPERTY_INITIALIZER to setOf("EXPECTED_PROPERTY_INITIALIZER"),
    KotlinDiagnosticCodes.EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE to setOf("EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE"),
    KotlinDiagnosticCodes.INVALID_CHARACTERS to setOf("INVALID_CHARACTERS"),
    KotlinDiagnosticCodes.DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE to setOf("DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE"),
    KotlinDiagnosticCodes.PLACEHOLDER_PROJECTION_IN_QUALIFIER to setOf("PLACEHOLDER_PROJECTION_IN_QUALIFIER"),
)

private fun error(at: KtElement, message: String, code: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

/** The `val`/`var` property, local or member, whose accessor most closely encloses [e]. */
private fun closestAccessor(e: KtElement): KtPropertyAccessor? {
    var node: KtElement? = e.parent as? KtElement
    while (node != null && node !is KtFile) {
        if (node is KtPropertyAccessor) return node
        node = node.parent as? KtElement
    }
    return null
}

// --- FirReassignmentAndInvisibleSetterChecker.checkValReassignmentViaBackingField ---

/**
 * VAL_REASSIGNMENT_VIA_BACKING_FIELD_ERROR: `field = …`, `field += …` or `field++` where `field` is the backing
 * field of a `val` and the closest accessor around the assignment is that `val`'s getter. A local function or a
 * local class's member inside the getter still writes the same field; an accessor of a local class's own
 * property is the closer one. A local or member named `field` takes the name over, and is left alone.
 */
internal fun valReassignmentViaBackingField(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    if (ref.getReferencedName() != "field") return null
    val parent = ref.parent
    val assigned = when (parent) {
        is KtBinaryExpression -> parent.left === ref && parent.operationToken in ASSIGNMENT_TOKENS
        is KtPrefixExpression -> parent.baseExpression === ref && parent.operationToken in INC_DEC
        is KtPostfixExpression -> parent.baseExpression === ref && parent.operationToken in INC_DEC
        else -> false
    }
    if (!assigned) return null
    val accessor = closestAccessor(ref) ?: return null
    if (!accessor.isGetter) return null
    val property = accessor.property ?: return null
    if (property.isVar || property.hasModifier(KtTokens.LATEINIT_KEYWORD)) return null
    val off = ref.textRange.startOffset
    // The accessor's own `field` is a FIELD symbol of the scope; any other local of that name takes it over.
    if (resolver.localsAt(off, "field").any { it.kind != SymbolKind.FIELD } || resolver.enclosingClassMembersContain(off, "field")) return null
    return error(ref, "Reassignment of read-only property via backing field is prohibited", KotlinDiagnosticCodes.VAL_REASSIGNMENT_VIA_BACKING_FIELD)
}

private val ASSIGNMENT_TOKENS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
private val INC_DEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

// --- coneDiagnosticToFirDiagnostic: ConeNotAnnotationClass, NOT_A_CLASS on a type-parameter annotation ---

/**
 * NOT_AN_ANNOTATION_CLASS: `@Foo` where `Foo` is a class, but not an annotation class. NOT_A_CLASS: `@T` with `T`
 * a type parameter. A class is known not to be an annotation when it is a project class not declared
 * `annotation`, a plain final class (source or binary), or a Java class that is not an interface (Java
 * annotations are interfaces in bytecode). A Kotlin binary that is open, abstract or an interface is not
 * judged: its metadata kind is not read here.
 */
internal fun KotlinSemanticChecks.annotationNotAClass(entry: KtAnnotationEntry, resolver: KotlinResolver): Diagnostic? {
    val typeRef = entry.typeReference ?: return null
    val user = typeRef.typeElement as? KtUserType ?: return null
    val nameRef = user.referenceExpression ?: return null
    val name = nameRef.getReferencedName()
    if (name.isEmpty()) return null
    if (user.qualifier == null && resolver.isTypeParameterInScope(name, typeRef.textRange.startOffset) && !nestedClassifierInScope(entry, name)) {
        return error(nameRef, "'$name' is not a class", KotlinDiagnosticCodes.NOT_A_CLASS)
    }
    val fqn = classifierFqnOf(typeRef, resolver) ?: return null
    // A library class met by a bare name may lose to a star import this resolver cannot read (`kotlin.native.*`).
    if (user.qualifier == null && service.sourceClass(fqn) == null) {
        val imports = resolver.fileContext.imports
        if (imports.none { !it.isStar && it.simpleName == name } && imports.any { it.isStar }) return null
    }
    val notAnnotation = fqn == "kotlin.Annotation" || service.sourceClass(fqn)?.let { !it.isAnnotation }
        ?: when {
            service.isFinalType(fqn) == true -> true
            service.isJavaType(fqn) && service.isInterfaceType(fqn) == false -> true
            else -> false
        }
    if (!notAnnotation) return null
    return error(nameRef, "'$fqn' is not an annotation class", KotlinDiagnosticCodes.NOT_AN_ANNOTATION_CLASS)
}

// --- FirWhenConditionChecker / raw FIR: ELSE_MISPLACED_IN_WHEN ---

/** ELSE_MISPLACED_IN_WHEN: an `else` branch with another branch after it, reported on its `else`. */
internal fun elseMisplacedInWhen(w: KtWhenExpression): List<Diagnostic> {
    val entries = w.entries
    if (entries.size < 2) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    for (i in 0 until entries.size - 1) {
        val e = entries[i]
        if (!e.isElse) continue
        // `else if cond ->` is a guarded branch (KT-85244), not the `else` one.
        if (e.guard != null || e.text.removePrefix("else").trimStart().startsWith("if")) continue
        val kw = e.children.firstOrNull { it.elementType == KtTokens.ELSE_KEYWORD } as? KtElement ?: e
        out += error(kw, "'else' entry must be the last one in a when-expression", KotlinDiagnosticCodes.ELSE_MISPLACED_IN_WHEN)
    }
    return out
}

// --- FirDataObjectContentChecker ---

/**
 * DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE: an `override` of `equals(Any?)` or `hashCode()` declared in a
 * `data object`, which generates both itself. Reported on the `override` modifier.
 */
internal fun dataObjectCustomEqualsOrHashCode(f: KtNamedFunction): Diagnostic? {
    val override = f.modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD) ?: return null
    val obj = (f.parent?.parent as? KtObjectDeclaration)?.takeIf { f.parent is dev.ide.kotlin.syntax.psi.KtClassBody } ?: return null
    if (obj.isCompanion() || !obj.hasModifier(KtTokens.DATA_KEYWORD)) return null
    if (f.receiverTypeReference != null || f.typeParameters.isNotEmpty()) return null
    val params = f.valueParameters
    val isEquals = f.name == "equals" && params.size == 1 && params[0].typeReference?.text?.filterNot { it.isWhitespace() }
        .let { t -> t == "Any?" || t == "kotlin.Any?" || (t != null && obj.superTypeListEntries.isEmpty() && t.firstOrNull()?.isUpperCase() == true) }
    val isHashCode = f.name == "hashCode" && params.isEmpty()
    if (!isEquals && !isHashCode) return null
    return error(override, "Data object cannot have a custom implementation of 'equals' or 'hashCode'", KotlinDiagnosticCodes.DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE)
}

// --- FirMemberFunctionsChecker: PRIVATE_FUNCTION_WITH_NO_BODY ---

/** PRIVATE_FUNCTION_WITH_NO_BODY: a `private` function without a body in an interface (which would make it an
 *  abstract member no implementation can see). Reported on the `private` modifier. */
internal fun privateFunctionWithNoBody(f: KtNamedFunction): Diagnostic? {
    if (f.hasBody()) return null
    val priv = f.modifierList?.getModifier(KtTokens.PRIVATE_KEYWORD) ?: return null
    val cls = (f.parent?.parent as? KtClass)?.takeIf { f.parent is dev.ide.kotlin.syntax.psi.KtClassBody } ?: return null
    if (!cls.isInterface()) return null
    return error(priv, "Private function without a body", KotlinDiagnosticCodes.PRIVATE_FUNCTION_WITH_NO_BODY)
}

// --- raw FIR: DEPRECATED_TYPE_PARAMETER_SYNTAX ---

/** DEPRECATED_TYPE_PARAMETER_SYNTAX: `fun foo<T>()`, the type parameter list written after the name. */
internal fun deprecatedTypeParameterSyntax(f: KtNamedFunction): Diagnostic? {
    val list = f.typeParameterList ?: return null
    val nameId = f.nameIdentifier ?: return null
    if (list.textRange.startOffset < nameId.textRange.endOffset) return null
    return error(list, "Type parameters must be placed before the name of the function", KotlinDiagnosticCodes.DEPRECATED_TYPE_PARAMETER_SYNTAX)
}

// --- FirPropertyAccessorsTypesChecker ---

/** VAL_WITH_SETTER: a setter on a `val` that is not `lateinit`. */
internal fun valWithSetter(a: KtPropertyAccessor): Diagnostic? {
    if (a.isGetter) return null
    val p = a.property ?: return null
    if (p.isVar || p.hasModifier(KtTokens.LATEINIT_KEYWORD)) return null
    return error(a, "A 'val' property cannot have a setter", KotlinDiagnosticCodes.VAL_WITH_SETTER)
}

/**
 * WRONG_SETTER_RETURN_TYPE: a setter's written return type other than `Unit`. WRONG_GETTER_RETURN_TYPE: a
 * getter's written return type naming another class than the property's written type. Both types are compared
 * by the class they resolve to (nullability included); an unresolved or generic one is not judged.
 */
internal fun KotlinSemanticChecks.wrongAccessorReturnType(a: KtPropertyAccessor, resolver: KotlinResolver): Diagnostic? {
    val ret = a.returnTypeReference ?: return null
    val p = a.property ?: return null
    if (a.isSetter) {
        val t = typeIdentity(ret, resolver) ?: return null
        if (t == "kotlin.Unit") return null
        return error(ret, "Setter return type must be 'Unit'", KotlinDiagnosticCodes.WRONG_SETTER_RETURN_TYPE)
    }
    val g = typeIdentity(ret, resolver) ?: return null
    val propType = p.typeReference
    val pt = if (propType != null) typeIdentity(propType, resolver) ?: return null else {
        // An implicit type is judged only off a literal initializer, whose type is certain.
        val init = p.initializer?.takeIf { it is dev.ide.kotlin.syntax.psi.KtConstantExpression || it is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression } ?: return null
        resolver.inferType(init)?.takeIf { !it.nullable && it.typeArguments.isEmpty() }?.qualifiedName ?: return null
    }
    if (g == pt) return null
    return error(ret, "Getter return type must be equal to the type of the property", KotlinDiagnosticCodes.WRONG_GETTER_RETURN_TYPE)
}

/** `fqn` or `fqn?` for a plain (argument-less) class type, or null when it is anything else or unknown. */
private fun KotlinSemanticChecks.typeIdentity(ref: KtTypeReference, resolver: KotlinResolver): String? {
    var el: KtTypeElement? = ref.typeElement
    var nullable = false
    while (el is KtNullableType) { nullable = true; el = el.innerType }
    val user = el as? KtUserType ?: return null
    if (user.typeArgumentList != null) return null
    val fqn = classifierFqnOf(ref, resolver) ?: return null
    if (service.isProjectTypeAliasFqn(fqn)) return null
    return if (nullable) "$fqn?" else fqn
}

// --- FirSupertypesChecker: SUPERTYPE_NOT_A_CLASS_OR_INTERFACE ---

/** SUPERTYPE_NOT_A_CLASS_OR_INTERFACE: a type parameter (or `T & Any`) written as a supertype. */
internal fun supertypeNotAClassOrInterface(entry: KtSuperTypeListEntry): Diagnostic? {
    val ref = entry.typeReference ?: return null
    val el = ref.typeElement
    val user = when (el) {
        is KtUserType -> el
        is KtIntersectionType -> el.children.firstOrNull { it is KtTypeReference }?.let { (it as KtTypeReference).typeElement as? KtUserType }
        else -> null
    } ?: return null
    if (user.qualifier != null || user.typeArgumentList != null) return null
    val name = user.referencedName ?: return null
    val cls = entry.parent?.parent as? KtClassOrObject ?: return null
    if (cls.typeParameters.none { it.name == name }) return null
    return error(ref, "Supertype is not a class or interface", KotlinDiagnosticCodes.SUPERTYPE_NOT_A_CLASS_OR_INTERFACE)
}

// --- FirTypeParameterInQualifiedAccessChecker ---

/** The type parameter a bare name [e] stands for as an expression: one in scope there, with no local and no
 *  member of an enclosing class of that name to take it over. */
private fun typeParameterExpression(e: dev.ide.kotlin.syntax.psi.KtExpression?, resolver: KotlinResolver): String? {
    var x = e
    while (x is KtParenthesizedExpression) x = x.expression
    val ref = x as? KtNameReferenceExpression ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val off = ref.textRange.startOffset
    if (!resolver.isTypeParameterInScope(name, off)) return null
    if (resolver.localsAt(off, name).isNotEmpty() || resolver.enclosingClassMembersContain(off, name)) return null
    if (nestedClassifierInScope(ref, name)) return null
    return name
}

/** Whether a class around [at] declares a nested classifier [name], which a bare name there means before a
 *  type parameter of that class. */
private fun nestedClassifierInScope(at: KtElement, name: String): Boolean {
    var node: KtElement? = at.parent as? KtElement
    while (node != null && node !is KtFile) {
        if (node is KtClassOrObject && node.declarations.any { (it is KtClassOrObject || it is dev.ide.kotlin.syntax.psi.KtTypeAlias) && (it as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == name }) return true
        node = node.parent as? KtElement
    }
    return false
}

/** CALLABLE_REFERENCE_LHS_NOT_A_CLASS: `T::member` with `T` a type parameter, reified or not. */
internal fun callableReferenceLhsNotAClass(e: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    typeParameterExpression(e.receiverExpression, resolver) ?: return null
    return error(e, "Left-hand side of a callable reference cannot be a type parameter", KotlinDiagnosticCodes.CALLABLE_REFERENCE_LHS_NOT_A_CLASS)
}

/** TYPE_PARAMETER_ON_LHS_OF_DOT: `T.member` with `T` a type parameter, reported on `T`. */
internal fun typeParameterOnLhsOfDot(q: KtDotQualifiedExpression, resolver: KotlinResolver): Diagnostic? {
    val recv = q.receiverExpression
    val name = typeParameterExpression(recv, resolver) ?: return null
    return error(recv, "Type parameter '$name' cannot have or inherit a companion object, so it cannot be on the left-hand side of a dot",
        KotlinDiagnosticCodes.TYPE_PARAMETER_ON_LHS_OF_DOT)
}

// --- FirCatchParameterChecker ---

/**
 * CATCH_PARAMETER_WITH_DEFAULT_VALUE: `catch (e: E = …)`. TYPE_PARAMETER_IN_CATCH_CLAUSE: a catch parameter
 * typed by a non-reified type parameter (or `T & Any`). THROWABLE_TYPE_MISMATCH: one typed `Nothing`, a function
 * type, or a class whose (completely known) supertypes do not include `Throwable`.
 */
internal fun KotlinSemanticChecks.catchParameterShape(c: KtCatchClause, resolver: KotlinResolver?): List<Diagnostic> {
    val p = c.catchParameter ?: return emptyList()
    val out = ArrayList<Diagnostic>(1)
    if (p.defaultValue != null) {
        out += error(p, "Catch clause parameter may not have a default value", KotlinDiagnosticCodes.CATCH_PARAMETER_WITH_DEFAULT_VALUE)
    }
    val ref = p.typeReference ?: return out
    var el = ref.typeElement
    while (el is KtNullableType) el = el.innerType
    val off = ref.textRange.startOffset
    if (el is KtIntersectionType) {
        val first = (el.children.firstOrNull { it is KtTypeReference } as? KtTypeReference)?.typeElement as? KtUserType
        val n = first?.takeIf { it.qualifier == null }?.referencedName
        if (n != null && resolver != null && resolver.isTypeParameterInScope(n, off) && !reifiedTypeParameter(p, n)) {
            out += error(p, "Type parameter is forbidden for catch parameter", KotlinDiagnosticCodes.TYPE_PARAMETER_IN_CATCH_CLAUSE)
        }
        return out
    }
    if (el is KtFunctionType) {
        out += error(p, "Throwable type mismatch", KotlinDiagnosticCodes.THROWABLE_TYPE_MISMATCH)
        return out
    }
    val user = el as? KtUserType ?: return out
    val name = user.referencedName ?: return out
    if (user.qualifier == null && user.typeArgumentList == null && resolver != null && resolver.isTypeParameterInScope(name, off)) {
        if (!reifiedTypeParameter(p, name)) {
            out += error(p, "Type parameter is forbidden for catch parameter", KotlinDiagnosticCodes.TYPE_PARAMETER_IN_CATCH_CLAUSE)
        }
        return out
    }
    if (resolver == null) return out
    val fqn = classifierFqnOf(ref, resolver) ?: return out
    val mismatch = when (fqn) {
        "kotlin.Nothing" -> true
        "kotlin.Throwable", "java.lang.Throwable" -> false
        in NON_THROWABLE_BUILTINS -> true
        else -> service.isSourceClass(fqn) && service.supertypeClosureKnown(fqn) &&
            inheritsFrom(fqn, "kotlin.Throwable") == false && inheritsFrom(fqn, "java.lang.Throwable") == false
    }
    if (mismatch) out += error(p, "Throwable type mismatch", KotlinDiagnosticCodes.THROWABLE_TYPE_MISMATCH)
    return out
}

private val NON_THROWABLE_BUILTINS = setOf(
    "kotlin.Any", "kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Char",
    "kotlin.Boolean", "kotlin.Double", "kotlin.Float", "kotlin.Unit",
)

/** Whether the type parameter [name] seen from [at] is declared `reified`. */
private fun reifiedTypeParameter(at: KtElement, name: String): Boolean {
    var node: KtElement? = at.parent as? KtElement
    while (node != null) {
        if (node is dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner) {
            node.typeParameters.firstOrNull { it.name == name }?.let { return it.hasModifier(KtTokens.REIFIED_KEYWORD) }
        }
        node = node.parent as? KtElement
    }
    return false
}

// --- FirSealedInterfaceAllowedChecker / FirFunInterfaceDeclarationChecker: UNSUPPORTED_SEALED_FUN_INTERFACE ---

/** UNSUPPORTED_SEALED_FUN_INTERFACE: `sealed fun interface`, reported on `sealed`. */
internal fun sealedFunInterface(c: KtClass): Diagnostic? {
    if (!c.isInterface() || !c.hasModifier(KtTokens.FUN_KEYWORD)) return null
    val sealed = c.modifierList?.getModifier(KtTokens.SEALED_KEYWORD) ?: return null
    return error(sealed, "'sealed fun interface' is not supported", KotlinDiagnosticCodes.UNSUPPORTED_SEALED_FUN_INTERFACE)
}

// --- FirAbstractPropertyInPrimaryConstructorParametersChecker (raw FIR) ---

/** ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS: `abstract val` in a primary constructor. */
internal fun abstractPropertyInPrimaryConstructor(p: KtParameter): Diagnostic? {
    if (!p.hasValOrVar()) return null
    if (p.parent?.parent !is KtPrimaryConstructor) return null
    val abs = p.modifierList?.getModifier(KtTokens.ABSTRACT_KEYWORD) ?: return null
    return error(abs, "Abstract property in primary constructor parameters", KotlinDiagnosticCodes.ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS)
}

// --- FirSuperReceiverChecker: UNEXPECTED_SAFE_CALL ---

/** UNEXPECTED_SAFE_CALL: `super?.f()`; `super` is never null. Reported on the `?.`. */
internal fun safeCallOnSuper(q: KtSafeQualifiedExpression): Diagnostic? {
    if (q.receiverExpression !is KtSuperExpression) return null
    val op = q.operationTokenNode
    return error(op, "Safe call is not allowed here", KotlinDiagnosticCodes.UNEXPECTED_SAFE_CALL)
}

// --- FirDefinitelyNotNullableChecker: NULLABLE_ON_DEFINITELY_NOT_NULLABLE ---

/** NULLABLE_ON_DEFINITELY_NOT_NULLABLE: `(T & Any)?`, a definitely-non-nullable type made nullable again. */
internal fun nullableOnDefinitelyNotNullable(t: KtNullableType): Diagnostic? {
    // `(T & Any)?` keeps its parentheses as a nested type reference.
    var inner: KtElement? = t.children.firstOrNull { it is KtTypeReference || it is KtTypeElement } as? KtElement
    while (inner is KtTypeReference) inner = inner.typeElement as? KtElement
    if (inner !is KtIntersectionType) return null
    return error(t, "Definitely non-nullable type cannot be nullable", KotlinDiagnosticCodes.NULLABLE_ON_DEFINITELY_NOT_NULLABLE)
}

// --- FirAnnotationExpressionChecker (FirExpressionEvaluator): control flow and type-parameter class literals ---

/**
 * ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED: an `if` or `when` inside an annotation argument, which the
 * constant evaluator does not fold. ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR: `T::class` or
 * `Array<T>::class` (arrays nested to any depth) of a type parameter in an annotation argument. The walk follows
 * the shapes a constant is built of (operators, parentheses, array factories and literals) and nothing else.
 */
internal fun annotationArgumentShapes(entry: KtAnnotationEntry, resolver: KotlinResolver): List<Diagnostic> {
    val args = entry.valueArguments
    if (args.isEmpty()) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    fun walk(e: dev.ide.kotlin.syntax.psi.KtExpression?, depth: Int) {
        if (e == null || depth > 32) return
        when (e) {
            is dev.ide.kotlin.syntax.psi.KtIfExpression, is KtWhenExpression ->
                out += error(e, "Control flow is not supported in annotation arguments", KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED)
            is KtParenthesizedExpression -> walk(e.expression, depth + 1)
            is KtBinaryExpression -> { walk(e.left, depth + 1); walk(e.right, depth + 1) }
            is KtPrefixExpression -> walk(e.baseExpression, depth + 1)
            is dev.ide.kotlin.syntax.psi.KtCollectionLiteralExpression -> e.innerExpressions.forEach { walk(it, depth + 1) }
            is dev.ide.kotlin.syntax.psi.KtCallExpression -> {
                val name = (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (name != null && name in ARRAY_FACTORIES) e.valueArguments.forEach { walk(it.getArgumentExpression(), depth + 1) }
            }
            is dev.ide.kotlin.syntax.psi.KtClassLiteralExpression -> {
                val lhs = e.receiverExpression?.text?.filterNot { it.isWhitespace() } ?: return
                val m = ARRAY_OF_NAME.matchEntire(lhs) ?: return
                val name = m.groupValues[2]
                if (m.groupValues[1].length != m.groupValues[3].length * "Array<".length) return
                if (!resolver.isTypeParameterInScope(name, e.textRange.startOffset)) return
                out += error(e, "Class literal of a type parameter is not allowed in an annotation argument",
                    KotlinDiagnosticCodes.ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR)
            }
            else -> {}
        }
    }
    for (a in args) walk(a.getArgumentExpression(), 0)
    return out
}

private val ARRAY_FACTORIES = setOf("arrayOf", "intArrayOf", "longArrayOf", "shortArrayOf", "byteArrayOf", "charArrayOf",
    "floatArrayOf", "doubleArrayOf", "booleanArrayOf", "emptyArray")
private val ARRAY_OF_NAME = Regex("""((?:Array<)*)([A-Za-z_][A-Za-z0-9_]*)(>*)""")

// --- FirReifiedChecker: arrays of type parameters and definitely-non-nullable types as reified arguments ---

/**
 * TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR: an explicit type argument of a `reified` type parameter that is an array
 * (`Array<T>`, nested, or a type alias of this file that expands to one) of a non-reified type parameter.
 * DEFINITELY_NON_NULLABLE_AS_REIFIED: an explicit `T & Any` there. The callee is a project function (its
 * parameters read off its declaration) or one of the standard library's array factories.
 */
internal fun reifiedArgumentShapes(call: dev.ide.kotlin.syntax.psi.KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    val args = call.typeArguments
    if (args.isEmpty()) return emptyList()
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    val file = call.containingKtFile
    val reified: List<Boolean> = if (name in STDLIB_REIFIED_FACTORIES && file.declarations.none { it is KtNamedFunction && it.name == name }) listOf(true)
    else {
        val fn = resolver.calleeFunctionOfCall(call) ?: return emptyList()
        if (fn.name != name) return emptyList()
        fn.typeParameters.map { it.hasModifier(KtTokens.REIFIED_KEYWORD) }
    }
    if (reified.size != args.size) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    for ((i, arg) in args.withIndex()) {
        if (!reified[i]) continue
        val ref = arg.typeReference ?: continue
        val el = ref.typeElement
        if (el is KtIntersectionType) {
            out += error(ref, "Definitely non-nullable type cannot be a reified type argument", KotlinDiagnosticCodes.DEFINITELY_NON_NULLABLE_AS_REIFIED)
            continue
        }
        val tp = arrayOfTypeParameter(el, file, call, resolver, inArray = false, depth = 0) ?: continue
        out += error(ref, "Cannot use '$tp' as reified type parameter in an array. Use a class instead", KotlinDiagnosticCodes.TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR)
    }
    return out
}

private val STDLIB_REIFIED_FACTORIES = setOf("arrayOf", "arrayOfNulls", "emptyArray")

/** The project function a call names, read off its declaration, or null when it is not one. */
private fun KotlinResolver.calleeFunctionOfCall(call: dev.ide.kotlin.syntax.psi.KtCallExpression): KtNamedFunction? =
    (calleeFunctionOf(call)?.takeIf { it.origin.fromSource }?.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction

/**
 * The name of a non-reified type parameter that [el], a type written at the call [at], holds as an array element:
 * under `Array<…>` (to any depth) or a type alias of [file] that expands to one.
 */
private fun arrayOfTypeParameter(el: KtTypeElement?, file: KtFile, at: KtElement, resolver: KotlinResolver, inArray: Boolean, depth: Int): String? {
    if (depth > 8) return null
    var t = el
    while (t is KtNullableType) t = t.innerType
    val user = t as? KtUserType ?: return null
    if (user.qualifier != null) return null
    val name = user.referencedName ?: return null
    val args = user.typeArguments
    if (args.isEmpty()) {
        if (!inArray) return null
        if (!resolver.isTypeParameterInScope(name, at.textRange.startOffset)) return null
        return name.takeIf { !reifiedTypeParameter(at, name) }
    }
    if (name == "Array" && file.declarations.none { (it is KtClassOrObject || it is dev.ide.kotlin.syntax.psi.KtTypeAlias) && (it as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == "Array" }) {
        val inner = args.singleOrNull()?.typeReference ?: return null
        return arrayOfTypeParameter(inner.typeElement, file, at, resolver, inArray = true, depth + 1)
    }
    val alias = file.declarations.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtTypeAlias && it.name == name } as? dev.ide.kotlin.syntax.psi.KtTypeAlias ?: return null
    val params = alias.typeParameters
    if (params.size != args.size) return null
    val env = HashMap<String, KtTypeReference>()
    for ((i, p) in params.withIndex()) env[p.name ?: return null] = args[i].typeReference ?: return null
    return arrayOfTypeParameterInAlias(alias.typeReference?.typeElement, env, file, at, resolver, inArray, depth + 1)
}

/** [arrayOfTypeParameter] over a type alias's right-hand side, with its parameters bound by [env] to the
 *  arguments written at the use; any other name there is a class, never the call site's type parameter. */
private fun arrayOfTypeParameterInAlias(
    el: KtTypeElement?, env: Map<String, KtTypeReference>, file: KtFile, at: KtElement, resolver: KotlinResolver,
    inArray: Boolean, depth: Int,
): String? {
    if (depth > 8) return null
    var t = el
    while (t is KtNullableType) t = t.innerType
    val user = t as? KtUserType ?: return null
    if (user.qualifier != null) return null
    val name = user.referencedName ?: return null
    env[name]?.let { bound ->
        if (user.typeArgumentList != null) return null
        return arrayOfTypeParameter(bound.typeElement, file, at, resolver, inArray, depth + 1)
    }
    if (name != "Array") return null
    val inner = user.typeArguments.singleOrNull()?.typeReference ?: return null
    return arrayOfTypeParameterInAlias(inner.typeElement, env, file, at, resolver, inArray = true, depth + 1)
}

// --- FirAnnotationClassDeclarationChecker: RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR ---

/**
 * RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR: an annotation class whose `@Target` allows EXPRESSION but
 * whose retention is not SOURCE (an absent `@Retention` means RUNTIME). Reported on the `@Retention`, or on the
 * `@Target` when there is none.
 */
internal fun restrictedRetentionForExpressionAnnotation(c: KtClass): Diagnostic? {
    if (!c.isAnnotation()) return null
    val entries = c.annotationEntries
    val target = entries.firstOrNull { it.typeReference?.text?.let { t -> t == "Target" || t == "kotlin.annotation.Target" } == true } ?: return null
    val allowsExpression = target.valueArguments.any { a ->
        a.getArgumentExpression()?.text?.filterNot { it.isWhitespace() }
            ?.let { it == "AnnotationTarget.EXPRESSION" || it == "EXPRESSION" || it == "kotlin.annotation.AnnotationTarget.EXPRESSION" } == true
    }
    if (!allowsExpression) return null
    val retention = entries.firstOrNull { it.shortName?.asString() == "Retention" }
    if (retention != null) {
        val arg = retention.valueArguments.singleOrNull()?.getArgumentExpression()?.text?.filterNot { it.isWhitespace() } ?: return null
        val value = arg.substringAfterLast('.')
        if (value != "BINARY" && value != "RUNTIME") return null
    }
    return error(retention ?: target, "Expression annotations with retention other than SOURCE are prohibited",
        KotlinDiagnosticCodes.RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR)
}

// --- FirCallableReferenceChecker / FirJavaSamInterfaceConstructorReferenceChecker ---

/**
 * CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR: `::Ann` with `Ann` a project annotation class.
 * JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE: `::Runnable`, the SAM constructor of a Java functional interface.
 * Only a receiver-less reference whose name no local, member or top-level function of this file takes over.
 */
internal fun KotlinSemanticChecks.constructorLikeReference(e: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.receiverExpression != null) return null
    val ref = e.callableReference ?: return null
    val name = ref.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val off = ref.textRange.startOffset
    if (resolver.isTypeParameterInScope(name, off)) return null
    if (resolver.localsAt(off, name).isNotEmpty() || resolver.enclosingClassMembersContain(off, name)) return null
    if (e.containingKtFile.declarations.any { it is KtNamedFunction && it.name == name }) return null
    val fqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(e))?.takeIf { service.isKnownType(it) } ?: return null
    service.sourceClass(fqn)?.let { rc ->
        if (!rc.isAnnotation) return null
        return error(ref, "Callable reference to annotation constructor", KotlinDiagnosticCodes.CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR)
    }
    if (fqn in JAVA_SAM_INTERFACES) {
        return error(ref, "Callable reference to the constructor of a Java SAM interface is prohibited", KotlinDiagnosticCodes.JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE)
    }
    return null
}

/** Java functional interfaces of `java.base` whose SAM constructor a reference may name. */
private val JAVA_SAM_INTERFACES = setOf(
    "java.lang.Runnable", "java.util.concurrent.Callable", "java.util.function.Function",
    "java.util.function.Supplier", "java.util.function.Consumer", "java.util.function.Predicate",
    "java.util.function.BiFunction", "java.util.function.BiConsumer", "java.util.function.UnaryOperator",
    "java.util.function.BinaryOperator",
)

// --- FirImplicitNothingReturnTypeChecker: ABBREVIATED_NOTHING_RETURN_TYPE / ABBREVIATED_NOTHING_PROPERTY_TYPE ---

/** A written return or property type that is a project type alias expanding to `Nothing`, which hides from
 *  the reader that the declaration never returns. Not on overrides or local properties. Reported on the name. */
internal fun KotlinSemanticChecks.abbreviatedNothingType(d: dev.ide.kotlin.syntax.psi.KtCallableDeclaration, resolver: KotlinResolver): Diagnostic? {
    if (d !is KtProperty && d !is KtNamedFunction) return null
    if (d.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    if (d is KtProperty && d.isLocal) return null
    val ref = d.typeReference ?: return null
    val user = ref.typeElement as? KtUserType ?: return null
    if (user.qualifier != null || user.typeArgumentList != null) return null
    val name = user.referencedName ?: return null
    if (name == "Nothing" || !service.isProjectTypeAlias(name)) return null
    if (service.typeAliasChainTargetFqn(name, resolver.fileContext) != "kotlin.Nothing") return null
    val at = d.nameIdentifier ?: return null
    return if (d is KtProperty) error(at, "Type alias of 'Nothing' as a property type", KotlinDiagnosticCodes.ABBREVIATED_NOTHING_PROPERTY_TYPE)
    else error(at, "Type alias of 'Nothing' as a return type", KotlinDiagnosticCodes.ABBREVIATED_NOTHING_RETURN_TYPE)
}

// --- FirPackageConflictsWithClassifierChecker ---

/** PACKAGE_CONFLICTS_WITH_CLASSIFIER: a package `a.b` (or a prefix of it) that is also the name of a project
 *  top-level class (`b` in package `a`). A whole-file check, run from [KotlinSemanticChecks.fileLevelDiagnostics]. */
internal fun KotlinSemanticChecks.packageConflictsWithClassifier(p: dev.ide.kotlin.syntax.psi.KtPackageDirective): Diagnostic? {
    val name = p.qualifiedName
    if (name.isEmpty()) return null
    val segs = name.split('.')
    for (i in segs.size downTo 1) {
        val fqn = segs.take(i).joinToString(".")
        val parent = segs.take(i - 1).joinToString(".")
        val rc = service.sourceClass(fqn) ?: continue
        if (rc.isLocal || rc.ctx.packageName != parent) continue
        val at = p.children.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtExpression } as? KtElement ?: p
        return error(at, "Package '$name' conflicts with the class '$fqn'", KotlinDiagnosticCodes.PACKAGE_CONFLICTS_WITH_CLASSIFIER)
    }
    return null
}

// --- FirSealedSupertypeChecker: SEALED_INHERITOR_IN_DIFFERENT_PACKAGE ---

/** SEALED_INHERITOR_IN_DIFFERENT_PACKAGE: a class extending a project sealed class (directly or through a type
 *  alias) declared in another package. */
internal fun KotlinSemanticChecks.sealedInheritorInDifferentPackage(entry: KtSuperTypeListEntry, resolver: KotlinResolver): Diagnostic? {
    val cls = entry.parent?.parent as? KtClassOrObject ?: return null
    if (cls.parent !is KtFile && cls.parent !is dev.ide.kotlin.syntax.psi.KtClassBody) return null
    val ref = entry.typeReference ?: return null
    val fqn = classifierFqnOf(ref, resolver) ?: return null
    val rc = service.sourceClass(fqn) ?: return null
    if (!rc.isSealed || rc.isLocal) return null
    if (rc.ctx.packageName == resolver.fileContext.packageName) return null
    return error(ref, "Inheritor of a sealed class or interface declared in a different package", KotlinDiagnosticCodes.SEALED_INHERITOR_IN_DIFFERENT_PACKAGE)
}

// --- shared: project-source class declarations reached through a supertype list ---

/** The project-source declaration of the class [ref] names, or null for a library, local or unknown type. */
private fun KotlinSemanticChecks.sourceClassOf(ref: KtTypeReference?, resolver: KotlinResolver): KtClassOrObject? {
    ref ?: return null
    val fqn = classifierFqnOf(ref, resolver) ?: return null
    val rc = service.sourceClass(fqn) ?: return null
    if (rc.isLocal) return null
    return (rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject
}

/** Whether [cls] is declared in the file of [from]: a supertype written in another file is resolved against
 *  that file's imports, which this file's resolver does not have. */
private fun sameFile(cls: KtClassOrObject, from: KtElement): Boolean = cls.containingFile === from.containingFile

// --- FirDelegationChecks (FirDelegatedMemberChecker): final and var members met by delegation ---

/**
 * OVERRIDING_FINAL_MEMBER_BY_DELEGATION: `class C : Base(), I by d` where a member of `I` is also a final member
 * of `Base` (or its superclasses): the delegate would override it. VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION: a `val` of
 * `I` delegated where another supertype declares that property as a `var`. Members `C` declares itself are not
 * delegated. Only project classes of this file are read, through their declarations.
 */
internal fun KotlinSemanticChecks.delegatedMemberClashes(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    val entries = cls.superTypeListEntries
    val delegated = entries.filterIsInstance<dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry>()
    if (delegated.isEmpty() || entries.size < 2) return emptyList()
    val own = HashSet<String>()
    cls.declarations.forEach { d -> memberKey(d)?.let { own += it } }
    cls.primaryConstructorParameters.forEach { p -> if (p.hasValOrVar()) p.name?.let { own += "p:$it" } }
    val at = cls.nameIdentifier ?: return emptyList()
    // Members of each non-delegated supertype, by key: (declaration, is it final in its class).
    val others = HashMap<String, MutableList<KtElement>>()
    for (e in entries) {
        if (e in delegated) continue
        val c = sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, cls) } ?: continue
        collectMembers(c, resolver, others, HashSet(), 0)
    }
    var finalClash = false
    var varClash = false
    for (d in delegated) {
        val i = sourceClassOf(d.typeReference, resolver)?.takeIf { sameFile(it, cls) && it is dev.ide.kotlin.syntax.psi.KtClass && it.isInterface() } ?: continue
        val mine = HashMap<String, MutableList<KtElement>>()
        collectMembers(i, resolver, mine, HashSet(), 0)
        for ((key, decls) in mine) {
            if (key in own) continue
            val theirs = others[key] ?: continue
            if (theirs.any { isFinalMember(it) }) finalClash = true
            if (key.startsWith("p:") && decls.all { (it as? KtProperty)?.isVar == false || (it as? KtParameter)?.isMutable == false } &&
                theirs.any { (it as? KtProperty)?.isVar == true || (it as? KtParameter)?.isMutable == true }
            ) varClash = true
        }
    }
    val out = ArrayList<Diagnostic>(2)
    if (finalClash) out += error(at, "Delegation overrides a final member of a supertype", KotlinDiagnosticCodes.OVERRIDING_FINAL_MEMBER_BY_DELEGATION)
    if (varClash) out += error(at, "A 'var' property is overridden by a 'val' implemented by delegation", KotlinDiagnosticCodes.VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION)
    return out
}

/** `f:name/arity` for a function, `p:name` for a property; null for anything else or a member of `Any`. */
private fun memberKey(d: KtElement): String? = when (d) {
    is KtNamedFunction -> d.name?.takeIf { d.receiverTypeReference == null && it !in ANY_MEMBERS }?.let { "f:$it/${d.valueParameters.size}" }
    is KtProperty -> d.name?.takeIf { d.receiverTypeReference == null }?.let { "p:$it" }
    is KtParameter -> d.name?.takeIf { d.hasValOrVar() }?.let { "p:$it" }
    else -> null
}

private val ANY_MEMBERS = setOf("equals", "hashCode", "toString")

/** Every member of [c] and of its project supertypes of this file, by [memberKey]; a member declared nearer
 *  hides the same key further up. */
private fun KotlinSemanticChecks.collectMembers(
    c: KtClassOrObject, resolver: KotlinResolver, out: MutableMap<String, MutableList<KtElement>>, seen: MutableSet<KtClassOrObject>, depth: Int,
) {
    if (depth > 8 || !seen.add(c)) return
    val here = HashMap<String, MutableList<KtElement>>()
    c.declarations.forEach { d -> if (d !is KtPropertyAccessor) memberKey(d)?.let { here.getOrPut(it) { ArrayList() } += d } }
    c.primaryConstructorParameters.forEach { p -> memberKey(p)?.let { here.getOrPut(it) { ArrayList() } += p } }
    for ((k, v) in here) if (k !in out) out[k] = v
    for (e in c.superTypeListEntries) {
        val s = sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, c) } ?: continue
        val up = HashMap<String, MutableList<KtElement>>()
        collectMembers(s, resolver, up, seen, depth + 1)
        for ((k, v) in up) if (k !in here) out.getOrPut(k) { ArrayList() } += v
    }
}

/** A member that cannot be overridden: declared in a class (not an interface) without `open`, `abstract` or
 *  `override`, or declared `final`. */
private fun isFinalMember(d: KtElement): Boolean {
    val decl = d as? dev.ide.kotlin.syntax.psi.KtModifierListOwner ?: return false
    if (decl.hasModifier(KtTokens.FINAL_KEYWORD)) return true
    val owner = (if (d is KtParameter) d.parent?.parent?.parent else d.parent?.parent) as? KtClassOrObject ?: return false
    if (owner is dev.ide.kotlin.syntax.psi.KtClass && owner.isInterface()) return false
    return !decl.hasModifier(KtTokens.OPEN_KEYWORD) && !decl.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !decl.hasModifier(KtTokens.OVERRIDE_KEYWORD)
}

// --- FirMultipleDefaultsInheritedFromSupertypesChecker ---

/**
 * MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES: a member function whose parameter has no default of its own but
 * would inherit one from two unrelated supertypes' declarations (`interface X { fun foo(a: Int = 1) }`,
 * `interface Y { fun foo(a: Int = 1) }`, `class Z : X, Y { override fun foo(a: Int) }`). Reported on each such
 * parameter. Only supertypes that are project classes of this file are read; their functions are matched by
 * name and parameter types as written.
 */
internal fun KotlinSemanticChecks.multipleInheritedDefaults(f: KtNamedFunction, resolver: KotlinResolver): List<Diagnostic> {
    val params = f.valueParameters
    if (params.isEmpty() || f.receiverTypeReference != null) return emptyList()
    val cls = (f.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent as? KtClassOrObject ?: return emptyList()
    val entries = cls.superTypeListEntries
    if (entries.size < 2) return emptyList()
    val name = f.name ?: return emptyList()
    val sig = params.map { it.typeReference?.text?.filterNot { c -> c.isWhitespace() } ?: return emptyList() }
    // For each direct supertype, the declaration of `name(sig)` it brings (its own or inherited).
    val found = ArrayList<KtNamedFunction>()
    for (e in entries) {
        val s = sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, cls) } ?: continue
        if ((e.typeReference?.typeElement as? KtUserType)?.typeArgumentList != null) continue
        // Only the supertype's own declaration counts: a default inherited through an intermediate supertype is
        // MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES_DEPRECATION_WARNING, a warning.
        val d = findFunction(s, name, sig, resolver, HashSet(), 0)?.takeIf { (it.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent === s } ?: continue
        if (found.none { it === d }) found += d
    }
    if (found.size < 2) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    for ((i, p) in params.withIndex()) {
        if (p.defaultValue != null) continue
        val withDefault = found.filter { it.valueParameters.getOrNull(i)?.defaultValue != null }
        if (withDefault.size < 2) continue
        out += error(p, "More than one overridden descriptor declares a default value for '${p.name}'", KotlinDiagnosticCodes.MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES)
    }
    return out
}

/** The function `name(sig)` that [c] declares, or the one it inherits from its first project supertype that
 *  has it; null when none or when two supertypes of [c] bring different ones (then [c] itself must decide). */
private fun KotlinSemanticChecks.findFunction(
    c: KtClassOrObject, name: String, sig: List<String>, resolver: KotlinResolver, seen: MutableSet<KtClassOrObject>, depth: Int,
): KtNamedFunction? {
    if (depth > 6 || !seen.add(c)) return null
    c.declarations.firstOrNull { it is KtNamedFunction && it.name == name && it.receiverTypeReference == null && it.valueParameters.size == sig.size &&
        it.valueParameters.map { p -> p.typeReference?.text?.filterNot { ch -> ch.isWhitespace() } } == sig
    }?.let { return it as KtNamedFunction }
    var hit: KtNamedFunction? = null
    for (e in c.superTypeListEntries) {
        val s = sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, c) } ?: continue
        val d = findFunction(s, name, sig, resolver, seen, depth + 1) ?: continue
        if (hit != null && hit !== d) return null
        hit = d
    }
    return hit
}

// --- FirCallableReferenceChecker (raw FIR): SAFE_CALLABLE_REFERENCE_CALL ---

/**
 * SAFE_CALLABLE_REFERENCE_CALL: `x?::foo`, a `?` after an expression on the left of `::` (reserved syntax). The
 * left side is known to be an expression, never a nullable type (`String?::length` is legal): a call with
 * parentheses, or the name of a local or of a member of an enclosing class, which K2 reads before a class.
 */
internal fun KotlinSemanticChecks.safeCallableReferenceCall(e: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val lhs = e.receiverExpression ?: return null
    // The `?` is a token between the left side and `::`, which the element's children do not list.
    val after = e.text.substring((lhs.textRange.endOffset - e.textRange.startOffset).coerceIn(0, e.textLength))
    if (!after.trimStart().startsWith("?")) return null
    val expression = when (lhs) {
        is dev.ide.kotlin.syntax.psi.KtCallExpression -> lhs.valueArgumentList != null
        is KtNameReferenceExpression -> {
            val name = lhs.getReferencedName()
            val off = lhs.textRange.startOffset
            // A local or a member property is read before a class of that name.
            name.isNotEmpty() && (resolver.localsAt(off, name).isNotEmpty() || resolver.enclosingClassMembersContain(off, name))
        }
        else -> false
    }
    if (!expression) return null
    return error(e, "This syntax is reserved for future use", KotlinDiagnosticCodes.SAFE_CALLABLE_REFERENCE_CALL)
}

/**
 * UNSAFE_CALLABLE_REFERENCE: `x?::foo` (see [safeCallableReferenceCall]) with `x` of a nullable type, where every
 * `foo` is an extension function of this file declared on a non-nullable receiver class, so none can take `x`.
 * Reported on `foo`.
 */
internal fun KotlinSemanticChecks.unsafeCallableReference(e: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    safeCallableReferenceCall(e, resolver) ?: return null
    val lhs = e.receiverExpression ?: return null
    val ref = e.callableReference ?: return null
    val name = ref.getReferencedName()
    val type = resolver.inferType(lhs) ?: return null
    if (!type.nullable || type.isTypeParameter) return null
    val fns = e.containingKtFile.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
    if (fns.isEmpty() || fns.any { it !is KtNamedFunction || it.typeParameters.isNotEmpty() }) return null
    for (f in fns) {
        val recv = (f as KtNamedFunction).receiverTypeReference ?: return null
        if (recv.typeElement !is KtUserType) return null
    }
    if (resolver.enclosingClassMembersContain(ref.textRange.startOffset, name)) return null
    if (service.membersNamed(type.qualifiedName, type.typeArguments, name).any { !it.isExtension }) return null
    return error(ref, "Only safe (?.) calls are allowed on a nullable receiver", KotlinDiagnosticCodes.UNSAFE_CALLABLE_REFERENCE)
}

// --- FirConstPropertyChecker / FirExpressionEvaluator: initializers the evaluator does not fold ---

/** Shapes of a `const val` initializer that K2's constant evaluator rejects without any resolution. */
internal object KotlinRound8Const {
    /** `a / 0` or `a % 0` with both operands integer literals (a floating division by zero is a value). */
    fun integerDivisionByZero(b: KtBinaryExpression): Boolean {
        if (b.operationToken != KtTokens.DIV && b.operationToken != KtTokens.PERC) return false
        val r = b.right ?: return false
        val l = b.left ?: return false
        return integerZero(r) && integerExpression(l)
    }

    /** `null` itself, which no constant expression may operate on. */
    fun isNullLiteral(e: KtExpression): Boolean = unwrapParen(e).let { it is KtConstantExpression && it.text == "null" }

    /** `5.mod(2)`, `5.floorDiv(3)` (extensions the evaluator does not run) and `0.div(0)`, `0.rem(0)` (a division
     *  by zero), each on an integer literal receiver. */
    fun nonConstLiteralCall(q: KtQualifiedExpression): Boolean {
        if (q !is KtDotQualifiedExpression) return false
        val call = q.selectorExpression as? KtCallExpression ?: return false
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        if (!integerExpression(q.receiverExpression)) return false
        val args = call.valueArguments
        if (args.size != 1 || call.lambdaArguments.isNotEmpty()) return false
        val arg = args[0].getArgumentExpression() ?: return false
        return when (name) {
            "mod", "floorDiv" -> integerExpression(arg)
            "div", "rem" -> integerZero(arg)
            else -> false
        }
    }

    private fun integerZero(e: KtExpression): Boolean {
        val u = unwrapParen(e)
        return u is KtConstantExpression && isIntegerLiteral(u) && u.text.trimEnd('L', 'l') == "0"
    }

    private fun isIntegerLiteral(c: KtConstantExpression): Boolean {
        val t = c.text
        return t.isNotEmpty() && t[0].isDigit() && t.none { it == '.' || it == 'e' || it == 'E' || it == 'f' || it == 'F' } &&
            !t.startsWith("0x") && !t.startsWith("0X") && !t.startsWith("0b") && !t.startsWith("0B")
    }

    /** An integer literal, a sign applied to one, or arithmetic over such. */
    private fun integerExpression(e: KtExpression): Boolean = when (val u = unwrapParen(e)) {
        is KtConstantExpression -> isIntegerLiteral(u)
        is KtPrefixExpression -> (u.operationToken == KtTokens.MINUS || u.operationToken == KtTokens.PLUS) && u.baseExpression?.let(::integerExpression) == true
        is KtBinaryExpression -> u.operationToken in INT_ARITHMETIC && u.left?.let(::integerExpression) == true && u.right?.let(::integerExpression) == true
        else -> false
    }

    private val INT_ARITHMETIC = setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC)
}

// --- FirInlineDeclarationChecker: INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE ---

/**
 * INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE: an inlined parameter of function type (not `noinline`, not
 * nullable) of an `inline` function whose default value is not a lambda, an anonymous function or a callable
 * reference (a call, a name), which cannot be inlined.
 */
internal fun invalidInlineDefault(p: KtParameter): Diagnostic? {
    val default = p.defaultValue ?: return null
    if (p.hasModifier(KtTokens.NOINLINE_KEYWORD)) return null
    val fn = (p.parent as? dev.ide.kotlin.syntax.psi.KtParameterList)?.parent as? KtNamedFunction ?: return null
    if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) return null
    if (p.typeReference?.typeElement !is KtFunctionType) return null
    val v = unwrapParen(default)
    if (v is dev.ide.kotlin.syntax.psi.KtLambdaExpression || v is KtCallableReferenceExpression || v is KtNamedFunction) return null
    if (v is KtConstantExpression && v.text == "null") return null
    if (v !is KtCallExpression && v !is KtNameReferenceExpression && v !is KtDotQualifiedExpression) return null
    return error(default, "Invalid default value for an inline functional parameter", KotlinDiagnosticCodes.INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE)
}

// --- FirInlineBodyResolvableExpressionChecker: NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE ---

/**
 * NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE: the default value of an inlined functional
 * parameter of an `inline` function naming ANOTHER inlined functional parameter of the same function.
 */
internal fun inlineParameterInInlineDefault(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    // Find the parameter whose default value holds [ref], without leaving the declaration.
    var node: KtElement? = ref.parent as? KtElement
    var param: KtParameter? = null
    while (node != null) {
        if (node is KtParameter) { param = node; break }
        if (node is KtFile || node is dev.ide.kotlin.syntax.psi.KtClassBody || node is KtNamedFunction || node is KtPropertyAccessor) return null
        node = node.parent as? KtElement
    }
    val p = param ?: return null
    val default = p.defaultValue ?: return null
    if (ref.textRange.startOffset < default.textRange.startOffset) return null
    val fn = (p.parent as? dev.ide.kotlin.syntax.psi.KtParameterList)?.parent as? KtNamedFunction ?: return null
    if (!fn.hasModifier(KtTokens.INLINE_KEYWORD) || !inlinable(p)) return null
    val name = ref.getReferencedName()
    val target = fn.valueParameters.firstOrNull { it.name == name } ?: return null
    if (target === p || !inlinable(target)) return null
    // A lambda parameter or local of that name inside the default value takes it over.
    if (resolver.localsAt(ref.textRange.startOffset, name).any { s ->
            val d = (s.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi
            d != null && d !== target
        }) return null
    return error(ref, "Usage of an inline parameter in the default value of another inline parameter is not supported",
        KotlinDiagnosticCodes.NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE)
}

/** A parameter an inline function inlines: of a (non-nullable) function type and not `noinline`. */
private fun inlinable(p: KtParameter): Boolean =
    !p.hasModifier(KtTokens.NOINLINE_KEYWORD) && p.typeReference?.typeElement is KtFunctionType

// --- FirTypeAliasExpansionChecker: TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS ---

/**
 * TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS: a type alias nested in a generic class whose expansion names
 * an `inner` class of that class (or a sibling alias that does) without the outer class's type arguments: the
 * alias has no type parameters of the outer class to supply them with. Reported on the right-hand side.
 */
internal fun typeAliasCapturesOuterTypeParameters(a: dev.ide.kotlin.syntax.psi.KtTypeAlias): Diagnostic? {
    val owner = (a.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent as? KtClass ?: return null
    if (owner.typeParameters.isEmpty()) return null
    val rhs = a.typeReference ?: return null
    if (!capturesInner(rhs.typeElement, owner, a.typeParameters.mapNotNull { it.name }.toSet(), 0)) return null
    return error(rhs, "Type alias expansion captures type parameters of the outer class", KotlinDiagnosticCodes.TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS)
}

private fun capturesInner(el: KtTypeElement?, owner: KtClass, own: Set<String>, depth: Int): Boolean {
    if (depth > 6) return false
    var t = el
    while (t is KtNullableType) t = t.innerType
    val user = t as? KtUserType ?: return false
    if (user.qualifier == null) {
        val name = user.referencedName ?: return false
        if (name !in own) {
            val decl = owner.declarations.firstOrNull { (it is KtClassOrObject || it is dev.ide.kotlin.syntax.psi.KtTypeAlias) && (it as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == name }
            if (decl is KtClass && decl.hasModifier(KtTokens.INNER_KEYWORD)) return true
            if (decl is dev.ide.kotlin.syntax.psi.KtTypeAlias && decl.typeParameters.isEmpty() &&
                capturesInner(decl.typeReference?.typeElement, owner, emptySet(), depth + 1)
            ) return true
        }
    }
    return user.typeArguments.any { capturesInner(it.typeReference?.typeElement, owner, own, depth + 1) }
}

// --- FirDelegatedPropertyChecker: DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR ---

/** DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR: a generic extension property whose delegate is built
 *  with one of the property's type parameters as an explicit type argument (`val <T> List<T>.x by D<T>()`): one
 *  delegate instance serves every receiver. Reported on the `by` clause. */
internal fun delegateUsesExtensionTypeParameter(p: KtProperty): Diagnostic? {
    if (p.receiverTypeReference == null) return null
    val names = p.typeParameters.mapNotNull { it.name }.toSet()
    if (names.isEmpty()) return null
    val delegate = p.delegate ?: return null
    val call = unwrapParen(delegate.expression ?: return null) as? KtCallExpression ?: return null
    if (call.typeArguments.none { arg -> mentions(arg.typeReference?.typeElement, names, 0) }) return null
    return error(delegate, "Delegate uses a type parameter of the extension property", KotlinDiagnosticCodes.DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR)
}

private fun mentions(el: KtTypeElement?, names: Set<String>, depth: Int): Boolean {
    if (depth > 6) return false
    var t = el
    while (t is KtNullableType) t = t.innerType
    val user = t as? KtUserType ?: return false
    if (user.qualifier == null && user.referencedName in names) return true
    return user.typeArguments.any { mentions(it.typeReference?.typeElement, names, depth + 1) }
}

// --- FirQualifiedSupertypeExtendedByOtherSupertypeChecker / super resolution: SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE ---

/** SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE: `super<A>` in an interface where `A` is a class (an interface can
 *  only reach its superinterfaces). */
internal fun KotlinSemanticChecks.superclassFromInterface(s: KtSuperExpression, resolver: KotlinResolver): Diagnostic? {
    val q = s.superTypeQualifier ?: return null
    var node: KtElement? = s.parent as? KtElement
    var owner: KtClassOrObject? = null
    while (node != null && node !is KtFile) {
        if (node is KtClassOrObject) { owner = node; break }
        node = node.parent as? KtElement
    }
    val iface = owner as? KtClass ?: return null
    if (!iface.isInterface()) return null
    val fqn = classifierFqnOf(q, resolver) ?: return null
    val isInterface = service.sourceClass(fqn)?.isInterface ?: service.isInterfaceType(fqn) ?: return null
    if (isInterface || fqn == "kotlin.Any") return null
    return error(s, "Superclass is not accessible from an interface", KotlinDiagnosticCodes.SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE)
}

// --- label resolution: AMBIGUOUS_LABEL ---

/**
 * AMBIGUOUS_LABEL: `this@L` or `super@L` where two receivers around it answer to `L`: classes named `L`, extension
 * functions named `L`, lambdas labelled `L`. An outer class's instance is out of reach past a nested (non-inner)
 * class, so the walk stops there.
 */
internal fun ambiguousLabel(e: dev.ide.kotlin.syntax.psi.KtInstanceExpressionWithLabel): Diagnostic? {
    val label = e.getLabelName() ?: return null
    if (label.isEmpty()) return null
    var count = 0
    var node: KtElement? = e.parent as? KtElement
    while (node != null && node !is KtFile) {
        when (node) {
            is KtClassOrObject -> {
                if (node.name == label) count++
                if (node is KtClass && !node.hasModifier(KtTokens.INNER_KEYWORD)) break
            }
            is KtNamedFunction -> if (node.name == label && node.receiverTypeReference != null) count++
            is dev.ide.kotlin.syntax.psi.KtLabeledExpression ->
                if (node.getLabelName() == label && node.baseExpression?.let { unwrapParen(it) } is dev.ide.kotlin.syntax.psi.KtLambdaExpression) count++
            else -> {}
        }
        node = node.parent as? KtElement
    }
    if (count < 2) return null
    val at = e.getTargetLabel() ?: e.labelQualifier ?: return null
    return error(at, "Ambiguous label", KotlinDiagnosticCodes.AMBIGUOUS_LABEL)
}

// --- FirFunInterfaceDeclarationChecker (JVM): FUNCTION_DELEGATE_MEMBER_NAME_CLASH ---

/** FUNCTION_DELEGATE_MEMBER_NAME_CLASH: a `fun interface` member property `functionDelegate` or a parameterless
 *  function `getFunctionDelegate()`, which clash with the JVM accessor of the SAM wrapper's delegate. */
internal fun functionDelegateMemberNameClash(d: dev.ide.kotlin.syntax.psi.KtCallableDeclaration): Diagnostic? {
    val cls = (d.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent as? KtClass ?: return null
    if (!cls.isInterface() || !cls.hasModifier(KtTokens.FUN_KEYWORD)) return null
    if (d.receiverTypeReference != null) return null
    val clash = when (d) {
        is KtProperty -> d.name == "functionDelegate"
        is KtNamedFunction -> d.name == "getFunctionDelegate" && d.valueParameters.isEmpty()
        else -> false
    }
    if (!clash) return null
    val at = d.nameIdentifier ?: return null
    return error(at, "Member name clashes with the function delegate of a fun interface", KotlinDiagnosticCodes.FUNCTION_DELEGATE_MEMBER_NAME_CLASH)
}

// --- FirImportsChecker: CANNOT_BE_IMPORTED ---

/** CANNOT_BE_IMPORTED: `import a.B.foo` naming a member of a project class `B` that is not an object (an instance
 *  member has no receiver to be imported with). Reported on `foo`. A whole-file check. */
internal fun KotlinSemanticChecks.membersCannotBeImported(file: KtFile): List<Diagnostic> {
    var out: ArrayList<Diagnostic>? = null
    for (imp in file.importDirectives) {
        if (imp.isAllUnder) continue
        val fq = imp.importedFqName?.asString() ?: continue
        val owner = fq.substringBeforeLast('.', "")
        val name = fq.substringAfterLast('.')
        if (owner.isEmpty()) continue
        val rc = service.sourceClass(owner) ?: continue
        if (rc.isObject || rc.isLocal) continue
        if (service.isSourceClass(fq)) continue
        val psi = (rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        val member = psi.declarations.any { (it is KtNamedFunction || it is KtProperty) && (it as dev.ide.kotlin.syntax.psi.KtNamedDeclaration).name == name } ||
            psi.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }
        if (!member) continue
        val ref = imp.importedReference ?: continue
        val last = (ref as? KtDotQualifiedExpression)?.selectorExpression ?: continue
        (out ?: ArrayList<Diagnostic>().also { out = it }) += error(last, "Cannot import '$name', functions and properties can be imported only from packages or objects",
            KotlinDiagnosticCodes.CANNOT_BE_IMPORTED)
    }
    return out ?: emptyList()
}

// --- FirCyclicTypeBoundsChecker: CYCLIC_GENERIC_UPPER_BOUND ---

/**
 * CYCLIC_GENERIC_UPPER_BOUND: type parameters of one declaration bounded by each other in a cycle (`<T : F?, F : T>`,
 * `<T : T>`, `<E : E & Any>`). Only a bound that IS a type parameter (nullable or `& Any` allowed) links two of
 * them, as in K2. A class reports each bound that leads into the cycle; a function or property reports the type
 * parameters themselves. The cycles are the ones K2's depth-first search finds, in its order.
 */
internal fun cyclicTypeBounds(owner: dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner): List<Diagnostic> {
    val params = owner.typeParameters
    if (params.isEmpty()) return emptyList()
    if (owner !is KtClass && owner !is KtNamedFunction && owner !is KtProperty) return emptyList()
    val byName = HashMap<String, dev.ide.kotlin.syntax.psi.KtTypeParameter>()
    for (p in params) byName[p.name ?: return emptyList()] = p
    // Each parameter's bounds, inline and from the `where` clause, and the parameters they name directly.
    val bounds = HashMap<dev.ide.kotlin.syntax.psi.KtTypeParameter, MutableList<KtTypeReference>>()
    for (p in params) p.extendsBound?.let { bounds.getOrPut(p) { ArrayList() } += it }
    for (c in owner.typeConstraints) {
        val subject = byName[c.subjectTypeParameterName?.getReferencedName() ?: continue] ?: continue
        c.boundTypeReference?.let { bounds.getOrPut(subject) { ArrayList() } += it }
    }
    if (bounds.isEmpty()) return emptyList()
    fun named(ref: KtTypeReference): dev.ide.kotlin.syntax.psi.KtTypeParameter? {
        var el: KtElement? = ref.typeElement as? KtElement
        while (true) {
            el = when (el) {
                is KtNullableType -> el.children.firstOrNull { it is KtTypeReference || it is KtTypeElement } as? KtElement
                is KtTypeReference -> el.typeElement as? KtElement
                is KtIntersectionType -> (el.children.firstOrNull { it is KtTypeReference } as? KtTypeReference)
                else -> break
            }
        }
        val user = el as? KtUserType ?: return null
        if (user.qualifier != null || user.typeArgumentList != null) return null
        return byName[user.referencedName ?: return null]
    }
    val processed = HashSet<dev.ide.kotlin.syntax.psi.KtTypeParameter>()
    val path = ArrayList<dev.ide.kotlin.syntax.psi.KtTypeParameter>()
    val cycles = ArrayList<List<dev.ide.kotlin.syntax.psi.KtTypeParameter>>()
    fun find(p: dev.ide.kotlin.syntax.psi.KtTypeParameter) {
        if (processed.add(p)) {
            path += p
            bounds[p].orEmpty().mapNotNull { named(it) }.toSet().forEach { find(it) }
            path.removeAt(path.size - 1)
        } else if (path.isNotEmpty()) {
            cycles += path.dropWhile { it !== p }
        }
    }
    params.forEach { find(it) }
    if (cycles.all { it.isEmpty() }) return emptyList()
    val out = ArrayList<Diagnostic>()
    val seen = HashSet<KtElement>()
    for (cycle in cycles) for (p in cycle) {
        val targets: List<KtElement> = if (owner is KtClass) bounds[p].orEmpty().filter { named(it) in cycle } else listOf(p)
        for (t in targets) if (seen.add(t)) out += error(t, "Type parameter has cyclic upper bounds", KotlinDiagnosticCodes.CYCLIC_GENERIC_UPPER_BOUND)
    }
    return out
}

// --- FirSupertypesChecker / supertype resolution loops: CYCLIC_INHERITANCE_HIERARCHY ---

/**
 * CYCLIC_INHERITANCE_HIERARCHY: a supertype of [cls] from which [cls] is reached again. Resolving a class's
 * supertypes needs the supertypes it names and, for a nested class, its outer class (whose supertypes shape the
 * nested scope), so `class E : E.Nested()` loops too. Followed only through project classes of this file.
 */
internal fun KotlinSemanticChecks.cyclicInheritance(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    val entries = cls.superTypeListEntries
    if (entries.isEmpty()) return emptyList()
    var out: ArrayList<Diagnostic>? = null
    for (e in entries) {
        val ref = e.typeReference ?: continue
        val target = sourceClassOf(ref, resolver)?.takeIf { sameFile(it, cls) } ?: continue
        if (!reaches(target, cls, resolver)) continue
        (out ?: ArrayList<Diagnostic>().also { out = it }) += error(ref, "There's a cycle in the inheritance hierarchy for this type", KotlinDiagnosticCodes.CYCLIC_INHERITANCE_HIERARCHY)
    }
    return out ?: emptyList()
}

/** Whether [goal] is [from] or is reached from it over the supertype-resolution dependencies of this file. */
private fun KotlinSemanticChecks.reaches(from: KtClassOrObject, goal: KtClassOrObject, resolver: KotlinResolver): Boolean {
    val seen = HashSet<KtClassOrObject>()
    val stack = ArrayDeque<KtClassOrObject>()
    stack += from
    while (stack.isNotEmpty()) {
        val c = stack.removeLast()
        if (c === goal) return true
        if (!seen.add(c) || seen.size > 64) continue
        stack.addAll(supertypeDependencies(c, resolver))
    }
    return false
}

/** The classes of this file that resolving [c]'s supertypes depends on: the ones it names, and its outer class. */
private fun KotlinSemanticChecks.supertypeDependencies(c: KtClassOrObject, resolver: KotlinResolver): List<KtClassOrObject> =
    round8Memo.supertypeDeps.getOrPut(c) {
        val out = ArrayList<KtClassOrObject>(2)
        for (e in c.superTypeListEntries) sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, c) }?.let { out += it }
        ((c.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent as? KtClassOrObject)?.let { out += it }
        out
    }

/** The per-pass tables of the eighth round of ported rules, cleared with [KotlinSemanticChecks.resetPassCaches]. */
internal class Round8Memo {
    val supertypeDeps = HashMap<KtClassOrObject, List<KtClassOrObject>>()

    fun clear() { supertypeDeps.clear() }
}

// --- FirImplementationMismatchChecker (intersection overrides): CANNOT_WEAKEN_ACCESS_PRIVILEGE ---

/**
 * CANNOT_WEAKEN_ACCESS_PRIVILEGE on a class that inherits an abstract public member of an interface and, for it,
 * an implementation of its superclass that is `internal` or `protected` (`class E : C(), T` with
 * `C.protected fun foo()` and `T.fun foo()`): the inherited implementation would narrow the interface's member.
 * A `var` implementing a `var` weakens its setter too, reported a second time. Members the class declares itself
 * are not inherited. Project classes of this file only.
 */
internal fun KotlinSemanticChecks.inheritedImplementationWeakensAccess(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    val entries = cls.superTypeListEntries
    if (entries.size < 2) return emptyList()
    val superEntry = entries.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry } ?: return emptyList()
    val superClass = sourceClassOf(superEntry.typeReference, resolver)?.takeIf { sameFile(it, cls) } ?: return emptyList()
    if (superClass is KtClass && superClass.isInterface()) return emptyList()
    val own = HashSet<String>()
    cls.declarations.forEach { d -> memberKey(d)?.let { own += it } }
    cls.primaryConstructorParameters.forEach { p -> if (p.hasValOrVar()) p.name?.let { own += "p:$it" } }
    val fromClass = HashMap<String, MutableList<KtElement>>()
    collectMembers(superClass, resolver, fromClass, HashSet(), 0)
    var count = 0
    val done = HashSet<String>()
    for (e in entries) {
        if (e === superEntry || e is dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry) continue
        val iface = sourceClassOf(e.typeReference, resolver)?.takeIf { sameFile(it, cls) && it is KtClass && it.isInterface() } ?: continue
        val fromInterface = HashMap<String, MutableList<KtElement>>()
        collectMembers(iface, resolver, fromInterface, HashSet(), 0)
        for ((key, ifaceDecls) in fromInterface) {
            if (key in own || key in done) continue
            val classDecl = fromClass[key]?.singleOrNull() ?: continue
            val ifaceDecl = ifaceDecls.singleOrNull() ?: continue
            if (!abstractInterfaceMember(ifaceDecl) || !weakerImplementation(classDecl)) continue
            done += key
            count++
            if ((classDecl as? KtProperty)?.isVar == true && (ifaceDecl as? KtProperty)?.isVar == true) count++
        }
    }
    if (count == 0) return emptyList()
    val at: KtElement = cls.nameIdentifier
        ?: cls.children.firstOrNull { it.elementType == KtTokens.OBJECT_KEYWORD } as? KtElement ?: return emptyList()
    return List(count) { error(at, "Cannot weaken access privilege of an inherited member", KotlinDiagnosticCodes.CANNOT_WEAKEN_ACCESS) }
}

/** A public interface member without a body: a function without one, or a property without accessor bodies. */
private fun abstractInterfaceMember(d: KtElement): Boolean = when (d) {
    is KtNamedFunction -> !d.hasBody() && !d.hasModifier(KtTokens.PRIVATE_KEYWORD) && !d.hasModifier(KtTokens.INTERNAL_KEYWORD)
    is KtProperty -> d.accessors.none { it.hasBody() } && !d.hasModifier(KtTokens.INTERNAL_KEYWORD) && !d.hasModifier(KtTokens.PRIVATE_KEYWORD)
    else -> false
}

/** A class member with an implementation whose visibility is `internal` or `protected`. */
private fun weakerImplementation(d: KtElement): Boolean {
    val decl = d as? dev.ide.kotlin.syntax.psi.KtModifierListOwner ?: return false
    if (decl.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false
    if (d is KtNamedFunction && !d.hasBody()) return false
    return decl.hasModifier(KtTokens.INTERNAL_KEYWORD) || decl.hasModifier(KtTokens.PROTECTED_KEYWORD)
}

// --- for-loop conventions (FirForLoopChecker): the iterator, hasNext() and next() of a project class ---

/**
 * The `for` loop conventions over a range whose class is a project class of this file: ITERATOR_ON_NULLABLE when
 * the range is nullable (and not smart-cast) while its class has an `iterator()` for a non-null receiver only;
 * HAS_NEXT_MISSING / NEXT_MISSING when the iterator's class (a project class of this file, or `Unit`) has no
 * `operator fun hasNext()` / `next()`; HAS_NEXT_FUNCTION_TYPE_MISMATCH when its `hasNext()` does not return
 * `Boolean`. A file that declares a top-level function of one of those names may supply it as an extension, and
 * is left alone. Reported on the range.
 */
internal fun KotlinSemanticChecks.forLoopConventions(loop: dev.ide.kotlin.syntax.psi.KtForExpression, resolver: KotlinResolver): List<Diagnostic> {
    val range = loop.loopRange ?: return emptyList()
    val file = loop.containingKtFile
    if (file.declarations.any { it is KtNamedFunction && it.name in LOOP_OPERATORS && it.receiverTypeReference == null }) return emptyList()
    val type = resolver.inferType(range) ?: return emptyList()
    if (type.isTypeParameter) return emptyList()
    val rc = service.sourceClass(type.qualifiedName) ?: return emptyList()
    val cls = (rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: return emptyList()
    if (!sameFile(cls, loop) || !service.supertypeClosureKnown(type.qualifiedName)) return emptyList()
    val extensions = file.declarations.filter { it is KtNamedFunction && it.name in LOOP_OPERATORS && it.receiverTypeReference != null }
    val members = HashMap<String, MutableList<KtElement>>()
    collectMembers(cls, resolver, members, HashSet(), 0)
    val iterator = members["f:iterator/0"]?.singleOrNull() as? KtNamedFunction
    val extIterators = extensions.filter { (it as KtNamedFunction).name == "iterator" }
    if (type.nullable) {
        val unwrapped = unwrapParen(range)
        if (unwrapped is KtNameReferenceExpression && resolver.flowProvenTypes(unwrapped, deep = true).isNotEmpty()) return emptyList()
        if (extIterators.any { (it as KtNamedFunction).receiverTypeReference?.text?.trimEnd()?.endsWith("?") != false }) return emptyList()
        if (iterator == null && extIterators.isEmpty()) return emptyList()
        return listOf(error(range, "Not nullable value required to call an 'iterator()' method on for-loop range", KotlinDiagnosticCodes.ITERATOR_ON_NULLABLE))
    }
    if (iterator == null || extIterators.isNotEmpty() || extensions.isNotEmpty()) return emptyList()
    if (!iterator.hasModifier(KtTokens.OPERATOR_KEYWORD) || iterator.typeParameters.isNotEmpty()) return emptyList()
    val retRef = iterator.typeReference ?: return emptyList()
    val retFqn = classifierFqnOf(retRef, resolver) ?: return emptyList()
    val out = ArrayList<Diagnostic>(2)
    if (retFqn == "kotlin.Unit") {
        out += error(range, "hasNext() cannot be called on iterator() of type 'Unit'", KotlinDiagnosticCodes.HAS_NEXT_MISSING)
        out += error(range, "next() cannot be called on iterator() of type 'Unit'", KotlinDiagnosticCodes.NEXT_MISSING)
        return out
    }
    val it = sourceClassOf(retRef, resolver)?.takeIf { sameFile(it, loop) } ?: return emptyList()
    if ((retRef.typeElement as? KtUserType)?.typeArgumentList != null || !service.supertypeClosureKnown(retFqn)) return emptyList()
    val itMembers = HashMap<String, MutableList<KtElement>>()
    collectMembers(it, resolver, itMembers, HashSet(), 0)
    val hasNext = itMembers["f:hasNext/0"]?.filterIsInstance<KtNamedFunction>()
    val next = itMembers["f:next/0"]?.filterIsInstance<KtNamedFunction>()
    if (hasNext.isNullOrEmpty()) {
        out += error(range, "hasNext() cannot be called on iterator()", KotlinDiagnosticCodes.HAS_NEXT_MISSING)
    } else if (hasNext.size == 1) {
        val h = hasNext[0]
        val rt = h.typeReference?.let { classifierFqnOf(it, resolver) }
        if (h.hasModifier(KtTokens.OPERATOR_KEYWORD) && rt != null && rt != "kotlin.Boolean" && h.typeReference?.typeElement is KtUserType) {
            out += error(range, "hasNext() function of iterator() must return 'Boolean'", KotlinDiagnosticCodes.HAS_NEXT_FUNCTION_TYPE_MISMATCH)
        }
    }
    if (next.isNullOrEmpty()) out += error(range, "next() cannot be called on iterator()", KotlinDiagnosticCodes.NEXT_MISSING)
    return out
}

private val LOOP_OPERATORS = setOf("iterator", "hasNext", "next")


// --- FirExternalDeclarationChecker / FirExpectActualDeclarationChecker shapes ---

/**
 * `external` declarations: one cannot be `abstract` (EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT, reported on
 * `abstract`), cannot sit in an interface (EXTERNAL_DECLARATION_IN_INTERFACE, on `external`) and cannot have a body
 * (EXTERNAL_DECLARATION_CANNOT_HAVE_BODY, on `external`). An accessor marked `external` makes its property one.
 */
internal fun externalDeclarationShape(d: dev.ide.kotlin.syntax.psi.KtDeclaration): Diagnostic? {
    val ext = d.modifierList?.getModifier(KtTokens.EXTERNAL_KEYWORD) ?: return null
    val (fn, prop) = when (d) {
        is KtNamedFunction -> d to null
        is KtPropertyAccessor -> null to (d.property ?: return null)
        else -> return null
    }
    val owner = ((fn ?: prop)?.parent as? dev.ide.kotlin.syntax.psi.KtClassBody)?.parent as? KtClassOrObject
    if (owner is KtClass && owner.isInterface()) {
        return error(ext, "External declaration cannot be declared in an interface", KotlinDiagnosticCodes.EXTERNAL_DECLARATION_IN_INTERFACE)
    }
    val abstract = (fn ?: prop)?.modifierList?.getModifier(KtTokens.ABSTRACT_KEYWORD)
    if (abstract != null) return error(abstract, "External declaration cannot be abstract", KotlinDiagnosticCodes.EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT)
    val hasBody = if (fn != null) fn.hasBody() else (d as KtPropertyAccessor).hasBody()
    if (hasBody) return error(ext, "External declaration cannot have a body", KotlinDiagnosticCodes.EXTERNAL_DECLARATION_CANNOT_HAVE_BODY)
    return null
}

/** EXPECTED_DECLARATION_WITH_BODY: an `expect fun` with a body. EXPECTED_PROPERTY_INITIALIZER: an `expect val` with
 *  an initializer, reported on the initializer. */
internal fun expectedDeclarationShape(d: dev.ide.kotlin.syntax.psi.KtDeclaration): Diagnostic? {
    if (!d.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
    return when (d) {
        is KtNamedFunction -> if (d.hasBody()) {
            val end = d.valueParameterList?.textRange?.endOffset ?: d.textRange.endOffset
            Diagnostic(TextRange(d.textRange.startOffset, end), Severity.ERROR, "Expected declaration must not have a body", KotlinDiagnosticCodes.EXPECTED_DECLARATION_WITH_BODY)
        } else null
        is KtProperty -> d.initializer?.let { error(it, "Expected property cannot have an initializer", KotlinDiagnosticCodes.EXPECTED_PROPERTY_INITIALIZER) }
        else -> null
    }
}

/** EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE: a top-level `expect class X` and `actual class X` of one file, which this
 *  single-module analysis sees together. Reported on each name. */
internal fun expectAndActualTogether(c: KtClassOrObject): Diagnostic? {
    if (c.parent !is KtFile) return null
    val isExpect = c.hasModifier(KtTokens.EXPECT_KEYWORD)
    if (!isExpect && !c.hasModifier(KtTokens.ACTUAL_KEYWORD)) return null
    val name = c.name ?: return null
    val other = if (isExpect) KtTokens.ACTUAL_KEYWORD else KtTokens.EXPECT_KEYWORD
    if ((c.parent as KtFile).declarations.none { it is KtClassOrObject && it.name == name && it.hasModifier(other) }) return null
    val at = c.nameIdentifier ?: return null
    return error(at, "'$name' has both expect and actual declarations in one module", KotlinDiagnosticCodes.EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE)
}

// --- FirInvalidAndDangerousCharactersChecker: INVALID_CHARACTERS in a package name ---

/** INVALID_CHARACTERS: a package name segment (backquoted) holding a character the JVM does not allow in a
 *  name: `.`, `;`, `[`, `]`, `/`, `<`, `>`, `:` or `\`. A whole-file check. */
internal fun invalidPackageCharacters(file: KtFile): Diagnostic? {
    val p = file.packageDirective ?: return null
    val expr = p.children.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtExpression } as? KtElement ?: return null
    val text = expr.text
    if ('`' !in text) return null
    val quoted = Regex("`([^`]*)`").findAll(text).map { it.groupValues[1] }
    if (quoted.none { s -> s.any { it in INVALID_NAME_CHARS } }) return null
    return error(expr, "Name contains illegal characters", KotlinDiagnosticCodes.INVALID_CHARACTERS)
}

private val INVALID_NAME_CHARS = setOf('.', ';', '[', ']', '/', '<', '>', ':', '\\')

// --- FirDeprecatedSinceKotlinAnnotationsChecker (outside the kotlin package) ---

/** DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE: `@DeprecatedSinceKotlin` in a file outside `kotlin.*`, where
 *  the annotation means nothing. Reported on its name. */
internal fun deprecatedSinceKotlinOutsideKotlin(entry: KtAnnotationEntry): Diagnostic? {
    val user = entry.typeReference?.typeElement as? KtUserType ?: return null
    val text = user.text.filterNot { it.isWhitespace() }
    if (text != "DeprecatedSinceKotlin" && text != "kotlin.DeprecatedSinceKotlin") return null
    val file = entry.containingKtFile
    val pkg = file.packageDirective?.qualifiedName.orEmpty()
    if (pkg == "kotlin" || pkg.startsWith("kotlin.")) return null
    if (text == "DeprecatedSinceKotlin") {
        if (file.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == "DeprecatedSinceKotlin" }) return null
        if (file.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == "DeprecatedSinceKotlin" }) return null
    }
    val at = user.referenceExpression ?: return null
    return error(at, "'@DeprecatedSinceKotlin' is only allowed in the 'kotlin' package", KotlinDiagnosticCodes.DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE)
}

// --- FirPlaceholderProjectionChecker: PLACEHOLDER_PROJECTION_IN_QUALIFIER ---

/** PLACEHOLDER_PROJECTION_IN_QUALIFIER: `_` as a type argument of the type on the left of `::` (`Box<_>::property`),
 *  where nothing infers it. Reported on each `_`. */
internal fun placeholderInCallableReferenceQualifier(e: KtCallableReferenceExpression): List<Diagnostic> {
    val lhs = e.receiverExpression as? KtCallExpression ?: return emptyList()
    if (lhs.valueArgumentList != null || lhs.lambdaArguments.isNotEmpty()) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    for (arg in lhs.typeArguments) {
        if (arg.typeReference?.text?.trim() == "_") out += error(arg, "Placeholder projection '_' is not allowed in a qualifier", KotlinDiagnosticCodes.PLACEHOLDER_PROJECTION_IN_QUALIFIER)
    }
    return out
}
