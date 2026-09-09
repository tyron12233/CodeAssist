package dev.ide.android.support.icons

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.FillRule
import dev.ide.android.support.preview.StrokeCap
import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorPath
import dev.ide.android.support.preview.VectorSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SVG → VectorDrawable conversion: the shape lowering, the transform strategy (a `<group>` when the matrix
 * allows one, baked coordinates when it doesn't), the style cascade, and the warnings a lossy conversion
 * reports. Every conversion is also round-tripped through [DrawablePreviewParser] so the XML this writes is
 * exactly what the preview renders.
 */
class SvgToVectorDrawableTest {

    @Test
    fun aPlainIconKeepsItsPathDataVerbatimAndNeedsNoGroup() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
              <path d="M12 2L2 22h20z" fill="#6200EE"/>
            </svg>
        """.trimIndent()
        val spec = specOf(svg)

        assertEquals(24f, spec.widthDp)
        assertEquals(24f, spec.viewportWidth)
        val path = spec.nodes.single() as VectorPath
        assertEquals("M12 2L2 22h20z", path.pathData, "an untransformed path is passed through untouched")
        assertEquals(0xFF6200EEL, path.fillColor)
        assertRoundTrips(spec)
    }

    /**
     * Material Symbols ships every icon in a 960-unit box whose viewBox origin is `-960` on Y, so the
     * conversion has to carry that offset as a `<group>` translate, which keeps the (compact, integer)
     * path data exactly as published.
     */
    @Test
    fun aMaterialSymbolsViewBoxOffsetBecomesAGroupTranslate() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24">
              <path d="M480-80 200-360l56-56 224 224 224-224 56 56L480-80Z"/>
            </svg>
        """.trimIndent()
        val spec = specOf(svg)

        assertEquals(960f, spec.viewportWidth)
        assertEquals(960f, spec.viewportHeight)
        assertEquals(24f, spec.widthDp, "the declared width is the intrinsic size, not the viewport")

        val group = spec.nodes.single() as VectorGroup
        assertEquals(0f, group.translateX)
        assertEquals(960f, group.translateY)
        val path = group.children.single() as VectorPath
        assertEquals("M480-80 200-360l56-56 224 224 224-224 56 56L480-80Z", path.pathData)
        assertEquals(0xFF000000L, path.fillColor, "SVG's initial fill is black")
        assertRoundTrips(spec)
    }

    @Test
    fun anExplicitSizeOverridesTheSvgsOwn() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path d="M0 0h48v48H0z"/></svg>"""
        val spec = specOf(svg, SvgConvertOptions(widthDp = 24f, heightDp = 24f))
        assertEquals(24f, spec.widthDp)
        assertEquals(24f, spec.heightDp)
        assertEquals(48f, spec.viewportWidth, "the coordinate space is untouched by an intrinsic-size change")
    }

    @Test
    fun overrideColorRepaintsEveryFillAndStroke() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h24v24H0z" fill="#FF0000"/>
              <path d="M2 2h20v20H2z" fill="none" stroke="#00FF00" stroke-width="2"/>
            </svg>
        """.trimIndent()
        val spec = specOf(svg, SvgConvertOptions(overrideColor = 0xFF123456L))
        val paths = spec.nodes.map { it as VectorPath }
        assertEquals(0xFF123456L, paths[0].fillColor)
        assertEquals(0xFF123456L, paths[1].strokeColor)
        assertNull(paths[1].fillColor, "fill=\"none\" stays unpainted")
    }

    // --- shapes ----------------------------------------------------------------------------------------

    @Test
    fun basicShapesLowerToPathData() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <rect x="1" y="2" width="10" height="20"/>
              <circle cx="50" cy="50" r="5"/>
              <ellipse cx="50" cy="50" rx="6" ry="4"/>
              <line x1="0" y1="0" x2="10" y2="10" stroke="#000"/>
              <polygon points="0,0 10,0 10,10"/>
              <polyline points="0,0 5,5" stroke="#000"/>
            </svg>
        """.trimIndent()
        val data = specOf(svg).nodes.map { (it as VectorPath).pathData }

        assertEquals("M1,2h10v20h-10Z", data[0])
        assertEquals("M45,50a5,5 0 1 0 10,0a5,5 0 1 0 -10,0Z", data[1])
        assertEquals("M44,50a6,4 0 1 0 12,0a6,4 0 1 0 -12,0Z", data[2])
        assertEquals("M0,0L10,10", data[3])
        assertEquals("M0,0L10,0L10,10Z", data[4])
        assertEquals("M0,0L5,5", data[5], "a polyline is not closed")
    }

    @Test
    fun aRoundedRectUsesCornerArcsClampedToHalfTheSide() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <rect width="20" height="20" rx="40"/>
            </svg>
        """.trimIndent()
        val d = (specOf(svg).nodes.single() as VectorPath).pathData
        // rx is clamped to width/2 = 10, and ry mirrors rx when it isn't given.
        assertEquals("M10,0h0a10,10 0 0 1 10,10v0a10,10 0 0 1 -10,10h0a10,10 0 0 1 -10,-10v0a10,10 0 0 1 10,-10Z", d)
    }

    @Test
    fun aZeroSizedShapeIsDropped() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
              <rect width="0" height="10"/><circle cx="5" cy="5" r="0"/>
            </svg>
        """.trimIndent()
        assertTrue(specOf(svg).nodes.isEmpty())
    }

    // --- transforms ------------------------------------------------------------------------------------

    @Test
    fun aScaleAndTranslateGroupStaysAGroupWithItsPathDataIntact() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g transform="translate(5 6) scale(2)">
                <path d="M0 0h4v4H0z"/>
              </g>
            </svg>
        """.trimIndent()
        val group = specOf(svg).nodes.single() as VectorGroup
        assertEquals(2f, group.scaleX)
        assertEquals(2f, group.scaleY)
        assertEquals(5f, group.translateX)
        assertEquals(6f, group.translateY)
        assertEquals("M0 0h4v4H0z", (group.children.single() as VectorPath).pathData)
    }

    @Test
    fun aGroupWithNoTransformIsSplicedAwayRatherThanEmitted() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g><path d="M0 0h4v4H0z"/></g>
            </svg>
        """.trimIndent()
        assertTrue(specOf(svg).nodes.single() is VectorPath, "an empty <g> adds no <group> to the output")
    }

    /** A rotation can't be composed as a nested `<group>` here, so it gets baked into the coordinates. */
    @Test
    fun aRotationIsBakedIntoTheCoordinates() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M10,0L20,0" transform="rotate(90)"/>
            </svg>
        """.trimIndent()
        val path = specOf(svg).nodes.single() as VectorPath
        assertEquals("M0,10L0,20", path.pathData, "rotating 90° takes (10,0) to (0,10)")
    }

    @Test
    fun aSkewIsBakedIntoTheCoordinates() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g transform="skewX(45)"><path d="M0,10L0,20"/></g>
            </svg>
        """.trimIndent()
        val path = specOf(svg).nodes.single() as VectorPath
        assertEquals("M10,10L20,20", path.pathData, "skewX(45) shifts x by y")
    }

    @Test
    fun anArcSurvivesBakingAsCubics() {
        // A quarter circle from (10,0) to (0,10), rotated 90° so the matrix has to be baked.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M10,0A10,10 0 0 1 0,10Z" transform="rotate(90)"/>
            </svg>
        """.trimIndent()
        val d = (specOf(svg).nodes.single() as VectorPath).pathData
        assertTrue(d.startsWith("M0,10"), "the start point is rotated: got $d")
        assertTrue(d.contains("C"), "the arc is emitted as cubics once a matrix has to be baked: got $d")
        assertTrue(d.endsWith("Z"))
        // The arc ended at (0,10); rotated 90° that is (-10,0).
        assertTrue(d.contains("-10,0"), "the arc's end point is rotated too: got $d")
    }

    @Test
    fun nestedTransformsCompose() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g transform="translate(10 0)">
                <g transform="scale(2)"><path d="M1,1L2,2"/></g>
              </g>
            </svg>
        """.trimIndent()
        val outer = specOf(svg).nodes.single() as VectorGroup
        assertEquals(10f, outer.translateX)
        val inner = outer.children.single() as VectorGroup
        assertEquals(2f, inner.scaleX)
    }

    // --- styling ---------------------------------------------------------------------------------------

    @Test
    fun inlineStyleBeatsPresentationAttributesAndCascades() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g style="fill:#00FF00" fill="#FF0000">
                <path d="M0 0h1v1H0z"/>
                <path d="M1 1h1v1H1z" fill="#0000FF"/>
              </g>
            </svg>
        """.trimIndent()
        val paths = specOf(svg).nodes.map { it as VectorPath }
        assertEquals(0xFF00FF00L, paths[0].fillColor, "style= wins over fill= on the same element")
        assertEquals(0xFF0000FFL, paths[1].fillColor, "a child's own fill wins over the inherited one")
    }

    @Test
    fun opacitiesMultiplyDownTheTree() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <g opacity="0.5"><path d="M0 0h1v1H0z" fill-opacity="0.5"/></g>
            </svg>
        """.trimIndent()
        val path = specOf(svg).nodes.single() as VectorPath
        assertEquals(0.25f, path.fillAlpha)
    }

    @Test
    fun strokeAndFillRulePropertiesCarryOver() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h1v1H0z" fill="none" fill-rule="evenodd" stroke="red"
                  stroke-width="3" stroke-linecap="round" stroke-miterlimit="6"/>
            </svg>
        """.trimIndent()
        val path = specOf(svg).nodes.single() as VectorPath
        assertNull(path.fillColor)
        assertEquals(0xFFFF0000L, path.strokeColor, "a named colour resolves")
        assertEquals(3f, path.strokeWidthVp)
        assertEquals(StrokeCap.ROUND, path.strokeCap)
        assertEquals(6f, path.strokeMiter)
        assertEquals(FillRule.EVEN_ODD, path.fillRule)
    }

    @Test
    fun colorFormsAreUnderstood() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h1v1H0z" fill="rgb(255, 0, 0)"/>
              <path d="M1 0h1v1H1z" fill="rgba(0,255,0,0.5)"/>
              <path d="M2 0h1v1H2z" fill="#12345680"/>
              <path d="M3 0h1v1H3z" fill="currentColor"/>
              <path d="M4 0h1v1H4z" fill="#ABC"/>
            </svg>
        """.trimIndent()
        val paths = specOf(svg).nodes.map { it as VectorPath }
        assertEquals(0xFFFF0000L, paths[0].fillColor)
        assertEquals(0xFF00FF00L, paths[1].fillColor)
        assertEquals(0.5f, paths[1].fillAlpha, absoluteTolerance = 0.01f)
        assertEquals(0xFF123456L, paths[2].fillColor, "CSS #RRGGBBAA moves the alpha out of the colour")
        assertEquals(0.5f, paths[2].fillAlpha, absoluteTolerance = 0.01f)
        assertEquals(0xFF000000L, paths[3].fillColor, "currentColor has nothing to inherit, so black")
        assertEquals(0xFFAABBCCL, paths[4].fillColor)
    }

    @Test
    fun aFullyTransparentPaintDrawsNothing() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h1v1H0z" fill="#000" fill-opacity="0"/>
            </svg>
        """.trimIndent()
        assertTrue(specOf(svg).nodes.isEmpty())
    }

    @Test
    fun displayNoneIsSkipped() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path d="M0 0h1v1H0z" display="none"/>
              <path d="M1 1h1v1H1z"/>
            </svg>
        """.trimIndent()
        assertEquals(1, specOf(svg).nodes.size)
    }

    // --- clips, gradients, warnings --------------------------------------------------------------------

    @Test
    fun aReferencedClipPathBecomesTheGroupsClipPath() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <defs><clipPath id="c"><path d="M0 0h12v24H0z"/></clipPath></defs>
              <g clip-path="url(#c)"><path d="M0 0h24v24H0z"/></g>
            </svg>
        """.trimIndent()
        val group = specOf(svg).nodes.single() as VectorGroup
        assertEquals("M0 0h12v24H0z", group.clipPathData)
        assertEquals(1, group.children.size)
    }

    @Test
    fun aGradientFillFlattensToItsFirstStopAndWarns() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <defs>
                <linearGradient id="g"><stop offset="0" stop-color="#FF0000"/><stop offset="1" stop-color="#0000FF"/></linearGradient>
              </defs>
              <path d="M0 0h24v24H0z" fill="url(#g)"/>
            </svg>
        """.trimIndent()
        val result = assertNotNull(SvgToVectorDrawable.toSpec(svg))
        assertEquals(0xFFFF0000L, (result.spec.nodes.single() as VectorPath).fillColor)
        assertTrue(result.warnings.any { it.contains("gradient", ignoreCase = true) }, result.warnings.toString())
    }

    @Test
    fun textAndUseElementsAreReportedRatherThanSilentlyDropped() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <text x="0" y="10">hi</text>
              <use href="#other"/>
              <path d="M0 0h1v1H0z"/>
            </svg>
        """.trimIndent()
        val result = assertNotNull(SvgToVectorDrawable.toSpec(svg))
        assertEquals(1, result.spec.nodes.size, "the drawable geometry still converts")
        assertTrue(result.warnings.any { it.contains("Text") }, result.warnings.toString())
        assertTrue(result.warnings.any { it.contains("<use>") }, result.warnings.toString())
    }

    @Test
    fun anEmptySvgWarnsInsteadOfProducingAnEmptyDrawableSilently() {
        val result = assertNotNull(
            SvgToVectorDrawable.toSpec("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"/>"""),
        )
        assertTrue(result.warnings.any { it.contains("No drawable geometry") }, result.warnings.toString())
    }

    @Test
    fun nonSvgInputIsRejected() {
        assertNull(SvgToVectorDrawable.toSpec("<vector/>"))
        assertNull(SvgToVectorDrawable.toSpec("not xml at all"))
        assertNull(SvgToVectorDrawable.toSpec(""))
    }

    @Test
    fun lengthUnitsResolveToUserUnits() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1in" height="72pt"><path d="M0 0h1v1H0z"/></svg>"""
        val spec = specOf(svg)
        assertEquals(96f, spec.widthDp, "1in is 96px at CSS's reference resolution")
        assertEquals(96f, spec.heightDp, "72pt is also 96px")
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun specOf(svg: String, options: SvgConvertOptions = SvgConvertOptions()): VectorSpec =
        assertNotNull(SvgToVectorDrawable.toSpec(svg, options), "conversion returned null").spec

    /** The XML we emit must parse back to the same tree: the writer and the preview parser agree. */
    private fun assertRoundTrips(spec: VectorSpec) {
        val xml = VectorDrawableWriter.write(spec)
        val reparsed = DrawablePreviewParser.parse(xml)
        assertTrue(reparsed is DrawablePreview.Vector, "generated XML did not parse as a vector:\n$xml")
        assertEquals(spec, reparsed.spec, "generated XML did not round-trip:\n$xml")
    }
}
