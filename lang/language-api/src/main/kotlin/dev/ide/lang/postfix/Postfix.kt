package dev.ide.lang.postfix

import dev.ide.platform.ExtensionPoint
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.template.SnippetExpansion

/**
 * Postfix completion: typing `expr.key` rewrites the whole expression — `list.for` → a `for` loop,
 * `cond.not` → `!cond`, `value.var` → `Type name = value;`, `x.sout` → `System.out.println(x);`.
 *
 * A [PostfixTemplate] is contributed through [POSTFIX_TEMPLATE_EP]; the engine's generic postfix
 * [dev.ide.lang.completion.CompletionContributor] collects the applicable ones at a `MEMBER_ACCESS` position
 * and surfaces each as an ordinary `CompletionItem(kind = SNIPPET)` whose `additionalEdits` delete the
 * `expr.` receiver span and whose `caret` is a [dev.ide.lang.completion.CaretAction.ExpandSnippet] driving
 * [PostfixExpansion.snippet]. So postfix reuses the completion + snippet seams end-to-end — no new editor path.
 */
interface PostfixTemplate {
    /** The trigger typed after the dot, e.g. "for", "not", "var", "sout", "null", "nn". */
    val key: String

    /** A one-line example shown in the popup, e.g. "expr.for → for (T it : expr)". */
    val example: String

    val description: String

    /** Languages this template applies to (by [dev.ide.lang.LanguageId]); empty = every language. A template
     *  emitting language-specific syntax (a Kotlin `?.let { }`, a Java `;`) scopes itself so the driver doesn't
     *  offer it in the wrong file. */
    val languages: Set<dev.ide.lang.LanguageId> get() = emptySet()

    /** True when this template makes sense for the receiver expression in [ctx] (type/shape check). */
    fun isApplicable(ctx: PostfixContext): Boolean

    /** Produce the edits (and optional snippet placeholders) that rewrite the expression. */
    fun expand(ctx: PostfixContext): PostfixExpansion
}

/**
 * The receiver expression a postfix template acts on — the part *before* the trailing `.key`. The
 * completion engine supplies the resolved [expression] node, its source [expressionText] and
 * [expressionRange], and its produced [type] (null when it can't be resolved on broken code).
 */
data class PostfixContext(
    val expression: DomNode,
    val expressionText: String,
    val expressionRange: TextRange,
    val type: TypeRef? = null,
    val document: DocumentSnapshot,
)

/**
 * The rewrite a postfix template emits. [edits] replace the `expr.key` text with the expansion (offsets in
 * document space); [snippet] optionally carries tab stops for the inserted text (e.g. the loop variable in
 * `for`), to be driven via [dev.ide.lang.completion.CaretAction.ExpandSnippet].
 */
data class PostfixExpansion(
    val edits: List<TextEdit>,
    val snippet: SnippetExpansion? = null,
)

/** The extension point through which postfix templates are contributed (cf. platform.languageBackend). */
val POSTFIX_TEMPLATE_EP = ExtensionPoint<PostfixTemplate>("platform.postfixTemplate")
