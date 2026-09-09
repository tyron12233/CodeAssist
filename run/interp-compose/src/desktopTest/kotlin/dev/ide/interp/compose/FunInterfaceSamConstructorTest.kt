package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression: a Kotlin `fun interface` SAM constructor — `BoundsTransform { a, b -> … }`, used pervasively in
 * Jetsnack's shared-element `boundsTransform = snackDetailBoundsTransform` — failed with
 * `no constructor(1) on androidx.compose.animation.BoundsTransform`. The interpreter treated the SAM-constructor
 * call as a real constructor; a `fun interface` has none. The fix realizes it as a proxy whose single abstract
 * method (`transform`, not `invoke`) runs the lambda.
 */
class FunInterfaceSamConstructorTest {


    private fun run(code: String, entry: String): Any? {
        val trimmed = code.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val program = dev.ide.lang.kotlin.interp.KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program[entry]!!, emptyList())
    }

    @Test
    fun boundsTransformSamConstructor() {
        val r = run(
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.BoundsTransform
            import androidx.compose.animation.core.spring
            import androidx.compose.ui.geometry.Rect
            fun box(): Any = BoundsTransform { _, _ -> spring<Rect>() }
            """,
            "box/0",
        )
        assertNotNull(r, "SAM constructor must produce an instance")
        assertTrue(
            r is androidx.compose.animation.BoundsTransform,
            "a fun-interface SAM constructor must yield a BoundsTransform proxy, got ${r::class.java.name}",
        )
    }

    private class MemDir(private val kids: List<VirtualFile>) : VirtualFile {
        override val path = "src"; override val name = "src"; override val isDirectory = true
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = kids
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
    private class MemFile(override val name: String, private val content: String) : VirtualFile {
        override val path = name; override val isDirectory = false; override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }
    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = MemFile("Main.kt", text.toString()); override val version = 1L
        override fun length() = text.length
    }
}
