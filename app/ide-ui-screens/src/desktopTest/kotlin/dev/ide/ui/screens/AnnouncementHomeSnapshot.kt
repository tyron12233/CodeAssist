package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAccent
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementButton
import dev.ide.ui.backend.UiAnnouncementButtonStyle
import dev.ide.ui.backend.UiAnnouncementStyle
import dev.ide.ui.components.AnnouncementCard
import dev.ide.ui.components.ClaudeAnnouncementCard
import dev.ide.ui.fakeAdController
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home with a server announcement in the card slot, under the "Use with Claude" card, on a 360 dp phone and on a
 * wide window. Judged by eye; the assertion only catches a blank frame.
 */
class AnnouncementHomeSnapshot {

    private val projects = listOf(
        ProjectInfo(name = "snow-globe", rootPath = "/p/snow-globe", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 3_600_000L),
    )

    private val served = UiAnnouncement(
        id = "plugins", revision = 1, priority = 1, dismissible = true, endsAtMillis = null,
        style = UiAnnouncementStyle.CARD, accent = UiAnnouncementAccent.TEAL, icon = "extension", action = null,
        blocks = listOf(
            UiAnnouncementBlock.Title("Plugins can add languages now"),
            UiAnnouncementBlock.Text("Install C# or Go support from the Plugins screen."),
            UiAnnouncementBlock.Buttons(listOf(UiAnnouncementButton("Get plugins", UiAnnouncementButtonStyle.PRIMARY, UiAnnouncementAction.Screen("plugins")))),
        ),
    )

    @Test
    fun phoneLight() = render("announce-home-phone-light.png", 360, 890, dark = false)

    @Test
    fun wideDark() = render("announce-home-wide-dark.png", 1200, 800, dark = true)

    @Composable
    private fun home() {
        HomeScreen(
            tab = HomeTab.Projects,
            onSelectTab = {},
            projectsContent = {
                ProjectsHomeScreen(
                    projects = projects,
                    onOpen = {},
                    onNewProject = {},
                    onImportProject = {},
                    seasonalCard = { m ->
                        Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ClaudeAnnouncementCard(onSetUp = {}, onDismiss = {})
                            AnnouncementCard(served, onAction = {}, onDismiss = {})
                        }
                    },
                )
            },
            storeContent = {},
            learnContent = {},
            challengesContent = {},
            projectCount = projects.size,
            sidebarFooter = { HomeSidebarItem(CaSymbols.accountCircle, "Account") {} },
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(StubBackend())) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { home() }
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..30) scene.render(frame * 50_000_000L)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(OUT, name).writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT = File(System.getenv("SNAPSHOT_DIR") ?: File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").path).apply { mkdirs() }
    }
}
