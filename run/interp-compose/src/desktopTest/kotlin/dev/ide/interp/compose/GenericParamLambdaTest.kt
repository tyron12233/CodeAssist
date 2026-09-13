package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A source lambda handed to a GENERIC parameter (`rememberUpdatedState(onClick)`, `Optional.of { }`,
 * `mutableStateOf(callback)`): the JVM signature erases `T` to `Object`, which is not an interface, so it
 * cannot be the type of a lambda proxy. The value has to travel through the library as a callable and come
 * back out invokable from source.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GenericParamLambdaTest {

    @Test
    fun aLambdaStoredThroughAGenericParameterComesBackInvokable() {
        val code = """
            package demo

            fun holder(): Int {
                // `MutableList.add(T)` erases to add(Object); `listOf(vararg T)` to listOf(Object[]).
                val added = mutableListOf<() -> Int>()
                added.add({ 3 })
                val packed = listOf({ 4 })
                val first = added[0]
                val second = packed[0]
                return first() + second()
            }
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val diags = program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "must lower cleanly; diags=$diags")
        val result = Interpreter(program, ComposeDispatcher(), classes = classes).call(program.getValue("holder/0"), emptyList())
        assertEquals(7, result)
    }

    @Test
    fun rememberUpdatedStateOfACallbackRendersAndInvokes() {
        val code = """
            package demo
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.rememberUpdatedState

            @Composable
            private fun Row(label: String, onSelect: () -> String) {
                val current by rememberUpdatedState(onSelect)
                Text(label + current())
            }

            @Composable
            fun box() {
                Row("one", onSelect = { "!" })
            }
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val diags = program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "must lower cleanly; diags=$diags")
        val entry = program.getValue("box/0")
        var hard: String? = null
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entry, program, classes, emptyList(), onError = { hard = it.message }, onPartialError = { partials.add(it?.message) })
        }
        val threw = try {
            val scene = ImageComposeScene(300, 300, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            null
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return
            "${t.javaClass.simpleName}: ${t.message}"
        }
        assertTrue(threw == null, "must render; threw $threw; hard=$hard partials=${partials.filterNotNull()}")
        assertTrue(hard == null && partials.filterNotNull().isEmpty(), "no render errors; hard=$hard partials=${partials.filterNotNull()}")
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
