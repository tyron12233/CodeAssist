package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.TokenSet

/**
 * The expression half of the grammar, mirroring `org.jetbrains.kotlin.parsing.KotlinExpressionParsing`.
 *
 * Binary operators are handled by one precedence-climbing loop over [Precedence] rather than by a rule per
 * level, which is how the compiler does it too. The ordering in that enum is not obvious and is worth reading
 * before changing anything: an infix function call binds TIGHTER than `?:` but LOOSER than `..`, so
 * `a ?: b to c` is `a ?: (b to c)` while `a..b step c` is `(a..b) step c`.
 *
 * The other thing to know is how qualified access is shaped. `foo.bar()` is not a call with a dotted name; it
 * is DOT_QUALIFIED_EXPRESSION(REFERENCE_EXPRESSION, CALL_EXPRESSION(REFERENCE_EXPRESSION, VALUE_ARGUMENT_LIST)),
 * with the call wrapping only the selector. Every postfix suffix is built the same way, by preceding what has
 * been parsed so far, which is what makes the nesting come out left-associative.
 */
class KotlinExpressionParsing(
    builder: SyntaxTreeBuilder,
    private val declarationParsing: KotlinParsing,
) : AbstractKotlinParsing(builder) {

    /**
     * Binary operator precedence, loosest first, mirroring the compiler's `Precedence` enum. The loop in
     * [parseBinaryExpression] walks this from the outside in.
     */
    private enum class Precedence(val operations: TokenSet, val nodeType: IElementType = KtNodeTypes.BINARY_EXPRESSION) {
        ASSIGNMENT(KtTokens.ALL_ASSIGNMENTS),
        DISJUNCTION(TokenSet.create(KtTokens.OROR)),
        CONJUNCTION(TokenSet.create(KtTokens.ANDAND)),
        EQUALITY(TokenSet.create(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)),
        COMPARISON(TokenSet.create(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ)),
        IN_OR_IS(TokenSet.create(KtTokens.IN_KEYWORD, KtTokens.NOT_IN, KtTokens.IS_KEYWORD, KtTokens.NOT_IS)),
        ELVIS(TokenSet.create(KtTokens.ELVIS)),
        INFIX_FUNCTION(TokenSet.create(KtTokens.IDENTIFIER)),
        RANGE(TokenSet.create(KtTokens.RANGE, KtTokens.RANGE_UNTIL)),
        ADDITIVE(TokenSet.create(KtTokens.PLUS, KtTokens.MINUS)),
        MULTIPLICATIVE(TokenSet.create(KtTokens.MUL, KtTokens.DIV, KtTokens.PERC)),

        /** `as` and `as?` bind tightest of the binary operators, and take a TYPE on the right. */
        AS(TokenSet.create(KtTokens.AS_KEYWORD, KtTokens.AS_SAFE), KtNodeTypes.BINARY_WITH_TYPE),
        ;

        companion object {
            val LOOSEST: Precedence = ASSIGNMENT
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------------------------------------

    /** A full expression, at the loosest precedence. */
    fun parseExpression() {
        parseBinaryExpression(Precedence.LOOSEST)
    }

    /**
     * `{ … }` as a block: a sequence of statements, each a declaration or an expression.
     *
     * Statements are separated by semicolons or by line breaks, so the loop has to make progress even when
     * neither is present, or a construct it does not understand would spin. [errorAndAdvance] is what
     * guarantees that.
     */
    fun parseBlock() {
        val marker = mark()
        builder.enableNewlines()
        expect(KtTokens.LBRACE, "Expecting '{'")
        parseStatements(KtTokens.RBRACE)
        expect(KtTokens.RBRACE, "Expecting '}'")
        builder.restoreNewlinesState()
        marker.done(KtNodeTypes.BLOCK)
    }

    private fun parseStatements(closing: IElementType) {
        while (!eof() && !at(closing)) {
            if (at(KtTokens.SEMICOLON)) {
                advance()
                continue
            }
            val before = builder.currentOffset
            parseStatement()
            if (builder.currentOffset == before) {
                errorAndAdvance("Expecting a statement")
            }
        }
    }

    private fun parseStatement() {
        if (declarationParsing.atDeclarationStart() && declarationParsing.parseDeclaration()) return
        parseExpression()
    }

    // -----------------------------------------------------------------------------------------------------
    // Binary operators
    // -----------------------------------------------------------------------------------------------------

    private fun parseBinaryExpression(precedence: Precedence) {
        val next = Precedence.entries.getOrNull(precedence.ordinal + 1)
        var marker = mark()
        if (next == null) parsePrefixExpression() else parseBinaryExpression(next)

        while (atOperation(precedence)) {
            // `is` and `as` take a type on the right rather than an expression, and produce their own
            // element rather than a plain binary one.
            val isTypeCheck = at(KtTokens.IS_KEYWORD) || at(KtTokens.NOT_IS)
            val isCast = at(KtTokens.AS_KEYWORD) || at(KtTokens.AS_SAFE)

            val operation = mark()
            advance()
            operation.done(KtNodeTypes.OPERATION_REFERENCE)

            val wrapper = marker.precede()
            when {
                isTypeCheck -> {
                    declarationParsing.parseTypeRef()
                    marker.done(KtNodeTypes.IS_EXPRESSION)
                }

                isCast -> {
                    declarationParsing.parseTypeRef()
                    marker.done(KtNodeTypes.BINARY_WITH_TYPE)
                }

                else -> {
                    if (next == null) parsePrefixExpression() else parseBinaryExpression(next)
                    marker.done(precedence.nodeType)
                }
            }
            marker = wrapper
        }
        marker.drop()
    }

    /**
     * Is the current token an operator at [precedence]?
     *
     * The infix-function level needs more than set membership: any identifier can be an infix operator, but
     * only when an expression follows on the SAME line. Without that check, `val a = b` followed by a line
     * starting with a name would swallow the next statement.
     */
    private fun atOperation(precedence: Precedence): Boolean {
        if (precedence == Precedence.INFIX_FUNCTION) {
            if (!at(KtTokens.IDENTIFIER)) return false
            if (builder.newlineBeforeCurrentToken()) return false
            val following = lookahead(1) ?: return false
            return following !in STATEMENT_BOUNDARY
        }
        if (!atSet(precedence.operations)) return false
        // A line break before a binary operator still continues the expression (the operator cannot start a
        // statement), but a break before the RIGHT operand of an assignment is a different statement.
        return true
    }

    // -----------------------------------------------------------------------------------------------------
    // Prefix and postfix
    // -----------------------------------------------------------------------------------------------------

    private fun parsePrefixExpression() {
        when {
            at(KtTokens.AT) && !declarationParsing.atDeclarationStart() -> {
                val marker = mark()
                declarationParsing.parseModifierList()
                parsePrefixExpression()
                marker.done(KtNodeTypes.ANNOTATED_EXPRESSION)
            }

            atLabelDefinition() -> {
                val marker = mark()
                parseLabelQualifier()
                parsePrefixExpression()
                marker.done(KtNodeTypes.LABELED_EXPRESSION)
            }

            atSet(PREFIX_OPERATIONS) -> {
                val marker = mark()
                val operation = mark()
                advance()
                operation.done(KtNodeTypes.OPERATION_REFERENCE)
                parsePrefixExpression()
                marker.done(KtNodeTypes.PREFIX_EXPRESSION)
            }

            else -> parsePostfixExpression()
        }
    }

    /** `label@ expr`: a name immediately followed by `@`, with nothing between them. */
    private fun atLabelDefinition(): Boolean =
        at(KtTokens.IDENTIFIER) && builder.rawLookup(1) === KtTokens.AT

    private fun parseLabelQualifier() {
        val marker = mark()
        advance() // name
        advance() // @
        marker.done(KtNodeTypes.LABEL_QUALIFIER)
    }

    private fun parsePostfixExpression() {
        var marker = mark()
        parseAtomicExpression()
        while (true) {
            val nodeType = when {
                at(KtTokens.DOT) -> {
                    val wrapper = marker.precede()
                    advance()
                    parseSelector()
                    marker.done(KtNodeTypes.DOT_QUALIFIED_EXPRESSION)
                    marker = wrapper
                    continue
                }

                at(KtTokens.SAFE_ACCESS) -> {
                    val wrapper = marker.precede()
                    advance()
                    parseSelector()
                    marker.done(KtNodeTypes.SAFE_ACCESS_EXPRESSION)
                    marker = wrapper
                    continue
                }

                at(KtTokens.LPAR) -> {
                    val wrapper = marker.precede()
                    parseValueArgumentList()
                    if (atTrailingLambda()) parseLambdaArgument()
                    marker.done(KtNodeTypes.CALL_EXPRESSION)
                    marker = wrapper
                    continue
                }

                atTrailingLambda() -> {
                    val wrapper = marker.precede()
                    parseLambdaArgument()
                    marker.done(KtNodeTypes.CALL_EXPRESSION)
                    marker = wrapper
                    continue
                }

                at(KtTokens.LBRACKET) -> {
                    val wrapper = marker.precede()
                    parseIndices()
                    marker.done(KtNodeTypes.ARRAY_ACCESS_EXPRESSION)
                    marker = wrapper
                    continue
                }

                at(KtTokens.COLONCOLON) -> {
                    val wrapper = marker.precede()
                    advance()
                    val type = if (at(KtTokens.CLASS_KEYWORD)) {
                        advance()
                        KtNodeTypes.CLASS_LITERAL_EXPRESSION
                    } else {
                        parseSimpleNameExpression()
                        KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION
                    }
                    marker.done(type)
                    marker = wrapper
                    continue
                }

                // `!!` reaches the parser as two adjacent `!`, because in PREFIX position the same two
                // tokens are a double negation. Position is what disambiguates, so the join happens here.
                at(KtTokens.EXCL) && builder.rawLookup(1) === KtTokens.EXCL -> {
                    val wrapper = marker.precede()
                    val operation = mark()
                    advance()
                    advance()
                    operation.done(KtNodeTypes.OPERATION_REFERENCE)
                    marker.done(KtNodeTypes.POSTFIX_EXPRESSION)
                    marker = wrapper
                    continue
                }

                at(KtTokens.PLUSPLUS) || at(KtTokens.MINUSMINUS) -> KtNodeTypes.POSTFIX_EXPRESSION

                else -> {
                    marker.drop()
                    return
                }
            }
            val wrapper = marker.precede()
            val operation = mark()
            advance()
            operation.done(KtNodeTypes.OPERATION_REFERENCE)
            marker.done(nodeType)
            marker = wrapper
        }
    }

    /** The part after a `.` or `?.`: a name, possibly called. */
    private fun parseSelector() {
        val marker = mark()
        if (at(KtTokens.IDENTIFIER)) {
            parseSimpleNameExpression()
        } else if (at(KtTokens.CLASS_KEYWORD)) {
            // `Foo::class.java` and friends: `class` is a legal selector name here.
            parseSimpleNameExpression()
        } else {
            marker.drop()
            error("Expecting a name")
            return
        }
        if (at(KtTokens.LT) && atTypeArgumentsBeforeCall()) declarationParsing.parseTypeArgumentList()
        if (at(KtTokens.LPAR)) {
            parseValueArgumentList()
            if (atTrailingLambda()) parseLambdaArgument()
            marker.done(KtNodeTypes.CALL_EXPRESSION)
        } else if (atTrailingLambda()) {
            parseLambdaArgument()
            marker.done(KtNodeTypes.CALL_EXPRESSION)
        } else {
            marker.drop()
        }
    }

    /**
     * Is the `<` here a type argument list rather than a less-than?
     *
     * Kotlin's classic ambiguity: `f<A>(b)` is a generic call and `f < A > (b)` is two comparisons. The only
     * honest answer is to try it, so the list is parsed speculatively and rewound unless a `(` or `{`
     * follows, which is the compiler's rule as well.
     */
    private fun atTypeArgumentsBeforeCall(): Boolean {
        val marker = mark()
        declarationParsing.parseTypeArgumentList()
        val isCall = at(KtTokens.LPAR) || at(KtTokens.LBRACE)
        marker.rollbackTo()
        return isCall
    }

    /** A lambda straight after a call, with no line break, is that call's last argument. */
    private fun atTrailingLambda(): Boolean = at(KtTokens.LBRACE) && !builder.newlineBeforeCurrentToken()

    private fun parseLambdaArgument() {
        val marker = mark()
        parseFunctionLiteral()
        marker.done(KtNodeTypes.LAMBDA_ARGUMENT)
    }

    private fun parseIndices() {
        val marker = mark()
        advance() // [
        builder.disableNewlines()
        while (!eof() && !at(KtTokens.RBRACKET)) {
            parseExpression()
            if (!consumeIf(KtTokens.COMMA)) break
        }
        builder.restoreNewlinesState()
        expect(KtTokens.RBRACKET, "Expecting ']'")
        marker.done(KtNodeTypes.INDICES)
    }

    /** `(a, name = b, *c)`. */
    internal fun parseValueArgumentList() {
        val marker = mark()
        expect(KtTokens.LPAR, "Expecting '('")
        builder.disableNewlines()
        while (!eof() && !at(KtTokens.RPAR)) {
            val argument = mark()
            if (at(KtTokens.IDENTIFIER) && lookahead(1) === KtTokens.EQ) {
                val name = mark()
                parseSimpleNameExpression()
                name.done(KtNodeTypes.VALUE_ARGUMENT_NAME)
                advance() // =
            }
            consumeIf(KtTokens.MUL) // spread
            parseExpression()
            argument.done(KtNodeTypes.VALUE_ARGUMENT)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        builder.restoreNewlinesState()
        expect(KtTokens.RPAR, "Expecting ')'")
        marker.done(KtNodeTypes.VALUE_ARGUMENT_LIST)
    }

    // -----------------------------------------------------------------------------------------------------
    // Atoms
    // -----------------------------------------------------------------------------------------------------

    private fun parseAtomicExpression() {
        when {
            at(KtTokens.LPAR) -> parseParenthesized()
            at(KtTokens.LBRACKET) -> parseCollectionLiteral()
            at(KtTokens.LBRACE) -> parseFunctionLiteral()
            at(KtTokens.INTEGER_LITERAL) -> parseLiteral(KtNodeTypes.INTEGER_CONSTANT)
            at(KtTokens.FLOAT_LITERAL) -> parseLiteral(KtNodeTypes.FLOAT_CONSTANT)
            at(KtTokens.CHARACTER_LITERAL) -> parseLiteral(KtNodeTypes.CHARACTER_CONSTANT)
            at(KtTokens.TRUE_KEYWORD) || at(KtTokens.FALSE_KEYWORD) -> parseLiteral(KtNodeTypes.BOOLEAN_CONSTANT)
            at(KtTokens.NULL_KEYWORD) -> parseLiteral(KtNodeTypes.NULL)
            at(KtTokens.OPEN_QUOTE) || at(KtTokens.INTERPOLATION_PREFIX) -> parseStringTemplate()
            at(KtTokens.IF_KEYWORD) -> parseIf()
            at(KtTokens.WHEN_KEYWORD) -> parseWhen()
            at(KtTokens.TRY_KEYWORD) -> parseTry()
            at(KtTokens.FOR_KEYWORD) -> parseFor()
            at(KtTokens.WHILE_KEYWORD) -> parseWhile()
            at(KtTokens.DO_KEYWORD) -> parseDoWhile()
            at(KtTokens.OBJECT_KEYWORD) -> parseObjectLiteral()
            at(KtTokens.FUN_KEYWORD) -> parseAnonymousFunction()
            at(KtTokens.THIS_KEYWORD) -> parseThisOrSuper(KtNodeTypes.THIS_EXPRESSION)
            at(KtTokens.SUPER_KEYWORD) -> parseThisOrSuper(KtNodeTypes.SUPER_EXPRESSION)
            at(KtTokens.RETURN_KEYWORD) -> parseReturn()
            at(KtTokens.THROW_KEYWORD) -> parseOneOperandKeyword(KtTokens.THROW_KEYWORD, KtNodeTypes.THROW)
            at(KtTokens.BREAK_KEYWORD) -> parseJump(KtNodeTypes.BREAK)
            at(KtTokens.CONTINUE_KEYWORD) -> parseJump(KtNodeTypes.CONTINUE)
            at(KtTokens.COLONCOLON) -> parseFreeCallableReference()
            at(KtTokens.IDENTIFIER) -> parseSimpleNameExpression()
            else -> errorAndAdvance("Expecting an expression")
        }
    }

    private fun parseLiteral(type: IElementType) {
        val marker = mark()
        advance()
        marker.done(type)
    }

    internal fun parseSimpleNameExpression() {
        val marker = mark()
        advance()
        marker.done(KtNodeTypes.REFERENCE_EXPRESSION)
    }

    private fun parseParenthesized() {
        val marker = mark()
        advance() // (
        builder.disableNewlines()
        if (!at(KtTokens.RPAR)) parseExpression()
        builder.restoreNewlinesState()
        expect(KtTokens.RPAR, "Expecting ')'")
        marker.done(KtNodeTypes.PARENTHESIZED)
    }

    /** `[a, b]`, legal only in an annotation argument, but parsed anywhere so the tree stays complete. */
    private fun parseCollectionLiteral() {
        val marker = mark()
        advance() // [
        builder.disableNewlines()
        while (!eof() && !at(KtTokens.RBRACKET)) {
            parseExpression()
            if (!consumeIf(KtTokens.COMMA)) break
        }
        builder.restoreNewlinesState()
        expect(KtTokens.RBRACKET, "Expecting ']'")
        marker.done(KtNodeTypes.COLLECTION_LITERAL_EXPRESSION)
    }

    /**
     * `{ a, b -> … }`.
     *
     * The parameters before `->` are only parameters if the arrow is there, and a lambda body can contain an
     * arrow of its own inside a nested `when`, so the prefix is parsed speculatively rather than scanned for.
     */
    private fun parseFunctionLiteral() {
        val outer = mark()
        val literal = mark()
        expect(KtTokens.LBRACE, "Expecting '{'")
        builder.enableNewlines()

        val probe = mark()
        val hasParameters = tryParseLambdaParameters()
        if (hasParameters) probe.drop() else probe.rollbackTo()

        val body = mark()
        parseStatements(KtTokens.RBRACE)
        body.done(KtNodeTypes.BLOCK)

        builder.restoreNewlinesState()
        expect(KtTokens.RBRACE, "Expecting '}'")
        literal.done(KtNodeTypes.FUNCTION_LITERAL)
        outer.done(KtNodeTypes.LAMBDA_EXPRESSION)
    }

    private fun tryParseLambdaParameters(): Boolean {
        if (at(KtTokens.ARROW)) {
            advance()
            return true
        }
        val list = mark()
        while (!eof() && !at(KtTokens.ARROW) && !at(KtTokens.RBRACE)) {
            val parameter = mark()
            when {
                at(KtTokens.LPAR) -> parseLambdaDestructuringParameter()
                at(KtTokens.IDENTIFIER) -> advance()
                else -> {
                    parameter.drop()
                    list.drop()
                    return false
                }
            }
            if (at(KtTokens.COLON)) {
                advance()
                declarationParsing.parseTypeRef()
            }
            parameter.done(KtNodeTypes.VALUE_PARAMETER)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        if (!at(KtTokens.ARROW)) {
            list.drop()
            return false
        }
        list.done(KtNodeTypes.VALUE_PARAMETER_LIST)
        advance() // ->
        return true
    }

    private fun parseLambdaDestructuringParameter() {
        val marker = mark()
        advance() // (
        while (!eof() && !at(KtTokens.RPAR)) {
            val entry = mark()
            if (at(KtTokens.IDENTIFIER)) advance() else errorAndAdvance("Expecting a name")
            if (at(KtTokens.COLON)) {
                advance()
                declarationParsing.parseTypeRef()
            }
            entry.done(KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        expect(KtTokens.RPAR, "Expecting ')'")
        marker.done(KtNodeTypes.DESTRUCTURING_DECLARATION)
    }

    /**
     * A string, as the sequence of entries the lexer produced. The entry elements are what makes an
     * interpolated expression reachable from the tree, which completion inside `"${…}"` depends on.
     */
    private fun parseStringTemplate() {
        val marker = mark()
        if (at(KtTokens.INTERPOLATION_PREFIX)) {
            val prefix = mark()
            advance()
            prefix.done(KtNodeTypes.STRING_INTERPOLATION_PREFIX)
        }
        advance() // open quote
        while (!eof() && !at(KtTokens.CLOSING_QUOTE) && !at(KtTokens.DANGLING_NEWLINE)) {
            val entry = mark()
            when {
                at(KtTokens.REGULAR_STRING_PART) -> {
                    advance()
                    entry.done(KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY)
                }

                at(KtTokens.ESCAPE_SEQUENCE) -> {
                    advance()
                    entry.done(KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY)
                }

                at(KtTokens.SHORT_TEMPLATE_ENTRY_START) -> {
                    advance()
                    if (at(KtTokens.IDENTIFIER)) parseSimpleNameExpression() else error("Expecting a name")
                    entry.done(KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY)
                }

                at(KtTokens.LONG_TEMPLATE_ENTRY_START) -> {
                    advance()
                    builder.disableNewlines()
                    parseExpression()
                    builder.restoreNewlinesState()
                    expect(KtTokens.LONG_TEMPLATE_ENTRY_END, "Expecting '}'")
                    entry.done(KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY)
                }

                else -> {
                    entry.drop()
                    errorAndAdvance("Unexpected token in a string")
                }
            }
        }
        if (at(KtTokens.DANGLING_NEWLINE)) {
            errorAndAdvance("Expecting '\"'")
        } else {
            expect(KtTokens.CLOSING_QUOTE, "Expecting '\"'")
        }
        marker.done(KtNodeTypes.STRING_TEMPLATE)
    }

    private fun parseIf() {
        val marker = mark()
        advance() // if
        parseParenthesizedCondition()
        val then = mark()
        parseControlStructureBody()
        then.done(KtNodeTypes.THEN)
        // `else` binds to the nearest `if`, and a line break before it does not end the statement.
        if (at(KtTokens.ELSE_KEYWORD)) {
            advance()
            val elseBranch = mark()
            parseControlStructureBody()
            elseBranch.done(KtNodeTypes.ELSE)
        }
        marker.done(KtNodeTypes.IF)
    }

    private fun parseParenthesizedCondition() {
        expect(KtTokens.LPAR, "Expecting '('")
        builder.disableNewlines()
        val condition = mark()
        parseExpression()
        condition.done(KtNodeTypes.CONDITION)
        builder.restoreNewlinesState()
        expect(KtTokens.RPAR, "Expecting ')'")
    }

    /** The body of an `if`, loop or `when` entry: a block, or a single expression. */
    private fun parseControlStructureBody() {
        if (at(KtTokens.LBRACE)) parseBlock() else parseStatement()
    }

    private fun parseWhen() {
        val marker = mark()
        advance() // when
        if (at(KtTokens.LPAR)) {
            advance()
            builder.disableNewlines()
            // `when (val x = f())` declares a subject variable.
            if (!declarationParsing.parseDeclaration()) parseExpression()
            builder.restoreNewlinesState()
            expect(KtTokens.RPAR, "Expecting ')'")
        }
        expect(KtTokens.LBRACE, "Expecting '{'")
        builder.enableNewlines()
        while (!eof() && !at(KtTokens.RBRACE)) {
            // Entries may be separated by semicolons as well as line breaks.
            if (at(KtTokens.SEMICOLON)) {
                advance()
                continue
            }
            val before = builder.currentOffset
            parseWhenEntry()
            if (builder.currentOffset == before) errorAndAdvance("Expecting a 'when' entry")
        }
        builder.restoreNewlinesState()
        expect(KtTokens.RBRACE, "Expecting '}'")
        marker.done(KtNodeTypes.WHEN)
    }

    private fun parseWhenEntry() {
        val marker = mark()
        if (at(KtTokens.ELSE_KEYWORD)) {
            advance()
        } else {
            do {
                parseWhenCondition()
            } while (consumeIf(KtTokens.COMMA))
            // `when (x) { is Foo if p -> … }`: a guard on the entry (Kotlin 2.1).
            if (at(KtTokens.IF_KEYWORD)) {
                val guard = mark()
                advance()
                parseExpression()
                guard.done(KtNodeTypes.WHEN_ENTRY_GUARD)
            }
        }
        expect(KtTokens.ARROW, "Expecting '->'")
        parseControlStructureBody()
        marker.done(KtNodeTypes.WHEN_ENTRY)
    }

    private fun parseWhenCondition() {
        val marker = mark()
        when {
            at(KtTokens.IN_KEYWORD) || at(KtTokens.NOT_IN) -> {
                val operation = mark()
                advance()
                operation.done(KtNodeTypes.OPERATION_REFERENCE)
                parseExpression()
                marker.done(KtNodeTypes.WHEN_CONDITION_IN_RANGE)
            }

            at(KtTokens.IS_KEYWORD) || at(KtTokens.NOT_IS) -> {
                advance()
                declarationParsing.parseTypeRef()
                marker.done(KtNodeTypes.WHEN_CONDITION_IS_PATTERN)
            }

            else -> {
                parseExpression()
                marker.done(KtNodeTypes.WHEN_CONDITION_WITH_EXPRESSION)
            }
        }
    }

    private fun parseTry() {
        val marker = mark()
        advance() // try
        parseBlock()
        while (at(KtTokens.CATCH_KEYWORD)) {
            val catch = mark()
            advance()
            declarationParsing.parseValueParameterList(isFunctionType = false, allowValVar = false)
            parseBlock()
            catch.done(KtNodeTypes.CATCH)
        }
        if (at(KtTokens.FINALLY_KEYWORD)) {
            val finally = mark()
            advance()
            parseBlock()
            finally.done(KtNodeTypes.FINALLY)
        }
        marker.done(KtNodeTypes.TRY)
    }

    private fun parseFor() {
        val marker = mark()
        advance() // for
        expect(KtTokens.LPAR, "Expecting '('")
        builder.disableNewlines()
        val parameter = mark()
        declarationParsing.parseModifierList()
        if (at(KtTokens.LPAR)) {
            parseLambdaDestructuringParameter()
            parameter.drop()
        } else {
            if (at(KtTokens.IDENTIFIER)) advance() else error("Expecting a loop variable")
            if (at(KtTokens.COLON)) {
                advance()
                declarationParsing.parseTypeRef()
            }
            parameter.done(KtNodeTypes.VALUE_PARAMETER)
        }
        expect(KtTokens.IN_KEYWORD, "Expecting 'in'")
        val range = mark()
        parseExpression()
        range.done(KtNodeTypes.LOOP_RANGE)
        builder.restoreNewlinesState()
        expect(KtTokens.RPAR, "Expecting ')'")
        val body = mark()
        parseControlStructureBody()
        body.done(KtNodeTypes.BODY)
        marker.done(KtNodeTypes.FOR)
    }

    private fun parseWhile() {
        val marker = mark()
        advance() // while
        parseParenthesizedCondition()
        val body = mark()
        parseControlStructureBody()
        body.done(KtNodeTypes.BODY)
        marker.done(KtNodeTypes.WHILE)
    }

    private fun parseDoWhile() {
        val marker = mark()
        advance() // do
        val body = mark()
        parseControlStructureBody()
        body.done(KtNodeTypes.BODY)
        expect(KtTokens.WHILE_KEYWORD, "Expecting 'while'")
        parseParenthesizedCondition()
        marker.done(KtNodeTypes.DO_WHILE)
    }

    private fun parseObjectLiteral() {
        val marker = mark()
        val declaration = mark()
        advance() // object
        if (at(KtTokens.COLON)) {
            advance()
            parseObjectLiteralSuperTypes()
        }
        if (at(KtTokens.LBRACE)) parseObjectLiteralBody()
        declaration.done(KtNodeTypes.OBJECT_DECLARATION)
        marker.done(KtNodeTypes.OBJECT_LITERAL)
    }

    private fun parseObjectLiteralSuperTypes() {
        val marker = mark()
        do {
            val entry = mark()
            val typeRef = mark()
            declarationParsing.parseUserType()
            typeRef.done(KtNodeTypes.TYPE_REFERENCE)
            if (at(KtTokens.LPAR)) {
                parseValueArgumentList()
                entry.done(KtNodeTypes.SUPER_TYPE_CALL_ENTRY)
            } else {
                entry.done(KtNodeTypes.SUPER_TYPE_ENTRY)
            }
        } while (consumeIf(KtTokens.COMMA))
        marker.done(KtNodeTypes.SUPER_TYPE_LIST)
    }

    private fun parseObjectLiteralBody() {
        val marker = mark()
        expect(KtTokens.LBRACE, "Expecting '{'")
        while (!eof() && !at(KtTokens.RBRACE)) {
            if (at(KtTokens.SEMICOLON)) {
                advance()
                continue
            }
            if (!declarationParsing.parseDeclaration()) errorAndAdvance("Expecting member declaration")
        }
        expect(KtTokens.RBRACE, "Expecting '}'")
        marker.done(KtNodeTypes.CLASS_BODY)
    }

    /** `fun(a: A): B { … }` used as a value. */
    private fun parseAnonymousFunction() {
        val marker = mark()
        advance() // fun
        if (at(KtTokens.LPAR)) {
            declarationParsing.parseValueParameterList(isFunctionType = false, allowValVar = false)
        }
        if (at(KtTokens.COLON)) {
            advance()
            declarationParsing.parseTypeRef()
        }
        when {
            at(KtTokens.LBRACE) -> parseBlock()
            at(KtTokens.EQ) -> {
                advance()
                parseExpression()
            }
        }
        marker.done(KtNodeTypes.FUN)
    }

    private fun parseThisOrSuper(type: IElementType) {
        val marker = mark()
        val instance = mark()
        advance()
        instance.done(KtNodeTypes.REFERENCE_EXPRESSION)
        if (at(KtTokens.AT) && builder.rawLookup(1) === KtTokens.IDENTIFIER) {
            val label = mark()
            advance() // @
            advance() // name
            label.done(KtNodeTypes.LABEL_QUALIFIER)
        }
        // `super<Foo>.bar()`
        if (type === KtNodeTypes.SUPER_EXPRESSION && at(KtTokens.LT)) {
            advance()
            declarationParsing.parseTypeRef()
            expect(KtTokens.GT, "Expecting '>'")
        }
        marker.done(type)
    }

    private fun parseReturn() {
        val marker = mark()
        advance() // return
        if (at(KtTokens.AT) && builder.rawLookup(1) === KtTokens.IDENTIFIER) {
            val label = mark()
            advance()
            advance()
            label.done(KtNodeTypes.LABEL_QUALIFIER)
        }
        if (!atExpressionEnd()) parseExpression()
        marker.done(KtNodeTypes.RETURN)
    }

    private fun parseOneOperandKeyword(keyword: IElementType, type: IElementType) {
        val marker = mark()
        advance()
        if (!atExpressionEnd()) parseExpression()
        marker.done(type)
    }

    private fun parseJump(type: IElementType) {
        val marker = mark()
        advance() // break / continue
        if (at(KtTokens.AT) && builder.rawLookup(1) === KtTokens.IDENTIFIER) {
            val label = mark()
            advance()
            advance()
            label.done(KtNodeTypes.LABEL_QUALIFIER)
        }
        marker.done(type)
    }

    /** `::foo` with no receiver. */
    private fun parseFreeCallableReference() {
        val marker = mark()
        advance() // ::
        if (at(KtTokens.CLASS_KEYWORD)) {
            advance()
            marker.done(KtNodeTypes.CLASS_LITERAL_EXPRESSION)
        } else {
            parseSimpleNameExpression()
            marker.done(KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION)
        }
    }

    /**
     * Is there no operand here? `return` and `throw` take an optional one, and what ends them is a line
     * break, a `}`, a `;` or the end of input.
     */
    private fun atExpressionEnd(): Boolean =
        eof() || at(KtTokens.SEMICOLON) || at(KtTokens.RBRACE) || at(KtTokens.RPAR) ||
            at(KtTokens.COMMA) || at(KtTokens.RBRACKET) || builder.newlineBeforeCurrentToken()

    private fun errorAndAdvance(message: String) {
        val marker = mark()
        advance()
        marker.error(message)
    }

    private companion object {
        val PREFIX_OPERATIONS: TokenSet = TokenSet.create(
            KtTokens.MINUS, KtTokens.PLUS, KtTokens.MINUSMINUS, KtTokens.PLUSPLUS, KtTokens.EXCL,
        )

        /** Tokens that cannot start the right-hand side of an infix function call. */
        val STATEMENT_BOUNDARY: TokenSet = TokenSet.create(
            KtTokens.RBRACE, KtTokens.RPAR, KtTokens.RBRACKET, KtTokens.SEMICOLON, KtTokens.COMMA,
            KtTokens.EQ, KtTokens.ARROW, KtTokens.COLON,
        )
    }
}
