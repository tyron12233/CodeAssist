package dev.ide.kotlin.syntax.lexer

import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.TokenSet
import dev.ide.kotlin.syntax.tree.TokenType

/**
 * The Kotlin token vocabulary, mirroring `org.jetbrains.kotlin.lexer.KtTokens` field for field.
 *
 * The names are the compiler's names, not better ones: `KtTokensParityTest` reflects over the real
 * `KtTokens` and fails if this object is missing a field it declares, which is only a usable check while the
 * two agree on spelling. The same goes for the values (`LBRACE` is `{`), which the lexer tests assert.
 *
 * Soft keywords are declared here but are NEVER emitted by [KotlinLexer]: the lexer gives IDENTIFIER and the
 * parser promotes it where the grammar expects one. That is the compiler's division too, and it is what lets
 * `data`, `value` and `by` stay usable as ordinary names.
 */
object KtTokens {

    // --- trivia and end of input -------------------------------------------------------------------------
    val EOF: KtToken = KtToken("EOF")
    val RESERVED: KtToken = KtToken("RESERVED")
    val BLOCK_COMMENT: KtToken = KtToken("BLOCK_COMMENT")
    val EOL_COMMENT: KtToken = KtToken("EOL_COMMENT")
    val SHEBANG_COMMENT: KtToken = KtToken("SHEBANG_COMMENT")

    /** `/** … */`. The compiler routes this through KDoc's own element type; here it is one token. */
    val DOC_COMMENT: KtToken = KtToken("DOC_COMMENT")
    val KDOC: KtToken = DOC_COMMENT

    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    // --- literals ----------------------------------------------------------------------------------------
    val INTEGER_LITERAL: KtToken = KtToken("INTEGER_LITERAL")
    val FLOAT_LITERAL: KtToken = KtToken("FLOAT_CONSTANT")
    val CHARACTER_LITERAL: KtToken = KtToken("CHARACTER_LITERAL")

    // --- string templates --------------------------------------------------------------------------------
    // A string is lexed as a sequence, not one token, because its interpolations contain arbitrary
    // expressions that the parser has to see: OPEN_QUOTE (REGULAR_STRING_PART | ESCAPE_SEQUENCE |
    // SHORT_TEMPLATE_ENTRY_START IDENTIFIER | LONG_TEMPLATE_ENTRY_START … LONG_TEMPLATE_ENTRY_END)* CLOSING_QUOTE.
    val INTERPOLATION_PREFIX: KtToken = KtToken("INTERPOLATION_PREFIX")
    val OPEN_QUOTE: KtToken = KtToken("OPEN_QUOTE")
    val CLOSING_QUOTE: KtToken = KtToken("CLOSING_QUOTE")
    val REGULAR_STRING_PART: KtToken = KtToken("REGULAR_STRING_PART")
    val ESCAPE_SEQUENCE: KtToken = KtToken("ESCAPE_SEQUENCE")
    val SHORT_TEMPLATE_ENTRY_START: KtToken = KtToken("SHORT_TEMPLATE_ENTRY_START")
    val LONG_TEMPLATE_ENTRY_START: KtToken = KtToken("LONG_TEMPLATE_ENTRY_START")
    val LONG_TEMPLATE_ENTRY_END: KtToken = KtToken("LONG_TEMPLATE_ENTRY_END")
    val DANGLING_NEWLINE: KtToken = KtToken("DANGLING_NEWLINE")

    // --- names -------------------------------------------------------------------------------------------
    val IDENTIFIER: KtToken = KtToken("IDENTIFIER")
    val FIELD_IDENTIFIER: KtToken = KtToken("FIELD_IDENTIFIER")

    // --- punctuation and operators -----------------------------------------------------------------------
    val LPAR: KtSingleValueToken = KtSingleValueToken("LPAR", "(")
    val RPAR: KtSingleValueToken = KtSingleValueToken("RPAR", ")")
    val LBRACKET: KtSingleValueToken = KtSingleValueToken("LBRACKET", "[")
    val RBRACKET: KtSingleValueToken = KtSingleValueToken("RBRACKET", "]")
    val LBRACE: KtSingleValueToken = KtSingleValueToken("LBRACE", "{")
    val RBRACE: KtSingleValueToken = KtSingleValueToken("RBRACE", "}")

    val MUL: KtSingleValueToken = KtSingleValueToken("MUL", "*")
    val PLUS: KtSingleValueToken = KtSingleValueToken("PLUS", "+")
    val MINUS: KtSingleValueToken = KtSingleValueToken("MINUS", "-")
    val EXCL: KtSingleValueToken = KtSingleValueToken("EXCL", "!")
    val DIV: KtSingleValueToken = KtSingleValueToken("DIV", "/")
    val PERC: KtSingleValueToken = KtSingleValueToken("PERC", "%")
    val LT: KtSingleValueToken = KtSingleValueToken("LT", "<")
    val GT: KtSingleValueToken = KtSingleValueToken("GT", ">")
    val LTEQ: KtSingleValueToken = KtSingleValueToken("LTEQ", "<=")
    val GTEQ: KtSingleValueToken = KtSingleValueToken("GTEQ", ">=")
    val EQEQEQ: KtSingleValueToken = KtSingleValueToken("EQEQEQ", "===")
    val EXCLEQEQEQ: KtSingleValueToken = KtSingleValueToken("EXCLEQEQEQ", "!==")
    val EQEQ: KtSingleValueToken = KtSingleValueToken("EQEQ", "==")
    val EXCLEQ: KtSingleValueToken = KtSingleValueToken("EXCLEQ", "!=")
    val EXCLEXCL: KtSingleValueToken = KtSingleValueToken("EXCLEXCL", "!!")
    val ANDAND: KtSingleValueToken = KtSingleValueToken("ANDAND", "&&")
    val AND: KtSingleValueToken = KtSingleValueToken("AND", "&")
    val OROR: KtSingleValueToken = KtSingleValueToken("OROR", "||")
    val SAFE_ACCESS: KtSingleValueToken = KtSingleValueToken("SAFE_ACCESS", "?.")
    val ELVIS: KtSingleValueToken = KtSingleValueToken("ELVIS", "?:")
    val QUEST: KtSingleValueToken = KtSingleValueToken("QUEST", "?")
    val COLONCOLON: KtSingleValueToken = KtSingleValueToken("COLONCOLON", "::")
    val COLON: KtSingleValueToken = KtSingleValueToken("COLON", ":")
    val SEMICOLON: KtSingleValueToken = KtSingleValueToken("SEMICOLON", ";")
    val DOUBLE_SEMICOLON: KtSingleValueToken = KtSingleValueToken("DOUBLE_SEMICOLON", ";;")
    val RANGE: KtSingleValueToken = KtSingleValueToken("RANGE", "..")
    val RANGE_UNTIL: KtSingleValueToken = KtSingleValueToken("RANGE_UNTIL", "..<")
    val EQ: KtSingleValueToken = KtSingleValueToken("EQ", "=")
    val MULTEQ: KtSingleValueToken = KtSingleValueToken("MULTEQ", "*=")
    val DIVEQ: KtSingleValueToken = KtSingleValueToken("DIVEQ", "/=")
    val PERCEQ: KtSingleValueToken = KtSingleValueToken("PERCEQ", "%=")
    val PLUSEQ: KtSingleValueToken = KtSingleValueToken("PLUSEQ", "+=")
    val MINUSEQ: KtSingleValueToken = KtSingleValueToken("MINUSEQ", "-=")
    val PLUSPLUS: KtSingleValueToken = KtSingleValueToken("PLUSPLUS", "++")
    val MINUSMINUS: KtSingleValueToken = KtSingleValueToken("MINUSMINUS", "--")
    val HASH: KtSingleValueToken = KtSingleValueToken("HASH", "#")
    val AT: KtSingleValueToken = KtSingleValueToken("AT", "@")
    val COMMA: KtSingleValueToken = KtSingleValueToken("COMMA", ",")
    val DOT: KtSingleValueToken = KtSingleValueToken("DOT", ".")
    val ARROW: KtSingleValueToken = KtSingleValueToken("ARROW", "->")
    val DOUBLE_ARROW: KtSingleValueToken = KtSingleValueToken("DOUBLE_ARROW", "=>")

    // `!in` / `!is`: two tokens in the text, one operation to the grammar. The lexer emits EXCL then the
    // keyword; the parser joins them, which is why they carry a spelling with a space in it here.
    val NOT_IN: KtKeywordToken = KtKeywordToken.keyword("NOT_IN", "!in")
    val NOT_IS: KtKeywordToken = KtKeywordToken.keyword("NOT_IS", "!is")

    // --- hard keywords -----------------------------------------------------------------------------------
    val PACKAGE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("PACKAGE_KEYWORD", "package")
    val AS_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("AS_KEYWORD", "as")
    val TYPE_ALIAS_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("TYPE_ALIAS_KEYWORD", "typealias")
    val CLASS_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("CLASS_KEYWORD", "class")
    val THIS_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("THIS_KEYWORD", "this")
    val SUPER_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("SUPER_KEYWORD", "super")
    val VAL_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("VAL_KEYWORD", "val")
    val VAR_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("VAR_KEYWORD", "var")
    val FUN_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("FUN_KEYWORD", "fun")
    val FOR_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("FOR_KEYWORD", "for")
    val NULL_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("NULL_KEYWORD", "null")
    val TRUE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("TRUE_KEYWORD", "true")
    val FALSE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("FALSE_KEYWORD", "false")
    val IS_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("IS_KEYWORD", "is")
    val IN_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("IN_KEYWORD", "in")
    val THROW_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("THROW_KEYWORD", "throw")
    val RETURN_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("RETURN_KEYWORD", "return")
    val BREAK_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("BREAK_KEYWORD", "break")
    val CONTINUE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("CONTINUE_KEYWORD", "continue")
    val OBJECT_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("OBJECT_KEYWORD", "object")
    val IF_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("IF_KEYWORD", "if")
    val TRY_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("TRY_KEYWORD", "try")
    val ELSE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("ELSE_KEYWORD", "else")
    val WHILE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("WHILE_KEYWORD", "while")
    val DO_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("DO_KEYWORD", "do")
    val WHEN_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("WHEN_KEYWORD", "when")
    val INTERFACE_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("INTERFACE_KEYWORD", "interface")
    val TYPEOF_KEYWORD: KtKeywordToken = KtKeywordToken.keyword("TYPEOF_KEYWORD", "typeof")
    val IMPORT_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("IMPORT_KEYWORD", "import")
    val AS_SAFE: KtKeywordToken = KtKeywordToken.keyword("AS_SAFE", "as?")

    // --- soft keywords -----------------------------------------------------------------------------------
    val FILE_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("FILE_KEYWORD", "file")
    val FIELD_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("FIELD_KEYWORD", "field")
    val PROPERTY_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("PROPERTY_KEYWORD", "property")
    val RECEIVER_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("RECEIVER_KEYWORD", "receiver")
    val PARAM_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("PARAM_KEYWORD", "param")
    val SETPARAM_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("SETPARAM_KEYWORD", "setparam")
    val DELEGATE_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("DELEGATE_KEYWORD", "delegate")
    val ALL_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("ALL_KEYWORD", "all")
    val GET_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("GET_KEYWORD", "get")
    val SET_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("SET_KEYWORD", "set")
    val CONSTRUCTOR_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("CONSTRUCTOR_KEYWORD", "constructor")
    val BY_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("BY_KEYWORD", "by")

    val INIT_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("INIT_KEYWORD", "init")
    val WHERE_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("WHERE_KEYWORD", "where")
    val CATCH_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("CATCH_KEYWORD", "catch")
    val FINALLY_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("FINALLY_KEYWORD", "finally")

    val DYNAMIC_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("DYNAMIC_KEYWORD", "dynamic")
    val CONTRACT_KEYWORD: KtKeywordToken = KtKeywordToken.softKeyword("CONTRACT_KEYWORD", "contract")

    // --- modifier keywords (all soft) --------------------------------------------------------------------
    // Order matters in an object: a `val` that reads a later one sees null, so every modifier is declared
    // before the sets built from them.
    //
    // `companion` and `out` are modifiers rather than plain soft keywords, because they appear in a modifier
    // list (`companion object`, `class Box<out T>`) and matching is by modifier type.
    val COMPANION_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("COMPANION_KEYWORD", "companion")
    val OUT_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("OUT_KEYWORD", "out")
    val PUBLIC_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("PUBLIC_KEYWORD", "public")
    val PRIVATE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("PRIVATE_KEYWORD", "private")
    val PROTECTED_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("PROTECTED_KEYWORD", "protected")
    val INTERNAL_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("INTERNAL_KEYWORD", "internal")
    val ENUM_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("ENUM_KEYWORD", "enum")
    val OPEN_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("OPEN_KEYWORD", "open")
    val INNER_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("INNER_KEYWORD", "inner")
    val OVERRIDE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("OVERRIDE_KEYWORD", "override")
    val ABSTRACT_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("ABSTRACT_KEYWORD", "abstract")
    val FINAL_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("FINAL_KEYWORD", "final")
    val VARARG_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("VARARG_KEYWORD", "vararg")
    val REIFIED_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("REIFIED_KEYWORD", "reified")
    val ANNOTATION_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("ANNOTATION_KEYWORD", "annotation")
    val DATA_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("DATA_KEYWORD", "data")
    val INLINE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("INLINE_KEYWORD", "inline")
    val NOINLINE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("NOINLINE_KEYWORD", "noinline")
    val TAILREC_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("TAILREC_KEYWORD", "tailrec")
    val EXTERNAL_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("EXTERNAL_KEYWORD", "external")
    val OPERATOR_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("OPERATOR_KEYWORD", "operator")
    val INFIX_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("INFIX_KEYWORD", "infix")
    val CONST_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("CONST_KEYWORD", "const")
    val SUSPEND_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("SUSPEND_KEYWORD", "suspend")
    val LATEINIT_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("LATEINIT_KEYWORD", "lateinit")
    val CROSSINLINE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("CROSSINLINE_KEYWORD", "crossinline")
    val SEALED_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("SEALED_KEYWORD", "sealed")
    val EXPECT_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("EXPECT_KEYWORD", "expect")
    val ACTUAL_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("ACTUAL_KEYWORD", "actual")
    val VALUE_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("VALUE_KEYWORD", "value")
    val CONTEXT_KEYWORD: KtModifierKeywordToken = KtModifierKeywordToken.softKeywordModifier("CONTEXT_KEYWORD", "context")


    /** The compiler's alias: an unmarked declaration is `public`. */
    val DEFAULT_VISIBILITY_KEYWORD: KtModifierKeywordToken = PUBLIC_KEYWORD

    // --- token sets --------------------------------------------------------------------------------------
    val KEYWORDS: TokenSet = TokenSet.create(
        PACKAGE_KEYWORD, AS_KEYWORD, TYPE_ALIAS_KEYWORD, CLASS_KEYWORD, INTERFACE_KEYWORD, THIS_KEYWORD,
        SUPER_KEYWORD, VAL_KEYWORD, VAR_KEYWORD, FUN_KEYWORD, FOR_KEYWORD, NULL_KEYWORD, TRUE_KEYWORD,
        FALSE_KEYWORD, IS_KEYWORD, IN_KEYWORD, THROW_KEYWORD, RETURN_KEYWORD, BREAK_KEYWORD, CONTINUE_KEYWORD,
        OBJECT_KEYWORD, IF_KEYWORD, ELSE_KEYWORD, WHILE_KEYWORD, DO_KEYWORD, TRY_KEYWORD, WHEN_KEYWORD,
        NOT_IN, NOT_IS, AS_SAFE, TYPEOF_KEYWORD,
    )

    val SOFT_KEYWORDS: TokenSet = TokenSet.create(
        FILE_KEYWORD, IMPORT_KEYWORD, WHERE_KEYWORD, BY_KEYWORD, GET_KEYWORD, SET_KEYWORD, ABSTRACT_KEYWORD,
        ENUM_KEYWORD, OPEN_KEYWORD, INNER_KEYWORD, OVERRIDE_KEYWORD, PRIVATE_KEYWORD, PUBLIC_KEYWORD,
        INTERNAL_KEYWORD, PROTECTED_KEYWORD, CATCH_KEYWORD, FINALLY_KEYWORD, OUT_KEYWORD, FINAL_KEYWORD,
        VARARG_KEYWORD, REIFIED_KEYWORD, DYNAMIC_KEYWORD, COMPANION_KEYWORD, CONSTRUCTOR_KEYWORD, INIT_KEYWORD,
        SEALED_KEYWORD, FIELD_KEYWORD, PROPERTY_KEYWORD, RECEIVER_KEYWORD, PARAM_KEYWORD, SETPARAM_KEYWORD,
        DELEGATE_KEYWORD, ANNOTATION_KEYWORD, DATA_KEYWORD, INLINE_KEYWORD, NOINLINE_KEYWORD, TAILREC_KEYWORD,
        EXTERNAL_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD, CONST_KEYWORD, SUSPEND_KEYWORD, LATEINIT_KEYWORD,
        CROSSINLINE_KEYWORD, EXPECT_KEYWORD, ACTUAL_KEYWORD, VALUE_KEYWORD, CONTEXT_KEYWORD, ALL_KEYWORD,
        CONTRACT_KEYWORD,
    )

    val MODIFIER_KEYWORDS_ARRAY: Array<KtModifierKeywordToken> = arrayOf(
        ABSTRACT_KEYWORD, ENUM_KEYWORD, OPEN_KEYWORD, INNER_KEYWORD, OVERRIDE_KEYWORD, PRIVATE_KEYWORD,
        PUBLIC_KEYWORD, INTERNAL_KEYWORD, PROTECTED_KEYWORD, OUT_KEYWORD, VARARG_KEYWORD, REIFIED_KEYWORD,
        COMPANION_KEYWORD, SEALED_KEYWORD, FINAL_KEYWORD, ANNOTATION_KEYWORD, DATA_KEYWORD, INLINE_KEYWORD,
        NOINLINE_KEYWORD, TAILREC_KEYWORD, EXTERNAL_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD, CONST_KEYWORD,
        SUSPEND_KEYWORD, LATEINIT_KEYWORD, CROSSINLINE_KEYWORD, EXPECT_KEYWORD, ACTUAL_KEYWORD, VALUE_KEYWORD,
        CONTEXT_KEYWORD,
    )

    val MODIFIER_KEYWORDS: TokenSet = TokenSet.create(*MODIFIER_KEYWORDS_ARRAY)

    val VISIBILITY_MODIFIERS: TokenSet =
        TokenSet.create(PRIVATE_KEYWORD, PUBLIC_KEYWORD, INTERNAL_KEYWORD, PROTECTED_KEYWORD)

    val MODALITY_MODIFIERS: TokenSet =
        TokenSet.create(ABSTRACT_KEYWORD, FINAL_KEYWORD, SEALED_KEYWORD, OPEN_KEYWORD)

    val TYPE_MODIFIER_KEYWORDS: TokenSet = TokenSet.create(SUSPEND_KEYWORD)
    val TYPE_ARGUMENT_MODIFIER_KEYWORDS: TokenSet = TokenSet.create(IN_KEYWORD, OUT_KEYWORD)

    val COMMENTS: TokenSet = TokenSet.create(EOL_COMMENT, BLOCK_COMMENT, DOC_COMMENT, SHEBANG_COMMENT)
    val WHITESPACES: TokenSet = TokenSet.create(WHITE_SPACE)
    val WHITE_SPACE_OR_COMMENT_BIT_SET: TokenSet = TokenSet.orSet(COMMENTS, WHITESPACES)

    val STRINGS: TokenSet = TokenSet.create(CHARACTER_LITERAL, REGULAR_STRING_PART)
    val OPERATIONS: TokenSet = TokenSet.create(
        AS_KEYWORD, AS_SAFE, IS_KEYWORD, IN_KEYWORD, DOT, PLUSPLUS, MINUSMINUS, EXCLEXCL, MUL, PLUS, MINUS,
        EXCL, DIV, PERC, LT, GT, LTEQ, GTEQ, EQEQEQ, EXCLEQEQEQ, EQEQ, EXCLEQ, ANDAND, OROR, SAFE_ACCESS,
        ELVIS, RANGE, RANGE_UNTIL, EQ, MULTEQ, DIVEQ, PERCEQ, PLUSEQ, MINUSEQ, NOT_IN, NOT_IS, IDENTIFIER,
    )

    val AUGMENTED_ASSIGNMENTS: TokenSet = TokenSet.create(PLUSEQ, MINUSEQ, MULTEQ, PERCEQ, DIVEQ)
    val ALL_ASSIGNMENTS: TokenSet = TokenSet.create(EQ, PLUSEQ, MINUSEQ, MULTEQ, PERCEQ, DIVEQ)
    val INCREMENT_AND_DECREMENT: TokenSet = TokenSet.create(PLUSPLUS, MINUSMINUS)
    val QUALIFIED_ACCESS: TokenSet = TokenSet.create(DOT, SAFE_ACCESS)
    val VAL_VAR: TokenSet = TokenSet.create(VAL_KEYWORD, VAR_KEYWORD)

    /** What ends a statement: a semicolon, or the line break the parser reads off the whitespace. */
    val EOL_OR_SEMICOLON: TokenSet = TokenSet.create(SEMICOLON, WHITE_SPACE)

    /** Every hard keyword by spelling, for the lexer's identifier-or-keyword decision. */
    internal val HARD_KEYWORD_BY_TEXT: Map<String, KtKeywordToken> =
        KEYWORDS.elements.asSequence()
            .filterIsInstance<KtKeywordToken>()
            .filter { !it.isSoft && it.value.all { c -> c.isLetter() } }
            .associateBy { it.value }
}
