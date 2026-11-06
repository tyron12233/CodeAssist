package dev.ide.lang.kotlin

import dev.ide.lang.KotlinGeneratedMember
import dev.ide.lang.KotlinGeneratedMemberContext
import dev.ide.lang.KotlinGeneratedMemberProvider
import dev.ide.lang.KotlinSourceClass
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.symbols.BUILTIN_KOTLIN_SYNTHETIC_MEMBER_PROVIDERS
import dev.ide.lang.kotlin.symbols.PublishedGeneratedMembers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A provider an installed plugin registers on the published `KOTLIN_GENERATED_MEMBER_EP`, for a compiler plugin
 * the IDE knows nothing about: `@AutoCreate` generates `Foo.create(x)` and an instance `describe` property.
 * Through the adapter its members complete, type a chain, and are not flagged unresolved.
 */
class PublishedGeneratedMembersTest {

    private var seen: KotlinSourceClass? = null

    private val autoCreate = object : KotlinGeneratedMemberProvider {
        override val id = "test.autoCreate"

        override fun staticMembers(cls: KotlinSourceClass, ctx: KotlinGeneratedMemberContext): List<KotlinGeneratedMember> {
            if ("AutoCreate" !in cls.annotations || !ctx.hasType("kotlin.String")) return emptyList()
            seen = cls
            val (param, type) = cls.constructorParameters.first()
            return listOf(KotlinGeneratedMember.function("create", listOf(param to type!!), cls.fqName))
        }

        override fun instanceMembers(cls: KotlinSourceClass, ctx: KotlinGeneratedMemberContext): List<KotlinGeneratedMember> =
            if ("AutoCreate" in cls.annotations) listOf(KotlinGeneratedMember.property("describe", "kotlin.String")) else emptyList()
    }

    private val throwing = object : KotlinGeneratedMemberProvider {
        override val id = "test.throws"
        override fun staticMembers(cls: KotlinSourceClass, ctx: KotlinGeneratedMemberContext): List<KotlinGeneratedMember> =
            error("a broken plugin")
    }

    private val model = """
        package demo
        annotation class AutoCreate
        @AutoCreate
        class Foo(val x: Int)
    """.trimIndent() + "\n"

    private fun analyzer(): Pair<KotlinSourceAnalyzer, java.nio.file.Path> {
        val srcDir = tempProject(emptyMap())
        val a = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        a.syntheticMemberProviders = {
            BUILTIN_KOTLIN_SYNTHETIC_MEMBER_PROVIDERS + listOf(throwing, autoCreate).map { PublishedGeneratedMembers(it) }
        }
        return a to srcDir
    }

    private fun completion(body: String): List<String> {
        val (a, srcDir) = analyzer()
        return runBlocking { a.completeAtCaret(srcDir, "Foo.kt", model + "fun use() { $body }\n") }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun aPluginsStaticMemberCompletesOnTheType() {
        val items = completion("Foo.|")
        assertTrue("create" in items, "`Foo.` should offer the generated `create`; got $items")
        val cls = seen!!
        assertEquals("demo.Foo", cls.fqName)
        assertEquals(listOf("x" to "Int"), cls.constructorParameters)
        assertEquals(listOf("x"), cls.properties.map { it.first })
    }

    @Test
    fun aChainThroughGeneratedMembersIsTyped() {
        val items = completion("Foo.create(1).describe.|")
        assertTrue("length" in items, "`create` returns Foo and `describe` is a String; got $items")
    }

    @Test
    fun generatedMembersAreNotUnresolved() {
        val (a, srcDir) = analyzer()
        val code = model + "fun use() { val s: String = Foo.create(1).describe }\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Foo.kt")))
        val diags: List<Diagnostic> = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val unresolved = diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED }
        assertTrue(unresolved.isEmpty(), "generated members must resolve; got ${unresolved.map { it.message }}")
    }
}
