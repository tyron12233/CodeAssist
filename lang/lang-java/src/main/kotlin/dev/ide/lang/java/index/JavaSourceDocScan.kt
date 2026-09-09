package dev.ide.lang.java.index

import com.intellij.lang.java.lexer.JavaLexer
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaTokenType
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.ide.index.SourceDocValue

/**
 * Lexer-based extractor of Java library-source docs: owner type FQN -> per-method real parameter NAMES +
 * cleaned javadoc. A type's own javadoc is the empty-name entry; a constructor is keyed by the class simple
 * name (matching the bytecode symbol) — the same value shape [JavaSourceIndexer.docsOf] produced.
 *
 * **Lexer-based, not a PSI parse.** This replaces the IntelliJ-PSI `docsOf` path that `java.sourceDoc` used to
 * run over every `.java` in an attached `-sources.jar` / JDK `src.zip` / Android `sources/`. That parse
 * serialized under the global parse lock (concurrent `buildTree` is not ART-safe) and was the dominant cost of
 * indexing a large `LIBRARY_SOURCE` tree. Extracting doc + signatures needs only the token stream, so this runs
 * the [JavaLexer] and walks tokens: no PSI tree, no method bodies parsed, and NO parse lock — so source-jar
 * indexing re-parallelizes across cores (mirrors what `KotlinSourceDocIndex` did for Kotlin).
 *
 * Unlike Kotlin (whose `@Metadata` already carries parameter names), plain Java bytecode has NO parameter
 * names, so this index is the ONLY source of real names for library Java APIs — it therefore emits a method
 * entry for EVERY method (names + arity), documented or not. Best-effort structural accuracy: a misattributed
 * doc only degrades a doc popup, never crashes; the real value (param names) stays authoritative via arity.
 */
internal object JavaSourceDocScan {

    private class Tok(val type: IElementType, val text: String, val isDoc: Boolean)

    /** Token types that can END a method's return type (so the following identifier is the method NAME): a class
     *  name (`String`), a generic close (`>`, `>>`, `>>>`), an array (`]`), or a primitive/`void` keyword. */
    private val TYPE_ENDING: Set<IElementType> = setOf(
        JavaTokenType.IDENTIFIER, JavaTokenType.GT, JavaTokenType.GTGT, JavaTokenType.GTGTGT,
        JavaTokenType.RBRACKET,
        JavaTokenType.INT_KEYWORD, JavaTokenType.LONG_KEYWORD, JavaTokenType.SHORT_KEYWORD,
        JavaTokenType.BYTE_KEYWORD, JavaTokenType.CHAR_KEYWORD, JavaTokenType.BOOLEAN_KEYWORD,
        JavaTokenType.FLOAT_KEYWORD, JavaTokenType.DOUBLE_KEYWORD, JavaTokenType.VOID_KEYWORD,
    )

    /** How many `>` a generic-close token carries, for tracking `<…>` nesting through the `>>`/`>>>` the lexer
     *  fuses (a param type `Map<K, List<V>>` closes with one `GTGT`, not two `GT`). */
    private val GT_COUNT: Map<IElementType, Int> =
        mapOf(JavaTokenType.GT to 1, JavaTokenType.GTGT to 2, JavaTokenType.GTGTGT to 3)

    fun scan(text: CharSequence): Map<String, Collection<SourceDocValue>> = runCatching { walk(text) }
        .getOrDefault(emptyMap())

    /** Lex into significant tokens (whitespace + line/block comments dropped; javadoc kept as a doc token). */
    private fun lex(text: CharSequence): List<Tok> {
        val lexer = JavaLexer(LanguageLevel.HIGHEST)
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Tok>()
        while (true) {
            val t = lexer.tokenType ?: break
            val s = lexer.tokenText
            when {
                t == TokenType.WHITE_SPACE -> {}
                s.startsWith("//") -> {}
                // A lexer comment token starting `/**` (and not the empty `/**/`) is javadoc; `/*` is a plain
                // block comment. Classify by text so we needn't depend on the exact doc-comment IElementType.
                s.startsWith("/*") -> if (s.startsWith("/**") && s.length > 4) out.add(Tok(t, s, isDoc = true))
                else -> out.add(Tok(t, s, isDoc = false))
            }
            lexer.advance()
        }
        return out
    }

    /** One type on the nesting stack: its FQN, simple name, and the brace depth of its body. */
    private class Frame(val fqn: String, val simple: String, val bodyDepth: Int)

    private fun walk(text: CharSequence): Map<String, Collection<SourceDocValue>> {
        val toks = lex(text)
        val n = toks.size
        val out = HashMap<String, MutableList<SourceDocValue>>()
        fun bucket(fqn: String) = out.getOrPut(fqn) { ArrayList() }

        var pkg = ""
        var pendingDoc: String? = null
        val stack = ArrayDeque<Frame>()
        var pending: Frame? = null // a type whose body '{' we haven't reached yet
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        fun prefix(): String = buildString {
            if (pkg.isNotEmpty()) append(pkg).append('.')
            stack.forEach { append(it.simple).append('.') }
        }
        // A member declaration sits at its type's own body brace level, outside any parens/brackets.
        fun atBodyLevel(): Boolean {
            val top = stack.lastOrNull() ?: return false
            return braceDepth == top.bodyDepth && parenDepth == 0 && bracketDepth == 0
        }
        // A type declaration may appear at file scope or directly in an enclosing type's body.
        fun atDeclLevel(): Boolean =
            parenDepth == 0 && bracketDepth == 0 && (stack.isEmpty() || braceDepth == stack.last().bodyDepth)

        // Open a named type: emit its class-doc (if any) and mark it pending until its body '{'.
        fun openType(simple: String) {
            val fqn = prefix() + simple
            pendingDoc?.let { doc -> bucket(fqn).add(SourceDocValue("", -1, emptyList(), doc)) }
            pendingDoc = null
            pending = Frame(fqn, simple, -1)
        }

        var i = 0
        while (i < n) {
            val tk = toks[i]
            if (tk.isDoc) { pendingDoc = JavaDoc.clean(tk.text).ifEmpty { null }; i++; continue }
            when (tk.type) {
                JavaTokenType.PACKAGE_KEYWORD -> {
                    var j = i + 1
                    val sb = StringBuilder()
                    while (j < n && (toks[j].type == JavaTokenType.IDENTIFIER || toks[j].type == JavaTokenType.DOT)) {
                        sb.append(toks[j].text); j++
                    }
                    pkg = sb.toString(); pendingDoc = null; i = j
                }

                JavaTokenType.LBRACE -> {
                    val p = pending
                    if (p != null && parenDepth == 0 && bracketDepth == 0) {
                        stack.addLast(Frame(p.fqn, p.simple, braceDepth + 1)); pending = null
                    }
                    braceDepth++; pendingDoc = null; i++
                }
                JavaTokenType.RBRACE -> {
                    braceDepth--
                    if (stack.isNotEmpty() && braceDepth < stack.last().bodyDepth) stack.removeLast()
                    pendingDoc = null; i++
                }
                JavaTokenType.LPARENTH -> { parenDepth++; i++ }
                JavaTokenType.RPARENTH -> { parenDepth--; i++ }
                JavaTokenType.LBRACKET -> { bracketDepth++; i++ }
                JavaTokenType.RBRACKET -> { bracketDepth--; i++ }
                JavaTokenType.SEMICOLON -> { pendingDoc = null; i++ }

                JavaTokenType.CLASS_KEYWORD, JavaTokenType.INTERFACE_KEYWORD, JavaTokenType.ENUM_KEYWORD -> {
                    // `@interface`/`interface`/`class`/`enum` — the name is the next identifier (annotations and
                    // modifiers before the keyword are earlier tokens; the `@` of `@interface` is ignored above).
                    val nameTok = toks.getOrNull(i + 1)
                    if (nameTok?.type == JavaTokenType.IDENTIFIER) { openType(nameTok.text); i += 2 }
                    else { pendingDoc = null; i++ }
                }

                JavaTokenType.IDENTIFIER -> {
                    val next = toks.getOrNull(i + 1)
                    when {
                        // `record` is a soft keyword (lexed as an identifier): `record Name(` at a type-decl spot.
                        tk.text == "record" && atDeclLevel() && next?.type == JavaTokenType.IDENTIFIER &&
                            toks.getOrNull(i + 2)?.type == JavaTokenType.LPARENTH -> {
                            openType(next.text); i += 2
                        }
                        // A member method/constructor: `name(` at the type's body level.
                        next?.type == JavaTokenType.LPARENTH && atBodyLevel() -> {
                            val top = stack.last()
                            val prev = toks.getOrNull(i - 1)?.type
                            val name = tk.text
                            // A method has a return type right before its name; a constructor is named for its
                            // class and is NOT led by a type (excludes `new Foo(`, `x.Foo(`).
                            val isMethod = prev != null && prev in TYPE_ENDING
                            val isCtor = !isMethod && name == top.simple &&
                                prev != JavaTokenType.NEW_KEYWORD && prev != JavaTokenType.DOT
                            if (isMethod || isCtor) {
                                val (names, after) = params(toks, i + 1)
                                // Confirm a declaration (not some other `ident(...)`): what follows `)` is a body
                                // `{`, an abstract/interface `;`, a `throws`, or an annotation-element `default`.
                                val follow = toks.getOrNull(after)?.type
                                if (follow == JavaTokenType.LBRACE || follow == JavaTokenType.SEMICOLON ||
                                    follow == JavaTokenType.THROWS_KEYWORD || follow == JavaTokenType.DEFAULT_KEYWORD
                                ) {
                                    bucket(top.fqn).add(
                                        SourceDocValue(if (isCtor) top.simple else name, names.size, names, pendingDoc)
                                    )
                                    pendingDoc = null
                                }
                                i = after
                            } else i++
                        }
                        else -> i++
                    }
                }

                else -> i++ // modifiers, annotations, operators, literals — never clear a pending javadoc
            }
        }
        return out
    }

    /** Parse a value-parameter list starting at '(' [open]; returns the parameter names and the index just past
     *  the matching ')'. A parameter's name is the last identifier before its top-level `,`/`)` — read at the
     *  list's own paren level, outside `<…>`/`[…]` (so a generic `Map<K, V>` argument comma/name doesn't count).
     *  Modifiers/annotations before the type don't matter (we keep only the LAST identifier). Best-effort. */
    private fun params(toks: List<Tok>, open: Int): Pair<List<String>, Int> {
        val names = ArrayList<String>()
        var paren = 0; var bracket = 0; var angle = 0
        var lastIdent: String? = null
        var j = open
        while (j < toks.size) {
            val t = toks[j].type
            when {
                t == JavaTokenType.LPARENTH -> paren++
                t == JavaTokenType.RPARENTH -> {
                    paren--
                    if (paren == 0) { lastIdent?.let { names.add(it) }; j++; break }
                }
                t == JavaTokenType.LBRACKET -> bracket++
                t == JavaTokenType.RBRACKET -> bracket--
                t == JavaTokenType.LT -> if (paren == 1 && bracket == 0) angle++
                GT_COUNT.containsKey(t) -> if (paren == 1 && bracket == 0) angle = maxOf(0, angle - GT_COUNT.getValue(t))
                t == JavaTokenType.COMMA ->
                    if (paren == 1 && bracket == 0 && angle == 0) { names.add(lastIdent ?: "_"); lastIdent = null }
                t == JavaTokenType.IDENTIFIER ->
                    if (paren == 1 && bracket == 0 && angle == 0) lastIdent = toks[j].text
            }
            j++
        }
        return names to j
    }
}
