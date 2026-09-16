package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.tree.IElementType

/** A composite (non-leaf) element kind, mirroring `org.jetbrains.kotlin.KtNodeType`. */
class KtNodeType(debugName: String) : IElementType(debugName)

/**
 * The Kotlin element vocabulary, mirroring `org.jetbrains.kotlin.KtNodeTypes` field for field.
 *
 * These are the names the parser produces and the names the `Kt*` facade dispatches on, so they are also the
 * names the tree oracle compares against the real compiler's. `KtNodeTypesParityTest` fails when the compiler
 * declares an element this object does not.
 *
 * A few fields are the compiler's own aliases rather than distinct kinds ([FUNCTION] is [FUN], [FILE] is
 * [KT_FILE]); they are reproduced as aliases so code written against either name resolves.
 */
object KtNodeTypes {

    // --- file structure ----------------------------------------------------------------------------------
    val KT_FILE: KtNodeType = KtNodeType("kotlin.FILE")
    val FILE: IElementType = KT_FILE
    val PACKAGE_DIRECTIVE: KtNodeType = KtNodeType("PACKAGE_DIRECTIVE")
    val IMPORT_LIST: KtNodeType = KtNodeType("IMPORT_LIST")
    val IMPORT_DIRECTIVE: KtNodeType = KtNodeType("IMPORT_DIRECTIVE")
    val IMPORT_ALIAS: KtNodeType = KtNodeType("IMPORT_ALIAS")
    val FILE_ANNOTATION_LIST: KtNodeType = KtNodeType("FILE_ANNOTATION_LIST")
    val SCRIPT: KtNodeType = KtNodeType("SCRIPT")
    val SCRIPT_INITIALIZER: KtNodeType = KtNodeType("SCRIPT_INITIALIZER")

    // --- declarations ------------------------------------------------------------------------------------
    val CLASS: KtNodeType = KtNodeType("CLASS")
    val OBJECT_DECLARATION: KtNodeType = KtNodeType("OBJECT_DECLARATION")
    val CLASS_BODY: KtNodeType = KtNodeType("CLASS_BODY")
    val COMPANION_BLOCK: KtNodeType = KtNodeType("COMPANION_BLOCK")
    val ENUM_ENTRY: KtNodeType = KtNodeType("ENUM_ENTRY")
    val ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION: KtNodeType =
        KtNodeType("ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION")
    val CLASS_INITIALIZER: KtNodeType = KtNodeType("CLASS_INITIALIZER")
    val PRIMARY_CONSTRUCTOR: KtNodeType = KtNodeType("PRIMARY_CONSTRUCTOR")
    val SECONDARY_CONSTRUCTOR: KtNodeType = KtNodeType("SECONDARY_CONSTRUCTOR")
    val CONSTRUCTOR_DELEGATION_CALL: KtNodeType = KtNodeType("CONSTRUCTOR_DELEGATION_CALL")
    val CONSTRUCTOR_CALLEE: KtNodeType = KtNodeType("CONSTRUCTOR_CALLEE")
    val CONSTRUCTOR_DELEGATION_REFERENCE: KtNodeType = KtNodeType("CONSTRUCTOR_DELEGATION_REFERENCE")
    val FUN: KtNodeType = KtNodeType("FUN")
    val FUNCTION: IElementType = FUN
    val PROPERTY: KtNodeType = KtNodeType("PROPERTY")
    val PROPERTY_ACCESSOR: KtNodeType = KtNodeType("PROPERTY_ACCESSOR")
    val PROPERTY_DELEGATE: KtNodeType = KtNodeType("PROPERTY_DELEGATE")
    val BACKING_FIELD: KtNodeType = KtNodeType("BACKING_FIELD")
    val TYPEALIAS: KtNodeType = KtNodeType("TYPEALIAS")
    val DESTRUCTURING_DECLARATION: KtNodeType = KtNodeType("DESTRUCTURING_DECLARATION")
    val DESTRUCTURING_DECLARATION_ENTRY: KtNodeType = KtNodeType("DESTRUCTURING_DECLARATION_ENTRY")
    val INITIALIZER_LIST: KtNodeType = KtNodeType("INITIALIZER_LIST")
    val BODY: KtNodeType = KtNodeType("BODY")

    // --- modifiers and annotations -----------------------------------------------------------------------
    val MODIFIER_LIST: KtNodeType = KtNodeType("MODIFIER_LIST")
    val ANNOTATION: KtNodeType = KtNodeType("ANNOTATION")
    val ANNOTATION_ENTRY: KtNodeType = KtNodeType("ANNOTATION_ENTRY")
    val ANNOTATION_TARGET: KtNodeType = KtNodeType("ANNOTATION_TARGET")

    // --- type parameters and constraints -----------------------------------------------------------------
    val TYPE_PARAMETER_LIST: KtNodeType = KtNodeType("TYPE_PARAMETER_LIST")
    val TYPE_PARAMETER: KtNodeType = KtNodeType("TYPE_PARAMETER")
    val TYPE_CONSTRAINT_LIST: KtNodeType = KtNodeType("TYPE_CONSTRAINT_LIST")
    val TYPE_CONSTRAINT: KtNodeType = KtNodeType("TYPE_CONSTRAINT")

    // --- parameters and arguments ------------------------------------------------------------------------
    val VALUE_PARAMETER_LIST: KtNodeType = KtNodeType("VALUE_PARAMETER_LIST")
    val VALUE_PARAMETER: KtNodeType = KtNodeType("VALUE_PARAMETER")
    val VALUE_ARGUMENT_LIST: KtNodeType = KtNodeType("VALUE_ARGUMENT_LIST")
    val VALUE_ARGUMENT: KtNodeType = KtNodeType("VALUE_ARGUMENT")
    val VALUE_ARGUMENT_NAME: KtNodeType = KtNodeType("VALUE_ARGUMENT_NAME")
    val LAMBDA_ARGUMENT: KtNodeType = KtNodeType("LAMBDA_ARGUMENT")
    val CONTEXT_RECEIVER_LIST: KtNodeType = KtNodeType("CONTEXT_RECEIVER_LIST")
    val CONTEXT_RECEIVER: KtNodeType = KtNodeType("CONTEXT_RECEIVER")
    val CONTEXT_PARAMETER_LIST: KtNodeType = KtNodeType("CONTEXT_PARAMETER_LIST")

    // --- types -------------------------------------------------------------------------------------------
    val TYPE_REFERENCE: KtNodeType = KtNodeType("TYPE_REFERENCE")
    val USER_TYPE: KtNodeType = KtNodeType("USER_TYPE")
    val NULLABLE_TYPE: KtNodeType = KtNodeType("NULLABLE_TYPE")
    val DYNAMIC_TYPE: KtNodeType = KtNodeType("DYNAMIC_TYPE")
    val FUNCTION_TYPE: KtNodeType = KtNodeType("FUNCTION_TYPE")
    val FUNCTION_TYPE_RECEIVER: KtNodeType = KtNodeType("FUNCTION_TYPE_RECEIVER")
    val INTERSECTION_TYPE: KtNodeType = KtNodeType("INTERSECTION_TYPE")
    val TYPE_ARGUMENT_LIST: KtNodeType = KtNodeType("TYPE_ARGUMENT_LIST")
    val TYPE_PROJECTION: KtNodeType = KtNodeType("TYPE_PROJECTION")

    // --- supertypes --------------------------------------------------------------------------------------
    val SUPER_TYPE_LIST: KtNodeType = KtNodeType("SUPER_TYPE_LIST")
    val SUPER_TYPE_ENTRY: KtNodeType = KtNodeType("SUPER_TYPE_ENTRY")
    val SUPER_TYPE_CALL_ENTRY: KtNodeType = KtNodeType("SUPER_TYPE_CALL_ENTRY")
    val DELEGATED_SUPER_TYPE_ENTRY: KtNodeType = KtNodeType("DELEGATED_SUPER_TYPE_ENTRY")

    // --- expressions -------------------------------------------------------------------------------------
    val BLOCK: KtNodeType = KtNodeType("BLOCK")
    val REFERENCE_EXPRESSION: KtNodeType = KtNodeType("REFERENCE_EXPRESSION")
    val OPERATION_REFERENCE: KtNodeType = KtNodeType("OPERATION_REFERENCE")
    val LABEL: KtNodeType = KtNodeType("LABEL")
    val LABEL_QUALIFIER: KtNodeType = KtNodeType("LABEL_QUALIFIER")
    val LABELED_EXPRESSION: KtNodeType = KtNodeType("LABELED_EXPRESSION")
    val ANNOTATED_EXPRESSION: KtNodeType = KtNodeType("ANNOTATED_EXPRESSION")
    val PARENTHESIZED: KtNodeType = KtNodeType("PARENTHESIZED")
    val CALL_EXPRESSION: KtNodeType = KtNodeType("CALL_EXPRESSION")
    val ARRAY_ACCESS_EXPRESSION: KtNodeType = KtNodeType("ARRAY_ACCESS_EXPRESSION")
    val INDICES: KtNodeType = KtNodeType("INDICES")
    val DOT_QUALIFIED_EXPRESSION: KtNodeType = KtNodeType("DOT_QUALIFIED_EXPRESSION")
    val SAFE_ACCESS_EXPRESSION: KtNodeType = KtNodeType("SAFE_ACCESS_EXPRESSION")
    val CALLABLE_REFERENCE_EXPRESSION: KtNodeType = KtNodeType("CALLABLE_REFERENCE_EXPRESSION")
    val CLASS_LITERAL_EXPRESSION: KtNodeType = KtNodeType("CLASS_LITERAL_EXPRESSION")
    val COLLECTION_LITERAL_EXPRESSION: KtNodeType = KtNodeType("COLLECTION_LITERAL_EXPRESSION")
    val BINARY_EXPRESSION: KtNodeType = KtNodeType("BINARY_EXPRESSION")
    val BINARY_WITH_TYPE: KtNodeType = KtNodeType("BINARY_WITH_TYPE")
    val IS_EXPRESSION: KtNodeType = KtNodeType("IS_EXPRESSION")
    val PREFIX_EXPRESSION: KtNodeType = KtNodeType("PREFIX_EXPRESSION")
    val POSTFIX_EXPRESSION: KtNodeType = KtNodeType("POSTFIX_EXPRESSION")
    val LAMBDA_EXPRESSION: KtNodeType = KtNodeType("LAMBDA_EXPRESSION")
    val FUNCTION_LITERAL: KtNodeType = KtNodeType("FUNCTION_LITERAL")
    val OBJECT_LITERAL: KtNodeType = KtNodeType("OBJECT_LITERAL")
    val THIS_EXPRESSION: KtNodeType = KtNodeType("THIS_EXPRESSION")
    val SUPER_EXPRESSION: KtNodeType = KtNodeType("SUPER_EXPRESSION")
    val INSTANCE: KtNodeType = KtNodeType("INSTANCE")

    // --- constants ---------------------------------------------------------------------------------------
    val INTEGER_CONSTANT: KtNodeType = KtNodeType("INTEGER_CONSTANT")
    val FLOAT_CONSTANT: KtNodeType = KtNodeType("FLOAT_CONSTANT")
    val BOOLEAN_CONSTANT: KtNodeType = KtNodeType("BOOLEAN_CONSTANT")
    val CHARACTER_CONSTANT: KtNodeType = KtNodeType("CHARACTER_CONSTANT")
    val NULL: KtNodeType = KtNodeType("NULL")

    // --- string templates --------------------------------------------------------------------------------
    val STRING_TEMPLATE: KtNodeType = KtNodeType("STRING_TEMPLATE")
    val STRING_INTERPOLATION_PREFIX: KtNodeType = KtNodeType("STRING_INTERPOLATION_PREFIX")
    val LITERAL_STRING_TEMPLATE_ENTRY: KtNodeType = KtNodeType("LITERAL_STRING_TEMPLATE_ENTRY")
    val ESCAPE_STRING_TEMPLATE_ENTRY: KtNodeType = KtNodeType("ESCAPE_STRING_TEMPLATE_ENTRY")
    val SHORT_STRING_TEMPLATE_ENTRY: KtNodeType = KtNodeType("SHORT_STRING_TEMPLATE_ENTRY")
    val LONG_STRING_TEMPLATE_ENTRY: KtNodeType = KtNodeType("LONG_STRING_TEMPLATE_ENTRY")

    // --- control flow ------------------------------------------------------------------------------------
    val IF: KtNodeType = KtNodeType("IF")
    val THEN: KtNodeType = KtNodeType("THEN")
    val ELSE: KtNodeType = KtNodeType("ELSE")
    val CONDITION: KtNodeType = KtNodeType("CONDITION")
    val WHEN: KtNodeType = KtNodeType("WHEN")
    val WHEN_ENTRY: KtNodeType = KtNodeType("WHEN_ENTRY")
    val WHEN_ENTRY_GUARD: KtNodeType = KtNodeType("WHEN_ENTRY_GUARD")
    val WHEN_CONDITION_EXPRESSION: KtNodeType = KtNodeType("WHEN_CONDITION_EXPRESSION")
    val WHEN_CONDITION_IN_RANGE: KtNodeType = KtNodeType("WHEN_CONDITION_IN_RANGE")
    val WHEN_CONDITION_IS_PATTERN: KtNodeType = KtNodeType("WHEN_CONDITION_IS_PATTERN")
    val WHEN_CONDITION_WITH_EXPRESSION: KtNodeType = KtNodeType("WHEN_CONDITION_WITH_EXPRESSION")
    val FOR: KtNodeType = KtNodeType("FOR")
    val WHILE: KtNodeType = KtNodeType("WHILE")
    val DO_WHILE: KtNodeType = KtNodeType("DO_WHILE")
    val LOOP_RANGE: KtNodeType = KtNodeType("LOOP_RANGE")
    val TRY: KtNodeType = KtNodeType("TRY")
    val CATCH: KtNodeType = KtNodeType("CATCH")
    val FINALLY: KtNodeType = KtNodeType("FINALLY")
    val RETURN: KtNodeType = KtNodeType("RETURN")
    val THROW: KtNodeType = KtNodeType("THROW")
    val BREAK: KtNodeType = KtNodeType("BREAK")
    val CONTINUE: KtNodeType = KtNodeType("CONTINUE")

    // --- contracts and code fragments --------------------------------------------------------------------
    val CONTRACT_EFFECT_LIST: KtNodeType = KtNodeType("CONTRACT_EFFECT_LIST")
    val CONTRACT_EFFECT: KtNodeType = KtNodeType("CONTRACT_EFFECT")
    val BLOCK_CODE_FRAGMENT: KtNodeType = KtNodeType("BLOCK_CODE_FRAGMENT")
    val EXPRESSION_CODE_FRAGMENT: KtNodeType = KtNodeType("EXPRESSION_CODE_FRAGMENT")
    val TYPE_CODE_FRAGMENT: KtNodeType = KtNodeType("TYPE_CODE_FRAGMENT")
}
