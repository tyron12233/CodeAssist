package dev.ide.kotlin.syntax.psi

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.TokenType

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
fun AstNode.toPsi(): KtElement {
    cachedPsi?.let { return it as KtElement }
    val element = createPsi(this)
    cachedPsi = element
    return element
}

private fun createPsi(node: AstNode): KtElement = when (node.elementType) {
    // File structure
    KtNodeTypes.KT_FILE -> KtFile(node)
    KtNodeTypes.PACKAGE_DIRECTIVE -> KtPackageDirective(node)
    KtNodeTypes.IMPORT_LIST -> KtImportList(node)
    KtNodeTypes.IMPORT_DIRECTIVE -> KtImportDirective(node)
    KtNodeTypes.IMPORT_ALIAS -> KtImportAlias(node)

    // Classifiers
    KtNodeTypes.CLASS -> KtClass(node)
    KtNodeTypes.OBJECT_DECLARATION -> KtObjectDeclaration(node)
    KtNodeTypes.CLASS_BODY -> KtClassBody(node)
    KtNodeTypes.ENUM_ENTRY -> KtEnumEntry(node)
    KtNodeTypes.PRIMARY_CONSTRUCTOR -> KtPrimaryConstructor(node)
    KtNodeTypes.SECONDARY_CONSTRUCTOR -> KtSecondaryConstructor(node)
    KtNodeTypes.CLASS_INITIALIZER -> KtClassInitializer(node)
    KtNodeTypes.SUPER_TYPE_LIST -> KtSuperTypeList(node)
    KtNodeTypes.SUPER_TYPE_ENTRY -> KtSuperTypeEntry(node)
    KtNodeTypes.SUPER_TYPE_CALL_ENTRY -> KtSuperTypeCallEntry(node)
    KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY -> KtDelegatedSuperTypeEntry(node)

    // Callables
    KtNodeTypes.FUN -> KtNamedFunction(node)
    KtNodeTypes.PROPERTY -> KtProperty(node)
    KtNodeTypes.PROPERTY_ACCESSOR -> KtPropertyAccessor(node)
    KtNodeTypes.PROPERTY_DELEGATE -> KtPropertyDelegate(node)
    KtNodeTypes.TYPEALIAS -> KtTypeAlias(node)
    KtNodeTypes.VALUE_PARAMETER_LIST -> KtParameterList(node)
    KtNodeTypes.VALUE_PARAMETER -> KtParameter(node)
    KtNodeTypes.TYPE_PARAMETER_LIST -> KtTypeParameterList(node)
    KtNodeTypes.TYPE_PARAMETER -> KtTypeParameter(node)
    KtNodeTypes.TYPE_CONSTRAINT_LIST -> KtTypeConstraintList(node)
    KtNodeTypes.TYPE_CONSTRAINT -> KtTypeConstraint(node)
    KtNodeTypes.DESTRUCTURING_DECLARATION -> KtDestructuringDeclaration(node)
    KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY -> KtDestructuringDeclarationEntry(node)

    // Modifiers, annotations, types
    KtNodeTypes.MODIFIER_LIST -> KtModifierList(node)
    KtNodeTypes.ANNOTATION_ENTRY -> KtAnnotationEntry(node)
    KtNodeTypes.TYPE_REFERENCE -> KtTypeReference(node)
    KtNodeTypes.USER_TYPE -> KtUserType(node)
    KtNodeTypes.NULLABLE_TYPE -> KtNullableType(node)
    KtNodeTypes.INTERSECTION_TYPE -> KtIntersectionType(node)
    KtNodeTypes.DYNAMIC_TYPE -> KtDynamicType(node)
    KtNodeTypes.FUNCTION_TYPE -> KtFunctionType(node)
    KtNodeTypes.TYPE_ARGUMENT_LIST -> KtTypeArgumentList(node)
    KtNodeTypes.TYPE_PROJECTION -> KtTypeProjection(node)

    // Expressions
    KtNodeTypes.BLOCK -> KtBlockExpression(node)
    KtNodeTypes.REFERENCE_EXPRESSION -> KtNameReferenceExpression(node)
    KtNodeTypes.OPERATION_REFERENCE -> KtOperationReferenceExpression(node)
    KtNodeTypes.CALL_EXPRESSION -> KtCallExpression(node)
    KtNodeTypes.VALUE_ARGUMENT_LIST -> KtValueArgumentList(node)
    KtNodeTypes.VALUE_ARGUMENT -> KtValueArgument(node)
    KtNodeTypes.LAMBDA_ARGUMENT -> KtLambdaArgument(node)
    KtNodeTypes.LAMBDA_EXPRESSION -> KtLambdaExpression(node)
    KtNodeTypes.FUNCTION_LITERAL -> KtFunctionLiteral(node)
    KtNodeTypes.DOT_QUALIFIED_EXPRESSION -> KtDotQualifiedExpression(node)
    KtNodeTypes.SAFE_ACCESS_EXPRESSION -> KtSafeQualifiedExpression(node)
    KtNodeTypes.BINARY_EXPRESSION -> KtBinaryExpression(node)
    KtNodeTypes.BINARY_WITH_TYPE -> KtBinaryExpressionWithTypeRHS(node)
    KtNodeTypes.IS_EXPRESSION -> KtIsExpression(node)
    KtNodeTypes.PREFIX_EXPRESSION -> KtPrefixExpression(node)
    KtNodeTypes.POSTFIX_EXPRESSION -> KtPostfixExpression(node)
    KtNodeTypes.PARENTHESIZED -> KtParenthesizedExpression(node)
    KtNodeTypes.ARRAY_ACCESS_EXPRESSION -> KtArrayAccessExpression(node)
    KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION -> KtCallableReferenceExpression(node)
    KtNodeTypes.CLASS_LITERAL_EXPRESSION -> KtClassLiteralExpression(node)
    KtNodeTypes.COLLECTION_LITERAL_EXPRESSION -> KtCollectionLiteralExpression(node)
    KtNodeTypes.INTEGER_CONSTANT,
    KtNodeTypes.FLOAT_CONSTANT,
    KtNodeTypes.BOOLEAN_CONSTANT,
    KtNodeTypes.CHARACTER_CONSTANT,
    KtNodeTypes.NULL,
    -> KtConstantExpression(node)

    // Strings
    KtNodeTypes.STRING_TEMPLATE -> KtStringTemplateExpression(node)
    KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY -> KtLiteralStringTemplateEntry(node)
    KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY -> KtEscapeStringTemplateEntry(node)
    KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY -> KtSimpleNameStringTemplateEntry(node)
    KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY -> KtBlockStringTemplateEntry(node)

    // Control flow
    KtNodeTypes.IF -> KtIfExpression(node)
    KtNodeTypes.WHEN -> KtWhenExpression(node)
    KtNodeTypes.WHEN_ENTRY -> KtWhenEntry(node)
    KtNodeTypes.WHEN_CONDITION_WITH_EXPRESSION -> KtWhenConditionWithExpression(node)
    KtNodeTypes.WHEN_CONDITION_IN_RANGE -> KtWhenConditionInRange(node)
    KtNodeTypes.WHEN_CONDITION_IS_PATTERN -> KtWhenConditionIsPattern(node)
    KtNodeTypes.TRY -> KtTryExpression(node)
    KtNodeTypes.CATCH -> KtCatchClause(node)
    KtNodeTypes.FINALLY -> KtFinallySection(node)
    KtNodeTypes.FOR -> KtForExpression(node)
    KtNodeTypes.WHILE -> KtWhileExpression(node)
    KtNodeTypes.DO_WHILE -> KtDoWhileExpression(node)
    KtNodeTypes.RETURN -> KtReturnExpression(node)
    KtNodeTypes.THROW -> KtThrowExpression(node)
    KtNodeTypes.BREAK -> KtBreakExpression(node)
    KtNodeTypes.CONTINUE -> KtContinueExpression(node)
    KtNodeTypes.THIS_EXPRESSION -> KtThisExpression(node)
    KtNodeTypes.SUPER_EXPRESSION -> KtSuperExpression(node)
    KtNodeTypes.OBJECT_LITERAL -> KtObjectLiteralExpression(node)
    KtNodeTypes.LABELED_EXPRESSION -> KtLabeledExpression(node)
    KtNodeTypes.ANNOTATED_EXPRESSION -> KtAnnotatedExpression(node)

    TokenType.ERROR_ELEMENT -> KtErrorElement(node)

    else -> KtElement(node)
}
