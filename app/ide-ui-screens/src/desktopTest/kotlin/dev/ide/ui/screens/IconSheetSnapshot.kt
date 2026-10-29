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
import dev.ide.ui.backend.UiAppIconPlan
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiAppIconState
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiGradient
import dev.ide.ui.backend.UiIconArtwork
import dev.ide.ui.backend.UiIconEntry
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiResourceConfig
import dev.ide.ui.backend.UiResourceIcon
import dev.ide.ui.backend.UiVectorGroup
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
            UiIconArtwork(glyph())

        override suspend fun projectIcons(moduleName: String?) = listOf(
            UiResourceIcon(
                "app", "drawable", "ic_logo",
                listOf(UiResourceConfig("", "/p/app/src/main/res/drawable/ic_logo.xml", isRaster = false)),
            ),
            UiResourceIcon(
                "app", "drawable", "ic_cart",
                listOf(UiResourceConfig("", "/p/app/src/main/res/drawable/ic_cart.xml", isRaster = false)),
            ),
        )

        override suspend fun resourceArtwork(path: String) = UiIconArtwork(glyph())

        override suspend fun iconUsages(resType: String, name: String) = listOf(
            UiNavTarget("/p/a.xml", 0, "a.xml:3", "usage"),
            UiNavTarget("/p/B.kt", 0, "B.kt:12", "usage"),
            UiNavTarget("/p/C.kt", 0, "C.kt:40", "usage"),
        )

        override suspend fun launcherIcon(moduleName: String?) =
            UiAppIconState("app", "@mipmap/ic_launcher", "@mipmap/ic_launcher_round", current = glyph())

        override suspend fun previewAppIcon(spec: UiAppIconSpec) = UiAppIconPreview(
            background = when (val bg = spec.background) {
                is UiIconLayer.Color -> UiDrawable.SolidColor(bg.argb)
                is UiIconLayer.Gradient -> UiDrawable.Shape(
                    "rectangle", null, UiGradient("linear", bg.startArgb, null, bg.endArgb, bg.angle, .5f, .5f, .5f),
                    null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 108f, 108f, 0f, 0f,
                )
                else -> null
            },
            foreground = UiDrawable.Vector(
                widthDp = 108f, heightDp = 108f, viewportWidth = 108f, viewportHeight = 108f, rootAlpha = 1f,
                nodes = listOf(
                    UiVectorGroup(
                        children = listOf(UiVectorPath(STAR, 0xFF0B8043L, null, 0f, 1f, 1f)),
                        scaleX = 3f, scaleY = 3f, translateX = 18f, translateY = 18f,
                    ),
                ),
            ),
            monochrome = null,
        )

        override suspend fun planAppIcon(spec: UiAppIconSpec) = UiAppIconPlan(
            resDirPath = "/p/app/src/main/res",
            files = listOf(
                "values/ic_launcher_background.xml", "drawable/ic_launcher_foreground.xml",
                "mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml",
                "mipmap-hdpi/ic_launcher.png", "../ic_launcher-playstore.png",
            ),
            rasters = emptyList(),
            replacing = listOf("mipmap-anydpi-v26/ic_launcher.xml"),
            manifestChange = "android:icon=@mipmap/ic_launcher",
        )

        private fun glyph() = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
            nodes = listOf(UiVectorPath(STAR, 0xFF000000L, null, 0f, 1f, 1f)),
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
                            onUseAsAppIcon = {},
                        )
                    }
                }
            }
        }
    }

    /** The browser with its style toolbar and a batch of two ticked icons, and a project icon's sheet. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderBrowserBatchAndProjectSheet() {
        snapshot("icon-browser-batch-phone.png", 720, 1520) {
            val state = IconManagerState(Fake(), CoroutineScope(Dispatchers.Unconfined), null)
            state.updateQuery("")
            state.toggleChecked(IconSelection.FromRepo(state.results[0]))
            state.toggleChecked(IconSelection.FromRepo(state.results[2]))
            Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                IconBrowser(state, onClick = {}, modifier = Modifier.weight(1f))
                BatchSheet(state, 420.dp)
            }
        }
        snapshot("icon-project-sheet-phone.png", 720, 1520) {
            val state = IconManagerState(Fake(), CoroutineScope(Dispatchers.Unconfined), "/p/app/src/main/res")
            val icon = state.projectIcons.first()
            val selection = IconSelection.FromProject(icon, icon.configurations.first())
            state.select(selection)
            Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                IconBrowser(
                    state, onClick = {}, modifier = Modifier.weight(1f),
                    isSelected = { it == selection },
                )
                DetailSheet(
                    state = state,
                    selection = selection,
                    insertionTarget = UiInsertionTarget("Screen.kt", composeContext = true),
                    maxHeight = 460.dp,
                    onInsert = {},
                    onCopy = {},
                    onUseAsAppIcon = {},
                    onOpenFile = { _, _ -> },
                )
            }
        }
    }

    /** The studio with its preview pinned on a phone and beside the controls on a wide window. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderStudio() {
        val seed = UiIconLayer.RepoIcon("bundled", "star")
        snapshot("app-icon-studio-phone.png", 720, 1600) {
            AppIconStudioScreen(Fake(), onBack = {}, seed = seed, onOpenFile = {}, onRunApp = {})
        }
        snapshot("app-icon-studio-wide.png", 2000, 1400) {
            AppIconStudioScreen(Fake(), onBack = {}, seed = seed, onOpenFile = {}, onRunApp = {})
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
        /** A five-point star in a 24-unit box, so the renders show something icon-shaped. */
        const val STAR = "M12,2l3.09,6.26L22,9.27l-5,4.87 1.18,6.88L12,17.77l-6.18,3.25L7,14.14 2,9.27l6.91,-1.01L12,2z"

        /** Under the module's build dir by default; point `-Dicon.snapshot.dir=…` elsewhere to inspect them. */
        val OUT_DIR: String = System.getProperty("icon.snapshot.dir") ?: "build/reports/icon-snapshots"
    }
}
