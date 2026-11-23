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
import dev.ide.ui.components.ChristmasCard
import dev.ide.ui.components.SeasonalCardMode
import dev.ide.ui.fakeAdController
import dev.ide.ui.holiday.HolidayActivity
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.screens.advent.AdventCardStatus
import dev.ide.ui.screens.advent.AdventHomeCard
import dev.ide.ui.screens.advent.AdventScreen
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import dev.ide.ui.theme.colors.ColorSchemeStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The Christmas season with the season forced on, under the Evergreen theme: Home with the dress-up and Advent
 * cards, the Advent calendar on December 12 with some doors open and ornaments hung, and a couple of door
 * sheets. Judged by eye; the assertion only catches a blank frame.
 */
class ChristmasHomeSnapshot {

    private val projects = listOf(
        ProjectInfo(name = "snow-globe", rootPath = "/p/snow-globe", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 3_600_000L),
        ProjectInfo(name = "gift-list", rootPath = "/p/gift-list", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 30 * 3_600_000L),
    )

    private val prefs = HashMap<String, String>()
    private val ledger = HolidayLedger(prefs::get, { k, v -> prefs[k] = v }, { 20261212 }).also { l ->
        var day = 20261201
        for (d in listOf(1, 2, 3, 4, 5, 6, 8, 9)) {
            l.open(d, day)
            l.record(HolidayActivity.Build, day)
            if (d % 2 == 0) l.record(HolidayActivity.Challenge, day)
            if (d % 3 == 0) l.record(HolidayActivity.Lesson, day)
            day++
        }
        l.record(HolidayActivity.Publish, 20261210)
    }
    private val schemes = ColorSchemeStore(prefs::get) { k, v -> prefs[k] = v }

    @Test
    fun homeDark() = render("christmas-home-dark.png", 411, 890, dark = true) { home() }

    @Test
    fun homeLight() = render("christmas-home-light.png", 411, 890, dark = false) { home() }

    @Test
    fun calendarDark() = render("christmas-advent-dark.png", 411, 1300, dark = true) { advent(null) }

    @Test
    fun calendarLight() = render("christmas-advent-light.png", 411, 1300, dark = false) { advent(null) }

    @Test
    fun keepsakeSheet() = render("christmas-door-keepsake.png", 411, 890, dark = true) { advent(3) }

    @Test
    fun tipSheet() = render("christmas-door-tip.png", 411, 890, dark = false) { advent(4) }

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
                            ChristmasCard(SeasonalCardMode.Offer, onPrimary = {}, onSecondary = {})
                            AdventHomeCard(AdventCardStatus.of(ledger), ledger.ornaments(), starLit = false, onOpen = {})
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

    @Composable
    private fun advent(door: Int?) {
        AdventScreen(
            backend = StubBackend(),
            ledger = ledger,
            schemes = schemes,
            onBack = {},
            onSolve = {},
            onOpenLesson = {},
            onOpenStore = {},
            onSchemeApplied = {},
            initialDoor = door,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark, accent = CaAccent.Evergreen) {
                CompositionLocalProvider(
                    LocalSeason provides Season.Christmas,
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
            File(OUT, name).writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}
