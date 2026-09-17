package dev.ide.kotlin.syntax.psi

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.SyntaxElementTypeSet
import org.jetbrains.kotlin.kmp.lexer.KtTokens as Vendored

/**
 * The token vocabulary under the names PSI used, so a consumer swaps one import instead of editing hundreds
 * of call sites.
 *
 * The multiplatform vocabulary renamed every modifier: `PRIVATE_MODIFIER` where PSI said `PRIVATE_KEYWORD`,
 * and so on for 34 of them. That is a rename, not a redesign — the instances are the same tokens doing
 * the same job — but measured on `:lang-kotlin` it touches 82 references, which is 82 chances to mistype one.
 *
 * Every token and token set the vendored `KtTokens` declares appears here exactly once, under its PSI name
 * where the two differ and its own name where they agree. Regenerate after a vendor re-sync: a token that
 * disappears upstream should fail to compile here rather than be quietly aliased away.
 */
object KtTokens {
    val EOF: SyntaxElementType = Vendored.EOF
    val RESERVED: SyntaxElementType = Vendored.RESERVED
    val BLOCK_COMMENT: SyntaxElementType = Vendored.BLOCK_COMMENT
    val EOL_COMMENT: SyntaxElementType = Vendored.EOL_COMMENT
    val SHEBANG_COMMENT: SyntaxElementType = Vendored.SHEBANG_COMMENT
    val DOC_COMMENT: SyntaxElementType = Vendored.DOC_COMMENT
    val WHITE_SPACE: SyntaxElementType = Vendored.WHITE_SPACE
    val INTEGER_LITERAL: SyntaxElementType = Vendored.INTEGER_LITERAL
    val FLOAT_LITERAL: SyntaxElementType = Vendored.FLOAT_LITERAL
    val CHARACTER_LITERAL: SyntaxElementType = Vendored.CHARACTER_LITERAL
    val INTERPOLATION_PREFIX: SyntaxElementType = Vendored.INTERPOLATION_PREFIX
    val CLOSING_QUOTE: SyntaxElementType = Vendored.CLOSING_QUOTE
    val OPEN_QUOTE: SyntaxElementType = Vendored.OPEN_QUOTE
    val REGULAR_STRING_PART: SyntaxElementType = Vendored.REGULAR_STRING_PART
    val ESCAPE_SEQUENCE: SyntaxElementType = Vendored.ESCAPE_SEQUENCE
    val SHORT_TEMPLATE_ENTRY_START: SyntaxElementType = Vendored.SHORT_TEMPLATE_ENTRY_START
    val LONG_TEMPLATE_ENTRY_START: SyntaxElementType = Vendored.LONG_TEMPLATE_ENTRY_START
    val LONG_TEMPLATE_ENTRY_END: SyntaxElementType = Vendored.LONG_TEMPLATE_ENTRY_END
    val DANGLING_NEWLINE: SyntaxElementType = Vendored.DANGLING_NEWLINE
    val PACKAGE_KEYWORD: SyntaxElementType = Vendored.PACKAGE_KEYWORD
    val AS_KEYWORD: SyntaxElementType = Vendored.AS_KEYWORD
    val TYPE_ALIAS_KEYWORD: SyntaxElementType = Vendored.TYPE_ALIAS_KEYWORD
    val CLASS_KEYWORD: SyntaxElementType = Vendored.CLASS_KEYWORD
    val THIS_KEYWORD: SyntaxElementType = Vendored.THIS_KEYWORD
    val SUPER_KEYWORD: SyntaxElementType = Vendored.SUPER_KEYWORD
    val VAL_KEYWORD: SyntaxElementType = Vendored.VAL_KEYWORD
    val VAR_KEYWORD: SyntaxElementType = Vendored.VAR_KEYWORD
    val FUN_KEYWORD: SyntaxElementType = Vendored.FUN_MODIFIER // upstream: FUN_MODIFIER
    val FOR_KEYWORD: SyntaxElementType = Vendored.FOR_KEYWORD
    val NULL_KEYWORD: SyntaxElementType = Vendored.NULL_KEYWORD
    val TRUE_KEYWORD: SyntaxElementType = Vendored.TRUE_KEYWORD
    val FALSE_KEYWORD: SyntaxElementType = Vendored.FALSE_KEYWORD
    val IS_KEYWORD: SyntaxElementType = Vendored.IS_KEYWORD
    val IN_KEYWORD: SyntaxElementType = Vendored.IN_MODIFIER // upstream: IN_MODIFIER
    val THROW_KEYWORD: SyntaxElementType = Vendored.THROW_KEYWORD
    val RETURN_KEYWORD: SyntaxElementType = Vendored.RETURN_KEYWORD
    val BREAK_KEYWORD: SyntaxElementType = Vendored.BREAK_KEYWORD
    val CONTINUE_KEYWORD: SyntaxElementType = Vendored.CONTINUE_KEYWORD
    val OBJECT_KEYWORD: SyntaxElementType = Vendored.OBJECT_KEYWORD
    val IF_KEYWORD: SyntaxElementType = Vendored.IF_KEYWORD
    val TRY_KEYWORD: SyntaxElementType = Vendored.TRY_KEYWORD
    val ELSE_KEYWORD: SyntaxElementType = Vendored.ELSE_KEYWORD
    val WHILE_KEYWORD: SyntaxElementType = Vendored.WHILE_KEYWORD
    val DO_KEYWORD: SyntaxElementType = Vendored.DO_KEYWORD
    val WHEN_KEYWORD: SyntaxElementType = Vendored.WHEN_KEYWORD
    val INTERFACE_KEYWORD: SyntaxElementType = Vendored.INTERFACE_KEYWORD
    val TYPEOF_KEYWORD: SyntaxElementType = Vendored.TYPEOF_KEYWORD
    val AS_SAFE: SyntaxElementType = Vendored.AS_SAFE
    val IDENTIFIER: SyntaxElementType = Vendored.IDENTIFIER
    val FIELD_IDENTIFIER: SyntaxElementType = Vendored.FIELD_IDENTIFIER
    val LBRACKET: SyntaxElementType = Vendored.LBRACKET
    val RBRACKET: SyntaxElementType = Vendored.RBRACKET
    val LBRACE: SyntaxElementType = Vendored.LBRACE
    val RBRACE: SyntaxElementType = Vendored.RBRACE
    val LPAR: SyntaxElementType = Vendored.LPAR
    val RPAR: SyntaxElementType = Vendored.RPAR
    val DOT: SyntaxElementType = Vendored.DOT
    val PLUSPLUS: SyntaxElementType = Vendored.PLUSPLUS
    val MINUSMINUS: SyntaxElementType = Vendored.MINUSMINUS
    val MUL: SyntaxElementType = Vendored.MUL
    val PLUS: SyntaxElementType = Vendored.PLUS
    val MINUS: SyntaxElementType = Vendored.MINUS
    val EXCL: SyntaxElementType = Vendored.EXCL
    val DIV: SyntaxElementType = Vendored.DIV
    val PERC: SyntaxElementType = Vendored.PERC
    val LT: SyntaxElementType = Vendored.LT
    val GT: SyntaxElementType = Vendored.GT
    val LTEQ: SyntaxElementType = Vendored.LTEQ
    val GTEQ: SyntaxElementType = Vendored.GTEQ
    val EQEQEQ: SyntaxElementType = Vendored.EQEQEQ
    val ARROW: SyntaxElementType = Vendored.ARROW
    val DOUBLE_ARROW: SyntaxElementType = Vendored.DOUBLE_ARROW
    val EXCLEQEQEQ: SyntaxElementType = Vendored.EXCLEQEQEQ
    val EQEQ: SyntaxElementType = Vendored.EQEQ
    val EXCLEQ: SyntaxElementType = Vendored.EXCLEQ
    val EXCLEXCL: SyntaxElementType = Vendored.EXCLEXCL
    val ANDAND: SyntaxElementType = Vendored.ANDAND
    val AND: SyntaxElementType = Vendored.AND
    val OROR: SyntaxElementType = Vendored.OROR
    val OR: SyntaxElementType = Vendored.OR
    val SAFE_ACCESS: SyntaxElementType = Vendored.SAFE_ACCESS
    val ERROR_SAFE_ACCESS: SyntaxElementType = Vendored.ERROR_SAFE_ACCESS
    val ELVIS: SyntaxElementType = Vendored.ELVIS
    val QUEST: SyntaxElementType = Vendored.QUEST
    val COLONCOLON: SyntaxElementType = Vendored.COLONCOLON
    val COLON: SyntaxElementType = Vendored.COLON
    val SEMICOLON: SyntaxElementType = Vendored.SEMICOLON
    val DOUBLE_SEMICOLON: SyntaxElementType = Vendored.DOUBLE_SEMICOLON
    val RANGE: SyntaxElementType = Vendored.RANGE
    val RANGE_UNTIL: SyntaxElementType = Vendored.RANGE_UNTIL
    val EQ: SyntaxElementType = Vendored.EQ
    val MULTEQ: SyntaxElementType = Vendored.MULTEQ
    val DIVEQ: SyntaxElementType = Vendored.DIVEQ
    val PERCEQ: SyntaxElementType = Vendored.PERCEQ
    val PLUSEQ: SyntaxElementType = Vendored.PLUSEQ
    val MINUSEQ: SyntaxElementType = Vendored.MINUSEQ
    val NOT_IN: SyntaxElementType = Vendored.NOT_IN
    val NOT_IS: SyntaxElementType = Vendored.NOT_IS
    val HASH: SyntaxElementType = Vendored.HASH
    val AT: SyntaxElementType = Vendored.AT
    val COMMA: SyntaxElementType = Vendored.COMMA
    val EOL_OR_SEMICOLON: SyntaxElementType = Vendored.EOL_OR_SEMICOLON
    val ALL_KEYWORD: SyntaxElementType = Vendored.ALL_KEYWORD
    val FILE_KEYWORD: SyntaxElementType = Vendored.FILE_KEYWORD
    val FIELD_KEYWORD: SyntaxElementType = Vendored.FIELD_KEYWORD
    val PROPERTY_KEYWORD: SyntaxElementType = Vendored.PROPERTY_KEYWORD
    val RECEIVER_KEYWORD: SyntaxElementType = Vendored.RECEIVER_KEYWORD
    val PARAM_KEYWORD: SyntaxElementType = Vendored.PARAM_KEYWORD
    val SETPARAM_KEYWORD: SyntaxElementType = Vendored.SETPARAM_KEYWORD
    val DELEGATE_KEYWORD: SyntaxElementType = Vendored.DELEGATE_KEYWORD
    val IMPORT_KEYWORD: SyntaxElementType = Vendored.IMPORT_KEYWORD
    val WHERE_KEYWORD: SyntaxElementType = Vendored.WHERE_KEYWORD
    val BY_KEYWORD: SyntaxElementType = Vendored.BY_KEYWORD
    val GET_KEYWORD: SyntaxElementType = Vendored.GET_KEYWORD
    val SET_KEYWORD: SyntaxElementType = Vendored.SET_KEYWORD
    val CONSTRUCTOR_KEYWORD: SyntaxElementType = Vendored.CONSTRUCTOR_KEYWORD
    val INIT_KEYWORD: SyntaxElementType = Vendored.INIT_KEYWORD
    val CONTEXT_KEYWORD: SyntaxElementType = Vendored.CONTEXT_KEYWORD
    val ABSTRACT_KEYWORD: SyntaxElementType = Vendored.ABSTRACT_MODIFIER // upstream: ABSTRACT_MODIFIER
    val ENUM_KEYWORD: SyntaxElementType = Vendored.ENUM_MODIFIER // upstream: ENUM_MODIFIER
    val CONTRACT_KEYWORD: SyntaxElementType = Vendored.CONTRACT_MODIFIER // upstream: CONTRACT_MODIFIER
    val OPEN_KEYWORD: SyntaxElementType = Vendored.OPEN_MODIFIER // upstream: OPEN_MODIFIER
    val INNER_KEYWORD: SyntaxElementType = Vendored.INNER_MODIFIER // upstream: INNER_MODIFIER
    val OVERRIDE_KEYWORD: SyntaxElementType = Vendored.OVERRIDE_MODIFIER // upstream: OVERRIDE_MODIFIER
    val PRIVATE_KEYWORD: SyntaxElementType = Vendored.PRIVATE_MODIFIER // upstream: PRIVATE_MODIFIER
    val PUBLIC_KEYWORD: SyntaxElementType = Vendored.PUBLIC_MODIFIER // upstream: PUBLIC_MODIFIER
    val INTERNAL_KEYWORD: SyntaxElementType = Vendored.INTERNAL_MODIFIER // upstream: INTERNAL_MODIFIER
    val PROTECTED_KEYWORD: SyntaxElementType = Vendored.PROTECTED_MODIFIER // upstream: PROTECTED_MODIFIER
    val CATCH_KEYWORD: SyntaxElementType = Vendored.CATCH_KEYWORD
    val OUT_KEYWORD: SyntaxElementType = Vendored.OUT_MODIFIER // upstream: OUT_MODIFIER
    val VARARG_KEYWORD: SyntaxElementType = Vendored.VARARG_MODIFIER // upstream: VARARG_MODIFIER
    val REIFIED_KEYWORD: SyntaxElementType = Vendored.REIFIED_MODIFIER // upstream: REIFIED_MODIFIER
    val DYNAMIC_KEYWORD: SyntaxElementType = Vendored.DYNAMIC_KEYWORD
    val COMPANION_KEYWORD: SyntaxElementType = Vendored.COMPANION_MODIFIER // upstream: COMPANION_MODIFIER
    val SEALED_KEYWORD: SyntaxElementType = Vendored.SEALED_MODIFIER // upstream: SEALED_MODIFIER
    val FINALLY_KEYWORD: SyntaxElementType = Vendored.FINALLY_KEYWORD
    val FINAL_KEYWORD: SyntaxElementType = Vendored.FINAL_MODIFIER // upstream: FINAL_MODIFIER
    val LATEINIT_KEYWORD: SyntaxElementType = Vendored.LATEINIT_MODIFIER // upstream: LATEINIT_MODIFIER
    val DATA_KEYWORD: SyntaxElementType = Vendored.DATA_MODIFIER // upstream: DATA_MODIFIER
    val VALUE_KEYWORD: SyntaxElementType = Vendored.VALUE_MODIFIER // upstream: VALUE_MODIFIER
    val INLINE_KEYWORD: SyntaxElementType = Vendored.INLINE_MODIFIER // upstream: INLINE_MODIFIER
    val NOINLINE_KEYWORD: SyntaxElementType = Vendored.NOINLINE_MODIFIER // upstream: NOINLINE_MODIFIER
    val TAILREC_KEYWORD: SyntaxElementType = Vendored.TAILREC_MODIFIER // upstream: TAILREC_MODIFIER
    val EXTERNAL_KEYWORD: SyntaxElementType = Vendored.EXTERNAL_MODIFIER // upstream: EXTERNAL_MODIFIER
    val ANNOTATION_KEYWORD: SyntaxElementType = Vendored.ANNOTATION_MODIFIER // upstream: ANNOTATION_MODIFIER
    val CROSSINLINE_KEYWORD: SyntaxElementType = Vendored.CROSSINLINE_MODIFIER // upstream: CROSSINLINE_MODIFIER
    val OPERATOR_KEYWORD: SyntaxElementType = Vendored.OPERATOR_MODIFIER // upstream: OPERATOR_MODIFIER
    val INFIX_KEYWORD: SyntaxElementType = Vendored.INFIX_MODIFIER // upstream: INFIX_MODIFIER
    val ERROR_KEYWORD: SyntaxElementType = Vendored.ERROR_MODIFIER // upstream: ERROR_MODIFIER
    val CONST_KEYWORD: SyntaxElementType = Vendored.CONST_MODIFIER // upstream: CONST_MODIFIER
    val SUSPEND_KEYWORD: SyntaxElementType = Vendored.SUSPEND_MODIFIER // upstream: SUSPEND_MODIFIER
    val EXPECT_KEYWORD: SyntaxElementType = Vendored.EXPECT_MODIFIER // upstream: EXPECT_MODIFIER
    val ACTUAL_KEYWORD: SyntaxElementType = Vendored.ACTUAL_MODIFIER // upstream: ACTUAL_MODIFIER
    val SOFT_KEYWORDS_AND_MODIFIERS: SyntaxElementTypeSet = Vendored.SOFT_KEYWORDS_AND_MODIFIERS
    val HARD_KEYWORDS_AND_MODIFIERS: SyntaxElementTypeSet = Vendored.HARD_KEYWORDS_AND_MODIFIERS
    val MODIFIERS: SyntaxElementTypeSet = Vendored.MODIFIERS
    val TYPE_MODIFIER_KEYWORDS: SyntaxElementTypeSet = Vendored.TYPE_MODIFIER_KEYWORDS
    val TYPE_ARGUMENT_MODIFIER_KEYWORDS: SyntaxElementTypeSet = Vendored.TYPE_ARGUMENT_MODIFIER_KEYWORDS
    val RESERVED_VALUE_PARAMETER_MODIFIER_KEYWORDS: SyntaxElementTypeSet = Vendored.RESERVED_VALUE_PARAMETER_MODIFIER_KEYWORDS
    val WHITESPACES: SyntaxElementTypeSet = Vendored.WHITESPACES
    val COMMENTS: SyntaxElementTypeSet = Vendored.COMMENTS
    val WHITE_SPACE_OR_COMMENT_BIT_SET: SyntaxElementTypeSet = Vendored.WHITE_SPACE_OR_COMMENT_BIT_SET
    val VAL_VAR: SyntaxElementTypeSet = Vendored.VAL_VAR

    /** Upstream's `KEYWORDS` is the hard keywords; the vendored vocabulary folds modifiers in with them. */
    val KEYWORDS: SyntaxElementTypeSet = Vendored.HARD_KEYWORDS_AND_MODIFIERS

    val MODIFIER_KEYWORDS: SyntaxElementTypeSet = Vendored.MODIFIERS
}

/**
 * Upstream a modifier keyword has a type of its own, `KtModifierKeywordToken`, so a function can take one
 * and not any token. The vendored vocabulary has a single element type, so this is an alias: it keeps the
 * call sites readable and gives up the checking they had.
 */
typealias KtModifierKeywordToken = SyntaxElementType

/** `TokenSet.getTypes()` upstream, which hands back an array rather than the set. */
val SyntaxElementTypeSet.types: List<SyntaxElementType> get() = toList()
