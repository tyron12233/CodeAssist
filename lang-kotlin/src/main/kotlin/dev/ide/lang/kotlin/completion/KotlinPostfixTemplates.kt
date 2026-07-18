package dev.ide.lang.kotlin.completion

import dev.ide.lang.LanguageId
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.postfix.PostfixContext
import dev.ide.lang.postfix.PostfixExpansion
import dev.ide.lang.postfix.PostfixTemplate
import dev.ide.lang.template.SnippetExpansion

/**
 * Kotlin postfix templates as `platform.postfixTemplate` ([dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP]) entries,
 * driven by the engine's generic `PostfixContributor` (ide-core). Typing `expr.key` rewrites the whole
 * expression (`cond.if` → `if (cond) { }`, `value.val` → `val name = value`, `list.for` → `for (item in list)
 * { }`). The contributor reconstructs the receiver and resolves its type (`analyzer.resolveType`) into
 * [PostfixContext.type]; these templates gate on that type and emit the rewrite as a snippet.
 *
 * Scoped to Kotlin via [PostfixTemplate.languages] (they emit Kotlin syntax). Deliberately excludes the
 * scope-function rewrites (`.let`-as-block is included only as a null-safe idiom; `.also`/`.run`/`.apply` are
 * real stdlib functions already offered as members). Registered by the host (ide-core).
 */
object KotlinPostfixTemplates {

    /** All Kotlin postfix templates, for the host to register on `POSTFIX_TEMPLATE_EP`. */
    fun all(): List<PostfixTemplate> = TEMPLATES

    private val KOTLIN = setOf(KotlinLanguageBackend.LANGUAGE_ID)

    private val PRIMITIVES = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte",
        "kotlin.Double", "kotlin.Float", "kotlin.Char", "kotlin.Boolean",
    )

    private fun isBoolean(ctx: PostfixContext) = ctx.type?.qualifiedName == "kotlin.Boolean"
    private fun isReference(ctx: PostfixContext) = ctx.type?.qualifiedName.let { it == null || it !in PRIMITIVES }
    private fun isIterable(ctx: PostfixContext): Boolean {
        val s = ctx.type?.qualifiedName?.substringAfterLast('.') ?: return false
        return s.endsWith("List") || s.endsWith("Set") || s.endsWith("Collection") ||
            s.endsWith("Array") || s.endsWith("Sequence") || s == "Iterable" || s == "Iterator"
    }

    private fun isArray(ctx: PostfixContext): Boolean =
        ctx.type?.qualifiedName?.substringAfterLast('.')?.endsWith("Array") == true

    private fun template(
        key: String,
        example: String,
        description: String,
        applicable: (PostfixContext) -> Boolean = { true },
        build: (PostfixContext) -> SnippetExpansion,
    ): PostfixTemplate = object : PostfixTemplate {
        override val key = key
        override val languages: Set<LanguageId> = KOTLIN
        override val example = example
        override val description = description
        override fun isApplicable(ctx: PostfixContext) = applicable(ctx)
        // The rewrite is the snippet; the driver adds the edit that deletes the original `receiver.` span.
        override fun expand(ctx: PostfixContext) = PostfixExpansion(emptyList(), build(ctx))
    }

    private val TEMPLATES: List<PostfixTemplate> = listOf(
        template("val", "expr.val → val name = expr", "Introduce a read-only variable") { c ->
            snippet { text("val "); stop(1, "name"); text(" = ${c.expressionText}"); finalHere() }
        },
        template("var", "expr.var → var name = expr", "Introduce a mutable variable") { c ->
            snippet { text("var "); stop(1, "name"); text(" = ${c.expressionText}"); finalHere() }
        },
        template("return", "expr.return → return expr", "Return the expression") { c ->
            snippet { text("return ${c.expressionText}"); finalHere() }
        },
        template("if", "expr.if → if (expr) { }", "Wrap in an if", ::isBoolean) { c ->
            snippet { text("if (${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("not", "expr.not → !expr", "Negate a boolean", ::isBoolean) { c ->
            snippet { text("!(${c.expressionText})"); finalHere() }
        },
        template("while", "expr.while → while (expr) { }", "Wrap in a while loop", ::isBoolean) { c ->
            snippet { text("while (${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("null", "expr.null → if (expr == null) { }", "Null check", ::isReference) { c ->
            snippet { text("if (${c.expressionText} == null) {\n    "); finalHere(); text("\n}") }
        },
        template("notnull", "expr.notnull → if (expr != null) { }", "Not-null check", ::isReference) { c ->
            snippet { text("if (${c.expressionText} != null) {\n    "); finalHere(); text("\n}") }
        },
        template("nn", "expr.nn → if (expr != null) { }", "Not-null check", ::isReference) { c ->
            snippet { text("if (${c.expressionText} != null) {\n    "); finalHere(); text("\n}") }
        },
        template("let", "expr.let → expr?.let { }", "Safe-call let block", ::isReference) { c ->
            snippet { text("${c.expressionText}?.let { "); finalHere(); text(" }") }
        },
        template("for", "coll.for → for (item in coll) { }", "Iterate (for-each)", ::isIterable) { c ->
            snippet { text("for ("); stop(1, "item"); text(" in ${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("iter", "coll.iter → for (item in coll) { }", "Iterate (for-each)", ::isIterable) { c ->
            snippet { text("for ("); stop(1, "item"); text(" in ${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("with", "expr.with → with(expr) { }", "with block") { c ->
            snippet { text("with(${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("println", "expr.println → println(expr)", "Print the expression") { c ->
            snippet { text("println(${c.expressionText})"); finalHere() }
        },
        template("print", "expr.print → print(expr)", "Print the expression") { c ->
            snippet { text("print(${c.expressionText})"); finalHere() }
        },
        template("when", "expr.when → when (expr) { }", "Wrap in a when") { c ->
            snippet { text("when (${c.expressionText}) {\n    "); finalHere(); text("\n}") }
        },
        template("par", "expr.par → (expr)", "Wrap in parentheses") { c ->
            snippet { text("(${c.expressionText})"); finalHere() }
        },
        template("cast", "expr.cast → expr as T", "Cast to a type") { c ->
            snippet { text("${c.expressionText} as "); stop(1, "Type"); finalHere() }
        },
        template("try", "expr.try → try { expr } catch (e) { }", "Wrap in try/catch") { c ->
            snippet {
                text("try {\n    ${c.expressionText}\n} catch ("); stop(1, "e"); text(": Exception) {\n    ")
                finalHere(); text("\n}")
            }
        },
        template("takeIf", "expr.takeIf → expr.takeIf { }", "Keep the value when a predicate holds", ::isReference) { c ->
            snippet { text("${c.expressionText}.takeIf { "); finalHere(); text(" }") }
        },
        template("takeUnless", "expr.takeUnless → expr.takeUnless { }", "Keep the value unless a predicate holds", ::isReference) { c ->
            snippet { text("${c.expressionText}.takeUnless { "); finalHere(); text(" }") }
        },
        template("spread", "arr.spread → *arr", "Spread an array into a vararg", ::isArray) { c ->
            snippet { text("*${c.expressionText}"); finalHere() }
        },
    )
}
