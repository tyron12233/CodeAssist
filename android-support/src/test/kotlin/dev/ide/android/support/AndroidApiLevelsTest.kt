package dev.ide.android.support

import dev.ide.android.support.templates.AndroidTemplateSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The API levels the IDE offers, and the defaults derived from them.
 *
 * These had drifted: the pickers, a new module's starter facet, and the Gradle import fallback all sat at API
 * 34 (Android 14) while Android 16 was current. That is not cosmetic. A modern AndroidX AAR declares a
 * `minCompileSdk` in its metadata and `CheckAarMetadataTask` fails the build when the module's compileSdk is
 * below it, so a stale default makes ordinary dependencies unusable. The point of these tests is that the
 * three defaults move together the next time the level is raised.
 */
class AndroidApiLevelsTest {

    @Test fun latestIsTheNewestOfferedLevel() {
        assertEquals(AndroidApiLevels.LEVELS.last().api, AndroidApiLevels.LATEST)
        assertEquals(AndroidApiLevels.LEVELS.map { it.api }.sorted(), AndroidApiLevels.LEVELS.map { it.api })
    }

    @Test fun everyDefaultTracksTheLatestLevel() {
        assertEquals(AndroidApiLevels.LATEST, AndroidModuleType.DEFAULT_COMPILE_SDK, "a new module's facet")
        assertEquals(AndroidApiLevels.LATEST, AndroidTemplateSupport.COMPILE_SDK, "the built-in templates")
    }

    @Test fun theDefaultTargetIsNeverAboveWhatTheModuleCompilesAgainst() {
        // compileSdk < targetSdk is the one combination that is always wrong: the app declares behaviour it
        // was not built against. The picker's default has to stay at or below the compile level.
        val defaultTarget = AndroidApiLevels.TARGET_SDK_LEVELS[AndroidTemplateSupport.targetSdkParam.defaultIndex]
        assertTrue(
            defaultTarget.api <= AndroidTemplateSupport.COMPILE_SDK,
            "default targetSdk ${defaultTarget.api} exceeds compileSdk ${AndroidTemplateSupport.COMPILE_SDK}",
        )
        assertEquals(AndroidApiLevels.LATEST, defaultTarget.api, "a new app should target the newest level")
    }

    @Test fun thePickersOfferTheCatalogAndDefaultToARealOption() {
        val min = AndroidTemplateSupport.minSdkParam
        assertEquals(AndroidApiLevels.MIN_SDK_LEVELS.map { it.api.toString() }, min.options.map { it.value })
        assertEquals(AndroidApiLevels.DEFAULT_MIN_SDK.toString(), min.options[min.defaultIndex].value)

        val target = AndroidTemplateSupport.targetSdkParam
        assertEquals(AndroidApiLevels.TARGET_SDK_LEVELS.map { it.api.toString() }, target.options.map { it.value })
        assertTrue(target.options.map { it.value }.containsAll(listOf("34", "35", "36")), "the recent levels")
        assertTrue(target.defaultIndex in target.options.indices)
    }

    @Test fun levelsAreLabelledByTheirAndroidRelease() {
        assertEquals("API 36 · Android 16", AndroidApiLevels.label(36))
        assertEquals("API 21 · Android 5.0", AndroidApiLevels.label(21))
        // A level the table doesn't name (a preview, or one a project set by hand) still formats.
        assertEquals("API 99", AndroidApiLevels.label(99))
    }
}
