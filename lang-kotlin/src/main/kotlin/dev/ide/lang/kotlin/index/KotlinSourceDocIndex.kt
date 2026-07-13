package dev.ide.lang.kotlin.index

import com.intellij.psi.tree.IElementType
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.SourceDocExternalizer
import dev.ide.index.SourceDocValue
import dev.ide.index.StringKeyDescriptor
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

/**
 * sourceDoc (Kotlin): owner class FQN -> per-member-function real parameter NAMES + cleaned KDoc, recovered
 * from an attached Kotlin `-sources.jar`. Kotlin `@Metadata` already carries parameter names, so the win here
 * is the KDoc text the editor's doc panel shows; names/arity are filled too for overload disambiguation. A
 * class's own KDoc is the empty-name entry.
 *
 * **Lexer-based, not a PSI parse.** Extracting KDoc + declaration signatures needs only the token stream, so
 * this runs the Kotlin [KotlinLexer] and walks tokens rather than building (and fully materializing) a PSI
 * tree per file. That is dramatically cheaper for a large `-sources.jar` (no tree allocation, no function
 * bodies parsed) and holds no parse lock — the PSI path was the dominant cost of indexing library sources.
 * The trade-off is best-effort structural accuracy: the arity/owner heuristics can occasionally misattribute
 * a doc, which only degrades a doc popup (never crashes), and `@Metadata` remains the source of truth for
 * parameter names on the binary symbol being enriched (see `KotlinSymbolService.enrich`).
 *
 * Members of classes/objects only (keyed by the declaring class FQN, matching a binary member symbol's
 * `declaringClassFqn`). Top-level/extension callables — keyed by the `<File>Kt` facade — are not indexed yet.
 */
object KotlinSourceDocIndex : IndexExtension<String, SourceDocValue> {
    override val id = IndexId("kotlin.sourceDoc")
    // v2: switched from a PSI parse to a lexer scan (bumped so stale v1 segments rebuild with the new extractor).
    override val version = 2
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SourceDocExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY_SOURCE && it.unitName?.endsWith(".kt") == true }

    override fun index(input: IndexInput): Map<String, Collection<SourceDocValue>> {
        val text = input.text() ?: return emptyMap()
        // The only thing this index adds over the class file's `@Metadata` (which already carries Kotlin
        // parameter names) is the KDoc text the doc panel shows. A source with no KDoc at all contributes
        // nothing new, so skip it entirely — a cheap substring scan drops every undocumented library file.
        if (!text.contains("/**")) return emptyMap()
        return runCatching { scan(text) }.getOrDefault(emptyMap())
    }

    private class Tok(val type: IElementType, val text: String)

    /** Lex into the significant tokens (whitespace + non-KDoc comments dropped; KDoc kept). */
    private fun lex(text: CharSequence): List<Tok> {
        val lexer = KotlinLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Tok>()
        while (true) {
            val t = lexer.tokenType ?: break
            if (t != KtTokens.WHITE_SPACE && t != KtTokens.EOL_COMMENT &&
                t != KtTokens.BLOCK_COMMENT && t != KtTokens.SHEBANG_COMMENT
            ) out.add(Tok(t, lexer.tokenText))
            lexer.advance()
        }
        return out
    }

    /** One type currently open on the nesting stack: its FQN, simple name, and the brace depth of its body. */
    private class Frame(val fqn: String, val simple: String, val bodyDepth: Int)

    private fun scan(text: CharSequence): Map<String, Collection<SourceDocValue>> {
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
        // A declaration keyword seen at the enclosing brace level means a preceding body-less type ended.
        fun clearBodylessPending() { if (pending != null && parenDepth == 0 && bracketDepth == 0) pending = null }

        var i = 0
        while (i < n) {
            val tk = toks[i]
            when (tk.type) {
                KtTokens.DOC_COMMENT -> { pendingDoc = cleanKDoc(tk.text); i++ }

                KtTokens.PACKAGE_KEYWORD -> {
                    var j = i + 1
                    val sb = StringBuilder()
                    while (j < n && (toks[j].type == KtTokens.IDENTIFIER || toks[j].type == KtTokens.DOT)) {
                        sb.append(toks[j].text); j++
                    }
                    pkg = sb.toString(); i = j
                }

                KtTokens.LBRACE -> {
                    val p = pending
                    if (p != null && parenDepth == 0 && bracketDepth == 0) {
                        stack.addLast(Frame(p.fqn, p.simple, braceDepth + 1)); pending = null
                    }
                    braceDepth++; pendingDoc = null; i++
                }
                KtTokens.RBRACE -> {
                    braceDepth--
                    if (stack.isNotEmpty() && braceDepth < stack.last().bodyDepth) stack.removeLast()
                    pendingDoc = null; i++
                }
                KtTokens.LPAR -> { parenDepth++; i++ }
                KtTokens.RPAR -> { parenDepth--; i++ }
                KtTokens.LBRACKET -> { bracketDepth++; i++ }
                KtTokens.RBRACKET -> { bracketDepth--; i++ }

                KtTokens.CLASS_KEYWORD, KtTokens.INTERFACE_KEYWORD, KtTokens.OBJECT_KEYWORD -> {
                    clearBodylessPending()
                    val nameTok = toks.getOrNull(i + 1)
                    // `companion` is a soft keyword — the lexer emits it as an IDENTIFIER, so match by text.
                    val prev = toks.getOrNull(i - 1)
                    val simple = when {
                        nameTok?.type == KtTokens.IDENTIFIER -> nameTok.text
                        tk.type == KtTokens.OBJECT_KEYWORD &&
                            (prev?.type == KtTokens.COMPANION_KEYWORD || prev?.text == "companion") -> "Companion"
                        else -> null // anonymous object expression: don't track (its members aren't attributed)
                    }
                    if (simple != null) {
                        val fqn = prefix() + simple
                        val doc = pendingDoc; pendingDoc = null
                        if (doc != null) {
                            bucket(fqn).add(SourceDocValue("", -1, emptyList(), doc))
                            // Constructor completion looks up by the class simple name; surface the class doc
                            // there too. Metadata supplies ctor param names/arity, so leave those unknown.
                            bucket(fqn).add(SourceDocValue(simple, -1, emptyList(), doc))
                        }
                        pending = Frame(fqn, simple, -1)
                        i += if (nameTok?.type == KtTokens.IDENTIFIER) 2 else 1
                    } else i++
                }

                KtTokens.FUN_KEYWORD -> {
                    val top = stack.lastOrNull()
                    when {
                        // `fun interface Foo` — hand off to the INTERFACE_KEYWORD branch (keep pendingDoc for it).
                        toks.getOrNull(i + 1)?.type == KtTokens.INTERFACE_KEYWORD -> i++
                        else -> {
                            clearBodylessPending()
                            val isMember = top != null && braceDepth == top.bodyDepth &&
                                parenDepth == 0 && bracketDepth == 0
                            if (!isMember) { pendingDoc = null; i++ } else {
                                // The name is the last identifier before the param-list '('.
                                var j = i + 1
                                var name: String? = null
                                while (j < n) {
                                    val t = toks[j].type
                                    if (t == KtTokens.LPAR || t == KtTokens.EQ ||
                                        t == KtTokens.LBRACE || t == KtTokens.SEMICOLON
                                    ) break
                                    if (t == KtTokens.IDENTIFIER) name = toks[j].text
                                    j++
                                }
                                val doc = pendingDoc; pendingDoc = null
                                if (name != null && j < n && toks[j].type == KtTokens.LPAR) {
                                    val (names, after) = params(toks, j)
                                    bucket(top!!.fqn).add(SourceDocValue(name, names.size, names, doc))
                                    i = after
                                } else i = j
                            }
                        }
                    }
                }

                KtTokens.CONSTRUCTOR_KEYWORD -> {
                    clearBodylessPending()
                    val top = stack.lastOrNull()
                    val isMember = top != null && braceDepth == top.bodyDepth && parenDepth == 0 && bracketDepth == 0
                    val doc = pendingDoc; pendingDoc = null
                    var j = i + 1
                    while (j < n && toks[j].type != KtTokens.LPAR && toks[j].type != KtTokens.LBRACE) j++
                    if (isMember && j < n && toks[j].type == KtTokens.LPAR) {
                        val (names, after) = params(toks, j)
                        bucket(top!!.fqn).add(SourceDocValue(top.simple, names.size, names, doc))
                        i = after
                    } else i++
                }

                KtTokens.VAL_KEYWORD, KtTokens.VAR_KEYWORD, KtTokens.SEMICOLON -> {
                    clearBodylessPending(); pendingDoc = null; i++
                }

                else -> i++
            }
        }
        return out
    }

    /** Parse a value-parameter list starting at the '(' [open]; returns the parameter names and the index just
     *  past the matching ')'. A parameter is one top-level `name : Type`, so we count the colons at the list's
     *  own paren level (colons nested in a default value, lambda type, or generic argument don't count) and
     *  take the identifier immediately before each as the name. Robust to `Map<K, V>` commas without tracking
     *  generics; best-effort against the rare default value with a bare top-level colon. */
    private fun params(toks: List<Tok>, open: Int): Pair<List<String>, Int> {
        val names = ArrayList<String>()
        var p = 0; var b = 0; var br = 0
        var lastIdent: String? = null
        var j = open
        while (j < toks.size) {
            when (toks[j].type) {
                KtTokens.LPAR -> p++
                KtTokens.RPAR -> { p--; if (p == 0) { j++; break } }
                KtTokens.LBRACKET -> b++
                KtTokens.RBRACKET -> b--
                KtTokens.LBRACE -> br++
                KtTokens.RBRACE -> br--
                KtTokens.IDENTIFIER -> if (p == 1 && b == 0 && br == 0) lastIdent = toks[j].text
                KtTokens.COLON -> if (p == 1 && b == 0 && br == 0) { names.add(lastIdent ?: "_"); lastIdent = null }
            }
            j++
        }
        return names to j
    }

    /** Strip the `/** … */` markers and `@tag` lines, keep paragraph breaks, cap the length. */
    private fun cleanKDoc(raw: String): String =
        raw.lineSequence()
            .map { it.trim().removePrefix("/**").removePrefix("/*").let { l -> if (l.endsWith("*/")) l.dropLast(2) else l }.trim().removePrefix("*").trim() }
            .filterNot { it.startsWith("@") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(2000)
}
