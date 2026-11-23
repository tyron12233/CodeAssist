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
import dev.ide.ui.components.WinterCard
import dev.ide.ui.components.SeasonalCardMode
import dev.ide.ui.fakeAdController
import dev.ide.ui.holiday.HolidayActivity
import dev.ide.ui.holiday.HolidayLedger
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.screens.countdown.CountdownCardStatus
import dev.ide.ui.screens.countdown.CountdownHomeCard
import dev.ide.ui.screens.countdown.CountdownScreen
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
 * The winter season with the season forced on, under the Aurora theme: Home with the dress-up and countdown
 * cards, the New Year countdown on December 12 with some days open and stars in the sky, and a couple of day
 * sheets, New Year's Eve among them. Judged by eye; the assertion only catches a blank frame.
 */
class WinterHomeSnapshot {

    private val projects = listOf(
        ProjectInfo(name = "snow-globe", rootPath = "/p/snow-globe", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 3_600_000L),
        ProjectInfo(name = "cocoa-timer", rootPath = "/p/cocoa-timer", moduleCount = 1, isAndroid = true,
            lastOpened = System.currentTimeMillis() - 30 * 3_600_000L),
    )

    private val prefs = HashMap<String, String>()
    private val ledger = HolidayLedger(prefs::get, { k, v -> prefs[k] = v }, { 20261212 }).also { l ->
        var day = 20261201
        for (d in listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11)) {
            l.open(d, day)
            l.record(HolidayActivity.Build, day)
            if (d % 2 == 0) l.record(HolidayActivity.Challenge, day)
            if (d % 3 == 0) l.record(HolidayActivity.Lesson, day)
            day++
        }
        l.record(HolidayActivity.Publish, 20261210)
    }
    /** A second calendar on New Year's Eve with every day open, for the finale. */
    private val newYearsEve = HashMap<String, String>().let { p ->
        HolidayLedger(p::get, { k, v -> p[k] = v }, { 20261231 }).also { l ->
            for (d in 1..31) l.open(d, 20261200 + d)
        }
    }
    private val schemes = ColorSchemeStore(prefs::get) { k, v -> prefs[k] = v }

    @Test
    fun homeDark() = render("winter-home-dark.png", 411, 890, dark = true) { home() }

    @Test
    fun homeLight() = render("winter-home-light.png", 411, 890, dark = false) { home() }

    @Test
    fun countdownDark() = render("winter-countdown-dark.png", 411, 1300, dark = true) { countdown(null) }

    @Test
    fun countdownLight() = render("winter-countdown-light.png", 411, 1300, dark = false) { countdown(null) }

    @Test
    fun keepsakeSheet() = render("winter-day-keepsake.png", 411, 890, dark = true) { countdown(4) }

    @Test
    fun tipSheet() = render("winter-day-tip.png", 411, 890, dark = false) { countdown(3) }

    @Test
    fun newYearsEve() = render("winter-day-finale.png", 411, 890, dark = true) { countdown(31, finalDay = true) }

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
                            WinterCard(SeasonalCardMode.Offer, onPrimary = {}, onSecondary = {})
                            CountdownHomeCard(CountdownCardStatus.of(ledger), ledger.stars(), auroraOut = false, onOpen = {})
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
    private fun countdown(day: Int?, finalDay: Boolean = false) {
        CountdownScreen(
            backend = StubBackend(),
            ledger = if (finalDay) newYearsEve else ledger,
            schemes = schemes,
            onBack = {},
            onSolve = {},
            onOpenLesson = {},
            onOpenStore = {},
            onSchemeApplied = {},
            initialDay = day,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark, accent = CaAccent.Aurora) {
                CompositionLocalProvider(
                    LocalSeason provides Season.Winter,
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
