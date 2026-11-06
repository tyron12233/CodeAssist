package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiUsageHighlight
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of highlight-usages-at-caret: the textual fallback (every `x` in the file) next to the
 * resolved answer (only this function's `x`, its declaration and assignment in the write tint), and a property
 * with compound assignments and an increment. Not assertions; PNGs for review.
 *
 * The backend answers what the Kotlin analyzer answers for this source (pinned by `KotlinUsageHighlightTest`,
 * which runs the real analyzer over the same code); `:ide-ui-editor` has no language backend of its own.
 */
class UsageHighlightSnapshot {
    private val sample = """
        class Counter {
            var count = 0

            fun bump() {
                count += 1
                count++
                println(count)
            }
        }

        fun one() {
            var x = 1
            x = x + 2
            println(x)
        }

        fun two() {
            val x = "other"
            println(x)
        }
    """.trimIndent() + "\n"

    private fun nth(needle: String, n: Int): Int {
        var at = -1
        repeat(n + 1) { at = sample.indexOf(needle, at + 1) }
        return at
    }

    private fun name(needle: String, n: Int, nameLen: Int, write: Boolean): UiUsageHighlight {
        val at = nth(needle, n)
        return UiUsageHighlight(at, at + nameLen, write)
    }

    /** one()'s `x`: `var x` (declaration), `x =` (write), `x + 2`, `println(x)` (reads). */
    private val localX by lazy {
        listOf(
            name("x = 1", 0, 1, write = true),
            name("x = x", 0, 1, write = true),
            name("x + 2", 0, 1, write = false),
            name("println(x)", 0, "println(x)".length, false).let { UiUsageHighlight(it.endOffset - 2, it.endOffset - 1, false) },
        )
    }

    /** Counter.count: the declaration, `+=`, `++` (writes) and the read in `println`. */
    private val count by lazy {
        listOf(
            name("count = 0", 0, 5, write = true),
            name("count += 1", 0, 5, write = true),
            name("count++", 0, 5, write = true),
            name("(count)", 0, 7, false).let { UiUsageHighlight(it.startOffset + 1, it.startOffset + 6, false) },
        )
    }

    private fun backend(resolved: Boolean): IdeBackend = object : IdeBackend by PreviewBackend {
        override val editor: EditorService = object : EditorService by PreviewBackend {
            override suspend fun usageHighlights(path: String, text: String, offset: Int): List<UiUsageHighlight>? {
                if (!resolved) return null // the textual fallback: a backend without the query
                return listOf(localX, count).firstOrNull { u -> u.any { offset >= it.startOffset && offset < it.endOffset } }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun shot(file: String, caret: Int, resolved: Boolean, dark: Boolean = true) {
        val session = EditorSession(sample, languageFor("Counter.kt"), TextRange(caret))
        val scene = ImageComposeScene(width = 1100, height = 760, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("Counter.kt", session, backend(resolved), Modifier.fillMaxSize())
                }
            }
        }
        try {
            var t = 0L
            val end = System.currentTimeMillis() + 900 // past the 300ms settle and the backend round trip
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$file").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Test
    fun usageHighlights() {
        val onX = nth("x = x", 0)
        shot("usages-1-before-textual.png", onX, resolved = false)
        shot("usages-2-after-resolved-local.png", onX, resolved = true)
        shot("usages-3-after-property-writes.png", nth("count++", 0), resolved = true)
        shot("usages-4-after-light.png", nth("count++", 0), resolved = true, dark = false)
    }
}
