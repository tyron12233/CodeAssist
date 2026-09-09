package dev.ide.lang.kotlin.analysis

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.parse.KotlinDomNode
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import com.intellij.psi.PsiElement

/**
 * `equals`/`hashCode` and `toString` for a Kotlin class, written from its constructor properties and
 * property declarations.
 *
 * A `data class` already gets all three from the compiler, so neither is offered for one. The generators
 * are also skipped when the member is already declared, since a duplicate override does not compile.
 * `override` stubs for inherited abstract members are a separate intention
 * ([KotlinImplementMembersActionProvider]).
 */
private val KOTLIN = KotlinLanguageBackend.LANGUAGE_ID

/** `equals` and `hashCode` over every property, generated together so they cannot disagree. */
class KotlinGenerateEqualsActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        if (site.properties.isEmpty()) return emptyList()
        if (site.declares("equals") || site.declares("hashCode")) return emptyList()
        return listOf(
            site.generate("Generate 'equals()' and 'hashCode()'") { member, body ->
                buildString {
                    append("${member}override fun equals(other: Any?): Boolean {\n")
                    append("${body}if (this === other) return true\n")
                    append("${body}if (other !is ${site.name}) return false\n")
                    for (p in site.properties) append("${body}if ($p != other.$p) return false\n")
                    append("${body}return true\n")
                    append("$member}\n")
                    append('\n')
                    append("${member}override fun hashCode(): Int {\n")
                    append("${body}var result = ${hashOf(site.properties.first())}\n")
                    for (p in site.properties.drop(1)) {
                        append("${body}result = 31 * result + ${hashOf(p)}\n")
                    }
                    append("${body}return result\n")
                    append("$member}\n")
                }
            },
        )
    }

    /** Kotlin's `Any?.hashCode()` is not callable on a nullable receiver, so a null-safe call is used. */
    private fun hashOf(name: String) = "($name?.hashCode() ?: 0)"
}

/** `toString` listing every property, in the `Class(prop=value)` shape Kotlin's data classes use. */
class KotlinGenerateToStringActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        if (site.properties.isEmpty()) return emptyList()
        if (site.declares("toString")) return emptyList()
        return listOf(
            site.generate("Generate 'toString()'") { member, _ ->
                val rendered = site.properties.joinToString(", ") { "$it=\$$it" }
                "${member}override fun toString(): String = \"${site.name}($rendered)\"\n"
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------------

/** The class to generate into: its name, its property names, its members, and where the body ends. */
private class Site(
    val klass: KtClass,
    val name: String,
    val properties: List<String>,
    val functionNames: Set<String>,
    val insertAt: Int,
    val indent: String,
    val bodyIsEmpty: Boolean,
    val hasBody: Boolean,
) {
    fun declares(name: String) = name in functionNames

    /**
     * A [QuickFix] inserting a generated member into the class body, opening a body when there is none.
     *
     * [render] is handed the two indents it needs: `member` for a member's own lines and `body` for the
     * statements inside it, both derived from the class's indentation.
     */
    fun generate(title: String, render: (member: String, body: String) -> String): QuickFix =
        object : QuickFix {
            override val title = title
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                val memberIndent = indent + INDENT
                val generated = render(memberIndent, memberIndent + INDENT)
                val text = if (hasBody) {
                    (if (bodyIsEmpty) "" else "\n") + generated + indent
                } else {
                    // `class C(val a: Int)` has no body: open one around the generated member.
                    " {\n" + generated + indent + "}"
                }
                return WorkspaceEdit.of(ctx.target.file, DocumentEdit(insertAt, 0, text))
            }
        }
}

/**
 * The class to generate into, or null when the caret is not at a member position: outside any class,
 * inside a function body, or in a `data class` (whose members the compiler already generates).
 */
private fun generationSite(ctx: EditorActionContext): Site? {
    var n: PsiElement? = (ctx.node as? KotlinDomNode)?.psi ?: return null
    var klass: KtClass? = null
    while (n != null) {
        if (n is KtBlockExpression && n.parent is KtNamedFunction) return null
        if (n is KtClass) { klass = n; break }
        n = n.parent
    }
    val decl = klass ?: return null
    if (decl.isData() || decl.isInterface() || decl.isEnum() || decl.isAnnotation()) return null
    val name = decl.name ?: return null

    // Constructor `val`/`var` parameters plus the class's own property declarations, in source order.
    val properties = ArrayList<String>()
    decl.primaryConstructor?.valueParameters?.forEach { p ->
        if (p.hasValOrVar()) p.name?.let { properties.add(it) }
    }
    decl.body?.properties?.forEach { p: KtProperty -> p.name?.let { properties.add(it) } }

    val functions = decl.body?.functions?.mapNotNullTo(HashSet()) { it.name } ?: HashSet()
    val text = ctx.target.parsed.text()
    val indent = lineIndentOf(text, decl.textRange.startOffset)
    val body = decl.body
    return if (body != null) {
        val close = body.textRange.endOffset - 1
        Site(
            klass = decl,
            name = name,
            properties = properties,
            functionNames = functions,
            insertAt = close.coerceIn(0, text.length),
            indent = indent,
            bodyIsEmpty = body.declarations.isEmpty(),
            hasBody = true,
        )
    } else {
        Site(
            klass = decl,
            name = name,
            properties = properties,
            functionNames = functions,
            insertAt = decl.textRange.endOffset,
            indent = indent,
            bodyIsEmpty = true,
            hasBody = false,
        )
    }
}

/** The leading whitespace of the line containing [offset]. */
private fun lineIndentOf(text: CharSequence, offset: Int): String {
    var start = offset.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] != '\n') start--
    val sb = StringBuilder()
    var i = start
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
        sb.append(text[i])
        i++
    }
    return sb.toString()
}

/** One indentation level in generated code. */
private const val INDENT = "    "
