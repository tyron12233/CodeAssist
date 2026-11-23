package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity

/**
 * `@Suppress("NAME")`, honoured the way the compiler honours it: a diagnostic inside a declaration, an
 * annotated expression or a file (`@file:Suppress`) that suppresses one of the K2 names it stands for is not
 * reported. K2 accepts this for errors too (with an ERROR_SUPPRESSION warning), and `"warnings"` drops every
 * warning. Our codes are coarser than K2's names, so a code is suppressed by ANY of the names it covers.
 */
internal object KotlinSuppression {

    fun filter(file: KtFile, diags: List<Diagnostic>): List<Diagnostic> {
        if (diags.isEmpty() || !file.text.contains("Suppress")) return diags
        val memo = HashMap<KtElement, Set<String>>()
        return diags.filterNot { d -> suppressed(file, d, memo) }
    }

    private fun suppressed(file: KtFile, d: Diagnostic, memo: MutableMap<KtElement, Set<String>>): Boolean {
        val names = K2_NAMES[d.code] ?: emptySet()
        val warning = d.severity == Severity.WARNING
        if (names.isEmpty() && !warning) return false
        val len = file.textLength
        if (len == 0) return false
        var node: KtElement? = file.findElementAt(d.range.start.coerceIn(0, len - 1))
        while (node != null) {
            val suppressedHere = memo.getOrPut(node) { suppressionsOn(node!!) }
            if (suppressedHere.isNotEmpty()) {
                if (names.any { it in suppressedHere } || d.code in suppressedHere) return true
                if (warning && "warnings" in suppressedHere) return true
            }
            node = node.parent as? KtElement
        }
        return false
    }

    private fun suppressionsOn(e: KtElement): Set<String> {
        val entries: List<KtAnnotationEntry> = when (e) {
            is KtFile -> e.fileAnnotationList?.annotationEntries.orEmpty() + e.annotationEntries
            is KtAnnotated -> e.annotationEntries
            else -> return emptySet()
        }
        if (entries.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (entry in entries) {
            if (entry.shortName?.asString() != "Suppress") continue
            for (arg in entry.valueArguments) {
                val expr = arg.getArgumentExpression() ?: continue
                // A constant expression names the diagnostic as well as a literal does (`"UNRESOLVED" + "_REFERENCE"`,
                // a `const val DIAG = "…"`), which K2 honours (reporting ERROR_SUPPRESSION on it besides).
                constantString(expr, 0)?.let { out += it; continue }
                STRING_LITERAL.findAll(expr.text).forEach { out += it.groupValues[1] }
            }
        }
        return out
    }

    private val STRING_LITERAL = Regex("\"([^\"]*)\"")

    /** The value of a constant string expression: a plain literal, a `+` of constants, or a `const val` of the file. */
    private fun constantString(e: dev.ide.kotlin.syntax.psi.KtExpression, depth: Int): String? {
        if (depth > 8) return null
        return when (e) {
            is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression -> e.expression?.let { constantString(it, depth + 1) }
            is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression ->
                e.text.takeIf { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") && '$' !in it && !it.startsWith("\"\"\"") }?.let { it.substring(1, it.length - 1) }
            is dev.ide.kotlin.syntax.psi.KtBinaryExpression -> if (e.operationToken == dev.ide.kotlin.syntax.psi.KtTokens.PLUS) {
                val l = e.left?.let { constantString(it, depth + 1) } ?: return null
                val r = e.right?.let { constantString(it, depth + 1) } ?: return null
                l + r
            } else null
            is dev.ide.kotlin.syntax.psi.KtNameReferenceExpression -> {
                val name = e.getReferencedName()
                val decl = e.containingKtFile.declarations.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtProperty && it.name == name } as? dev.ide.kotlin.syntax.psi.KtProperty
                decl?.takeIf { it.hasModifier(dev.ide.kotlin.syntax.psi.KtTokens.CONST_KEYWORD) }?.initializer?.let { constantString(it, depth + 1) }
            }
            else -> null
        }
    }

    /** Our code -> the K2 diagnostic names it can stand for. */
    val K2_NAMES: Map<String, Set<String>> = mapOf(
        "kt.unresolved" to setOf("UNRESOLVED_REFERENCE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_IMPORT", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER"),
        "kt.typeMismatch" to setOf("ARGUMENT_TYPE_MISMATCH", "TYPE_MISMATCH", "RETURN_TYPE_MISMATCH", "INITIALIZER_TYPE_MISMATCH", "ASSIGNMENT_TYPE_MISMATCH", "CONDITION_TYPE_MISMATCH", "NULL_FOR_NONNULL_TYPE", "CONSTANT_EXPECTED_TYPE_MISMATCH", "RESULT_TYPE_MISMATCH"),
        "kt.argumentCount" to setOf("TOO_MANY_ARGUMENTS", "NO_VALUE_FOR_PARAMETER", "NONE_APPLICABLE", "INAPPLICABLE_CANDIDATE", "ARGUMENT_TYPE_MISMATCH", "NAMED_PARAMETER_NOT_FOUND"),
        "kt.constructorArgs" to setOf("TOO_MANY_ARGUMENTS", "NO_VALUE_FOR_PARAMETER", "NONE_APPLICABLE", "INAPPLICABLE_CANDIDATE", "ARGUMENT_TYPE_MISMATCH"),
        "kt.namedArgument" to setOf("NAMED_PARAMETER_NOT_FOUND", "NAMED_ARGUMENTS_NOT_ALLOWED", "ARGUMENT_PASSED_TWICE"),
        "kt.notCallable" to setOf("FUNCTION_EXPECTED"),
        "kt.missingReturn" to setOf("NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY"),
        "kt.unsafeNullable" to setOf("UNSAFE_CALL", "UNSAFE_INFIX_CALL", "UNSAFE_OPERATOR_CALL", "UNSAFE_IMPLICIT_INVOKE_CALL"),
        "kt.smartcastImpossible" to setOf("SMARTCAST_IMPOSSIBLE", "SMARTCAST_IMPOSSIBLE_ON_IMPLICIT_INVOKE_RECEIVER"),
        "kt.memberProjectedOut" to setOf("MEMBER_PROJECTED_OUT"),
        "kt.whenExhaustive" to setOf("NO_ELSE_IN_WHEN"),
        "kt.abstractNotImplemented" to setOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED"),
        "kt.nonLocalDestructuringDeclaration" to setOf("NON_LOCAL_DESTRUCTURING_DECLARATION"),
        "kt.infixModifierRequired" to setOf("INFIX_MODIFIER_REQUIRED"),
        // Codes that had no K2 name, so an `@Suppress` with K2's name for them did nothing.
        "kt.syntax" to setOf("SYNTAX"),
        "kt.abstractModifier" to setOf(
            "ABSTRACT_FUNCTION_WITH_BODY", "ABSTRACT_PROPERTY_WITH_INITIALIZER", "ABSTRACT_DELEGATED_PROPERTY",
            "ABSTRACT_PROPERTY_WITH_GETTER", "ABSTRACT_PROPERTY_WITH_SETTER", "ABSTRACT_FUNCTION_IN_NON_ABSTRACT_CLASS",
            "ABSTRACT_PROPERTY_IN_NON_ABSTRACT_CLASS",
        ),
        "kt.assignmentInExpression" to setOf("ASSIGNMENT_IN_EXPRESSION_CONTEXT"),
        "kt.breakOrContinueOutsideLoop" to setOf("BREAK_OR_CONTINUE_OUTSIDE_A_LOOP"),
        "kt.classifierAsValue" to setOf("NO_COMPANION_OBJECT"),
        "kt.conflictingImport" to setOf("CONFLICTING_IMPORT"),
        "kt.dataClass" to setOf("DATA_CLASS_WITHOUT_PARAMETERS", "DATA_CLASS_NOT_PROPERTY_PARAMETER"),
        "kt.delegateOperator" to setOf("DELEGATE_SPECIAL_FUNCTION_MISSING"),
        "kt.destructuring" to setOf("COMPONENT_FUNCTION_MISSING"),
        "kt.duplicateWhenBranch" to setOf("DUPLICATE_BRANCH_CONDITION_IN_WHEN"),
        "kt.expectedParameterTypeMismatch" to setOf("EXPECTED_PARAMETER_TYPE_MISMATCH"),
        "kt.finalSupertype" to setOf("FINAL_SUPERTYPE"),
        "kt.forbiddenIdentityEquals" to setOf("FORBIDDEN_IDENTITY_EQUALS"),
        "kt.functionCallExpected" to setOf("FUNCTION_CALL_EXPECTED"),
        "kt.functionNoBody" to setOf("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "NON_MEMBER_FUNCTION_NO_BODY"),
        "kt.ifAsExpression" to setOf("INVALID_IF_AS_EXPRESSION"),
        "kt.impossibleIsCheck" to setOf("IMPOSSIBLE_IS_CHECK_ERROR", "IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR"),
        "kt.nameInConstraintIsNotATypeParameter" to setOf("NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER"),
        "kt.noSetOperator" to setOf("NO_SET_METHOD"),
        "kt.noThis" to setOf("NO_THIS"),
        "kt.noTypeNoInitializer" to setOf("PROPERTY_WITH_NO_TYPE_NO_INITIALIZER"),
        "kt.notIterable" to setOf("ITERATOR_MISSING"),
        "kt.redundantProjection" to setOf("REDUNDANT_PROJECTION"),
        "kt.returnValueExpected" to setOf("RETURN_TYPE_MISMATCH"),
        "kt.supertypeNotInitialized" to setOf("SUPERTYPE_NOT_INITIALIZED"),
        "kt.suspendContext" to setOf("ILLEGAL_SUSPEND_FUNCTION_CALL", "ILLEGAL_SUSPEND_PROPERTY_ACCESS"),
        "kt.suspendOverride" to setOf("SUSPEND_OVERRIDDEN_BY_NON_SUSPEND", "NON_SUSPEND_OVERRIDDEN_BY_SUSPEND"),
        "kt.valVarParameter" to setOf(
            "VAL_OR_VAR_ON_FUN_PARAMETER", "VAL_OR_VAR_ON_LOOP_PARAMETER", "VAL_OR_VAR_ON_CATCH_PARAMETER",
            "VAL_OR_VAR_ON_SECONDARY_CONSTRUCTOR_PARAMETER",
        ),
        "kt.explicitApiVisibility" to setOf("NO_EXPLICIT_VISIBILITY_IN_API_MODE"),
        "kt.explicitApiVisibilityWarning" to setOf("NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING"),
        "kt.explicitApiReturnType" to setOf("NO_EXPLICIT_RETURN_TYPE_IN_API_MODE"),
        "kt.explicitApiReturnTypeWarning" to setOf("NO_EXPLICIT_RETURN_TYPE_IN_API_MODE_WARNING"),
        "kt.inaccessibleOuterClassReceiver" to setOf("INACCESSIBLE_OUTER_CLASS_RECEIVER"),
        "kt.innerClassConstructorNoReceiver" to setOf("INNER_CLASS_CONSTRUCTOR_NO_RECEIVER"),
        "kt.enumEntryAsType" to setOf("ENUM_ENTRY_AS_TYPE"),
        "kt.isEnumEntry" to setOf("IS_ENUM_ENTRY"),
        "kt.confusingBranchCondition" to setOf("CONFUSING_BRANCH_CONDITION_ERROR"),
        "kt.nameForAmbiguousParameter" to setOf("NAME_FOR_AMBIGUOUS_PARAMETER"),
        "kt.abstractClassMemberNotImplemented" to setOf("ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED"),
        "kt.manyImplMemberNotImplemented" to setOf("MANY_IMPL_MEMBER_NOT_IMPLEMENTED"),
        "kt.manyInterfacesMemberNotImplemented" to setOf("MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED"),
        "kt.abstractInstantiation" to setOf("CREATING_AN_INSTANCE_OF_ABSTRACT_CLASS", "INTERFACE_AS_FUNCTION"),
        "kt.valReassign" to setOf("VAL_REASSIGNMENT", "VAL_REASSIGNMENT_VIA_BACKING_FIELD"),
        "kt.uninitializedVariable" to setOf("UNINITIALIZED_VARIABLE", "UNINITIALIZED_PARAMETER", "UNINITIALIZED_ENUM_ENTRY"),
        "kt.mustBeInitialized" to setOf("MUST_BE_INITIALIZED", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "MUST_BE_INITIALIZED_OR_BE_FINAL", "MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT"),
        "kt.conflictingDeclaration" to setOf("CONFLICTING_OVERLOADS", "REDECLARATION", "PACKAGE_OR_CLASSIFIER_REDECLARATION", "CLASSIFIER_REDECLARATION"),
        "kt.nothingToOverride" to setOf("NOTHING_TO_OVERRIDE"),
        "kt.overrideRequired" to setOf("VIRTUAL_MEMBER_HIDDEN"),
        "kt.overridingFinal" to setOf("OVERRIDING_FINAL_MEMBER"),
        "kt.unresolvedLabel" to setOf("UNRESOLVED_LABEL"),
        "kt.dslScopeViolation" to setOf("DSL_SCOPE_VIOLATION"),
        "kt.overloadAmbiguity" to setOf("OVERLOAD_RESOLUTION_AMBIGUITY", "AMBIGUOUS_CALL"),
        "kt.cannotInferType" to setOf("CANNOT_INFER_PARAMETER_TYPE", "CANNOT_INFER_VALUE_PARAMETER_TYPE", "NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER"),
        "kt.invisibleReference" to setOf("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "INVISIBLE_SETTER"),
        "kt.typeArgumentCount" to setOf("WRONG_NUMBER_OF_TYPE_ARGUMENTS"),
        "kt.upperBoundViolated" to setOf("UPPER_BOUND_VIOLATED", "UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION"),
        "kt.varianceConflict" to setOf("TYPE_VARIANCE_CONFLICT_ERROR", "TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE"),
        "kt.exposedVisibility" to setOf("EXPOSED_PARAMETER_TYPE", "EXPOSED_FUNCTION_RETURN_TYPE", "EXPOSED_PROPERTY_TYPE", "EXPOSED_SUPER_CLASS", "EXPOSED_SUPER_INTERFACE", "EXPOSED_RECEIVER_TYPE", "EXPOSED_TYPEALIAS_EXPANDED_TYPE"),
        "kt.modifiers" to setOf("INCOMPATIBLE_MODIFIERS", "REPEATED_MODIFIER"),
        "kt.wrongModifierTarget" to setOf("WRONG_MODIFIER_TARGET"),
        "kt.wrongModifierContainer" to setOf("WRONG_MODIFIER_CONTAINING_DECLARATION"),
        "kt.inapplicableOperator" to setOf("INAPPLICABLE_OPERATOR_MODIFIER"),
        "kt.inapplicableInfix" to setOf("INAPPLICABLE_INFIX_MODIFIER"),
        "kt.returnNotAllowed" to setOf("RETURN_NOT_ALLOWED"),
        "kt.declarationShape" to setOf("MULTIPLE_VARARG_PARAMETERS", "TYPE_PARAMETERS_IN_OBJECT", "LOCAL_OBJECT_NOT_ALLOWED", "LOCAL_INTERFACE_NOT_ALLOWED", "NESTED_CLASS_NOT_ALLOWED", "ANONYMOUS_FUNCTION_WITH_NAME", "VARIANCE_ON_TYPE_PARAMETER_NOT_ALLOWED", "NULLABLE_TYPE_OF_ANNOTATION_MEMBER", "MULTIPLE_LABELS_ARE_FORBIDDEN", "REPEATED_ANNOTATION", "PRIVATE_SETTER_FOR_OPEN_PROPERTY", "PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY"),
        "kt.lateinit" to setOf("INAPPLICABLE_LATEINIT_MODIFIER"),
        "kt.conflictingProjection" to setOf("CONFLICTING_PROJECTION"),
        "kt.wrongAnnotationTarget" to setOf("WRONG_ANNOTATION_TARGET"),
        "kt.wrongAnnotationTargetWithUseSiteTarget" to setOf("WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET"),
        "kt.notConstant" to setOf("ANNOTATION_ARGUMENT_MUST_BE_CONST", "NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION", "CONST_VAL_WITH_NON_CONST_INITIALIZER"),
        "kt.constMisuse" to setOf("CONST_VAL_WITH_NON_CONST_INITIALIZER", "CONST_VAL_NOT_TOP_LEVEL_OR_OBJECT", "CONST_VAL_WITH_GETTER", "CONST_VAL_WITH_DELEGATE", "CONST_VAL_WITHOUT_INITIALIZER", "TYPE_CANT_BE_USED_FOR_CONST_VAL"),
        "kt.cannotWeakenAccess" to setOf("CANNOT_WEAKEN_ACCESS_PRIVILEGE", "CANNOT_CHANGE_ACCESS_PRIVILEGE"),
        "kt.uninitializedEnumCompanion" to setOf("UNINITIALIZED_ENUM_COMPANION"),
        "kt.instanceAccessBeforeSuperCall" to setOf("INSTANCE_ACCESS_BEFORE_SUPER_CALL"),
        "kt.usageIsNotInlinable" to setOf("USAGE_IS_NOT_INLINABLE"),
        "kt.nonPublicCallFromPublicInline" to setOf("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE", "PRIVATE_CLASS_MEMBER_FROM_INLINE"),
        "kt.optInUsage" to setOf("OPT_IN_USAGE"),
        "kt.optInUsageError" to setOf("OPT_IN_USAGE_ERROR", "OPT_IN_USAGE_FUTURE_ERROR"),
        "kt.deprecation" to setOf("DEPRECATION", "DEPRECATION_ERROR"),
        "kt.uselessCast" to setOf("USELESS_CAST"),
        "kt.castNeverSucceeds" to setOf("CAST_NEVER_SUCCEEDS"),
        "kt.uselessElvis" to setOf("USELESS_ELVIS", "USELESS_ELVIS_RIGHT_IS_NULL"),
        "kt.redundantNotNull" to setOf("UNNECESSARY_NOT_NULL_ASSERTION"),
        "kt.redundantSafeCall" to setOf("UNNECESSARY_SAFE_CALL"),
        "kt.senselessComparison" to setOf("SENSELESS_COMPARISON", "SENSELESS_NULL_IN_WHEN"),
        "kt.uselessIsCheck" to setOf("USELESS_IS_CHECK"),
        "kt.incomparableEquality" to setOf("EQUALITY_NOT_APPLICABLE", "INCOMPATIBLE_TYPES", "INCOMPATIBLE_ENUM_COMPARISON_ERROR", "INCOMPATIBLE_ENUM_COMPARISON"),
        "kt.unreachable" to setOf("UNREACHABLE_CODE"),
        "kt.unusedExpression" to setOf("UNUSED_EXPRESSION"),
        "kt.unusedLocal" to setOf("UNUSED_VARIABLE"),
        "kt.unusedParameter" to setOf("UNUSED_PARAMETER"),
        "kt.nameShadowing" to setOf("NAME_SHADOWING"),
        "kt.varCouldBeVal" to setOf("CAN_BE_VAL"),
        "kt.whenExhaustiveWarning" to setOf("NON_EXHAUSTIVE_WHEN_STATEMENT"),
        "kt.castErased" to setOf("CANNOT_CHECK_FOR_ERASED", "NO_TYPE_ARGUMENTS_ON_RHS"),
        "kt.illegalLiteral" to setOf("ILLEGAL_ESCAPE", "WRONG_LONG_SUFFIX", "ILLEGAL_UNDERSCORE", "INT_LITERAL_WITH_LEADING_ZEROS", "INT_LITERAL_OUT_OF_RANGE", "EMPTY_CHARACTER_LITERAL", "TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL", "INCORRECT_CHARACTER_LITERAL"),
        "kt.illegalSelector" to setOf("ILLEGAL_SELECTOR", "NO_RECEIVER_ALLOWED"),
        "kt.superMisuse" to setOf("SUPER_IS_NOT_AN_EXPRESSION", "SUPER_NOT_AVAILABLE", "AMBIGUOUS_SUPER", "ABSTRACT_SUPER_CALL"),
        "kt.importFromSingleton" to setOf("CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON"),
        "kt.operatorModifierRequired" to setOf("OPERATOR_MODIFIER_REQUIRED"),
        "kt.nonLocalReturnNotAllowed" to setOf("NON_LOCAL_RETURN_NOT_ALLOWED"),
        "kt.classLiteral" to setOf("NULLABLE_TYPE_IN_CLASS_LITERAL_LHS", "EXPRESSION_OF_NULLABLE_TYPE_IN_CLASS_LITERAL_LHS", "CLASS_LITERAL_LHS_NOT_A_CLASS", "TYPE_PARAMETER_AS_REIFIED"),
        "kt.underscoreUsage" to setOf("UNDERSCORE_USAGE_WITHOUT_BACKTICKS"),
        "kt.expressionExpected" to setOf("EXPRESSION_EXPECTED"),
        "kt.argumentMapping" to setOf("MIXING_NAMED_AND_POSITIONAL_ARGUMENTS", "ARGUMENT_PASSED_TWICE", "NON_VARARG_SPREAD", "VARARG_OUTSIDE_PARENTHESES", "MANY_LAMBDA_EXPRESSION_ARGUMENTS", "NAMED_ARGUMENTS_NOT_ALLOWED"),
    ) + PORTED_DECLARATION_K2_NAMES + ROUND6_K2_NAMES + INHERITANCE_CLASH_K2_NAMES + ROUND7_K2_NAMES + ROUND8_K2_NAMES
}
