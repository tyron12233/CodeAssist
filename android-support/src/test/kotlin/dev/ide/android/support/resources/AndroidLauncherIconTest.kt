package dev.ide.android.support.resources

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.templates.AndroidAppAssets
import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidLauncherIconTest {

    private fun writeRaster(dir: Path, rel: String): Path {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, byteArrayOf(1, 2, 3))
        return p
    }

    @Test
    fun picksDensestRasterAndSkipsAdaptiveXml() {
        withTempDir("res") { res ->
            // The adaptive-icon XML (anydpi) plus the raster fallbacks a real project ships alongside it.
            writeRaster(res, "mipmap-anydpi-v26/ic_launcher.xml")
            writeRaster(res, "mipmap-mdpi/ic_launcher.png")
            val xxxhdpi = writeRaster(res, "mipmap-xxxhdpi/ic_launcher.png")
            writeRaster(res, "mipmap-hdpi/ic_launcher.png")

            val icon = AndroidLauncherIcon.locate(listOf(res), "@mipmap/ic_launcher", null)
            assertEquals(xxxhdpi, (icon as LauncherIcon.Raster).path)
        }
    }

    @Test
    fun resolvesWebpAndDrawableType() {
        withTempDir("res") { res ->
            val webp = writeRaster(res, "drawable-xhdpi/logo.webp")
            val icon = AndroidLauncherIcon.locate(listOf(res), "@drawable/logo", null)
            assertEquals(webp, (icon as LauncherIcon.Raster).path)
        }
    }

    @Test
    fun fallsBackToRoundIconWhenPrimaryUnresolvable() {
        withTempDir("res") { res ->
            val round = writeRaster(res, "mipmap-xxhdpi/ic_launcher_round.png")
            // Primary @mipmap/ic_launcher has no resource on disk; the round icon resolves.
            val icon = AndroidLauncherIcon.locate(listOf(res), "@mipmap/ic_launcher", "@mipmap/ic_launcher_round")
            assertEquals(round, (icon as LauncherIcon.Raster).path)
        }
    }

    @Test
    fun fallsBackToConventionalLauncherNameWithoutManifestRef() {
        withTempDir("res") { res ->
            val launcher = writeRaster(res, "mipmap-xxhdpi/ic_launcher.png")
            val icon = AndroidLauncherIcon.locate(listOf(res), null, null)
            assertEquals(launcher, (icon as LauncherIcon.Raster).path)
        }
    }

    @Test
    fun ignoresFrameworkReferences() {
        withTempDir("res") { res ->
            writeRaster(res, "mipmap-xxhdpi/sym_def_app_icon.png") // present but not what the framework ref names
            assertNull(AndroidLauncherIcon.locate(listOf(res), "@android:drawable/sym_def_app_icon", null))
        }
    }

    @Test
    fun nullWhenIconIsUnparseableXmlOnly() {
        withTempDir("res") { res ->
            writeRaster(res, "mipmap-anydpi-v26/ic_launcher.xml") // garbage bytes, no raster fallback
            assertNull(AndroidLauncherIcon.locate(listOf(res), "@mipmap/ic_launcher", null))
        }
    }

    @Test
    fun emptyRootsResolveToNull() {
        assertNull(AndroidLauncherIcon.locate(emptyList(), "@mipmap/ic_launcher", null))
    }

    @Test
    fun rendersTheTemplateVectorLauncherIconAsLayers() {
        // The default Android templates ship a vector-only adaptive/legacy launcher icon (no raster). Verify
        // we resolve it to a render-ready layered drawable (background + foreground), not the letter fallback.
        withTempDir("res") { res ->
            for ((rel, content) in AndroidAppAssets.launcherIconResFiles) res.writeSource(rel, content, trim = false)
            res.writeSource("values/colors.xml", "<resources>${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}</resources>", trim = false)

            val icon = AndroidLauncherIcon.locate(listOf(res), "@mipmap/ic_launcher", "@mipmap/ic_launcher_round")
            val preview = (icon as LauncherIcon.Drawable).preview
            assertTrue(preview is DrawablePreview.Layers, "expected layered icon, got $preview")
            assertEquals(2, (preview as DrawablePreview.Layers).layers.size)
        }
    }
}
