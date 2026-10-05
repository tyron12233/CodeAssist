package dev.ide.android.support.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `<animation-list>` and `<animated-vector>` parse into playable models instead of an "unsupported" placeholder. */
class AnimatedDrawableParserTest {

    private val vectorXml = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android" android:name="root"
            android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
          <group android:name="spinner" android:pivotX="12" android:pivotY="12">
            <path android:name="arc" android:pathData="@string/arc_path" android:strokeColor="#FF000000"
                android:strokeWidth="2" android:trimPathEnd="0.5"/>
          </group>
        </vector>
    """.trimIndent()

    private val resolver = object : DrawableResolver {
        override fun resolveColor(ref: String): Long? = if (ref.endsWith("/accent")) 0xFF03DAC5L else null
        override fun resolveDimenDp(ref: String): Float? = null
        override fun resolveDrawable(ref: String): ResolvedDrawable? = when (ref.substringAfterLast('/')) {
            "frame_a" -> ResolvedDrawable.BitmapFile("drawable", "frame_a", "/res/drawable/frame_a.png")
            "spinner" -> ResolvedDrawable.Xml(vectorXml)
            else -> null
        }
        override fun resolveXml(ref: String): String? = when (ref) {
            "@animator/rotate" -> """
                <objectAnimator xmlns:android="http://schemas.android.com/apk/res/android"
                    android:propertyName="rotation" android:valueFrom="0" android:valueTo="360"
                    android:duration="1000" android:repeatCount="infinite"
                    android:interpolator="@android:interpolator/linear"/>
            """.trimIndent()
            "@interpolator/custom" -> """
                <pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"
                    android:controlX1="0.4" android:controlY1="0" android:controlX2="0.2" android:controlY2="1"/>
            """.trimIndent()
            else -> null
        }
        override fun resolveValue(ref: String): String? = if (ref == "@string/arc_path") "M2,12 A10,10 0 1,1 22,12" else null
    }

    @Test
    fun animationListKeepsFramesDurationsAndOneShot() {
        val xml = """
            <animation-list xmlns:android="http://schemas.android.com/apk/res/android" android:oneshot="true">
              <item android:drawable="@drawable/frame_a" android:duration="120"/>
              <item android:duration="80"><shape><solid android:color="#FF0000"/></shape></item>
              <item android:drawable="@drawable/missing" android:duration="50"/>
            </animation-list>
        """.trimIndent()
        val d = assertIs<DrawablePreview.Frames>(DrawablePreviewParser.parse(xml, resolver))
        assertTrue(d.oneShot)
        assertEquals(listOf(120, 80, 50), d.frames.map { it.durationMs })
        assertIs<DrawablePreview.BitmapRef>(d.frames[0].drawable)
        assertIs<DrawablePreview.Shape>(d.frames[1].drawable)
    }

    @Test
    fun animatedVectorResolvesItsVectorTargetsAndAnimators() {
        val xml = """
            <animated-vector xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="@drawable/spinner">
              <target android:name="spinner" android:animation="@animator/rotate"/>
              <target android:name="unknown" android:animation="@animator/missing"/>
            </animated-vector>
        """.trimIndent()
        val d = assertIs<DrawablePreview.AnimatedVector>(DrawablePreviewParser.parse(xml, resolver))
        assertEquals("root", d.vector.name)
        val group = assertIs<VectorGroup>(d.vector.nodes.single())
        assertEquals("spinner", group.name)
        val path = assertIs<VectorPath>(group.children.single())
        assertEquals("M2,12 A10,10 0 1,1 22,12", path.pathData) // from the @string resource
        assertEquals(0.5f, path.trimPathEnd)

        val target = d.targets.single()
        assertEquals("spinner", target.name)
        val rotate = assertIs<AnimatorSpec.Property>(target.animator)
        assertEquals("rotation", rotate.property)
        assertEquals(1000L, rotate.durationMs)
        assertEquals(-1, rotate.repeatCount)
        assertEquals(InterpolatorSpec.LINEAR, rotate.interpolator)
        assertEquals(listOf(AnimatedValue.Number(0f), AnimatedValue.Number(360f)), rotate.keyframes.map { it.value })
    }

    @Test
    fun inlineAaptAttrsCarryTheVectorAnimatorsAndInterpolator() {
        val xml = """
            <animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:aapt="http://schemas.android.com/aapt">
              <aapt:attr name="android:drawable">
                <vector android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
                  <path android:name="check" android:pathData="M4,12 L9,17 L20,6" android:strokeColor="#000"
                      android:strokeWidth="2" android:fillColor="#00000000"/>
                </vector>
              </aapt:attr>
              <target android:name="check">
                <aapt:attr name="android:animation">
                  <set android:ordering="sequentially">
                    <objectAnimator android:propertyName="trimPathEnd" android:valueFrom="0" android:valueTo="1"
                        android:duration="400" android:interpolator="@interpolator/custom"/>
                    <objectAnimator android:propertyName="strokeColor" android:valueTo="@color/accent"
                        android:duration="200" android:startOffset="50">
                      <aapt:attr name="android:interpolator">
                        <pathInterpolator android:controlX1="0.5" android:controlY1="0"/>
                      </aapt:attr>
                    </objectAnimator>
                  </set>
                </aapt:attr>
              </target>
            </animated-vector>
        """.trimIndent()
        val d = assertIs<DrawablePreview.AnimatedVector>(DrawablePreviewParser.parse(xml, resolver))
        assertEquals("check", assertIs<VectorPath>(d.vector.nodes.single()).name)
        val set = assertIs<AnimatorSpec.Set>(d.targets.single().animator)
        assertTrue(set.sequential)
        val trim = assertIs<AnimatorSpec.Property>(set.children[0])
        assertEquals(InterpolatorKind.CUBIC, trim.interpolator.kind)
        assertEquals(listOf(0.4f, 0f, 0.2f, 1f), trim.interpolator.points)

        val color = assertIs<AnimatorSpec.Property>(set.children[1])
        assertEquals(50L, color.startOffsetMs)
        // No valueFrom: the animation starts from the property's current value.
        assertNull(color.keyframes[0].value)
        assertEquals(AnimatedValue.Color(0xFF03DAC5L), color.keyframes[1].value)
        // A quadratic pathInterpolator becomes the equivalent cubic.
        assertEquals(InterpolatorKind.CUBIC, color.interpolator.kind)
        assertEquals(1f / 3f, color.interpolator.points[0], 1e-6f)
    }

    @Test
    fun propertyValuesHoldersAndKeyframesBecomeOneAnimatorPerProperty() {
        val xml = """
            <animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:aapt="http://schemas.android.com/aapt" android:drawable="@drawable/spinner">
              <target android:name="spinner">
                <aapt:attr name="android:animation">
                  <objectAnimator android:duration="600">
                    <propertyValuesHolder android:propertyName="scaleX" android:valueFrom="1" android:valueTo="0.5"/>
                    <propertyValuesHolder android:propertyName="scaleY">
                      <keyframe android:fraction="0" android:value="1"/>
                      <keyframe android:fraction="0.25" android:value="1.2"/>
                      <keyframe android:value="0.5"/>
                    </propertyValuesHolder>
                  </objectAnimator>
                </aapt:attr>
              </target>
            </animated-vector>
        """.trimIndent()
        val d = assertIs<DrawablePreview.AnimatedVector>(DrawablePreviewParser.parse(xml, resolver))
        val set = assertIs<AnimatorSpec.Set>(d.targets.single().animator)
        val props = set.children.map { assertIs<AnimatorSpec.Property>(it) }
        assertEquals(listOf("scaleX", "scaleY"), props.map { it.property })
        assertTrue(props.all { it.durationMs == 600L })
        assertEquals(listOf(0f, 0.25f, 1f), props[1].keyframes.map { it.fraction })
    }

    @Test
    fun animatedSelectorShowsItsStatesAndAnUnresolvedVectorSaysSo() {
        val selector = """
            <animated-selector xmlns:android="http://schemas.android.com/apk/res/android">
              <item android:id="@+id/on" android:state_checked="true" android:drawable="@drawable/spinner"/>
              <item android:id="@+id/off"><shape><solid android:color="#FF0000"/></shape></item>
              <transition android:fromId="@id/off" android:toId="@id/on" android:drawable="@drawable/spinner"/>
            </animated-selector>
        """.trimIndent()
        val states = assertIs<DrawablePreview.States>(DrawablePreviewParser.parse(selector, resolver))
        assertEquals(2, states.states.size)
        assertIs<DrawablePreview.Shape>(states.defaultLayer)

        val missing = """<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"/>"""
        assertIs<DrawablePreview.Unsupported>(DrawablePreviewParser.parse(missing, resolver))
    }
}
