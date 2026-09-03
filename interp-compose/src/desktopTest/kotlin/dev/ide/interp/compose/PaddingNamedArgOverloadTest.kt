package dev.ide.interp.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.interp.ReflectiveDispatcher
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `Modifier.padding(start = 16.dp)` padded EVERY direction in the preview: named-argument reordering trims the
 * trailing omitted slots, so the four-way overload's call arrived as a single `Dp` — indistinguishable from
 * `Modifier.padding(16.dp)`, and the exact-arity reflective lookup bound the one-parameter `padding(all)`
 * sibling. Static resolution had picked the right overload all along, so the fix is to carry its declared
 * parameter count into dispatch (as the constructor and `@Composable` paths already do) and let it pin the
 * `$default` synthetic. Asserted against the real Compose `PaddingElement` the call produces.
 */
class PaddingNamedArgOverloadTest {

    @Test
    fun aLeadingNamedArgumentKeepsTheOverloadStaticResolutionPicked() {
        assertEquals(
            listOf(16f, 0f, 0f, 0f),
            paddingOf("Modifier.padding(start = 16.dp)"),
            "`padding(start = 16.dp)` must pad only the start — the four-way overload with three defaults",
        )
    }

    @Test
    fun aNamedArgumentInTheMiddleStillBindsByPosition() {
        assertEquals(
            listOf(0f, 0f, 12f, 0f),
            paddingOf("Modifier.padding(end = 12.dp)"),
            "`padding(end = 12.dp)` must pad only the end",
        )
    }

    @Test
    fun theTwoWayOverloadIsReachableByItsOwnLeadingName() {
        // `horizontal` is also the FIRST parameter of its overload, so it trims to the same single-`Dp` shape.
        assertEquals(
            listOf(8f, 0f, 8f, 0f),
            paddingOf("Modifier.padding(horizontal = 8.dp)"),
            "`padding(horizontal = 8.dp)` must pad start and end only",
        )
    }

    @Test
    fun aPositionalCallStillReachesTheAllOverload() {
        assertEquals(
            listOf(4f, 4f, 4f, 4f),
            paddingOf("Modifier.padding(4.dp)"),
            "a positional `padding(4.dp)` is still the one-parameter `all` overload",
        )
    }

    /** The `start`/`top`/`end`/`bottom` of the `PaddingElement` that [expr] builds, in dp — lowered by the real
     *  preview pipeline and dispatched by the real reflective dispatcher. */
    private fun paddingOf(expr: String): List<Float> {
        val code = """
            package demo
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.padding
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.tooling.preview.Preview
            @Preview @Composable
            fun P() { Box(modifier = $expr) { } }
        """.trimIndent()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program["P/0"] ?: error("P not lowered; keys=${program.keys}")
        var call: RNode.Call? = null
        entry.body.walk { if (it is RNode.Call && it.callee.displayName == "padding") call = it }
        val padding = assertNotNull(call, "`$expr` should lower to a padding call")
        val dp = Regex("""(\d+)\.dp""").find(expr)!!.groupValues[1].toFloat()
        val modifier = ReflectiveDispatcher().dispatch(padding, Modifier, listOf(Dp(dp))) as Modifier
        val element = assertNotNull(
            modifier.foldIn<Any?>(null) { acc, e -> acc ?: e.takeIf { it.javaClass.simpleName == "PaddingElement" } },
            "the call should build a PaddingElement; got $modifier",
        )
        return listOf("start", "top", "end", "bottom").map { name ->
            element.javaClass.getDeclaredField(name).also { it.isAccessible = true }.getFloat(element)
        }
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
