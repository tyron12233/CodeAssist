package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `MutableList.addAll/removeAll/retainAll(collection)` must lower to a MEMBER call, not tie out to
 * "unresolved/ambiguous call". These three collection mutators exist as BOTH a `MutableCollection` member
 * (`…(Collection<E>)`, from the `.kotlin_builtins` shape) AND a `kotlin.collections.CollectionsKt` extension
 * (`…(Iterable<T>)`); a `List` argument binds to both, so the preview lowerer's overload picker used to tie out
 * and blank the preview with the reported error. Kotlin resolves the member (an applicable member shadows a
 * same-named extension); `chooseCallee` now mirrors that. Lowered against the real Kotlin stdlib on the test
 * classpath.
 */
class MutableListMemberExtensionOverloadTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun addAllRemoveAllRetainAllResolveToTheMember() {
        val code = """
            package demo
            fun f() {
                val xs = mutableListOf("Bread", "Butter", "Milk")
                xs.addAll(mutableListOf("Jam", "Honey"))
                xs.retainAll(listOf("Butter", "Milk"))
                xs.removeAll(listOf("Milk"))
            }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val entry = assertNotNull(KotlinPreviewLowering(service).program(parsed)["f/0"], "f must lower")

        for (name in listOf("addAll", "retainAll", "removeAll")) {
            var call: RNode.Call? = null
            entry.body.walk { if (it is RNode.Call && it.callee.displayName == name) call = it }
            val c = assertNotNull(
                call,
                "`$name` must lower to a Call, not Unsupported; diags=${entry.diagnostics.map { it.reason }}",
            )
            assertTrue(c.dispatch == DispatchKind.MEMBER, "`$name` must be a MEMBER call, was ${c.dispatch}")
        }
        assertTrue(
            entry.diagnostics.none { "unresolved/ambiguous call" in it.reason },
            "no unresolved/ambiguous diagnostic; got ${entry.diagnostics.map { it.reason }}",
        )
    }

    @Test
    fun addAllDispatchesToTheRealCollectionMember() {
        val code = """
            package demo
            fun f() {
                val xs = mutableListOf("Bread")
                xs.addAll(mutableListOf("Jam", "Honey"))
            }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val entry = assertNotNull(KotlinPreviewLowering(service).program(parsed)["f/0"], "f must lower")
        var call: RNode.Call? = null
        entry.body.walk { if (it is RNode.Call && it.callee.displayName == "addAll") call = it }
        val c = assertNotNull(call, "`addAll` must lower to a Call")

        // End-to-end: a MEMBER addAll on a real ArrayList (what `mutableListOf` produces at runtime) mutates it
        // via reflective member dispatch.
        val recv = arrayListOf("Bread")
        val ok = ComposeDispatcher().dispatch(c, receiver = recv, args = listOf(listOf("Jam", "Honey")))
        assertEquals(true, ok, "addAll returns true")
        assertEquals(listOf("Bread", "Jam", "Honey"), recv)
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
