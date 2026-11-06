package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of the Back / Forward arrows that lead the breadcrumb row, at a phone's width: after a Go to
 * Declaration into another file (Back lit), then after going back (Forward lit). Not assertions; PNGs for review.
 */
class NavArrowsSnapshot {
    private val disk = mapOf(
        "/p/app/src/main/kotlin/Main.kt" to "fun main() {\n    val total = sum(2, 3)\n    println(total)\n}\n",
        "/p/app/src/main/kotlin/MathUtils.kt" to "/** Adds two numbers. */\nfun sum(a: Int, b: Int): Int {\n    return a + b\n}\n",
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun navArrows() {
        val backend = object : StubBackend() {
            override fun readFile(path: String) = disk[path] ?: ""
        }
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        val main = disk.keys.first()
        val utils = disk.keys.last()
        runBlocking { state.openSuspend(main, "Main.kt") }
        state.active!!.session.setCaret(disk.getValue(main).indexOf("sum("))
        state.openAt(utils, disk.getValue(utils).indexOf("sum")) // Go to Declaration

        val scene = ImageComposeScene(width = 822, height = 420, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                val active = state.active ?: return@CodeAssistTheme
                Column(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    BreadcrumbBar(state, active, hasPreview = false)
                    CodeEditor(active.path, active.session, backend, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        var t = 0L
        fun shot(name: String) {
            repeat(30) { t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        }
        try {
            shot("nav-1-after-goto-declaration.png")
            state.navigateBack()
            shot("nav-2-after-back.png")
        } finally {
            scene.close()
        }
    }
}
