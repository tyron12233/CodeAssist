package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.KtNodeTypes
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.TokenSet

/**
 * The declaration half of the grammar, mirroring `org.jetbrains.kotlin.parsing.KotlinParsing`: the file
 * preamble, declarations, and types. Expressions live in [KotlinExpressionParsing], and the two call each
 * other exactly as the compiler's pair does.
 *
 * Every rule follows the same shape — open a marker, consume, close it with the element type from
 * [KtNodeTypes] — so the tree this produces is comparable, node for node, with the compiler's. That is what
 * `KotlinParserOracleTest` checks; the element names are not decoration.
 *
 * The parser is error-tolerant everywhere, because its first caller is an editor looking at half-typed code:
 * a rule that cannot match reports an error, resynchronises on a recovery set, and keeps going, so the tree
 * always spans the whole file.
 */
class KotlinParsing(builder: SyntaxTreeBuilder) : AbstractKotlinParsing(builder) {

    private val expressionParsing = KotlinExpressionParsing(builder, this)

    /**
     * Did the modifier list just parsed contain `enum`?
     *
     * The class body's shape depends on it — an enum's starts with a comma-separated entry list — and the
     * modifiers are consumed before the `class` keyword is even seen, so the answer has to be carried this
     * short distance rather than re-derived.
     */
    private var lastModifierListHadEnum = false

    // -----------------------------------------------------------------------------------------------------
    // File
    // -----------------------------------------------------------------------------------------------------

    /** Parse a whole file. The root element is added by [KotlinParser]. */
    fun parseFile() {
        parsePreamble()
        while (!eof()) {
            if (at(KtTokens.SEMICOLON)) {
                advance()
                continue
            }
            parseTopLevelDeclaration()
        }
    }

    /** File annotations, the package directive and the import list, in that order and all optional. */
    private fun parsePreamble() {
        parseFileAnnotationList()
        parsePackageDirective()
        parseImportList()
    }

    /**
     * `@file:Foo` annotations. They only count before the package directive, and only with an explicit
     * `file:` target, which is what separates them from an annotation on the first declaration.
     */
    private fun parseFileAnnotationList() {
        if (!atFileAnnotation()) return
        val marker = mark()
        while (atFileAnnotation()) parseAnnotationEntry()
        marker.done(KtNodeTypes.FILE_ANNOTATION_LIST)
    }

    private fun atFileAnnotation(): Boolean =
        at(KtTokens.AT) && lookahead(1) === KtTokens.IDENTIFIER && lookahead(2) === KtTokens.COLON

    /**
     * `package a.b.c`. The element is created even when the keyword is absent: the compiler always gives a
     * file a package directive, empty for the default package, and downstream code reads it unconditionally.
     */
    private fun parsePackageDirective() {
        val marker = mark()
        if (at(KtTokens.PACKAGE_KEYWORD)) {
            advance()
            parseQualifiedName()
            consumeSemis()
            marker.done(KtNodeTypes.PACKAGE_DIRECTIVE)
        } else {
            marker.done(KtNodeTypes.PACKAGE_DIRECTIVE)
        }
    }

    private fun parseImportList() {
        val marker = mark()
        while (at(KtTokens.IMPORT_KEYWORD)) parseImportDirective()
        marker.done(KtNodeTypes.IMPORT_LIST)
    }

    /** `import a.b.C`, `import a.b.*`, `import a.b.C as D`. */
    private fun parseImportDirective() {
        val marker = mark()
        advance() // import
        if (at(KtTokens.IDENTIFIER)) {
            var reference = mark()
            advance()
            reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
            while (at(KtTokens.DOT)) {
                if (lookahead(1) === KtTokens.MUL) {
                    advance() // .
                    advance() // *
                    break
                }
                val qualified = reference.precede()
                advance() // .
                if (at(KtTokens.IDENTIFIER)) {
                    reference = mark()
                    advance()
                    reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
                } else {
                    error("Qualified name part expected")
                    qualified.drop()
                    break
                }
                qualified.done(KtNodeTypes.DOT_QUALIFIED_EXPRESSION)
                reference = qualified
            }
        } else {
            errorWithRecovery("Expecting qualified name", IMPORT_RECOVERY_SET)
        }
        if (at(KtTokens.AS_KEYWORD)) {
            val alias = mark()
            advance()
            expect(KtTokens.IDENTIFIER, "Expecting import alias name")
            alias.done(KtNodeTypes.IMPORT_ALIAS)
        }
        consumeSemis()
        marker.done(KtNodeTypes.IMPORT_DIRECTIVE)
    }

    private fun parseQualifiedName() {
        if (!at(KtTokens.IDENTIFIER)) {
            errorWithRecovery("Expecting qualified name", PACKAGE_RECOVERY_SET)
            return
        }
        var reference = mark()
        advance()
        reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
        while (at(KtTokens.DOT) && lookahead(1) === KtTokens.IDENTIFIER) {
            val qualified = reference.precede()
            advance() // .
            val next = mark()
            advance()
            next.done(KtNodeTypes.REFERENCE_EXPRESSION)
            qualified.done(KtNodeTypes.DOT_QUALIFIED_EXPRESSION)
            reference = qualified
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------------------------------------

    private fun parseTopLevelDeclaration() {
        if (!parseDeclaration()) {
            errorAndAdvance("Expecting a top level declaration")
        }
    }

    /**
     * One declaration, modifiers included. Returns false without consuming anything when the position does
     * not start a declaration, so callers that also accept expressions (a block, a class body) can try this
     * first and fall through.
     */
    internal fun parseDeclaration(): Boolean {
        val marker = mark()
        parseModifierList()
        val isEnum = lastModifierListHadEnum
        val parsed = when {
            at(KtTokens.CLASS_KEYWORD) || at(KtTokens.INTERFACE_KEYWORD) -> parseClass(marker, isEnum)
            at(KtTokens.FUN_KEYWORD) -> parseFunction(marker)
            at(KtTokens.VAL_KEYWORD) || at(KtTokens.VAR_KEYWORD) -> parseProperty(marker)
            at(KtTokens.OBJECT_KEYWORD) -> parseObject(marker)
            at(KtTokens.TYPE_ALIAS_KEYWORD) -> parseTypeAlias(marker)
            at(KtTokens.INIT_KEYWORD) -> parseClassInitializer(marker)
            at(KtTokens.CONSTRUCTOR_KEYWORD) -> parseSecondaryConstructor(marker)
            else -> false
        }
        if (!parsed) {
            // Modifiers with nothing to modify: rewind so the caller sees the original position, soft-keyword
            // promotions included.
            marker.rollbackTo()
        }
        return parsed
    }

    /**
     * Is this position a declaration rather than an expression?
     *
     * Needed wherever both are legal (inside a block, most importantly). It cannot be answered by looking at
     * one token, because `data`, `value` and `suspend` are ordinary names until a declaration keyword turns
     * up after them, so the answer is found by parsing speculatively and rewinding.
     */
    internal fun atDeclarationStart(): Boolean {
        if (atSet(DECLARATION_FIRST)) return true
        if (!at(KtTokens.AT) && !atSet(KtTokens.MODIFIER_KEYWORDS)) return false
        val marker = mark()
        while (true) {
            if (at(KtTokens.AT)) {
                parseAnnotationEntry()
            } else if (atSet(KtTokens.MODIFIER_KEYWORDS)) {
                advance()
            } else {
                break
            }
        }
        val result = atSet(DECLARATION_FIRST)
        marker.rollbackTo()
        return result
    }

    /**
     * Annotations and modifier keywords, in any order and any number.
     *
     * The element is only created when something was found: the compiler gives a declaration with no
     * modifiers a null modifier list rather than an empty one, and code that tests for presence relies on it.
     */
    internal fun parseModifierList(): Boolean {
        val marker = mark()
        var found = false
        var sawEnum = false
        while (!eof()) {
            if (at(KtTokens.AT) && lookahead(1) !== KtTokens.LBRACKET) {
                parseAnnotationEntry()
                found = true
            } else if (at(KtTokens.AT)) {
                parseAnnotationArray()
                found = true
            } else if (atSet(KtTokens.MODIFIER_KEYWORDS)) {
                if (at(KtTokens.ENUM_KEYWORD)) sawEnum = true
                advance()
                found = true
            } else {
                break
            }
        }
        lastModifierListHadEnum = sawEnum
        if (found) marker.done(KtNodeTypes.MODIFIER_LIST) else marker.drop()
        return found
    }

    /** `@Foo`, `@Foo(1)`, `@field:Foo`. */
    private fun parseAnnotationEntry() {
        val marker = mark()
        advance() // @
        if (lookahead(1) === KtTokens.COLON && atSet(ANNOTATION_TARGETS)) {
            val target = mark()
            advance()
            target.done(KtNodeTypes.ANNOTATION_TARGET)
            advance() // :
        }
        parseTypeRefInAnnotation()
        if (at(KtTokens.LPAR)) expressionParsing.parseValueArgumentList()
        marker.done(KtNodeTypes.ANNOTATION_ENTRY)
    }

    /** `@[Foo Bar]`: several annotations sharing one `@`. */
    private fun parseAnnotationArray() {
        val marker = mark()
        advance() // @
        advance() // [
        while (!eof() && !at(KtTokens.RBRACKET)) {
            val entry = mark()
            parseTypeRefInAnnotation()
            if (at(KtTokens.LPAR)) expressionParsing.parseValueArgumentList()
            entry.done(KtNodeTypes.ANNOTATION_ENTRY)
        }
        expect(KtTokens.RBRACKET, "Expecting ']'")
        marker.done(KtNodeTypes.ANNOTATION)
    }

    /** The name in an annotation is a constructor callee, not a plain type reference. */
    private fun parseTypeRefInAnnotation() {
        val callee = mark()
        val typeRef = mark()
        parseUserType()
        typeRef.done(KtNodeTypes.TYPE_REFERENCE)
        callee.done(KtNodeTypes.CONSTRUCTOR_CALLEE)
    }

    /** `class Foo<T>(…) : Bar(), Baz where … { … }`, and the same for `interface`. */
    private fun parseClass(marker: SyntaxTreeBuilder.Marker, isEnum: Boolean): Boolean {
        advance() // class / interface
        expect(KtTokens.IDENTIFIER, "Class name expected", CLASS_NAME_RECOVERY_SET)
        parseTypeParameterList()
        parsePrimaryConstructor()
        if (at(KtTokens.COLON)) {
            advance()
            parseSuperTypeList()
        }
        parseTypeConstraintsClause()
        if (at(KtTokens.LBRACE)) parseClassBody(isEnum)
        marker.done(KtNodeTypes.CLASS)
        return true
    }

    /** `object Foo : Bar { … }`. A companion object is this with a `companion` modifier. */
    private fun parseObject(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // object
        if (at(KtTokens.IDENTIFIER)) advance()
        if (at(KtTokens.COLON)) {
            advance()
            parseSuperTypeList()
        }
        if (at(KtTokens.LBRACE)) parseClassBody(isEnum = false)
        marker.done(KtNodeTypes.OBJECT_DECLARATION)
        return true
    }

    /**
     * The parameter list on the class header. It only becomes a primary constructor when there is one, and
     * the optional `constructor` keyword can carry its own modifiers (`private constructor(…)`).
     */
    private fun parsePrimaryConstructor() {
        if (!at(KtTokens.LPAR) && !at(KtTokens.CONSTRUCTOR_KEYWORD) && !atSet(KtTokens.MODIFIER_KEYWORDS)) return
        if (!at(KtTokens.LPAR)) {
            // Modifiers here belong to the constructor only if `constructor` actually follows.
            val probe = mark()
            parseModifierList()
            val isConstructor = at(KtTokens.CONSTRUCTOR_KEYWORD)
            probe.rollbackTo()
            if (!isConstructor) return
        }
        val marker = mark()
        parseModifierList()
        consumeIf(KtTokens.CONSTRUCTOR_KEYWORD)
        if (at(KtTokens.LPAR)) {
            parseValueParameterList(isFunctionType = false, allowValVar = true)
        } else {
            error("Expecting '('")
        }
        marker.done(KtNodeTypes.PRIMARY_CONSTRUCTOR)
    }

    /** `constructor(…) : this(…) { … }`. */
    private fun parseSecondaryConstructor(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // constructor
        if (at(KtTokens.LPAR)) {
            parseValueParameterList(isFunctionType = false, allowValVar = false)
        } else {
            error("Expecting '('")
        }
        if (at(KtTokens.COLON)) {
            advance()
            val delegation = mark()
            if (at(KtTokens.THIS_KEYWORD) || at(KtTokens.SUPER_KEYWORD)) {
                val callee = mark()
                advance()
                callee.done(KtNodeTypes.CONSTRUCTOR_CALLEE)
            } else {
                error("Expecting 'this' or 'super'")
            }
            if (at(KtTokens.LPAR)) expressionParsing.parseValueArgumentList()
            delegation.done(KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL)
        }
        if (at(KtTokens.LBRACE)) expressionParsing.parseBlock()
        marker.done(KtNodeTypes.SECONDARY_CONSTRUCTOR)
        return true
    }

    private fun parseClassInitializer(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // init
        if (at(KtTokens.LBRACE)) expressionParsing.parseBlock() else error("Expecting '{'")
        marker.done(KtNodeTypes.CLASS_INITIALIZER)
        return true
    }

    private fun parseSuperTypeList() {
        val marker = mark()
        do {
            if (eof() || at(KtTokens.LBRACE)) break
            val entry = mark()
            val typeRef = mark()
            parseTypeElement()
            typeRef.done(KtNodeTypes.TYPE_REFERENCE)
            when {
                at(KtTokens.LPAR) -> {
                    expressionParsing.parseValueArgumentList()
                    entry.done(KtNodeTypes.SUPER_TYPE_CALL_ENTRY)
                }

                at(KtTokens.BY_KEYWORD) -> {
                    advance()
                    expressionParsing.parseExpression()
                    entry.done(KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY)
                }

                else -> entry.done(KtNodeTypes.SUPER_TYPE_ENTRY)
            }
        } while (consumeIf(KtTokens.COMMA))
        marker.done(KtNodeTypes.SUPER_TYPE_LIST)
    }

    /**
     * `{ … }` after a class header.
     *
     * An enum's body is different in shape, not just in content: it begins with a comma-separated entry list
     * that ends at a `;` or at the closing brace, and only then do ordinary members start.
     */
    private fun parseClassBody(isEnum: Boolean) {
        val marker = mark()
        expect(KtTokens.LBRACE, "Expecting '{'")
        if (isEnum) parseEnumEntries()
        while (!eof() && !at(KtTokens.RBRACE)) {
            if (at(KtTokens.SEMICOLON)) {
                advance()
                continue
            }
            if (!parseDeclaration()) {
                errorAndAdvance("Expecting member declaration")
            }
        }
        expect(KtTokens.RBRACE, "Expecting '}'")
        marker.done(KtNodeTypes.CLASS_BODY)
    }

    private fun parseEnumEntries() {
        while (!eof() && !at(KtTokens.RBRACE) && !at(KtTokens.SEMICOLON)) {
            val marker = mark()
            parseModifierList()
            if (!at(KtTokens.IDENTIFIER)) {
                marker.rollbackTo()
                return
            }
            advance() // name
            if (at(KtTokens.LPAR)) expressionParsing.parseValueArgumentList()
            if (at(KtTokens.LBRACE)) parseClassBody(isEnum = false)
            marker.done(KtNodeTypes.ENUM_ENTRY)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        consumeIf(KtTokens.SEMICOLON)
    }

    /** `fun <T> Receiver.name(params): Type where … = expr` or `{ … }`. */
    private fun parseFunction(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // fun
        parseTypeParameterList()
        parseReceiverTypeIfPresent()
        if (at(KtTokens.IDENTIFIER)) advance() else if (!at(KtTokens.LPAR)) error("Function name expected")
        if (at(KtTokens.LPAR)) {
            parseValueParameterList(isFunctionType = false, allowValVar = false)
        } else {
            error("Expecting '('")
        }
        if (at(KtTokens.COLON)) {
            advance()
            parseTypeRef()
        }
        parseTypeConstraintsClause()
        when {
            at(KtTokens.LBRACE) -> expressionParsing.parseBlock()
            at(KtTokens.EQ) -> {
                advance()
                expressionParsing.parseExpression()
            }
        }
        marker.done(KtNodeTypes.FUN)
        return true
    }

    /** `val <T> Receiver.name: Type by delegate` / `= initializer`, with accessors. */
    private fun parseProperty(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // val / var
        parseTypeParameterList()
        if (at(KtTokens.LPAR)) {
            parseDestructuringDeclaration()
        } else {
            parseReceiverTypeIfPresent()
            if (at(KtTokens.IDENTIFIER)) advance() else error("Expecting property name")
        }
        if (at(KtTokens.COLON)) {
            advance()
            parseTypeRef()
        }
        parseTypeConstraintsClause()
        when {
            at(KtTokens.BY_KEYWORD) -> {
                val delegate = mark()
                advance()
                expressionParsing.parseExpression()
                delegate.done(KtNodeTypes.PROPERTY_DELEGATE)
            }

            at(KtTokens.EQ) -> {
                advance()
                expressionParsing.parseExpression()
            }
        }
        parsePropertyAccessors()
        marker.done(KtNodeTypes.PROPERTY)
        return true
    }

    /** `val (a, b) = pair`. */
    private fun parseDestructuringDeclaration() {
        val marker = mark()
        advance() // (
        while (!eof() && !at(KtTokens.RPAR)) {
            val entry = mark()
            parseModifierList()
            if (at(KtTokens.IDENTIFIER)) advance() else errorAndAdvance("Expecting a name")
            if (at(KtTokens.COLON)) {
                advance()
                parseTypeRef()
            }
            entry.done(KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        expect(KtTokens.RPAR, "Expecting ')'")
        marker.done(KtNodeTypes.DESTRUCTURING_DECLARATION)
    }

    /**
     * `get()` / `set(value)` accessors.
     *
     * They only belong to the property when they are on the following lines with no blank declaration in
     * between, but `get` is a soft keyword, so `get` followed by anything other than `(` or `=` is somebody's
     * function call and must be left alone.
     */
    private fun parsePropertyAccessors() {
        while (true) {
            val probe = mark()
            val hadModifiers = parseModifierList()
            // `get`/`set` are soft keywords, so `get` followed by anything else is somebody's function call.
            // A parameter list settles it; so do modifiers, which is the `private set` form that has neither
            // parentheses nor a body.
            val isAccessor = (at(KtTokens.GET_KEYWORD) || at(KtTokens.SET_KEYWORD)) &&
                (lookahead(1) === KtTokens.LPAR || hadModifiers)
            probe.rollbackTo()
            if (!isAccessor) return

            val marker = mark()
            parseModifierList()
            // Re-assert the keyword: the probe above promoted it and then rewound, which undid the promotion.
            // Without this the tree keeps IDENTIFIER and `isGetter` can never be true.
            if (!at(KtTokens.GET_KEYWORD)) at(KtTokens.SET_KEYWORD)
            advance() // get / set
            if (at(KtTokens.LPAR)) {
                parseValueParameterList(isFunctionType = false, allowValVar = false)
            }
            if (at(KtTokens.COLON)) {
                advance()
                parseTypeRef()
            }
            when {
                at(KtTokens.LBRACE) -> expressionParsing.parseBlock()
                at(KtTokens.EQ) -> {
                    advance()
                    expressionParsing.parseExpression()
                }
            }
            marker.done(KtNodeTypes.PROPERTY_ACCESSOR)
        }
    }

    private fun parseTypeAlias(marker: SyntaxTreeBuilder.Marker): Boolean {
        advance() // typealias
        expect(KtTokens.IDENTIFIER, "Type alias name expected")
        parseTypeParameterList()
        expect(KtTokens.EQ, "Expecting '='")
        parseTypeRef()
        marker.done(KtNodeTypes.TYPEALIAS)
        return true
    }

    /**
     * A receiver type, if this is an extension.
     *
     * `fun Foo.bar()` and `fun bar()` share a prefix, and only the dot before the NAME tells them apart. It
     * cannot be found by parsing a type and checking for a dot, because a qualified type swallows dots
     * greedily: `String.trimAll` parses as one type called `String.trimAll` and then reports no receiver at
     * all. So the last dot is located first, and the type is told to stop there.
     */
    private fun parseReceiverTypeIfPresent() {
        val dotOffset = lastReceiverDotOffset()
        if (dotOffset < 0) return
        parseTypeRef(stopOffset = dotOffset)
        expect(KtTokens.DOT, "Expecting '.'")
    }

    /**
     * The offset of the last dot that separates a receiver from a declaration's name, or -1 for a
     * non-extension.
     *
     * Scans ahead to the start of the parameter list (or whatever else ends the name), tracking angle-bracket
     * depth so that a dot inside a generic argument is not mistaken for the separator, then rewinds.
     */
    private fun lastReceiverDotOffset(): Int {
        val probe = mark()
        var depth = 0
        var lastDot = -1
        var scanned = false
        while (!eof()) {
            if (scanned && depth == 0 && builder.newlineBeforeCurrentToken()) break
            if (depth == 0 && (at(KtTokens.LPAR) || at(KtTokens.LBRACE) || at(KtTokens.EQ) ||
                    at(KtTokens.COLON) || at(KtTokens.SEMICOLON) || at(KtTokens.BY_KEYWORD))
            ) {
                break
            }
            when {
                at(KtTokens.LT) -> depth++
                at(KtTokens.GT) -> depth--
                at(KtTokens.DOT) -> if (depth == 0) lastDot = builder.currentOffset
            }
            advance()
            scanned = true
        }
        probe.rollbackTo()
        return lastDot
    }

    // -----------------------------------------------------------------------------------------------------
    // Type parameters, constraints, value parameters
    // -----------------------------------------------------------------------------------------------------

    private fun parseTypeParameterList() {
        if (!at(KtTokens.LT)) return
        val marker = mark()
        advance() // <
        while (!eof() && !at(KtTokens.GT)) {
            val parameter = mark()
            parseModifierList()
            if (at(KtTokens.IDENTIFIER)) advance() else errorAndAdvance("Type parameter name expected")
            if (at(KtTokens.COLON)) {
                advance()
                parseTypeRef()
            }
            parameter.done(KtNodeTypes.TYPE_PARAMETER)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        expect(KtTokens.GT, "Expecting '>'")
        marker.done(KtNodeTypes.TYPE_PARAMETER_LIST)
    }

    /** `where T : Foo, U : Bar`. */
    private fun parseTypeConstraintsClause() {
        if (!at(KtTokens.WHERE_KEYWORD)) return
        val marker = mark()
        advance() // where
        do {
            val constraint = mark()
            parseModifierList()
            if (at(KtTokens.IDENTIFIER)) {
                val reference = mark()
                advance()
                reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
            } else {
                error("Expecting type parameter name")
            }
            expect(KtTokens.COLON, "Expecting ':'")
            parseTypeRef()
            constraint.done(KtNodeTypes.TYPE_CONSTRAINT)
        } while (consumeIf(KtTokens.COMMA))
        marker.done(KtNodeTypes.TYPE_CONSTRAINT_LIST)
    }

    /**
     * `(a: A, val b: B = default, vararg c: C)`.
     *
     * [allowValVar] is on only for a primary constructor, which is the one place a parameter also declares a
     * property. [isFunctionType] drops the requirement for names, since `(Int, String) -> Unit` has none.
     */
    internal fun parseValueParameterList(isFunctionType: Boolean, allowValVar: Boolean) {
        val marker = mark()
        expect(KtTokens.LPAR, "Expecting '('")
        // A newline inside a parameter list is insignificant, whatever the enclosing construct decided.
        builder.disableNewlines()
        while (!eof() && !at(KtTokens.RPAR)) {
            parseValueParameter(isFunctionType, allowValVar)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        builder.restoreNewlinesState()
        expect(KtTokens.RPAR, "Expecting ')'")
        marker.done(KtNodeTypes.VALUE_PARAMETER_LIST)
    }

    private fun parseValueParameter(isFunctionType: Boolean, allowValVar: Boolean) {
        val marker = mark()
        parseModifierList()
        if (allowValVar && (at(KtTokens.VAL_KEYWORD) || at(KtTokens.VAR_KEYWORD))) advance()

        if (isFunctionType) {
            // `(name: Type)` and `(Type)` are both legal in a function type, and only the colon separates them.
            val probe = mark()
            if (at(KtTokens.IDENTIFIER) && lookahead(1) === KtTokens.COLON) {
                probe.drop()
                advance() // name
                advance() // :
                parseTypeRef()
            } else {
                probe.drop()
                parseTypeRef()
            }
        } else {
            if (at(KtTokens.IDENTIFIER)) advance() else error("Parameter name expected")
            if (at(KtTokens.COLON)) {
                advance()
                parseTypeRef()
            } else {
                error("Expecting ':' before the parameter type")
            }
        }
        if (at(KtTokens.EQ)) {
            advance()
            expressionParsing.parseExpression()
        }
        marker.done(KtNodeTypes.VALUE_PARAMETER)
    }

    // -----------------------------------------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------------------------------------

    /**
     * A type with its modifiers and annotations, which is what every type position actually accepts.
     *
     * [stopOffset] bounds how far a qualified type may extend, so a receiver type can be parsed without
     * swallowing the name that follows the last dot. It is unbounded everywhere else.
     */
    internal fun parseTypeRef(stopOffset: Int = Int.MAX_VALUE) {
        val marker = mark()
        parseModifierList()
        parseTypeElement(stopOffset)
        marker.done(KtNodeTypes.TYPE_REFERENCE)
    }

    /**
     * The type itself.
     *
     * Suffixes wrap rather than extend, matching the compiler: `A?` is a NULLABLE_TYPE around a USER_TYPE,
     * and `A & B` an INTERSECTION_TYPE around both, so each is built by preceding what was already parsed.
     */
    private fun parseTypeElement(stopOffset: Int = Int.MAX_VALUE) {
        var marker = mark()
        when {
            at(KtTokens.LPAR) -> parseParenthesizedOrFunctionType()
            at(KtTokens.DYNAMIC_KEYWORD) -> {
                val dynamic = mark()
                advance()
                dynamic.done(KtNodeTypes.DYNAMIC_TYPE)
            }

            atFunctionTypeWithReceiver(stopOffset) -> parseFunctionTypeWithReceiver(stopOffset)
            at(KtTokens.IDENTIFIER) -> parseUserType(stopOffset)
            at(KtTokens.SUSPEND_KEYWORD) -> {
                advance()
                parseTypeElement(stopOffset)
            }

            else -> {
                marker.drop()
                error("Type expected")
                return
            }
        }
        // `?` and `&` are postfix, and each one wraps everything to its left.
        while (true) {
            // `?` in a type is the nullable marker, never the start of `?.` or `?:`, so the builder's
            // complex-token joining is off while it is read.
            builder.disableJoiningComplexTokens()
            val nullable = at(KtTokens.QUEST)
            builder.restoreJoiningComplexTokensState()
            when {
                nullable -> {
                    val next = marker.precede()
                    builder.disableJoiningComplexTokens()
                    advance()
                    builder.restoreJoiningComplexTokensState()
                    marker.done(KtNodeTypes.NULLABLE_TYPE)
                    marker = next
                }

                at(KtTokens.AND) -> {
                    val next = marker.precede()
                    advance()
                    parseTypeElement()
                    marker.done(KtNodeTypes.INTERSECTION_TYPE)
                    marker = next
                }

                else -> {
                    marker.drop()
                    return
                }
            }
        }
    }

    /**
     * Does a receiver-qualified function type start here (`Int.(String) -> Unit`)?
     *
     * The receiver is an ordinary type, so the only thing separating this from a qualified type is the `(`
     * after the dot — and a qualified type will have already eaten the dot by the time that is visible. The
     * answer is therefore found by parsing and rewinding.
     */
    private fun atFunctionTypeWithReceiver(stopOffset: Int): Boolean {
        if (!at(KtTokens.IDENTIFIER)) return false
        val probe = mark()
        parseUserType(stopOffset)
        val result = at(KtTokens.DOT) && lookahead(1) === KtTokens.LPAR
        probe.rollbackTo()
        return result
    }

    private fun parseFunctionTypeWithReceiver(stopOffset: Int) {
        val marker = mark()
        val receiver = mark()
        val receiverRef = mark()
        parseUserType(stopOffset)
        receiverRef.done(KtNodeTypes.TYPE_REFERENCE)
        receiver.done(KtNodeTypes.FUNCTION_TYPE_RECEIVER)
        advance() // .
        parseValueParameterList(isFunctionType = true, allowValVar = false)
        expect(KtTokens.ARROW, "Expecting '->'")
        parseTypeRef()
        marker.done(KtNodeTypes.FUNCTION_TYPE)
    }

    /**
     * `(A, B) -> C` and `(A)` start the same way, so the parenthesised part is parsed as a parameter list and
     * only the arrow that follows decides what it was.
     */
    private fun parseParenthesizedOrFunctionType() {
        val marker = mark()
        parseValueParameterList(isFunctionType = true, allowValVar = false)
        if (at(KtTokens.ARROW)) {
            advance()
            parseTypeRef()
            marker.done(KtNodeTypes.FUNCTION_TYPE)
        } else {
            // Not a function type after all: it was a parenthesised type.
            marker.rollbackTo()
            advance() // (
            parseTypeElement()
            expect(KtTokens.RPAR, "Expecting ')'")
        }
    }

    /**
     * `a.b.C<T>`, built left-nested: each qualifier wraps what came before it, which is the shape the
     * compiler produces and the shape `KtUserType.qualifier` reads.
     */
    internal fun parseUserType(stopOffset: Int = Int.MAX_VALUE) {
        var marker = mark()
        if (at(KtTokens.IDENTIFIER)) {
            val reference = mark()
            advance()
            reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
        } else {
            error("Expecting type name")
        }
        if (at(KtTokens.LT)) parseTypeArgumentList()
        marker.done(KtNodeTypes.USER_TYPE)

        while (at(KtTokens.DOT) && builder.currentOffset < stopOffset && lookahead(1) === KtTokens.IDENTIFIER) {
            val qualified = marker.precede()
            advance() // .
            val reference = mark()
            advance()
            reference.done(KtNodeTypes.REFERENCE_EXPRESSION)
            if (at(KtTokens.LT)) parseTypeArgumentList()
            qualified.done(KtNodeTypes.USER_TYPE)
            marker = qualified
        }
    }

    /** `<A, out B, *>`. Each argument is a projection, even when it carries no variance. */
    internal fun parseTypeArgumentList() {
        val marker = mark()
        advance() // <
        builder.disableNewlines()
        while (!eof() && !at(KtTokens.GT)) {
            val projection = mark()
            parseModifierList()
            if (at(KtTokens.MUL)) advance() else parseTypeRef()
            projection.done(KtNodeTypes.TYPE_PROJECTION)
            if (!consumeIf(KtTokens.COMMA)) break
        }
        builder.restoreNewlinesState()
        expect(KtTokens.GT, "Expecting '>'")
        marker.done(KtNodeTypes.TYPE_ARGUMENT_LIST)
    }

    // -----------------------------------------------------------------------------------------------------

    private fun errorAndAdvance(message: String) {
        val marker = mark()
        advance()
        marker.error(message)
    }

    private companion object {
        val DECLARATION_FIRST: TokenSet = TokenSet.create(
            KtTokens.CLASS_KEYWORD, KtTokens.INTERFACE_KEYWORD, KtTokens.FUN_KEYWORD, KtTokens.VAL_KEYWORD,
            KtTokens.VAR_KEYWORD, KtTokens.OBJECT_KEYWORD, KtTokens.TYPE_ALIAS_KEYWORD, KtTokens.INIT_KEYWORD,
            KtTokens.CONSTRUCTOR_KEYWORD,
        )

        val ANNOTATION_TARGETS: TokenSet = TokenSet.create(
            KtTokens.FILE_KEYWORD, KtTokens.FIELD_KEYWORD, KtTokens.PROPERTY_KEYWORD, KtTokens.GET_KEYWORD,
            KtTokens.SET_KEYWORD, KtTokens.RECEIVER_KEYWORD, KtTokens.PARAM_KEYWORD, KtTokens.SETPARAM_KEYWORD,
            KtTokens.DELEGATE_KEYWORD, KtTokens.ALL_KEYWORD,
        )

        val IMPORT_RECOVERY_SET: TokenSet = TokenSet.create(KtTokens.AS_KEYWORD, KtTokens.SEMICOLON)

        val PACKAGE_RECOVERY_SET: TokenSet = TokenSet.create(KtTokens.SEMICOLON, KtTokens.IMPORT_KEYWORD)

        val CLASS_NAME_RECOVERY_SET: TokenSet = TokenSet.create(
            KtTokens.LT, KtTokens.LPAR, KtTokens.COLON, KtTokens.LBRACE,
        )
    }
}

