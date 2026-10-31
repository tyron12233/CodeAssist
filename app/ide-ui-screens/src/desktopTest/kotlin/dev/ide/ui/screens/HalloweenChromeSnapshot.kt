package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.HomeTab
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.components.EmptyStoreHero
import dev.ide.ui.fakeAdController
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The Halloween chrome with the season forced on, under the Pumpkin theme: Home's moon and fog behind a phone
 * and a wide window, the dressed-up nav bar and sidebar, and the haunted empty states. Decoration is right or
 * wrong by eye, so the value is the PNGs; the assertion only catches a blank frame.
 */
class HalloweenChromeSnapshot {

    private val projects = listOf(
        ProjectInfo(name = "haunted-house", rootPath = "/p/haunted-house", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 3_600_000L),
        ProjectInfo(name = "pumpkin-carver", rootPath = "/p/pumpkin-carver", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 30 * 3_600_000L),
    )

    @Test
    fun phoneEmptyDark() = render("halloween-home-phone-empty-dark.png", 411, 890, dark = true) { home(emptyList()) }

    @Test
    fun phoneProjectsLight() = render("halloween-home-phone-light.png", 411, 890, dark = false) { home(projects) }

    @Test
    fun wideDark() = render("halloween-home-wide-dark.png", 1100, 720, dark = true) { home(projects) }

    @Test
    fun storeEmpty() = render("halloween-store-empty.png", 411, 520, dark = true) {
        Column(Modifier.padding(top = 24.dp)) { EmptyStoreHero(hasSubmission = false, onPublish = {}, onGuide = {}) }
    }

    @Composable
    private fun home(list: List<ProjectInfo>) {
        HomeScreen(
            tab = HomeTab.Projects,
            onSelectTab = {},
            projectsContent = {
                ProjectsHomeScreen(projects = list, onOpen = {}, onNewProject = {}, onImportProject = {})
            },
            storeContent = {},
            learnContent = {},
            challengesContent = {},
            projectCount = list.size,
            sidebarFooter = { HomeSidebarItem(CaSymbols.accountCircle, "Account") {} },
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark, accent = CaAccent.Pumpkin) {
                CompositionLocalProvider(
                    LocalSeason provides Season.Halloween,
                    LocalAds provides fakeAdController(StubBackend()),
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..30) scene.render(frame * 50_000_000L)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }
}
