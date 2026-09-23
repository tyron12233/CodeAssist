package dev.ide.ui.editor.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The preview chrome starts on the configuration the opened resource folder names, so the device card matches
 * the configuration the backend resolves the layout under.
 */
class ResourceQualifierChromeTest {

    private fun seeded(path: String) = PreviewSurfaceState().apply { applyResourceQualifiers(path) }

    @Test fun landscapeFolderStartsRotated() {
        val state = seeded("/p/app/src/main/res/layout-land/main.xml")
        assertTrue(state.landscape)
        assertTrue(state.wdp > state.hdp, "the frame follows the rotation: ${state.wdp}x${state.hdp}")
    }

    @Test fun unqualifiedFolderKeepsTheDefaults() {
        val state = seeded("/p/app/src/main/res/layout/main.xml")
        assertFalse(state.landscape)
        assertFalse(state.night)
        assertEquals(0, state.deviceIndex)
    }

    @Test fun nightFolderStartsDark() {
        assertTrue(seeded("/p/app/src/main/res/layout-night/main.xml").night)
    }

    @Test fun smallestWidthFolderPicksADeviceThatSatisfiesIt() {
        val state = seeded("/p/app/src/main/res/layout-sw600dp/main.xml")
        assertTrue(minOf(state.wdp, state.hdp) >= 600, "frame ${state.wdp}x${state.hdp} must satisfy sw600dp")
    }

    @Test fun anUnsatisfiableFolderKeepsTheDefaultFrame() {
        // No built-in profile is 1280dp wide; the frame stays and the backend widens its render configuration.
        val state = seeded("/p/app/src/main/res/layout-sw1280dp/main.xml")
        assertEquals(0, state.deviceIndex)
    }
}
