package dev.ide.android.support.aidl

/** Thrown on the first syntax error; [AidlCompiler] turns it into one positioned diagnostic. */
class AidlSyntaxException(message: String, val pos: AidlPos) : Exception(message)

/**
 * A hand-written lexer + recursive-descent parser for AIDL.
 *
 * AIDL's grammar is small enough that a table-driven parser buys nothing: a file is a package statement,
 * imports, and one declaration. Two details shape the implementation:
 *
 *  - **Punctuation is lexed one character at a time.** That makes `List<List<String>>` fall out naturally
 *    (no `>>` token to split) and keeps multi-character operators inside constant expressions from needing
 *    lexer rules of their own.
 *  - **Constant/default expressions are captured as raw source text**, not parsed. AIDL expression syntax is
 *    a subset of Java's, so the generator emits the slice unchanged rather than evaluating it.
 *
 * Errors are fatal at the first problem ([AidlSyntaxException]) rather than recovered: AIDL files are a few
 * dozen lines, so a single precise message beats a cascade of invented ones. Doc comments
 * riding ahead of a declaration are carried onto it so they reach the generated Java.
 */
object AidlParser {

    /** Parse [source] (named [path] for diagnostics) into an [AidlFile]. Throws [AidlSyntaxException]. */
    fun parse(source: String, path: String = ""): AidlFile = Parser(Lexer(source).lex(), source, path).parseFile()

    // ---------------------------------------------------------------- lexer

    private enum class Kind { IDENT, NUMBER, STRING, CHAR, PUNCT, EOF }

    private class Token(
        val kind: Kind,
        val text: String,
        val pos: AidlPos,
        /** Offsets into the source, so the parser can slice raw expression text. */
        val start: Int,
        val end: Int,
        /** The doc comment immediately preceding this token, already stripped of its markers. */
        val doc: String?,
    ) {
        fun isIdent(name: String) = kind == Kind.IDENT && text == name
        fun isPunct(ch: String) = kind == Kind.PUNCT && text == ch
        override fun toString() = if (kind == Kind.EOF) "end of file" else "'$text'"
    }

    private class Lexer(private val src: String) {
        private var i = 0
        private var line = 1
        private var lineStart = 0
        private var pendingDoc: String? = null

        private fun pos() = AidlPos(line, i - lineStart + 1)

        fun lex(): List<Token> {
            val out = ArrayList<Token>()
            while (true) {
                skipTrivia()
                if (i >= src.length) {
                    out.add(Token(Kind.EOF, "", pos(), i, i, null)); return out
                }
                val start = i
                val p = pos()
                val doc = pendingDoc.also { pendingDoc = null }
                val c = src[i]
                val kind = when {
                    c.isLetter() || c == '_' -> { while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++; Kind.IDENT }
                    c.isDigit() -> { lexNumber(); Kind.NUMBER }
                    c == '"' -> { lexQuoted('"'); Kind.STRING }
                    c == '\'' -> { lexQuoted('\''); Kind.CHAR }
                    else -> { i++; Kind.PUNCT }
                }
                out.add(Token(kind, src.substring(start, i), p, start, i, doc))
            }
        }

        private fun lexNumber() {
            if (src[i] == '0' && i + 1 < src.length && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                i += 2
                while (i < src.length && (src[i].isLetterOrDigit())) i++
                return
            }
            while (i < src.length && src[i].isDigit()) i++
            if (i < src.length && src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit()) {
                i++
                while (i < src.length && src[i].isDigit()) i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                while (i < src.length && src[i].isDigit()) i++
            }
            // Java-style width/precision suffixes: 1L, 1.0f, 1.0d.
            if (i < src.length && src[i] in "lLfFdD") i++
        }

        private fun lexQuoted(quote: Char) {
            val open = pos()
            i++ // opening quote
            while (i < src.length && src[i] != quote) {
                if (src[i] == '\\' && i + 1 < src.length) i++
                if (src[i] == '\n') throw AidlSyntaxException("unterminated ${if (quote == '"') "string" else "character"} literal", open)
                i++
            }
            if (i >= src.length) throw AidlSyntaxException("unterminated ${if (quote == '"') "string" else "character"} literal", open)
            i++ // closing quote
        }

        private fun skipTrivia() {
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\n' -> { i++; line++; lineStart = i }
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipBlockComment() {
            val open = pos()
            val start = i
            val isDoc = i + 2 < src.length && src[i + 2] == '*'
            i += 2
            while (i < src.length && !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')) {
                if (src[i] == '\n') { line++; lineStart = i + 1 }
                i++
            }
            if (i >= src.length) throw AidlSyntaxException("unterminated comment", open)
            i += 2
            // `/**/` is an empty block comment, not a doc comment, despite starting `/**`.
            if (isDoc && i - start > 4) pendingDoc = stripDocMarkers(src.substring(start, i))
        }

        /** The comment body with its opening and closing markers and its leading gutter stars removed. */
        private fun stripDocMarkers(raw: String): String =
            raw.removePrefix("/**").removeSuffix("*/")
                .lines().joinToString("\n") { it.trim().removePrefix("*").trim() }
                .trim()
    }

    // ---------------------------------------------------------------- parser

    private val DIRECTIONS = mapOf("in" to AidlDirection.IN, "out" to AidlDirection.OUT, "inout" to AidlDirection.INOUT)

    private class Parser(private val tokens: List<Token>, private val src: String, private val path: String) {
        private var p = 0

        private fun peek(offset: Int = 0): Token = tokens[(p + offset).coerceAtMost(tokens.size - 1)]
        private fun advance(): Token = tokens[p].also { if (p < tokens.size - 1) p++ }
        private fun atEnd() = peek().kind == Kind.EOF

        private fun fail(message: String, tok: Token = peek()): Nothing =
            throw AidlSyntaxException(message, tok.pos)

        private fun matchIdent(name: String): Boolean = peek().isIdent(name).also { if (it) advance() }
        private fun matchPunct(ch: String): Boolean = peek().isPunct(ch).also { if (it) advance() }

        private fun expectPunct(ch: String): Token =
            if (peek().isPunct(ch)) advance() else fail("expected '$ch' but found ${peek()}")

        private fun expectIdent(what: String): Token =
            if (peek().kind == Kind.IDENT) advance() else fail("expected $what but found ${peek()}")

        fun parseFile(): AidlFile {
            var packageName = ""
            if (peek().isIdent("package")) {
                advance()
                packageName = qualifiedName()
                expectPunct(";")
            }
            val imports = ArrayList<String>()
            while (peek().isIdent("import")) {
                advance()
                imports.add(qualifiedName())
                expectPunct(";")
            }
            val decls = ArrayList<AidlDecl>()
            while (!atEnd()) decls.add(parseDecl())
            return AidlFile(path, packageName, imports, decls)
        }

        // -------------------------------------------------------- declarations

        private fun parseDecl(): AidlDecl {
            val doc = peek().doc
            val annotations = parseAnnotations()
            val start = peek()
            // `oneway interface IFoo { … }`: every method of the interface is implicitly oneway.
            val oneway = matchIdent("oneway")
            return when {
                matchIdent("interface") -> parseInterface(oneway, annotations, start.pos, doc)
                oneway -> fail("'oneway' may only precede 'interface' or a method", start)
                matchIdent("parcelable") -> parseParcelable(annotations, start.pos, doc)
                matchIdent("enum") -> parseEnum(annotations, start.pos, doc)
                matchIdent("union") -> parseUnion(annotations, start.pos, doc)
                else -> fail("expected 'interface', 'parcelable', 'enum' or 'union' but found ${peek()}")
            }
        }

        private fun parseInterface(oneway: Boolean, annotations: List<AidlAnnotation>, pos: AidlPos, doc: String?): AidlDecl {
            val name = qualifiedName()
            // `interface a.b.IFoo;` is a forward declaration, as found in the SDK's preprocessed framework.aidl.
            if (matchPunct(";")) {
                return AidlInterface(name, oneway, annotations = annotations, forwardDeclaration = true, pos = pos, doc = doc)
            }
            expectPunct("{")
            val methods = ArrayList<AidlMethod>()
            val constants = ArrayList<AidlConstant>()
            while (!peek().isPunct("}")) {
                if (atEnd()) fail("unterminated interface '$name': expected '}'")
                val memberDoc = peek().doc
                val memberAnnotations = parseAnnotations()
                if (matchIdent("const")) {
                    constants.add(parseConstant(memberDoc))
                } else {
                    methods.add(parseMethod(oneway, memberAnnotations, memberDoc))
                }
            }
            expectPunct("}")
            return AidlInterface(name, oneway, methods, constants, forwardDeclaration = false, annotations = annotations, pos = pos, doc = doc)
        }

        private fun parseMethod(interfaceOneway: Boolean, annotations: List<AidlAnnotation>, doc: String?): AidlMethod {
            val start = peek()
            val methodOneway = matchIdent("oneway")
            val returnType = parseType()
            val name = expectIdent("a method name").text
            expectPunct("(")
            val params = ArrayList<AidlParam>()
            if (!peek().isPunct(")")) {
                do params.add(parseParam()) while (matchPunct(","))
            }
            expectPunct(")")
            // `void foo() = 12;` pins an explicit transaction id, so the wire protocol survives reordering.
            var transactionId: Int? = null
            if (matchPunct("=")) {
                val tok = peek()
                if (tok.kind != Kind.NUMBER) fail("expected a transaction id but found $tok")
                advance()
                transactionId = parseIntLiteral(tok) ?: fail("'${tok.text}' is not a valid transaction id", tok)
            }
            expectPunct(";")
            return AidlMethod(name, returnType, params, interfaceOneway || methodOneway, transactionId, annotations, start.pos, doc)
        }

        private fun parseParam(): AidlParam {
            val start = peek()
            // The reference grammar is `direction annotations type name`; accept annotations on either side of
            // the direction, since both orderings read naturally and neither is ambiguous.
            var annotations = parseAnnotations()
            val direction = if (peek().kind == Kind.IDENT) DIRECTIONS[peek().text]?.also { advance() } else null
            if (annotations.isEmpty()) annotations = parseAnnotations()
            val type = parseType()
            val name = expectIdent("a parameter name").text
            return AidlParam(name, type, direction, annotations, start.pos)
        }

        private fun parseConstant(doc: String?): AidlConstant {
            val start = peek()
            val type = parseType()
            val name = expectIdent("a constant name").text
            expectPunct("=")
            val value = rawExpression()
            expectPunct(";")
            return AidlConstant(name, type, value, start.pos, doc)
        }

        private fun parseParcelable(annotations: List<AidlAnnotation>, pos: AidlPos, doc: String?): AidlDecl {
            val name = qualifiedName()
            skipTypeParameters()
            // `parcelable Foo cpp_header "foo.h";` is recorded so the file parses; the Java backend ignores it.
            var cppHeader: String? = null
            if (matchIdent("cpp_header")) {
                val tok = peek()
                if (tok.kind != Kind.STRING) fail("expected a header name in quotes but found $tok")
                advance()
                cppHeader = unquote(tok.text)
            }
            if (matchPunct(";")) return AidlParcelableDecl(name, cppHeader, annotations, pos, doc)
            expectPunct("{")
            val fields = ArrayList<AidlField>()
            val constants = ArrayList<AidlConstant>()
            parseBody(name) { memberDoc ->
                if (matchIdent("const")) constants.add(parseConstant(memberDoc)) else fields.add(parseField(memberDoc))
            }
            return AidlStructuredParcelable(name, fields, constants, annotations, pos, doc)
        }

        private fun parseUnion(annotations: List<AidlAnnotation>, pos: AidlPos, doc: String?): AidlDecl {
            val name = qualifiedName()
            skipTypeParameters()
            expectPunct("{")
            val fields = ArrayList<AidlField>()
            parseBody(name) { memberDoc -> fields.add(parseField(memberDoc)) }
            return AidlUnion(name, fields, annotations, pos, doc)
        }

        private fun parseEnum(annotations: List<AidlAnnotation>, pos: AidlPos, doc: String?): AidlDecl {
            val name = qualifiedName()
            expectPunct("{")
            val enumerators = ArrayList<AidlEnumerator>()
            while (!peek().isPunct("}")) {
                if (atEnd()) fail("unterminated enum '$name': expected '}'")
                val memberDoc = peek().doc
                parseAnnotations()
                val member = expectIdent("an enumerator name")
                val value = if (matchPunct("=")) rawExpression() else null
                enumerators.add(AidlEnumerator(member.text, value, member.pos, memberDoc))
                if (!matchPunct(",")) break
            }
            expectPunct("}")
            // `@Backing(type="int")` picks the integral type the constants are emitted as; AIDL defaults to byte.
            val backing = annotations.firstOrNull { it.name == "Backing" }?.args?.get("type")?.let { unquote(it) } ?: "byte"
            return AidlEnum(name, backing, enumerators, annotations, pos, doc)
        }

        /** Members of a `{ … }` body: doc + annotations, then whatever [member] parses, until the closing brace. */
        private fun parseBody(owner: String, member: (String?) -> Unit) {
            while (!peek().isPunct("}")) {
                if (atEnd()) fail("unterminated declaration '$owner': expected '}'")
                val doc = peek().doc
                member(doc)
            }
            expectPunct("}")
        }

        private fun parseField(doc: String?): AidlField {
            val start = peek()
            val annotations = parseAnnotations()
            val type = parseType()
            val name = expectIdent("a field name").text
            val default = if (matchPunct("=")) rawExpression() else null
            expectPunct(";")
            return AidlField(name, type, default, annotations, start.pos, doc)
        }

        // -------------------------------------------------------- types & annotations

        private fun parseType(): AidlTypeRef {
            val annotations = parseAnnotations()
            val start = peek()
            val name = qualifiedName()
            val typeArgs = ArrayList<AidlTypeRef>()
            if (matchPunct("<")) {
                do typeArgs.add(parseType()) while (matchPunct(","))
                expectPunct(">")
            }
            var dims = 0
            while (peek().isPunct("[")) {
                advance()
                expectPunct("]")
                dims++
            }
            return AidlTypeRef(name, typeArgs, dims, annotations, start.pos)
        }

        private fun parseAnnotations(): List<AidlAnnotation> {
            if (!peek().isPunct("@")) return emptyList()
            val out = ArrayList<AidlAnnotation>()
            while (matchPunct("@")) {
                val name = qualifiedName()
                out.add(AidlAnnotation(name, if (peek().isPunct("(")) parseAnnotationArgs() else emptyMap()))
            }
            return out
        }

        /** `(a = 1, b = "x")` or the single-value form `("x")`, with values kept as raw source text. */
        private fun parseAnnotationArgs(): Map<String, String> {
            expectPunct("(")
            if (matchPunct(")")) return emptyMap()
            val out = LinkedHashMap<String, String>()
            do {
                val first = rawExpression(stopAtEquals = true)
                if (matchPunct("=")) out[first] = rawExpression() else out["value"] = first
            } while (matchPunct(","))
            expectPunct(")")
            return out
        }

        private fun qualifiedName(): String {
            val sb = StringBuilder(expectIdent("a name").text)
            // A trailing `.` only continues the name when an identifier follows (`a.b.C`), never into `1..`.
            while (peek().isPunct(".") && peek(1).kind == Kind.IDENT) {
                advance()
                sb.append('.').append(advance().text)
            }
            return sb.toString()
        }

        /** `parcelable Foo<T> { … }`: generic parameters are parsed and dropped, the Java backend having no use for them. */
        private fun skipTypeParameters() {
            if (!matchPunct("<")) return
            var depth = 1
            while (depth > 0 && !atEnd()) {
                val tok = advance()
                if (tok.isPunct("<")) depth++ else if (tok.isPunct(">")) depth--
            }
        }

        /**
         * The raw source text of a constant / default / annotation-argument expression: every token up to the
         * next `,` `;` `)` or `}` that is not nested inside brackets. AIDL expression syntax is a subset of
         * Java's, so the slice is emitted into the generated code unchanged.
         */
        private fun rawExpression(stopAtEquals: Boolean = false): String {
            val start = peek()
            var depth = 0
            var end = start
            var consumed = false
            while (!atEnd()) {
                val tok = peek()
                if (depth == 0) {
                    if (tok.isPunct(",") || tok.isPunct(";") || tok.isPunct(")") || tok.isPunct("}")) break
                    if (stopAtEquals && tok.isPunct("=")) break
                }
                if (tok.isPunct("(") || tok.isPunct("{") || tok.isPunct("[")) depth++
                if (tok.isPunct(")") || tok.isPunct("}") || tok.isPunct("]")) depth--
                end = advance()
                consumed = true
            }
            if (!consumed) fail("expected an expression but found ${peek()}")
            return src.substring(start.start, end.end).trim()
        }

        private fun parseIntLiteral(tok: Token): Int? {
            val text = tok.text.removeSuffix("L").removeSuffix("l")
            return if (text.startsWith("0x") || text.startsWith("0X")) text.drop(2).toIntOrNull(16) else text.toIntOrNull()
        }
    }

    /** Strip the surrounding quotes from a lexed string/char literal. */
    internal fun unquote(text: String): String =
        if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) {
            text.substring(1, text.length - 1)
        } else text
}
