package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
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
 * A PLAIN `Card { … }` (no onClick) that crashes when tapped means the preview built the CLICKABLE `Card(onClick,
 * …)` overload with a null onClick (a spuriously-clickable node → `Function0.invoke()` NPE in `ClickableNode`).
 * Against real Compose-for-Desktop, `Card { Text("") }` must resolve to the NON-clickable 6-param overload — the
 * clickable overload's required-but-unbound `onClick` makes it inapplicable (Kotlin's overload rule).
 */
class PlainCardOverloadTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun plainCardResolvesToTheNonClickableOverload() {
        val code = """
            package demo
            import androidx.compose.material3.Card
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            @Preview @Composable
            fun P() { Card { Text("") } }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program["P/0"] ?: error("P not lowered; keys=${program.keys}")
        var card: ResolvedCallable.Library? = null
        entry.body.walk {
            if (it is RNode.Call && it.callee.displayName == "Card") card = it.callee as? ResolvedCallable.Library
        }
        val c = assertNotNull(card, "the Card call should lower to a library callable")
        assertTrue(
            c.paramNames.firstOrNull() != "onClick" &&
                c.paramTypes.firstOrNull()?.qualifiedName?.startsWith("kotlin.Function") != true,
            "a plain `Card { }` must NOT pick the clickable `Card(onClick, …)` overload (would build a clickable " +
                "node with null onClick → tap NPE); got paramNames=${c.paramNames}",
        )
        assertTrue(c.paramNames.contains("content"), "the chosen overload must still bind the content lambda; got ${c.paramNames}")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L; override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null; override fun children() = emptyList<VirtualFile>()
        override fun contentHash() = ContentHash(""); override fun readBytes() = ByteArray(0); override fun readText() = ""
    }
}
