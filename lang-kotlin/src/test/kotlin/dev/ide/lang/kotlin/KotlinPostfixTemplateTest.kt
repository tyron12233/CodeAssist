package dev.ide.lang.kotlin

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.completion.KotlinPostfixTemplates
import dev.ide.lang.postfix.PostfixContext
import dev.ide.lang.postfix.PostfixTemplate
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.TypeRef
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Kotlin postfix templates ([KotlinPostfixTemplates]) in isolation: type gating (Boolean/reference/
 * iterable) and the rewrite each emits. The end-to-end path (engine + generic PostfixContributor +
 * receiver scan + type resolution) is covered by ide-core's PostfixContributorTest.
 */
class KotlinPostfixTemplateTest {

    private val templates = KotlinPostfixTemplates.all()
    private fun byKey(key: String) = templates.first { it.key == key }

    private fun ctx(receiver: String, type: TypeRef? = null) =
        PostfixContext(Node, receiver, TextRange(0, receiver.length), type, Doc(receiver))

    private fun rewrite(t: PostfixTemplate, ctx: PostfixContext): String = t.expand(ctx).snippet!!.text

    @Test
    fun allAreScopedToKotlin() {
        assertTrue(templates.all { it.languages == setOf(KotlinLanguageBackend.LANGUAGE_ID) })
    }

    @Test
    fun typeAgnosticTemplatesAlwaysApply() {
        for (key in listOf("val", "var", "return", "println", "print", "with")) {
            assertTrue(byKey(key).isApplicable(ctx("x")), "$key should apply to any receiver")
        }
        assertEquals("val name = x", rewrite(byKey("val"), ctx("x")))
        assertEquals("return x", rewrite(byKey("return"), ctx("x")))
        assertEquals("println(x)", rewrite(byKey("println"), ctx("x")))
    }

    @Test
    fun booleanGatedTemplates() {
        val bool = type("kotlin.Boolean")
        val int = type("kotlin.Int")
        for (key in listOf("if", "not", "while")) {
            assertTrue(byKey(key).isApplicable(ctx("b", bool)), "$key applies to Boolean")
            assertFalse(byKey(key).isApplicable(ctx("n", int)), "$key must not apply to Int")
        }
        assertTrue(rewrite(byKey("if"), ctx("b", bool)).startsWith("if (b) {"))
        assertEquals("!(b)", rewrite(byKey("not"), ctx("b", bool)))
    }

    @Test
    fun referenceGatedTemplates() {
        val str = type("kotlin.String")
        val int = type("kotlin.Int")
        for (key in listOf("null", "notnull", "nn", "let")) {
            assertTrue(byKey(key).isApplicable(ctx("s", str)), "$key applies to a reference type")
            assertFalse(byKey(key).isApplicable(ctx("n", int)), "$key must not apply to a primitive")
        }
        assertTrue(rewrite(byKey("notnull"), ctx("s", str)).startsWith("if (s != null) {"))
        assertEquals("s?.let {  }", rewrite(byKey("let"), ctx("s", str)))
    }

    @Test
    fun iterableGatedTemplates() {
        val list = type("kotlin.collections.List")
        val int = type("kotlin.Int")
        for (key in listOf("for", "iter")) {
            assertTrue(byKey(key).isApplicable(ctx("xs", list)), "$key applies to a List")
            assertFalse(byKey(key).isApplicable(ctx("n", int)), "$key must not apply to Int")
        }
        assertTrue(rewrite(byKey("for"), ctx("xs", list)).startsWith("for (item in xs) {"))
    }

    @Test
    fun newTypeAgnosticTemplates() {
        assertEquals("(x)", rewrite(byKey("par"), ctx("x")))
        assertEquals("x as Type", rewrite(byKey("cast"), ctx("x")))
        assertTrue(rewrite(byKey("when"), ctx("x")).startsWith("when (x) {"))
        assertTrue(rewrite(byKey("try"), ctx("x")).startsWith("try {\n    x\n} catch ("))
        for (key in listOf("par", "cast", "when", "try")) {
            assertTrue(byKey(key).isApplicable(ctx("x")), "$key should apply to any receiver")
        }
    }

    @Test
    fun takeIfUnlessAreReferenceGated() {
        val str = type("kotlin.String")
        val int = type("kotlin.Int")
        for (key in listOf("takeIf", "takeUnless")) {
            assertTrue(byKey(key).isApplicable(ctx("s", str)), "$key applies to a reference type")
            assertFalse(byKey(key).isApplicable(ctx("n", int)), "$key must not apply to a primitive")
        }
        assertEquals("s.takeIf {  }", rewrite(byKey("takeIf"), ctx("s", str)))
    }

    @Test
    fun spreadIsArrayGated() {
        assertTrue(byKey("spread").isApplicable(ctx("a", type("kotlin.IntArray"))), "spread applies to an array")
        assertFalse(byKey("spread").isApplicable(ctx("xs", type("kotlin.collections.List"))), "spread must not apply to a List")
        assertEquals("*a", rewrite(byKey("spread"), ctx("a", type("kotlin.IntArray"))))
    }

    @Test
    fun expandIsAlwaysASnippetWithNoExtraEdits() {
        val exp = byKey("val").expand(ctx("x"))
        assertTrue(exp.edits.isEmpty(), "the rewrite rides on the snippet; the driver adds the receiver delete")
        assertEquals(CaretAction.ExpandSnippet(exp.snippet!!), CaretAction.ExpandSnippet(exp.snippet!!))
    }

    // --- fakes ---

    private fun type(fqn: String): TypeRef = object : TypeRef {
        override val qualifiedName = fqn
        override val typeArguments = emptyList<TypeRef>()
        override fun isAssignableFrom(other: TypeRef) = false
        override fun supertypes() = emptyList<TypeRef>()
        override fun members(accessibleFrom: Symbol?) = emptyList<Symbol>()
    }

    private object Node : DomNode {
        override val kind = NodeKind.NAME_REF
        override val range = TextRange(0, 0)
        override val parent: DomNode? = null
        override val children = emptyList<DomNode>()
        override fun text(): CharSequence = ""
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = DiskFile(java.nio.file.Path.of("/f.kt"))
        override val version = 1L
        override fun length() = text.length
    }
}
