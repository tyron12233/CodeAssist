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
import dev.ide.ui.components.ClaudeAnnouncementCard
import dev.ide.ui.fakeAdController
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.screens.countdown.CountdownCardStatus
import dev.ide.ui.screens.countdown.CountdownHomeCard
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home with the one-time "Use with Claude" card in the card slot: on its own, and under the winter countdown card
 * it shares the slot with. Judged by eye; the assertion only catches a blank frame.
 */
class ClaudeHomeSnapshot {

    private val projects = listOf(
        ProjectInfo(name = "snow-globe", rootPath = "/p/snow-globe", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 3_600_000L),
        ProjectInfo(name = "cocoa-timer", rootPath = "/p/cocoa-timer", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 30 * 3_600_000L),
    )

    private val prefs = HashMap<String, String>()
    private val ledger = HolidayLedger(prefs::get, { k, v -> prefs[k] = v }, { 20261212 })

    @Test
    fun aloneDark() = render("claude-home-dark.png", dark = true) { home(withCountdown = false) }

    @Test
    fun underCountdownLight() = render("claude-home-countdown-light.png", dark = false) { home(withCountdown = true) }

    @Composable
    private fun home(withCountdown: Boolean) {
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
                            if (withCountdown) {
                                CountdownHomeCard(CountdownCardStatus.of(ledger), ledger.stars(), auroraOut = false, onOpen = {})
                            }
                            ClaudeAnnouncementCard(onSetUp = {}, onDismiss = {})
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
    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 822, height = 1780, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(StubBackend())) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
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
