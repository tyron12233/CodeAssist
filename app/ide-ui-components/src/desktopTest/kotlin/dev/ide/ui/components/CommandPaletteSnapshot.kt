package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the command palette off-screen, empty and mid-query, so its grouping (one heading per section rather
 * than a label on every row), icons and match highlighting can be eyeballed, and so it is guarded against a
 * runtime layout error.
 */
class CommandPaletteSnapshot {

    private class FakeBackend : StubBackend() {
        override fun actionsFor(ctx: UiActionContext): List<UiActionItem> = listOf(
            UiActionItem("ai.ask", "Ask AI", "ai"),
            UiActionItem("editor.comment", "Comment / Uncomment Lines", "code"),
            UiActionItem("build.run", "Run Build", "run"),
            UiActionItem("build.stop", "Stop Build", "stop"),
            UiActionItem("index.reindex", "Re-index Project", "reindex"),
        )
        override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = listOf(
            SymbolHit("MainActivity", "com.example.app", "class", "/p/app/src/main/java/MainActivity.java", 0),
            SymbolHit("onCreate", "MainActivity", "method", "/p/app/src/main/java/MainActivity.java", 10),
        )
    }

    private class Host(override val backend: IdeBackend) : UiActionHost {
        override fun navigate(destination: String) {}
        override fun toggleTheme() {}
        override fun openFile(path: String, offset: Int) {}
    }

    private val files = listOf("MainActivity.java", "activity_main.xml", "build.gradle.kts", "AndroidManifest.xml").map {
        TreeNode(it, it, NodeKind.File, "/p/app/src/main/$it", iconId = when {
            it.endsWith(".java") -> "java"
            it.endsWith(".kts") -> "kotlin"
            it == "AndroidManifest.xml" -> "manifest"
            else -> "xml"
        })
    }

    @Test
    fun empty() {
        render("palette-empty.png", query = null)
    }

    @Test
    fun query() {
        render("palette-query.png", query = "main")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, query: String?) {
        val backend = FakeBackend()
        val scene = ImageComposeScene(width = 1600, height = 1400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                    CommandPalette(files, backend, Host(backend), onOpenFile = {}, onOpenAt = { _, _ -> }, onClose = {}, initialQuery = query.orEmpty())
                }
            }
        }
        try {
            scene.render(); scene.render(50_000_000L)
            scene.render(200_000_000L); scene.render(400_000_000L)
            val png = scene.render(600_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
    }
}

