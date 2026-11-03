package dev.ide.ui.screens

import dev.ide.ui.backend.UiSdkPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the SDK Manager splits a package category into rows shown up front and rows behind the expander. */
class SdkPackageGroupsTest {

    private fun platform(level: String, installed: Boolean = false, incomplete: Boolean = false) = UiSdkPackage(
        path = "platforms;android-$level", displayName = "Android SDK Platform $level", category = "PLATFORMS",
        sizeBytes = 50L shl 20, installed = installed, installable = true, incomplete = incomplete,
    )

    private val all = listOf("21", "26", "30", "31", "33", "34", "35", "36", "36.1").map { platform(it) }

    private fun paths(list: List<UiSdkPackage>) = list.map { it.path.substringAfter("android-") }

    @Test
    fun nothingInstalledShowsOnlyTheNewest() {
        val g = groupSdkPackages(all, neededLevels = emptySet(), activeIds = emptySet(), failedIds = emptySet())
        assertEquals(listOf("36.1"), paths(g.shown))
        assertEquals(listOf("36", "35", "34", "33", "31", "30", "26", "21"), paths(g.more))
        assertEquals("platforms;android-36.1", g.latest)
    }

    @Test
    fun installedNeededActiveAndFailedStayInView() {
        val pkgs = all.map {
            when (it.path) {
                "platforms;android-26" -> it.copy(installed = true)
                "platforms;android-30" -> it.copy(incomplete = true)
                else -> it
            }
        }
        val g = groupSdkPackages(
            pkgs, neededLevels = setOf(34), activeIds = setOf("platforms;android-31"),
            failedIds = setOf("platforms;android-21"),
        )
        assertEquals(listOf("36.1", "34", "31", "30", "26", "21"), paths(g.shown))
        assertEquals(listOf("36", "35", "33"), paths(g.more))
        assertEquals(setOf("platforms;android-34"), g.needed)
    }

    @Test
    fun aNeededLevelMatchesEveryMinorOfIt() {
        val g = groupSdkPackages(all, neededLevels = setOf(36), activeIds = emptySet(), failedIds = emptySet())
        assertEquals(setOf("platforms;android-36", "platforms;android-36.1"), g.needed)
        assertTrue(g.shown.map { it.path }.containsAll(g.needed))
    }

    @Test
    fun aSingleHiddenRowIsShownInsteadOfAnExpander() {
        val two = listOf(platform("35"), platform("34"))
        val g = groupSdkPackages(two, emptySet(), emptySet(), emptySet())
        assertEquals(listOf("35", "34"), paths(g.shown))
        assertTrue(g.more.isEmpty())
    }

    @Test
    fun emptyCategoryHasNoLatest() {
        val g = groupSdkPackages(emptyList(), emptySet(), emptySet(), emptySet())
        assertTrue(g.shown.isEmpty() && g.more.isEmpty())
        assertNull(g.latest)
    }

    @Test
    fun levelsParseFromPackageIds() {
        assertEquals(36, sdkMajorLevel("platforms;android-36.1"))
        assertEquals(34, sdkMajorLevel("sources;android-34"))
        assertNull(sdkMajorLevel("platform-tools"))
        assertNull(sdkMajorLevel("platforms;android-UpsideDownCake"))
    }
}
