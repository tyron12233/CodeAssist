package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.element.SyntaxTokenTypes
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode

/**
 * The tree-to-facade mapping: one element type, one `Kt*` class.
 *
 * Wrappers are created once per node and cached on it. Identity has to hold, because the analysis engine
 * keys caches and identity maps on elements, and a facade that minted a fresh wrapper per access would turn
 * every such map into a leak that still passed its tests.
 *
 * An element type with no entry here becomes a plain [KtElement] rather than an error. The tree is the
 * authority; the facade is a convenience over it, and a node the facade has no name for is still a node with
 * children, a range and text.
 */
internal fun createPsi(session: KtTreeSession, node: LightNode): KtElement = when (session.tree.getType(node)) {
    // File structure
    KtNodeTypes.FILE -> KtFile(session, node)
    KtNodeTypes.PACKAGE_DIRECTIVE -> KtPackageDirective(session, node)
    KtNodeTypes.IMPORT_LIST -> KtImportList(session, node)
    KtNodeTypes.IMPORT_DIRECTIVE -> KtImportDirective(session, node)
    KtNodeTypes.IMPORT_ALIAS -> KtImportAlias(session, node)

    // Classifiers
    KtNodeTypes.CLASS -> KtClass(session, node)
    KtNodeTypes.OBJECT_DECLARATION -> KtObjectDeclaration(session, node)
    KtNodeTypes.CLASS_BODY -> KtClassBody(session, node)
    KtNodeTypes.ENUM_ENTRY -> KtEnumEntry(session, node)
    KtNodeTypes.PRIMARY_CONSTRUCTOR -> KtPrimaryConstructor(session, node)
    KtNodeTypes.SECONDARY_CONSTRUCTOR -> KtSecondaryConstructor(session, node)
    KtNodeTypes.CLASS_INITIALIZER -> KtClassInitializer(session, node)
    KtNodeTypes.SUPER_TYPE_LIST -> KtSuperTypeList(session, node)
    KtNodeTypes.SUPER_TYPE_ENTRY -> KtSuperTypeEntry(session, node)
    KtNodeTypes.SUPER_TYPE_CALL_ENTRY -> KtSuperTypeCallEntry(session, node)
    KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY -> KtDelegatedSuperTypeEntry(session, node)

    // Callables
    KtNodeTypes.FUN -> KtNamedFunction(session, node)
    KtNodeTypes.PROPERTY -> KtProperty(session, node)
    KtNodeTypes.PROPERTY_ACCESSOR -> KtPropertyAccessor(session, node)
    KtNodeTypes.PROPERTY_DELEGATE -> KtPropertyDelegate(session, node)
    KtNodeTypes.TYPEALIAS -> KtTypeAlias(session, node)
    KtNodeTypes.VALUE_PARAMETER_LIST -> KtParameterList(session, node)
    KtNodeTypes.VALUE_PARAMETER -> KtParameter(session, node)
    KtNodeTypes.TYPE_PARAMETER_LIST -> KtTypeParameterList(session, node)
    KtNodeTypes.TYPE_PARAMETER -> KtTypeParameter(session, node)
    KtNodeTypes.TYPE_CONSTRAINT_LIST -> KtTypeConstraintList(session, node)
    KtNodeTypes.TYPE_CONSTRAINT -> KtTypeConstraint(session, node)
    KtNodeTypes.DESTRUCTURING_DECLARATION -> KtDestructuringDeclaration(session, node)
    KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY -> KtDestructuringDeclarationEntry(session, node)

    // Modifiers, annotations, types
    KtNodeTypes.MODIFIER_LIST -> KtModifierList(session, node)
    KtNodeTypes.ANNOTATION_ENTRY -> KtAnnotationEntry(session, node)
    KtNodeTypes.TYPE_REFERENCE -> KtTypeReference(session, node)
    KtNodeTypes.USER_TYPE -> KtUserType(session, node)
    KtNodeTypes.NULLABLE_TYPE -> KtNullableType(session, node)
    KtNodeTypes.INTERSECTION_TYPE -> KtIntersectionType(session, node)
    KtNodeTypes.DYNAMIC_TYPE -> KtDynamicType(session, node)
    KtNodeTypes.FUNCTION_TYPE -> KtFunctionType(session, node)
    KtNodeTypes.TYPE_ARGUMENT_LIST -> KtTypeArgumentList(session, node)
    KtNodeTypes.TYPE_PROJECTION -> KtTypeProjection(session, node)

    // Expressions
    KtNodeTypes.BLOCK -> KtBlockExpression(session, node)
    KtNodeTypes.REFERENCE_EXPRESSION -> KtNameReferenceExpression(session, node)
    KtNodeTypes.OPERATION_REFERENCE -> KtOperationReferenceExpression(session, node)
    KtNodeTypes.CALL_EXPRESSION -> KtCallExpression(session, node)
    KtNodeTypes.VALUE_ARGUMENT_LIST -> KtValueArgumentList(session, node)
    KtNodeTypes.VALUE_ARGUMENT -> KtValueArgument(session, node)
    KtNodeTypes.LAMBDA_ARGUMENT -> KtLambdaArgument(session, node)
    KtNodeTypes.LAMBDA_EXPRESSION -> KtLambdaExpression(session, node)
    KtNodeTypes.FUNCTION_LITERAL -> KtFunctionLiteral(session, node)
    KtNodeTypes.DOT_QUALIFIED_EXPRESSION -> KtDotQualifiedExpression(session, node)
    KtNodeTypes.SAFE_ACCESS_EXPRESSION -> KtSafeQualifiedExpression(session, node)
    KtNodeTypes.BINARY_EXPRESSION -> KtBinaryExpression(session, node)
    KtNodeTypes.BINARY_WITH_TYPE -> KtBinaryExpressionWithTypeRHS(session, node)
    KtNodeTypes.IS_EXPRESSION -> KtIsExpression(session, node)
    KtNodeTypes.PREFIX_EXPRESSION -> KtPrefixExpression(session, node)
    KtNodeTypes.POSTFIX_EXPRESSION -> KtPostfixExpression(session, node)
    KtNodeTypes.PARENTHESIZED -> KtParenthesizedExpression(session, node)
    KtNodeTypes.ARRAY_ACCESS_EXPRESSION -> KtArrayAccessExpression(session, node)
    KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION -> KtCallableReferenceExpression(session, node)
    KtNodeTypes.CLASS_LITERAL_EXPRESSION -> KtClassLiteralExpression(session, node)
    KtNodeTypes.COLLECTION_LITERAL_EXPRESSION -> KtCollectionLiteralExpression(session, node)
    KtNodeTypes.INTEGER_CONSTANT,
    KtNodeTypes.FLOAT_CONSTANT,
    KtNodeTypes.BOOLEAN_CONSTANT,
    KtNodeTypes.CHARACTER_CONSTANT,
    KtNodeTypes.NULL,
    -> KtConstantExpression(session, node)

    // Strings
    KtNodeTypes.STRING_TEMPLATE -> KtStringTemplateExpression(session, node)
    KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY -> KtLiteralStringTemplateEntry(session, node)
    KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY -> KtEscapeStringTemplateEntry(session, node)
    KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY -> KtSimpleNameStringTemplateEntry(session, node)
    KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY -> KtBlockStringTemplateEntry(session, node)

    // Control flow
    KtNodeTypes.IF -> KtIfExpression(session, node)
    KtNodeTypes.WHEN -> KtWhenExpression(session, node)
    KtNodeTypes.WHEN_ENTRY -> KtWhenEntry(session, node)
    KtNodeTypes.WHEN_CONDITION_EXPRESSION -> KtWhenConditionWithExpression(session, node)
    KtNodeTypes.WHEN_CONDITION_IN_RANGE -> KtWhenConditionInRange(session, node)
    KtNodeTypes.WHEN_CONDITION_IS_PATTERN -> KtWhenConditionIsPattern(session, node)
    KtNodeTypes.TRY -> KtTryExpression(session, node)
    KtNodeTypes.CATCH -> KtCatchClause(session, node)
    KtNodeTypes.FINALLY -> KtFinallySection(session, node)
    KtNodeTypes.FOR -> KtForExpression(session, node)
    KtNodeTypes.WHILE -> KtWhileExpression(session, node)
    KtNodeTypes.DO_WHILE -> KtDoWhileExpression(session, node)
    KtNodeTypes.RETURN -> KtReturnExpression(session, node)
    KtNodeTypes.THROW -> KtThrowExpression(session, node)
    KtNodeTypes.BREAK -> KtBreakExpression(session, node)
    KtNodeTypes.CONTINUE -> KtContinueExpression(session, node)
    KtNodeTypes.THIS_EXPRESSION -> KtThisExpression(session, node)
    KtNodeTypes.SUPER_EXPRESSION -> KtSuperExpression(session, node)
    KtNodeTypes.OBJECT_LITERAL -> KtObjectLiteralExpression(session, node)
    KtNodeTypes.LABELED_EXPRESSION -> KtLabeledExpression(session, node)
    KtNodeTypes.ANNOTATED_EXPRESSION -> KtAnnotatedExpression(session, node)

    SyntaxTokenTypes.ERROR_ELEMENT -> KtErrorElement(session, node)

    else -> KtElement(session, node)
}
