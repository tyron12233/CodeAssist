package dev.ide.interp.compose

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
 * The interpreter-level constructs of a reported RGB-picker `@Preview` screen, exercised against the real
 * Compose classpath. The full screen only RENDERS on device (it reads `LocalContext.current` and
 * `stringResource(R.string.*)`, and its material3 widgets need the host's `LocalDensity`/font resolver), so
 * these cover the pieces that resolve and interpret headlessly — the parts an interpreter gap would break.
 */
class CreateRgbScreenProbeTest {


    private fun run(code: String, key: String, args: List<Any?> = emptyList()): Any? {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val fn = program[key] ?: error("no lowered function $key; have ${program.keys}")
        return Interpreter(program, ComposeDispatcher()).call(fn, args)
    }

    /**
     * Every `OutlinedTextField` uses `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)`: a
     * library constructor with a single NAMED, non-first argument (a value class) and every other parameter
     * defaulted. The named argument must bind to `keyboardType` (not positionally to the leading param).
     */
    @Test
    fun keyboardOptionsBindsTheNamedValueClassArgument() {
        val code = """
            package demo
            import androidx.compose.foundation.text.KeyboardOptions
            import androidx.compose.ui.text.input.KeyboardType
            fun box(): Any = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        """.trimIndent()
        val r = run(code, "box/0")
        assertTrue(
            r != null && r.javaClass.name == "androidx.compose.foundation.text.KeyboardOptions" &&
                r.toString().contains("keyboardType=Ascii"),
            "KeyboardOptions(keyboardType = KeyboardType.Ascii) must bind Ascii to keyboardType; was $r",
        )
    }

    /**
     * The bottom `Box`: `var c by remember { mutableStateOf(Color.White) }` then `Modifier.background(c)`. The
     * `Color` value class is read back BOXED through `State.value` and handed to `background`'s defaulted
     * extension synthetic — it must unbox at the reflective boundary and build a real background element.
     */
    @Test
    fun stateColorFlowsIntoBackground() {
        val code = """
            package demo
            import androidx.compose.foundation.background
            import androidx.compose.foundation.layout.size
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.unit.dp
            fun box(): Any {
                val c = mutableStateOf(Color.White)
                return Modifier.size(100.dp).background(c.value)
            }
        """.trimIndent()
        val r = run(code, "box/0")
        assertTrue(
            r != null && r.toString().contains("BackgroundElement"),
            "Modifier.background(<boxed state Color>) must build a real BackgroundElement; was $r",
        )
    }

    /** The `Column`'s `verticalArrangement = Arrangement.spacedBy(16.dp)` — an object member taking a `Dp`. */
    @Test
    fun arrangementSpacedByResolves() {
        val code = """
            package demo
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.ui.unit.dp
            fun box(): Any = Arrangement.spacedBy(16.dp)
        """.trimIndent()
        val r = run(code, "box/0")
        assertTrue(
            r != null && r.javaClass.name.contains("Arrangement"),
            "Arrangement.spacedBy(16.dp) must resolve to an Arrangement; was $r",
        )
    }

    /**
     * The interactive `onClick` validator: `input.filter { it in '0'..'9' || … }.length == 2`. The preview is
     * interactive, so tapping the button runs this — `String.filter` over a `CharRange` membership test.
     */
    @Test
    fun hexInputValidatorRunsCharRangeMembership() {
        val code = """
            package demo
            fun check(input: String): Boolean {
                return input.filter {
                    it in '0'..'9' ||
                    it in 'A'..'F' ||
                    it in 'a'..'f'
                }.length == 2
            }
        """.trimIndent()
        for ((input, expected) in listOf("AB" to true, "0f" to true, "A" to false, "ABC" to false, "XY" to false)) {
            assertEquals(expected, run(code, "check/1", listOf(input)), "check(\"$input\")")
        }
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
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
