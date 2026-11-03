package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The Bookmarks & TODO panel with one open bookmark and two TODO comments. A PNG for review. */
@OptIn(ExperimentalComposeUiApi::class)
class BookmarksTodoSnapshot {
    @Test
    fun panel() {
        val text = "fun main() {\n    val total = 10\n    // TODO: read it from the config\n    println(total) // FIXME off by one\n}\n"
        val backend = object : StubBackend() {
            override fun readFile(path: String) = text
            override suspend fun findInFiles(query: String, options: UiSearchOptions, limit: Int) = listOf(
                UiTextMatch("/p/Main.kt", "Main.kt", 3, 5, "    // TODO: read it from the config", 7, 11, text.indexOf("TODO")),
                UiTextMatch("/p/Main.kt", "Main.kt", 4, 20, "    println(total) // FIXME off by one", 23, 28, text.indexOf("FIXME")),
            )
        }
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking { state.openSuspend("/p/Main.kt", "Main.kt") }
        state.bookmarks["/p/Main.kt"] = setOf(1)
        val scene = ImageComposeScene(width = 640, height = 700, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                BookmarksTodoPanel(state, onNavigated = {}, modifier = Modifier.fillMaxSize().background(Ide.colors.editorBg))
            }
        }
        try {
            var t = 0L
            repeat(20) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/bookmarks-todo.png").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }
}
