package dev.codeassist.lspdemo

/**
 * Calc, the sample's language: one statement per line.
 *
 * ```
 * # the area of a rectangle
 * let width = 4
 * let height = width * 2
 * print max(width, height) * 3
 * ```
 *
 * `let name = expression` declares a constant (once), `print expression` uses it, `#` starts a comment. An
 * expression is numbers, names declared on an earlier line, `+ - * /`, parentheses and the built-ins in
 * [BUILTINS]. This file is the whole of the language's "compiler": the server turns its [CalcAnalysis] into
 * protocol answers, and the unit tests check it on the JVM.
 */
data class Span(val line: Int, val start: Int, val end: Int) {
    fun contains(line: Int, column: Int): Boolean = line == this.line && column in start..end
}

enum class Severity(val lsp: Int) { ERROR(1), WARNING(2) }

data class Problem(val span: Span, val message: String, val severity: Severity, val code: String, val name: String? = null)

data class Declaration(val name: String, val span: Span, val value: Double?, val expression: String)

data class Builtin(val name: String, val parameters: List<String>, val doc: String, val apply: (List<Double>) -> Double) {
    val signature: String get() = "$name(${parameters.joinToString(", ")})"
}

val BUILTINS: Map<String, Builtin> = listOf(
    Builtin("max", listOf("a", "b"), "The larger of `a` and `b`.") { maxOf(it[0], it[1]) },
    Builtin("min", listOf("a", "b"), "The smaller of `a` and `b`.") { minOf(it[0], it[1]) },
    Builtin("abs", listOf("x"), "`x` without its sign.") { kotlin.math.abs(it[0]) },
).associateBy { it.name }

val KEYWORDS = listOf("let", "print", "test", "is")

/** A `test NAME: EXPR is EXPECTED` statement, and what its two sides evaluated to (null on an error). */
data class CalcTestCase(val name: String, val span: Span, val actual: Double?, val expected: Double?) {
    val passed: Boolean get() = actual != null && expected != null && kotlin.math.abs(actual - expected) < 1e-9
}

internal enum class TokenKind { IDENT, NUMBER, OP, COMMENT, BAD }

internal data class Token(val kind: TokenKind, val text: String, val start: Int) {
    val end: Int get() = start + text.length
}

internal fun tokenize(line: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c.isWhitespace() -> i++
            c == '#' -> { out += Token(TokenKind.COMMENT, line.substring(i), i); i = line.length }
            c.isLetter() || c == '_' -> {
                val s = i
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                out += Token(TokenKind.IDENT, line.substring(s, i), s)
            }
            c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                val s = i
                while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
                out += Token(TokenKind.NUMBER, line.substring(s, i), s)
            }
            c in "+-*/=(),:" -> { out += Token(TokenKind.OP, c.toString(), i); i++ }
            else -> { out += Token(TokenKind.BAD, c.toString(), i); i++ }
        }
    }
    return out
}

/** Everything the server needs to know about one version of a document. */
class CalcAnalysis(val text: String) {
    val lines: List<String> = text.split('\n').map { it.removeSuffix("\r") }
    val declarations = LinkedHashMap<String, Declaration>()
    val uses = ArrayList<Pair<String, Span>>()
    val calls = ArrayList<Pair<String, Span>>()
    val keywords = ArrayList<Span>()
    val problems = ArrayList<Problem>()

    /** The value each statement line evaluated to, for inlay hints. */
    val values = HashMap<Int, Double>()

    /** The file's `test` statements, in order. */
    val tests = ArrayList<CalcTestCase>()

    init {
        for ((index, line) in lines.withIndex()) statement(index, line)
        for (d in declarations.values) {
            if (uses.none { it.first == d.name }) {
                problems += Problem(d.span, "'${d.name}' is never used", Severity.WARNING, "calc.unused", d.name)
            }
        }
    }

    private fun statement(line: Int, source: String) {
        val tokens = tokenize(source).filter { it.kind != TokenKind.COMMENT }
        if (tokens.isEmpty()) return
        tokens.firstOrNull { it.kind == TokenKind.BAD }?.let {
            problems += Problem(Span(line, it.start, it.end), "Unexpected '${it.text}'", Severity.ERROR, "calc.syntax")
            return
        }
        val head = tokens[0]
        when {
            head.kind == TokenKind.IDENT && head.text == "let" -> {
                keywords += Span(line, head.start, head.end)
                val name = tokens.getOrNull(1)
                if (name == null || name.kind != TokenKind.IDENT || name.text in KEYWORDS || name.text in BUILTINS) {
                    val at = name ?: head
                    problems += Problem(Span(line, at.start, at.end), "Expected a name after 'let'", Severity.ERROR, "calc.syntax")
                    return
                }
                val equals = tokens.getOrNull(2)
                if (equals == null || equals.text != "=") {
                    problems += Problem(Span(line, name.start, name.end), "Expected '=' after '${name.text}'", Severity.ERROR, "calc.syntax")
                    return
                }
                val span = Span(line, name.start, name.end)
                val value = expression(line, tokens.drop(3), name.end)
                declarations[name.text]?.let { earlier ->
                    problems += Problem(
                        span, "'${name.text}' is already defined on line ${earlier.span.line + 1}", Severity.ERROR, "calc.redefined",
                    )
                    return
                }
                val exprText = tokens.drop(3).let { if (it.isEmpty()) "" else source.substring(it.first().start, it.last().end) }
                declarations[name.text] = Declaration(name.text, span, value, exprText)
                value?.let { values[line] = it }
            }
            head.kind == TokenKind.IDENT && head.text == "print" -> {
                keywords += Span(line, head.start, head.end)
                expression(line, tokens.drop(1), head.end)?.let { values[line] = it }
            }
            head.kind == TokenKind.IDENT && head.text == "test" -> {
                keywords += Span(line, head.start, head.end)
                val name = tokens.getOrNull(1)
                val colon = tokens.getOrNull(2)
                val isAt = tokens.indexOfFirst { it.kind == TokenKind.IDENT && it.text == "is" }
                if (name == null || name.kind != TokenKind.IDENT || colon?.text != ":" || isAt < 4) {
                    problems += Problem(Span(line, head.start, head.end), "Write a test as: test name: expression is expected", Severity.ERROR, "calc.syntax")
                    return
                }
                keywords += Span(line, tokens[isAt].start, tokens[isAt].end)
                val span = Span(line, name.start, name.end)
                if (tests.any { it.name == name.text }) {
                    problems += Problem(span, "There is already a test named '${name.text}'", Severity.ERROR, "calc.redefined")
                    return
                }
                val actual = expression(line, tokens.subList(3, isAt), colon.end)
                val expected = expression(line, tokens.drop(isAt + 1), tokens[isAt].end)
                tests += CalcTestCase(name.text, span, actual, expected)
            }
            else -> problems += Problem(
                Span(line, head.start, head.end), "A statement starts with 'let', 'print' or 'test'", Severity.ERROR, "calc.syntax",
            )
        }
    }

    /** Parse and evaluate [tokens] as one expression; null when it has an error (already reported). */
    private fun expression(line: Int, tokens: List<Token>, after: Int): Double? {
        if (tokens.isEmpty()) {
            problems += Problem(Span(line, after, after + 1), "Expected an expression", Severity.ERROR, "calc.syntax")
            return null
        }
        val parser = Parser(line, tokens)
        val value = parser.sum()
        if (parser.ok && parser.at < tokens.size) {
            val t = tokens[parser.at]
            problems += Problem(Span(line, t.start, t.end), "Unexpected '${t.text}'", Severity.ERROR, "calc.syntax")
            return null
        }
        return if (parser.ok) value else null
    }

    private inner class Parser(private val line: Int, private val tokens: List<Token>) {
        var at = 0
        var ok = true

        private fun peek(): Token? = tokens.getOrNull(at)

        private fun fail(token: Token?, message: String, code: String = "calc.syntax"): Double {
            if (ok) {
                val t = token ?: tokens.last()
                val start = if (token == null) t.end else t.start
                problems += Problem(Span(line, start, if (token == null) t.end + 1 else t.end), message, Severity.ERROR, code)
            }
            ok = false
            return 0.0
        }

        fun sum(): Double {
            var v = product()
            while (ok && peek()?.text.let { it == "+" || it == "-" }) {
                val op = tokens[at++].text
                val r = product()
                v = if (op == "+") v + r else v - r
            }
            return v
        }

        private fun product(): Double {
            var v = unary()
            while (ok && peek()?.text.let { it == "*" || it == "/" }) {
                val op = tokens[at++]
                val rightStart = peek()
                val r = unary()
                if (op.text == "/" && ok && r == 0.0) {
                    val s = rightStart ?: op
                    problems += Problem(Span(line, s.start, tokens[at - 1].end), "Division by zero", Severity.ERROR, "calc.divZero")
                    ok = false
                    return 0.0
                }
                v = if (op.text == "*") v * r else v / r
            }
            return v
        }

        private fun unary(): Double {
            if (peek()?.text == "-") { at++; return -unary() }
            return primary()
        }

        private fun primary(): Double {
            val t = peek() ?: return fail(null, "Expected a value")
            when {
                t.kind == TokenKind.NUMBER -> {
                    at++
                    return t.text.toDoubleOrNull() ?: fail(t, "'${t.text}' is not a number")
                }
                t.text == "(" -> {
                    at++
                    val v = sum()
                    if (!ok) return 0.0
                    if (peek()?.text != ")") return fail(peek(), "Expected ')'")
                    at++
                    return v
                }
                t.kind == TokenKind.IDENT && peek(1)?.text == "(" -> return call(t)
                t.kind == TokenKind.IDENT -> {
                    at++
                    val span = Span(line, t.start, t.end)
                    val d = declarations[t.text]
                    if (d == null) {
                        val code = if (t.text in BUILTINS) "calc.builtinValue" else "calc.undefined"
                        val message = if (t.text in BUILTINS) "'${t.text}' is a function: call it" else "'${t.text}' is not defined"
                        problems += Problem(span, message, Severity.ERROR, code, t.text)
                        ok = false
                        return 0.0
                    }
                    uses += t.text to span
                    return d.value ?: run { ok = false; 0.0 }
                }
                else -> return fail(t, "Unexpected '${t.text}'")
            }
        }

        private fun peek(ahead: Int): Token? = tokens.getOrNull(at + ahead)

        private fun call(name: Token): Double {
            at += 2 // name (
            val builtin = BUILTINS[name.text]
            calls += name.text to Span(line, name.start, name.end)
            val args = ArrayList<Double>()
            if (peek()?.text != ")") {
                while (true) {
                    args += sum()
                    if (!ok) return 0.0
                    if (peek()?.text == ",") { at++; continue }
                    break
                }
            }
            if (peek()?.text != ")") return fail(peek(), "Expected ')'")
            at++
            if (builtin == null) {
                problems += Problem(Span(line, name.start, name.end), "There is no function '${name.text}'", Severity.ERROR, "calc.noFunction")
                ok = false
                return 0.0
            }
            if (args.size != builtin.parameters.size) {
                val n = builtin.parameters.size
                problems += Problem(
                    Span(line, name.start, name.end), "${builtin.name} takes $n argument${if (n == 1) "" else "s"}, not ${args.size}",
                    Severity.ERROR, "calc.arity",
                )
                ok = false
                return 0.0
            }
            return builtin.apply(args)
        }
    }

    /** The name (declaration or use) at [line]:[column], with every place it is named. */
    fun symbolAt(line: Int, column: Int): String? =
        declarations.values.firstOrNull { it.span.contains(line, column) }?.name
            ?: uses.firstOrNull { it.second.contains(line, column) }?.first

    fun references(name: String, includeDeclaration: Boolean = true): List<Span> =
        (if (includeDeclaration) listOfNotNull(declarations[name]?.span) else emptyList()) +
            uses.filter { it.first == name }.map { it.second }

    /** The declarations visible on [line] (those above it), for completion. */
    fun visibleAt(line: Int): List<Declaration> = declarations.values.filter { it.span.line < line }

    /** [line] in its canonical spacing, or null when it is already canonical, blank, or does not tokenize. */
    fun formatted(line: Int): String? {
        val source = lines.getOrNull(line) ?: return null
        val tokens = tokenize(source)
        if (tokens.isEmpty() || tokens.any { it.kind == TokenKind.BAD }) return null
        val indent = source.takeWhile { it == ' ' || it == '\t' }
        val sb = StringBuilder(indent)
        var previous: Token? = null
        for (t in tokens) {
            val space = when {
                previous == null -> false
                t.kind == TokenKind.COMMENT -> true
                t.text == ")" || t.text == "," || t.text == ":" -> false
                previous.text == "(" -> false
                t.text == "(" -> previous.kind != TokenKind.IDENT || previous.text in KEYWORDS
                previous.text == "-" && isUnary(tokens, tokens.indexOf(previous)) -> false
                else -> true
            }
            if (space) sb.append(' ')
            sb.append(t.text)
            previous = t
        }
        val out = sb.toString()
        return out.takeIf { it != source }
    }

    private fun isUnary(tokens: List<Token>, index: Int): Boolean {
        val before = tokens.getOrNull(index - 1) ?: return true
        return before.kind == TokenKind.OP && before.text != ")" || before.text in KEYWORDS
    }
}

/** A value as Calc prints it: whole numbers without a decimal point. */
fun formatValue(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
