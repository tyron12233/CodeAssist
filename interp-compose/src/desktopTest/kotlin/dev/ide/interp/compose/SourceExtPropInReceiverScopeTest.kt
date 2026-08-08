package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Jetsnack's `HighlightSnackItem` gradient renders invisibly (no error) because
 * `Modifier.offsetGradientBackground(width = { 6 * cardWidthWithPaddingPx })` couldn't resolve — a chain of two
 * resolver gaps, both exercised here against a project source root (so the file's own symbols are indexed, as on
 * device):
 *  1. `cardWidthWithPaddingPx` is a file-level `Density` SOURCE extension property read by bare name inside a
 *     `Density.() -> Float` receiver scope — the receiver-scope path lowered it via `propertyBinding` (a facade
 *     getter that doesn't exist for a source extension) instead of a source EXTENSION call.
 *  2. its getter uses `Dp.toPx()`, a `Density` MEMBER-extension; the editor resolver surfaces that candidate with
 *     its declaring package set (not null) and doesn't import-gate it to the implicit receiver, so `chooseCallee`
 *     filtered it out and the call tied to "ambiguous (candidates=11)".
 * With both fixed, `6 * cardWidthWithPaddingPx` computes (width no longer collapses to 0 → the gradient draws).
 */
class SourceExtPropInReceiverScopeTest {
    private fun cp() = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }
    private class Disk(val p: Path) : VirtualFile {
        override val path get() = p.toString(); override val name get() = p.fileName?.toString() ?: p.toString()
        override val isDirectory get() = Files.isDirectory(p); override val exists get() = Files.exists(p)
        override val length get() = if (exists && !isDirectory) Files.size(p) else 0L
        override fun parent(): VirtualFile? = p.parent?.let { Disk(it) }
        override fun children(): List<VirtualFile> = if (isDirectory) Files.list(p).use { s -> s.map { Disk(it) as VirtualFile }.toList() } else emptyList()
        override fun contentHash() = ContentHash(if (exists && !isDirectory) Files.readString(p) else path)
        override fun readBytes() = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
        override fun readText(): CharSequence = if (exists && !isDirectory) Files.readString(p) else ""
    }
    private class Doc(val f: Path) : DocumentSnapshot { override val text: CharSequence = Files.readString(f); override val file: VirtualFile = Disk(f); override val version = 1L; override fun length() = text.length }

    private fun interpretIndexed(src: String): Any? {
        val dir = Files.createTempDirectory("srcext")
        val f = dir.resolve("Main.kt"); Files.writeString(f, src.trimIndent())
        val svc = KotlinSymbolService(sourceRoots = listOf(Disk(dir)), classpathJars = cp())
        val parsed = KotlinIncrementalParser().parseFull(Doc(f)) as KotlinParsedFile
        val prog = KotlinPreviewLowering(svc).program(parsed)
        return Interpreter(prog, ComposeDispatcher()).call(prog["box/0"] ?: error("no box/0; keys=${prog.keys}"), emptyList())
    }

    @Test
    fun sourceDensityExtPropUsingMemberExtensionResolvesInReceiverScope() {
        val r = interpretIndexed(
            """
            package demo
            import androidx.compose.ui.unit.Density
            import androidx.compose.ui.unit.dp
            private val CardW = 170.dp
            private val CardP = 16.dp
            private val Density.cardWidthWithPaddingPx: Float get() = (CardW + CardP).toPx()
            fun box(): Any = with(Density(2f)) { 6 * cardWidthWithPaddingPx }
            """,
        )
        // 6 * (170.dp + 16.dp = 186.dp; at density 2 → 372px) = 2232. Before the fix the read collapsed to 0.
        assertEquals(2232.0f, r, "the source Density extension property (whose getter uses the Dp.toPx member-extension) must resolve and compute in a receiver scope")
    }

    @Test
    fun bareMemberExtensionResolvesInAWithScope() {
        // Guard for fix #2 in isolation: a member-extension (`Dp.toPx()`) inside a `with(density)` block resolves
        // (this already worked, but the fix widens `inScope`; keep it green).
        val r = interpretIndexed(
            """
            package demo
            import androidx.compose.ui.unit.Density
            import androidx.compose.ui.unit.dp
            fun box(): Any = with(Density(2f)) { 24.dp.toPx() }
            """,
        )
        assertEquals(48.0f, r)
    }
}
