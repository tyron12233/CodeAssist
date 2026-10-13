package dev.ide.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the empty editor's start page off-screen, with recent files, at a desktop editor and a phone. */
class EmptyEditorSnapshot {

    private val recent = listOf(
        RecentFileEntry("/p/app/src/main/java/MainActivity.java", "MainActivity.java", "app/src/main/java/com/example"),
        RecentFileEntry("/p/app/src/main/res/layout/activity_main.xml", "activity_main.xml", "app/src/main/res/layout"),
        RecentFileEntry("/p/app/build.gradle.kts", "build.gradle.kts", "app"),
    )

    @Test
    fun desktop() {
        render("empty-editor-desktop.png", 1900, 1400)
    }

    @Test
    fun phone() {
        render("empty-editor-phone.png", 822, 1500)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, width: Int, height: Int) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) { NoOpenFilesView(Modifier.fillMaxSize(), recentFiles = recent) }
        }
        try {
            scene.render(); scene.render(50_000_000L)
            val png = scene.render(300_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
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
