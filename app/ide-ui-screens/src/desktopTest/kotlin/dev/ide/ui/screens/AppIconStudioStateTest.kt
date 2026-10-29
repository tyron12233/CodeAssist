package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiAppIconPlan
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiAppIconState
import dev.ide.ui.backend.UiIconImportResult
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiRasterFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app-icon studio's state: choosing a new foreground changes only the foreground (the rest of the icon is
 * what the user built), a new glyph starts small enough for a circle mask, and Apply refreshes "Current".
 */
class AppIconStudioStateTest {

    private class Fake : StubBackend() {
        var launcherIcon = UiAppIconState("app", "@mipmap/ic_launcher", null, current = null)
        var applied: UiAppIconSpec? = null

        override suspend fun launcherIcon(moduleName: String?) = launcherIcon

        override suspend fun previewAppIcon(spec: UiAppIconSpec) = UiAppIconPreview(null, null, null)

        override suspend fun planAppIcon(spec: UiAppIconSpec) =
            UiAppIconPlan("/p/res", files = listOf("mipmap-anydpi-v26/ic_launcher.xml"), rasters = emptyList())

        override suspend fun applyAppIcon(spec: UiAppIconSpec, rasters: List<UiRasterFile>): UiIconImportResult {
            applied = spec
            launcherIcon = launcherIcon.copy(iconRef = "@mipmap/${spec.name}", roundIconRef = "@mipmap/${spec.name}_round")
            return UiIconImportResult(ok = true)
        }
    }

    private fun <T> withState(seed: UiIconLayer? = null, body: suspend (AppIconStudioState, Fake) -> T): T = runBlocking {
        val backend = Fake()
        val state = AppIconStudioState(backend, CoroutineScope(coroutineContext + Dispatchers.Unconfined), seed)
        body(state, backend)
    }

    @Test
    fun aSeededIconStartsSmallEnoughForACircleMaskAndKeepsItsStyle() {
        withState(seed = UiIconLayer.RepoIcon("all", "rocket", UiIconVariant("sharp", filled = true))) { state, _ ->
            val fg = state.spec.foreground as UiIconLayer.RepoIcon
            assertEquals(UiIconVariant("sharp", filled = true), fg.variant)
            assertTrue(fg.scale < 0.75f, "a square glyph at 1.0 has its corners cut by the circle mask: ${fg.scale}")
        }
    }

    @Test
    fun choosingANewIconKeepsTheRestOfTheIcon() {
        withState(seed = UiIconLayer.RepoIcon("all", "rocket")) { state, _ ->
            state.setForegroundTint(0xFFFFFFFFL)
            state.setScale(0.9f)
            state.setBackgroundGradient(0xFF4FC3F7L, 0xFF1A237EL, 135)
            state.monochromeFromForeground()

            state.setForegroundLayer(UiIconLayer.RepoIcon("all", "favorite"))

            val fg = state.spec.foreground as UiIconLayer.RepoIcon
            assertEquals("favorite", fg.name)
            assertEquals(0xFFFFFFFFL, fg.tintArgb, "the tint stays")
            assertEquals(0.9f, fg.scale, "the placement stays")
            assertEquals(UiIconLayer.Gradient(0xFF4FC3F7L, 0xFF1A237EL, 135), state.spec.background, "the background stays")
            assertEquals("favorite", (state.spec.monochrome as UiIconLayer.RepoIcon).name, "the themed layer follows")
        }
    }

    @Test
    fun aDrawableBackgroundFillsTheWholeBox() {
        withState { state, _ ->
            state.setBackgroundLayer(UiIconLayer.Resource("/p/res/drawable/bg.xml"))
            assertEquals(1.5f, (state.spec.background as UiIconLayer.Resource).scale)
        }
    }

    @Test
    fun applyingRefreshesTheCurrentIconAndListsTheWrittenFiles() {
        withState(seed = UiIconLayer.RepoIcon("all", "rocket")) { state, backend ->
            delay(300) // the debounced preview
            state.apply()
            assertTrue(state.applied)
            assertEquals("@mipmap/ic_launcher_round", state.current?.roundIconRef, "Current reflects what was written")
            assertTrue(backend.applied != null)
            assertEquals(listOf("/p/res/mipmap-anydpi-v26/ic_launcher.xml"), state.written)
        }
    }
}
