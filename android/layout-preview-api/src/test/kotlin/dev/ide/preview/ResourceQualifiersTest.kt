package dev.ide.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceQualifiersTest {

    @Test
    fun unqualifiedFolderPinsNothing() {
        val q = ResourceQualifiers.parse("layout")
        assertNull(q.landscape)
        assertNull(q.night)
        assertNull(q.smallestWidthDp)
    }

    @Test
    fun readsOrientationNightAndDirection() {
        assertEquals(true, ResourceQualifiers.parse("layout-land").landscape)
        assertEquals(false, ResourceQualifiers.parse("layout-port").landscape)
        assertEquals(true, ResourceQualifiers.parse("values-night").night)
        assertEquals(false, ResourceQualifiers.parse("values-notnight").night)
        assertEquals(true, ResourceQualifiers.parse("layout-ldrtl").rtl)
    }

    @Test
    fun readsSizeQualifiers() {
        assertEquals(600, ResourceQualifiers.parse("layout-sw600dp").smallestWidthDp)
        assertEquals(720, ResourceQualifiers.parse("layout-w720dp").screenWidthDp)
        assertEquals(480, ResourceQualifiers.parse("layout-h480dp").screenHeightDp)
        assertEquals(720, ResourceQualifiers.parse("layout-xlarge").smallestWidthDp)
    }

    @Test
    fun ignoresQualifiersAPreviewCannotSimulate() {
        val q = ResourceQualifiers.parse("layout-en-rUS-v24-hdpi")
        assertNull(q.landscape)
        assertNull(q.screenWidthDp)
        assertNull(q.smallestWidthDp)
    }

    @Test
    fun combinesQualifiers() {
        val q = ResourceQualifiers.parse("layout-sw600dp-land-night-v21")
        assertEquals(true, q.landscape)
        assertEquals(true, q.night)
        assertEquals(600, q.smallestWidthDp)
    }

    @Test
    fun landscapeFileTurnsAPortraitFrame() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout-land/main.xml", frameWidthDp = 360, frameHeightDp = 800, night = false,
        )
        assertEquals(800, config.screenWidthDp)
        assertEquals(360, config.screenHeightDp)
        assertEquals(360, config.smallestWidthDp)
        assertTrue(config.landscape)
    }

    @Test
    fun portraitFileTurnsALandscapeFrame() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout-port/main.xml", frameWidthDp = 800, frameHeightDp = 360, night = false,
        )
        assertEquals(360, config.screenWidthDp)
        assertEquals(800, config.screenHeightDp)
        assertFalse(config.landscape)
    }

    @Test
    fun unqualifiedFileKeepsTheFrame() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout/main.xml", frameWidthDp = 800, frameHeightDp = 360, night = true,
        )
        assertEquals(800, config.screenWidthDp)
        assertEquals(360, config.screenHeightDp)
        assertTrue(config.landscape)
        assertTrue(config.night)
    }

    @Test
    fun sizeQualifiedFileWidensAFrameThatIsTooSmall() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout-sw600dp/main.xml", frameWidthDp = 360, frameHeightDp = 800, night = false,
        )
        assertEquals(600, config.screenWidthDp)
        assertEquals(800, config.screenHeightDp)
        assertEquals(600, config.smallestWidthDp)
    }

    @Test
    fun aFrameThatAlreadySatisfiesTheQualifierIsLeftAlone() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout-sw600dp/main.xml", frameWidthDp = 600, frameHeightDp = 960, night = false,
        )
        assertEquals(600, config.screenWidthDp)
        assertEquals(960, config.screenHeightDp)
    }

    @Test
    fun nightFolderOverridesTheFrameToggle() {
        val config = ResourceQualifiers.forFile(
            "/p/app/src/main/res/layout-night/main.xml", frameWidthDp = 360, frameHeightDp = 800, night = false,
        )
        assertTrue(config.night)
    }

    @Test
    fun windowsPathSeparatorsAreRead() {
        val q = ResourceQualifiers.parseFile("""C:\p\app\src\main\res\layout-land\main.xml""")
        assertEquals(true, q.landscape)
    }
}
