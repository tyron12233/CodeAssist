package dev.ide.android.support.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drawable XML → [DrawablePreview] parser: shape (solid/gradient/stroke/corners), vector paths,
 * selector/layer-list composites, `<color>`, reference resolution through a [DrawableResolver], and
 * tolerance of malformed/unknown input.
 */
class DrawablePreviewParserTest {

    private val resolver = object : DrawableResolver {
        override fun resolveColor(ref: String): Long? = when (ref.substringAfterLast('/')) {
            "primary" -> 0xFF6200EEL
            "accent" -> 0xFF03DAC5L
            else -> null
        }
        override fun resolveDimenDp(ref: String): Float? = if (ref.endsWith("/gap")) 8f else null
        override fun resolveDrawable(ref: String): ResolvedDrawable? = when (ref.substringAfterLast('/')) {
            "ic_logo" -> ResolvedDrawable.BitmapFile("drawable", "ic_logo", "/proj/res/drawable/ic_logo.png")
            "bg_round" -> ResolvedDrawable.Xml(
                """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
                     <solid android:color="#FF0000"/></shape>""",
            )
            else -> null
        }
    }

    @Test
    fun parsesAdaptiveIconAsBackgroundForegroundLayers() {
        val xml = """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@drawable/bg_round"/>
              <foreground android:drawable="@drawable/bg_round"/>
              <monochrome android:drawable="@drawable/bg_round"/>
            </adaptive-icon>
        """.trimIndent()
        val d = DrawablePreviewParser.parse(xml, resolver)
        assertTrue(d is DrawablePreview.Layers)
        // background + foreground (monochrome is themed-icon only and ignored).
        assertEquals(2, (d as DrawablePreview.Layers).layers.size)
        assertTrue(d.layers.all { it.drawable is DrawablePreview.Shape })
    }

    @Test
    fun parsesShapeWithSolidStrokeCorners() {
        val xml = """
            <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
              <solid android:color="#FF6200EE"/>
              <stroke android:width="2dp" android:color="#FF000000"/>
              <corners android:radius="8dp"/>
              <size android:width="48dp" android:height="24dp"/>
            </shape>
        """.trimIndent()
        val d = DrawablePreviewParser.parse(xml)
        assertTrue(d is DrawablePreview.Shape)
        val s = (d as DrawablePreview.Shape).spec
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(0xFF6200EEL, s.solidColor)
        assertEquals(0xFF000000L, s.strokeColor)
        assertEquals(2f, s.strokeWidthDp)
        assertEquals(8f, s.cornerTopLeftDp)
        assertEquals(8f, s.cornerBottomRightDp)
        assertEquals(48f, s.intrinsicWidthDp)
    }

    @Test
    fun parsesGradient() {
        val xml = """
            <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
              <gradient android:type="linear" android:angle="45"
                  android:startColor="#FFFF0000" android:endColor="#FF0000FF"/>
            </shape>
        """.trimIndent()
        val s = (DrawablePreviewParser.parse(xml) as DrawablePreview.Shape).spec
        assertEquals(ShapeKind.OVAL, s.shape)
        val g = assertNotNull(s.gradient)
        assertEquals(GradientKind.LINEAR, g.kind)
        assertEquals(45, g.angle)
        assertEquals(0xFFFF0000L, g.startColor)
        assertEquals(0xFF0000FFL, g.endColor)
    }

    @Test
    fun resolvesColorAndDimenReferences() {
        val xml = """
            <shape xmlns:android="http://schemas.android.com/apk/res/android">
              <solid android:color="@color/primary"/>
              <stroke android:width="@dimen/gap" android:color="@color/accent"/>
            </shape>
        """.trimIndent()
        val s = (DrawablePreviewParser.parse(xml, resolver) as DrawablePreview.Shape).spec
        assertEquals(0xFF6200EEL, s.solidColor)
        assertEquals(0xFF03DAC5L, s.strokeColor)
        assertEquals(8f, s.strokeWidthDp)
    }

    @Test
    fun parsesVectorPaths() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
              <path android:pathData="M12,2L2,22h20z" android:fillColor="#FF6200EE"/>
            </vector>
        """.trimIndent()
        val v = (DrawablePreviewParser.parse(xml) as DrawablePreview.Vector).spec
        assertEquals(24f, v.viewportWidth)
        assertEquals(1, v.paths.size)
        assertEquals("M12,2L2,22h20z", v.paths[0].pathData)
        assertEquals(0xFF6200EEL, v.paths[0].fillColor)
    }

    @Test
    fun parsesSelectorPicksDefaultAndResolvesNestedDrawableRef() {
        val xml = """
            <selector xmlns:android="http://schemas.android.com/apk/res/android">
              <item android:state_pressed="true" android:drawable="@drawable/ic_logo"/>
              <item android:drawable="@drawable/bg_round"/>
            </selector>
        """.trimIndent()
        val d = DrawablePreviewParser.parse(xml, resolver)
        assertTrue(d is DrawablePreview.States)
        val states = d as DrawablePreview.States
        assertEquals(2, states.states.size)
        // pressed item → a bitmap ref; default (no state) → the nested oval shape.
        assertTrue(states.states[0].drawable is DrawablePreview.BitmapRef)
        assertTrue(states.defaultLayer is DrawablePreview.Shape)
        assertEquals("/proj/res/drawable/ic_logo.png", (states.states[0].drawable as DrawablePreview.BitmapRef).filePath)
    }

    @Test
    fun parsesLayerListWithInsets() {
        val xml = """
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
              <item android:drawable="@color/primary"/>
              <item android:left="4dp" android:top="4dp">
                <shape android:shape="rectangle"><solid android:color="#FFFFFFFF"/></shape>
              </item>
            </layer-list>
        """.trimIndent()
        val layers = (DrawablePreviewParser.parse(xml, resolver) as DrawablePreview.Layers).layers
        assertEquals(2, layers.size)
        assertEquals(0xFF6200EEL, (layers[0].drawable as DrawablePreview.SolidColor).color)
        assertEquals(4f, layers[1].insetLeftDp)
    }

    @Test
    fun colorDrawableAndUnknownAndMalformed() {
        val color = DrawablePreviewParser.parse(
            """<color xmlns:android="http://schemas.android.com/apk/res/android" android:color="#FF112233"/>""",
        )
        assertEquals(0xFF112233L, (color as DrawablePreview.SolidColor).color)

        assertTrue(DrawablePreviewParser.parse("<unknown-root/>") is DrawablePreview.Unsupported)
        assertTrue(DrawablePreviewParser.parse("<<not xml") is DrawablePreview.Unsupported)
    }

    @Test
    fun colorHexForms() {
        assertEquals(0xFFFF0000L, AndroidColor.parseHex("#F00"))
        assertEquals(0x88FF0000L, AndroidColor.parseHex("#8F00")) // each nibble expands: 8 → 0x88
        assertEquals(0xFF123456L, AndroidColor.parseHex("#123456"))
        assertEquals(0x12345678L, AndroidColor.parseHex("#12345678"))
        assertNull(AndroidColor.parseHex("#xyz"))
        assertNull(AndroidColor.parseHex("notacolor"))
        assertEquals(0x00000000L, AndroidColor.framework("transparent"))
    }

    @Test
    fun colorResourcesExtractsSwatches() {
        val xml = """
            <resources>
              <color name="primary">#FF6200EE</color>
              <color name="alias">@color/primary</color>
              <color name="unresolved">@color/missing</color>
            </resources>
        """.trimIndent()
        val entries = ColorResources.parse(xml, object : DrawableResolver by DrawableResolver.NONE {
            override fun resolveColor(ref: String): Long? = if (ref.endsWith("/primary")) 0xFF6200EEL else null
        })
        assertEquals(3, entries.size)
        assertEquals(0xFF6200EEL, entries[0].argb)
        assertEquals(0xFF6200EEL, entries[1].argb)
        assertNull(entries[2].argb)
    }
}
