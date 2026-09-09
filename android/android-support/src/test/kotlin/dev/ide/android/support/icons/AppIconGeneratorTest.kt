package dev.ide.android.support.icons

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorPath
import dev.ide.android.support.preview.VectorSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launcher-icon generator: the file set it plans, the adaptive-icon geometry (artwork scaled into the
 * safe zone rather than the full box), the pre-26 fallback, and the manifest edit. Everything the generator
 * emits as XML is parsed back with the drawable parser, so a malformed document can't ship.
 */
class AppIconGeneratorTest {

    private val source = VectorSpec(
        widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f,
        nodes = listOf(VectorPath("M0,0h24v24H0z", fillColor = 0xFF000000L)),
    )

    private fun spec(
        foreground: AppIconLayer = AppIconLayer.Vector(source),
        background: AppIconLayer = AppIconLayer.Color(0xFF2196F3L),
        monochrome: AppIconLayer = AppIconLayer.None,
        rasters: Boolean = true,
        round: Boolean = true,
        playStore: Boolean = true,
    ) = AppIconSpec(
        background = background, foreground = foreground, monochrome = monochrome,
        generateRasters = rasters, generateRoundIcon = round, generatePlayStoreIcon = playStore,
    )

    private fun paths(plan: AppIconPlan) = plan.files.map { it.relativePath }

    private fun textOf(plan: AppIconPlan, path: String): String =
        assertNotNull(plan.files.filterIsInstance<AppIconFile.Text>().firstOrNull { it.relativePath == path }, path)
            .content

    @Test
    fun theFullSetCoversAdaptiveLayersRastersAndTheStoreImage() {
        val plan = AppIconGenerator.plan(spec())
        val files = paths(plan)

        assertTrue("mipmap-anydpi-v26/ic_launcher.xml" in files)
        assertTrue("mipmap-anydpi-v26/ic_launcher_round.xml" in files)
        assertTrue("values/ic_launcher_background.xml" in files, "a flat background is a colour resource")
        assertTrue("drawable/ic_launcher_foreground.xml" in files)
        // Every density bucket, square and round.
        for ((qualifier, _) in AppIconGenerator.DENSITIES) {
            assertTrue("mipmap-$qualifier/ic_launcher.png" in files, qualifier)
            assertTrue("mipmap-$qualifier/ic_launcher_round.png" in files, qualifier)
        }
        assertTrue("../ic_launcher-playstore.png" in files, "the store image sits beside res/, not inside it")
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString())
    }

    @Test
    fun theAdaptiveIconReferencesEveryLayerItWasGiven() {
        val plan = AppIconGenerator.plan(spec(monochrome = AppIconLayer.Vector(source)))
        val xml = textOf(plan, "mipmap-anydpi-v26/ic_launcher.xml")

        assertTrue(xml.contains("""<background android:drawable="@color/ic_launcher_background"/>"""), xml)
        assertTrue(xml.contains("""<foreground android:drawable="@drawable/ic_launcher_foreground"/>"""), xml)
        assertTrue(xml.contains("""<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>"""), xml)
        assertTrue("drawable/ic_launcher_monochrome.xml" in paths(plan))
    }

    @Test
    fun aMonochromeLayerIsOmittedWhenThereIsNone() {
        val xml = textOf(AppIconGenerator.plan(spec()), "mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue(!xml.contains("monochrome"), xml)
    }

    @Test
    fun theRoundIconIsTheSameDocumentAsTheSquareOne() {
        val plan = AppIconGenerator.plan(spec())
        assertEquals(
            textOf(plan, "mipmap-anydpi-v26/ic_launcher.xml"),
            textOf(plan, "mipmap-anydpi-v26/ic_launcher_round.xml"),
            "the launcher picks the mask, so both entries are the same adaptive icon",
        )
    }

    @Test
    fun turningOffTheRoundIconDropsItsFilesAndItsManifestReference() {
        val plan = AppIconGenerator.plan(spec(round = false))
        assertTrue(paths(plan).none { it.contains("_round") }, paths(plan).toString())
        assertNull(assertNotNull(plan.manifest).roundIconRef)
    }

    @Test
    fun theManifestIsPointedAtTheGeneratedMipmaps() {
        val manifest = assertNotNull(AppIconGenerator.plan(spec()).manifest)
        assertEquals("@mipmap/ic_launcher", manifest.iconRef)
        assertEquals("@mipmap/ic_launcher_round", manifest.roundIconRef)
    }

    @Test
    fun everyGeneratedXmlFileParsesBack() {
        val plan = AppIconGenerator.plan(spec(monochrome = AppIconLayer.Vector(source), rasters = false))
        val drawables = plan.files.filterIsInstance<AppIconFile.Text>()
            .filter { !it.relativePath.startsWith("values/") }
        assertTrue(drawables.size >= 4, paths(plan).toString())
        for (file in drawables) {
            val parsed = DrawablePreviewParser.parse(file.content)
            assertTrue(
                parsed !is DrawablePreview.Unsupported,
                "${file.relativePath} did not parse: $parsed\n${file.content}",
            )
        }
    }

    // --- geometry ----------------------------------------------------------------------------------------

    @Test
    fun artworkIsScaledIntoTheSafeZoneAndCentred() {
        val composed = AppIconGenerator.composeLayer(source)
        assertEquals(AppIconGenerator.BOX, composed.viewportWidth)
        assertEquals(AppIconGenerator.BOX, composed.viewportHeight)
        assertEquals(AppIconGenerator.BOX, composed.widthDp)

        val group = composed.nodes.single() as VectorGroup
        // 24 source units filling the 72-unit safe zone is a factor of 3.
        assertEquals(3f, group.scaleX)
        assertEquals(3f, group.scaleY)
        // The 72-unit artwork centred in a 108-unit box leaves 18 either side.
        assertEquals(18f, group.translateX)
        assertEquals(18f, group.translateY)
        assertEquals(source.nodes, group.children, "the source path data is wrapped, never rewritten")
    }

    @Test
    fun scaleAndOffsetMoveTheArtworkWithinTheBox() {
        val group = AppIconGenerator.composeLayer(source, scale = 0.5f, offsetX = 0.1f, offsetY = -0.1f)
            .nodes.single() as VectorGroup
        assertEquals(1.5f, group.scaleX, "half of the safe zone is 36 units for a 24-unit source")
        // Centred at (108 - 36) / 2 = 36, then nudged by a tenth of the box.
        assertEquals(36f + 10.8f, group.translateX, absoluteTolerance = 0.01f)
        assertEquals(36f - 10.8f, group.translateY, absoluteTolerance = 0.01f)
    }

    @Test
    fun anOversizedScaleIsClampedSoTheArtworkCannotRunOffTheCanvas() {
        val group = AppIconGenerator.composeLayer(source, scale = 99f).nodes.single() as VectorGroup
        // 1.5 x the safe zone is 108 units: exactly the full box, and no more.
        assertEquals(4.5f, group.scaleX)
        assertEquals(0f, group.translateX)
    }

    @Test
    fun aNonSquareSourceKeepsItsAspectRatio() {
        val wide = source.copy(viewportWidth = 48f, viewportHeight = 24f)
        val group = AppIconGenerator.composeLayer(wide).nodes.single() as VectorGroup
        assertEquals(1.5f, group.scaleX, "the longest side fills the safe zone")
        assertEquals(1.5f, group.scaleY, "and the other side scales by the same factor")
        assertEquals(18f, group.translateX)
        assertEquals(36f, group.translateY, "the shorter axis gets the larger inset")
    }

    @Test
    fun aDegenerateSourceViewportIsTreatedAsTwentyFourUnits() {
        val broken = source.copy(viewportWidth = 0f, viewportHeight = 0f)
        val group = AppIconGenerator.composeLayer(broken).nodes.single() as VectorGroup
        assertEquals(3f, group.scaleX)
    }

    // --- layers ------------------------------------------------------------------------------------------

    @Test
    fun aTintIsBakedIntoTheGeneratedForegroundLayer() {
        val plan = AppIconGenerator.plan(
            spec(foreground = AppIconLayer.Vector(source, tintArgb = 0xFFFF00FFL)),
        )
        assertTrue(textOf(plan, "drawable/ic_launcher_foreground.xml").contains("#FF00FF"))
    }

    @Test
    fun aColourBackgroundIsWrittenAsAColourResource() {
        val xml = textOf(AppIconGenerator.plan(spec()), "values/ic_launcher_background.xml")
        assertTrue(xml.contains("""<color name="ic_launcher_background">#2196F3</color>"""), xml)
    }

    @Test
    fun aBitmapLayerIsWrittenAsNodpiBytesAndWarnsAsABackground() {
        val bytes = byteArrayOf(1, 2, 3)
        val plan = AppIconGenerator.plan(
            spec(background = AppIconLayer.Raster(bytes, "png"), foreground = AppIconLayer.Vector(source)),
        )
        val file = assertNotNull(
            plan.files.filterIsInstance<AppIconFile.Bytes>()
                .firstOrNull { it.relativePath == "drawable-nodpi/ic_launcher_background.png" },
            paths(plan).toString(),
        )
        assertEquals(bytes.toList(), file.bytes.toList())
        assertTrue(plan.warnings.any { it.contains("re-themed") }, plan.warnings.toString())
    }

    @Test
    fun aTransparentBackgroundSimplyHasNoBackgroundEntry() {
        val plan = AppIconGenerator.plan(spec(background = AppIconLayer.None))
        assertTrue(paths(plan).none { it.startsWith("values/") })
        assertTrue(!textOf(plan, "mipmap-anydpi-v26/ic_launcher.xml").contains("<background"))
    }

    @Test
    fun aMissingForegroundIsReportedRatherThanSilentlyProducingABlankIcon() {
        val plan = AppIconGenerator.plan(spec(foreground = AppIconLayer.None))
        assertTrue(plan.warnings.any { it.contains("No foreground") }, plan.warnings.toString())
    }

    // --- fallbacks and replacement -----------------------------------------------------------------------

    @Test
    fun withoutRastersAVectorLayerListCoversPreOreo() {
        val plan = AppIconGenerator.plan(spec(rasters = false))
        assertTrue(paths(plan).none { it.endsWith(".png") && it.startsWith("mipmap-") }, paths(plan).toString())

        val legacy = textOf(plan, "mipmap/ic_launcher.xml")
        assertTrue(legacy.contains("<layer-list"), legacy)
        assertTrue(legacy.contains("@color/ic_launcher_background"), legacy)
        assertTrue(legacy.contains("@drawable/ic_launcher_foreground"), legacy)
        assertTrue(plan.warnings.any { it.contains("pre-Android 8") }, plan.warnings.toString())
    }

    @Test
    fun rasterDescriptorsCarryTheirSizeMaskAndOpacity() {
        val plan = AppIconGenerator.plan(spec())
        val rasters = plan.files.filterIsInstance<AppIconFile.Raster>()

        val xxxhdpi = assertNotNull(rasters.firstOrNull { it.relativePath == "mipmap-xxxhdpi/ic_launcher.png" })
        assertEquals(192, xxxhdpi.pixels)
        assertTrue(!xxxhdpi.round)
        assertTrue(!xxxhdpi.opaque)

        val round = assertNotNull(rasters.firstOrNull { it.relativePath == "mipmap-mdpi/ic_launcher_round.png" })
        assertEquals(48, round.pixels)
        assertTrue(round.round)

        val store = assertNotNull(rasters.firstOrNull { it.relativePath.contains("playstore") })
        assertEquals(512, store.pixels)
        assertTrue(store.opaque, "a store listing image may not be transparent")
    }

    @Test
    fun existingFilesAreReportedAsReplacements() {
        val taken = setOf("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-mdpi/ic_launcher.png")
        val plan = AppIconGenerator.plan(spec()) { it in taken }
        assertEquals(taken, plan.replacing.toSet())
    }

    @Test
    fun aCustomNameFlowsThroughEveryFile() {
        val plan = AppIconGenerator.plan(spec().copy(name = "ic_brand"))
        assertTrue(paths(plan).all { it.contains("ic_brand") }, paths(plan).toString())
        assertEquals("@mipmap/ic_brand", assertNotNull(plan.manifest).iconRef)
    }
}
