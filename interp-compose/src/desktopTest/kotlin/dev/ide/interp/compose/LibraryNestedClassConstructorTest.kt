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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A LIBRARY nested-class constructor call `GridCells.Fixed(2)` (the reported `LazyVerticalGrid(columns =
 * GridCells.Fixed(2))`) must lower to a CONSTRUCTOR call — not "unresolved/ambiguous call `Fixed`
 * (candidates=0, recv=…GridCells)", which blanked the preview. Lowered against REAL Compose-for-Desktop on the
 * test classpath (androidx.compose.foundation.lazy.grid.GridCells.Fixed).
 */
class LibraryNestedClassConstructorTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun gridCellsFixedLowersToAConstructorCall() {
        val code = """
            package demo
            import androidx.compose.foundation.lazy.grid.GridCells
            import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            @Preview @Composable
            fun P() { LazyVerticalGrid(columns = GridCells.Fixed(2)) {} }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = assertNotNull(program["P/0"], "the preview function must lower; keys=${program.keys}")

        var fixed: RNode.Call? = null
        entry.body.walk { if (it is RNode.Call && it.callee.displayName == "Fixed") fixed = it }
        val call = assertNotNull(fixed, "`GridCells.Fixed(2)` must lower to a Call, not Unsupported; diags=${entry.diagnostics.map { it.reason }}")
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "`GridCells.Fixed(2)` must be a CONSTRUCTOR call, was ${call.dispatch}")
        assertTrue(
            entry.diagnostics.none { "unresolved/ambiguous call `Fixed`" in it.reason },
            "no unresolved-Fixed diagnostic; got ${entry.diagnostics.map { it.reason }}",
        )
        // End-to-end: the lowered constructor callee's `$`-form owner must reflectively construct the real class.
        val built = ComposeDispatcher().dispatch(call, receiver = null, args = listOf(2))
        assertNotNull(built, "the interpreter must construct GridCells.Fixed(2)")
        assertTrue(
            built.javaClass.name.endsWith("GridCells\$Fixed"),
            "constructs androidx…GridCells\$Fixed, was ${built.javaClass.name}",
        )
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
