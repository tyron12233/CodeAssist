package dev.ide.core.completion

import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP
import dev.ide.lang.postfix.PostfixContext
import dev.ide.lang.postfix.PostfixExpansion
import dev.ide.lang.postfix.PostfixTemplate
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.template.SnippetExpansion
import dev.ide.platform.ContentHash
import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generic [PostfixContributor] driving `platform.postfixTemplate`: a plugin template surfaces at a
 *  `receiver.key` position, the receiver is reconstructed, and the item deletes `receiver.` + inserts the rewrite. */
class PostfixContributorTest {

    private val soutTemplate = object : PostfixTemplate {
        override val key = "sout"
        override val example = "expr.sout → println(expr)"
        override val description = "Print the expression"
        override fun isApplicable(ctx: PostfixContext) = true
        override fun expand(ctx: PostfixContext) = PostfixExpansion(
            edits = emptyList(),
            snippet = SnippetExpansion("println(${ctx.expressionText})", emptyList(), "println(${ctx.expressionText})".length),
        )
    }

    // A template that only applies to a Boolean receiver — exercises typeResolver gating.
    private val ifTemplate = object : PostfixTemplate {
        override val key = "if"
        override val example = "cond.if → if (cond) {}"
        override val description = "Wrap in if"
        override fun isApplicable(ctx: PostfixContext) = ctx.type?.qualifiedName == "kotlin.Boolean"
        override fun expand(ctx: PostfixContext) = PostfixExpansion(
            emptyList(), SnippetExpansion("if (${ctx.expressionText}) {}", emptyList(), 0),
        )
    }

    private fun run(text: String, offset: Int, prefix: String, type: TypeRef? = null, vararg templates: PostfixTemplate) =
        runBlocking {
            val reg = ExtensionRegistryImpl()
            templates.forEach { reg.register(POSTFIX_TEMPLATE_EP, it, PluginId("plugin")) }
            val engine = CompletionEngine(reg)
            val params = CompletionParams(
                document = Doc(text),
                offset = offset,
                prefix = prefix,
                language = LanguageId("kotlin"),
                trigger = CompletionTrigger.Explicit,
                replacementRange = TextRange(offset - prefix.length, offset),
                position = Node(NodeKind.NAME_REF),
                parsedFile = Parsed(text),
                typeResolver = { _ -> type },
            )
            // The contributor isn't registered on the EP here; pass it as a per-call contribution.
            engine.complete(params, listOf(CompletionContribution(PostfixContributor(reg))))
        }

    @Test
    fun postfixTemplateSurfacesAndRewrites() {
        val res = run("x.so", offset = 4, prefix = "so", templates = arrayOf(soutTemplate))
        val item = res.items.single { it.kind == CompletionItemKind.SNIPPET }
        assertEquals("sout", item.label)
        assertEquals("println(x)", item.insertText)
        assertTrue(item.caret is CaretAction.ExpandSnippet)
        // additionalEdits delete the `x.` receiver span [0,2).
        assertTrue(item.additionalEdits.any { it.range == TextRange(0, 2) && it.newText.isEmpty() }, "got ${item.additionalEdits}")
    }

    @Test
    fun typeGatedTemplateRespectsReceiverType() {
        val boolType = object : TypeRef {
            override val qualifiedName = "kotlin.Boolean"
            override val typeArguments = emptyList<TypeRef>()
            override fun isAssignableFrom(other: TypeRef) = false
            override fun supertypes() = emptyList<TypeRef>()
            override fun members(accessibleFrom: dev.ide.lang.resolve.Symbol?) = emptyList<dev.ide.lang.resolve.Symbol>()
        }
        assertTrue(run("b.i", 3, "i", type = boolType, templates = arrayOf(ifTemplate)).items.any { it.label == "if" })
        // No type resolved → the Boolean-only template must not apply.
        assertTrue(run("b.i", 3, "i", type = null, templates = arrayOf(ifTemplate)).items.none { it.label == "if" })
    }

    @Test
    fun noTemplatesIsNoop() {
        assertTrue(run("x.so", 4, "so").items.isEmpty())
    }

    // --- fakes ---

    private class Node(override val kind: NodeKind, override val parent: DomNode? = null) : DomNode {
        override val range = TextRange(0, 0)
        override val children = emptyList<DomNode>()
        override fun text(): CharSequence = ""
    }

    private class Parsed(private val txt: String) : ParsedFile {
        override val kind = NodeKind.COMPILATION_UNIT
        override val range = TextRange(0, txt.length)
        override val parent: DomNode? = null
        override val children = emptyList<DomNode>()
        override fun text(): CharSequence = txt
        override val file: VirtualFile = FakeFile
        override val documentVersion = 1L
        override val diagnostics = emptyList<dev.ide.lang.dom.Diagnostic>()
        override fun nodeAt(offset: Int): DomNode = Node(NodeKind.NAME_REF)
        override fun nodesIn(range: TextRange): Sequence<DomNode> = emptySequence()
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = FakeFile
        override val version = 1L
        override fun length() = text.length
    }

    private object FakeFile : VirtualFile {
        override val path = "/f.kt"; override val name = "f.kt"
        override val isDirectory = false; override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children() = emptyList<VirtualFile>()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
