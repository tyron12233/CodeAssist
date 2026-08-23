package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconArtwork
import dev.ide.ui.backend.UiIconEntry
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiVectorPath
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of the Icon Manager's detail sheet at phone width, where its controls are tightest: the
 * colour row and the style row have to scroll rather than clip, and the action buttons have to keep their
 * labels instead of being clamped by the wrapping row. Not an assertion, the PNGs are for eyeballing.
 */
class IconSheetSnapshot {

    private class Fake : StubBackend() {
        override fun iconRepositories() = listOf(
            UiIconRepo("bundled", "Material Symbols", "Apache-2.0", null, false, true, 300),
        )

        override fun importTargets() = listOf(
            UiIconTarget("app", "main", "/p/app/src/main/res", isDefault = true),
            UiIconTarget("app", "debug", "/p/app/src/debug/res"),
        )

        override suspend fun searchIcons(repoId: String, query: String, limit: Int) = listOf(
            entry("shopping_cart", "Shopping cart"),
            entry("home", "Home"),
            entry("settings", "Settings"),
        )

        override suspend fun iconArtwork(repoId: String, name: String, variant: UiIconVariant) =
            UiIconArtwork(
                UiDrawable.Vector(
                    widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
                    nodes = listOf(UiVectorPath("M4,4h16v16H4z", 0xFF000000L, null, 0f, 1f, 1f)),
                ),
            )

        private fun entry(name: String, display: String) = UiIconEntry(
            repoId = "bundled",
            name = name,
            displayName = display,
            styles = listOf("outlined", "rounded", "sharp"),
            supportsFill = true,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderDetailSheet() {
        // A narrow phone (360dp) is where the colour row and the action buttons are tightest.
        for ((label, widthDp) in listOf("phone" to 360, "tablet" to 700)) {
            snapshot("icon-sheet-$label.png", widthDp * 2, 1120) {
                val backend = Fake()
                val state = IconManagerState(backend, CoroutineScope(Dispatchers.Unconfined), null)
                val selection = IconSelection.FromRepo(state.results.first())
                state.select(selection)
                state.ensureRepoArtwork("bundled", selection.entry.name)

                Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f))
                        DetailSheet(
                            state = state,
                            selection = selection,
                            insertionTarget = UiInsertionTarget("Screen.kt", composeContext = true),
                            maxHeight = 460.dp,
                            onInsert = {},
                            onCopy = {},
                            onOpenAppIconStudio = { _, _ -> },
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render()
            var t = 0L
            repeat(8) { t += 32_000_000L; scene.render(t) }
            val png = scene.render(t + 200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        /** Under the module's build dir by default; point `-Dicon.snapshot.dir=…` elsewhere to inspect them. */
        val OUT_DIR: String = System.getProperty("icon.snapshot.dir") ?: "build/reports/icon-snapshots"
    }
}
