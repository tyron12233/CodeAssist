package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.RawCallable
import dev.ide.lang.kotlin.symbols.RawClass
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.kotlin.symbols.TypeAliasDecl
import dev.ide.lang.kotlin.symbols.sameDeclarations
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [sameDeclarations] decides whether a focal-file edit may keep the symbol service's source memos, so a wrong
 * "same" serves a stale resolution. These pin both directions, and that the comparison still covers every
 * field of the declaration classes.
 */
class SourceDeclarationsEqualityTest {

    private val dir = Files.createTempDirectory("same-decls")
    private val vf = DiskFile(dir.resolve("Main.kt"))

    private fun extract(text: String) = SourceIndexBuilder.extract(vf, text)!!

    private val base = """
        package p

        class Foo(val x: Int) {
            fun work(n: Int) {
                println(n)
            }
        }

        fun untyped() = 42

        fun helper(a: String) {
            val s = a.length
        }
    """.trimIndent()

    @Test
    fun anEditInsideABlockBodyChangesNoDeclaration() {
        val edited = base.replace("val s = a.length", "val s = a.length + 1\n    println(s)")
        assertTrue(sameDeclarations(extract(base), extract(edited)))
    }

    @Test
    fun aChangedSignatureNameOrExpressionBodyIsADifferentDeclaration() {
        val a = extract(base)
        assertFalse(sameDeclarations(a, extract(base.replace("fun helper(a: String)", "fun helper(a: Int)"))), "parameter type")
        assertFalse(sameDeclarations(a, extract(base.replace("class Foo", "class Bar"))), "class name")
        assertFalse(sameDeclarations(a, extract(base.replace("fun untyped() = 42", "fun untyped() = \"s\""))), "expression body types the function")
        assertFalse(sameDeclarations(a, extract(base.replace("package p\n", "package p\n\nimport kotlin.math.max\n"))), "imports")
    }

    @Test
    fun aLocalClassInsideABodyIsADeclaration() {
        val withLocal = base.replace("val s = a.length", "class Local { fun m() = 1 }\n    val s = a.length")
        assertFalse(sameDeclarations(extract(base), extract(withLocal)))
    }

    @Test
    fun theComparisonCoversEveryDeclarationField() {
        // sameDeclarations names each field explicitly. A field added to one of these classes without adding it
        // there would silently count two different declarations as the same; update both, then these counts.
        assertEquals(25, instanceFields(RawCallable::class.java), "RawCallable gained or lost a field: update sameDeclarations")
        assertEquals(25, instanceFields(RawClass::class.java), "RawClass gained or lost a field: update sameDeclarations")
        assertEquals(4, instanceFields(TypeAliasDecl::class.java), "TypeAliasDecl gained or lost a field: update sameDeclarations")
    }

    @Test
    fun aDeclarationChangeAfterABodyEditIsStillSeen() {
        Files.writeString(dir.resolve("Main.kt"), base)
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = emptyList())
        val ctx = FileContext(vf.path, "p", emptyList())
        service.syncFocal(vf.path, base.hashCode()) { extract(base) }
        assertEquals("p.Foo", service.resolveTypeName("Foo", ctx))

        // A body-only edit keeps the name memo; the answer must not change.
        val bodyEdit = base.replace("println(n)", "println(n + 1)")
        service.syncFocal(vf.path, bodyEdit.hashCode()) { extract(bodyEdit) }
        assertEquals("p.Foo", service.resolveTypeName("Foo", ctx))

        // A rename must drop it.
        val renamed = bodyEdit.replace("class Foo", "class Bar")
        service.syncFocal(vf.path, renamed.hashCode()) { extract(renamed) }
        assertEquals("p.Bar", service.resolveTypeName("Bar", ctx))
        assertTrue(service.resolveTypeName("Foo", ctx) != "p.Foo", "the old class must no longer resolve")
    }

    private fun instanceFields(c: Class<*>): Int = c.declaredFields.count { !Modifier.isStatic(it.modifiers) }
}
