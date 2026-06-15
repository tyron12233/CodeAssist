package dev.ide.android.support.metadata

import dev.ide.android.support.resources.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSdkMetadataTest {

    // A miniature framework attrs.xml: View + TextView own attrs, LinearLayout child layout params.
    private val attrsXml = """
        <resources>
            <attr name="text" format="string"/>
            <attr name="orientation">
                <enum name="horizontal" value="0"/>
                <enum name="vertical" value="1"/>
            </attr>
            <declare-styleable name="View">
                <attr name="id" format="reference"/>
                <attr name="background" format="reference|color"/>
                <attr name="visibility">
                    <enum name="visible" value="0"/>
                    <enum name="gone" value="2"/>
                </attr>
            </declare-styleable>
            <declare-styleable name="TextView">
                <attr name="text"/>
                <attr name="textColor" format="reference|color"/>
            </declare-styleable>
            <declare-styleable name="LinearLayout">
                <attr name="orientation"/>
            </declare-styleable>
            <declare-styleable name="LinearLayout_Layout">
                <attr name="layout_weight" format="float"/>
            </declare-styleable>
            <declare-styleable name="ViewGroup_MarginLayout">
                <attr name="layout_margin" format="dimension"/>
            </declare-styleable>
        </resources>
    """.trimIndent()

    private fun metadata(): AndroidSdkMetadata {
        val parsed = AttrsXmlParser.parse(attrsXml)
        // Button -> TextView -> View ; LinearLayout -> ViewGroup -> View
        val supers = mapOf(
            "Button" to "TextView", "TextView" to "View",
            "LinearLayout" to "ViewGroup", "ViewGroup" to "View",
        )
        val widgets = listOf(
            AndroidSdkMetadata.WidgetInfo("TextView", false),
            AndroidSdkMetadata.WidgetInfo("Button", false),
            AndroidSdkMetadata.WidgetInfo("LinearLayout", true),
        )
        return AndroidSdkMetadata(34, parsed.attrs, parsed.styleables, supers, widgets)
    }

    @Test
    fun attributesInheritUpTheClassHierarchy() {
        val names = metadata().attributesFor("Button", "LinearLayout").map { it.name }
        // Button's own (none extra) + TextView's (text, textColor) + View's (id, background, visibility)
        assertTrue("android:text" in names, names.toString())
        assertTrue("android:textColor" in names)
        assertTrue("android:id" in names)
        assertTrue("android:background" in names)
        // Layout params come from the LinearLayout parent + ViewGroup margins.
        assertTrue("android:layout_weight" in names)
        assertTrue("android:layout_margin" in names)
    }

    @Test
    fun enumAndFormatValuesSurvive() {
        val md = metadata()
        val orientation = md.attribute("LinearLayout", null, "android:orientation")!!
        assertEquals(listOf("horizontal", "vertical"), orientation.enumValues)
        val background = md.attribute("TextView", null, "android:background")!!
        // reference|color → COLOR (from format) merged with the curated hint (drawable+color).
        assertTrue(ResourceType.COLOR in background.resourceTypes, background.resourceTypes.toString())
    }

    @Test
    fun codecRoundTrips() {
        val parsed = AttrsXmlParser.parse(attrsXml)
        val supers = mapOf("Button" to "TextView", "TextView" to "View")
        val widgets = listOf(AndroidSdkMetadata.WidgetInfo("Button", false))
        val text = SdkMetadataCodec.write(34, parsed.attrs, parsed.styleables, supers, widgets)
        val restored = SdkMetadataCodec.read(text)!!
        assertEquals(34, restored.apiLevel)
        // Same inherited own-attribute set after a write→read cycle (margin layout params are always offered).
        val names = restored.attributesFor("Button", null).map { it.name }.toSet()
        assertTrue(
            names.containsAll(setOf("android:text", "android:textColor", "android:id", "android:background", "android:visibility")),
            names.toString(),
        )
    }

    @Test
    fun bundledAssetProvidesRelativeLayoutChildAttributes() {
        val md = AndroidSdkMetadata.bundled()
        assertTrue(md.apiLevel > 0, "the bundled asset should load")
        // The curated catalog never had these; the SDK metadata does (RelativeLayout_Layout styleable).
        val onRelative = md.attributesFor("Button", "RelativeLayout").map { it.name }
        assertTrue("android:layout_below" in onRelative, onRelative.take(40).toString())
        assertTrue("android:layout_toEndOf" in onRelative)
        // Button still inherits TextView/View attributes via the class hierarchy.
        assertTrue("android:text" in onRelative)
        assertTrue(md.isWidgetTag("RelativeLayout") && md.isWidgetTag("TextView"))

        // @id-referencing attributes accept ResourceType.ID (so `@id/…` / `@+id/…` complete).
        assertTrue(ResourceType.ID in md.attribute("Button", "RelativeLayout", "android:layout_below")!!.resourceTypes)
        assertTrue(ResourceType.ID in md.attribute("Button", "RelativeLayout", "android:layout_toEndOf")!!.resourceTypes)
        assertTrue(ResourceType.ID in md.attribute("View", null, "android:id")!!.resourceTypes)
        // …and the framework internal `__removed*` placeholders are never offered.
        assertTrue(onRelative.none { it.startsWith("android:__") }, onRelative.filter { it.startsWith("android:__") }.toString())
    }

    @Test
    fun customViewMetadataOffersOwnAttrsWithAppPrefix() {
        val custom = AttrsXmlParser.parse(
            """<resources>
                 <declare-styleable name="MyView">
                   <attr name="customColor" format="color"/>
                 </declare-styleable>
               </resources>"""
        )
        val md = AndroidSdkMetadata(0, custom.attrs, custom.styleables, emptyMap(), emptyList(), attrPrefix = "app:")
        val names = md.attributesFor("com.example.MyView", null).map { it.name }
        assertTrue("app:customColor" in names, names.toString())
        assertTrue(!md.isWidgetTag("com.example.MyView")) // custom metadata declares no widgets
    }
}
