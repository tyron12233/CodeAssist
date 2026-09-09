package dev.ide.android.support.resources

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The drawable-XML completion grammar: root tags, child models, attributes, and folder routing. */
class DrawableXmlCatalogTest {

    @Test
    fun routesByResFolder() {
        assertTrue(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/drawable/bg.xml"))
        assertTrue(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/drawable-night/bg.xml"))
        assertTrue(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/color/states.xml"))
        assertFalse(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/layout/main.xml"))
        assertFalse(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/values/colors.xml"))
        assertFalse(DrawableXmlCatalog.appliesTo("/p/app/src/main/res/drawable/ic.png"))
    }

    @Test
    fun rootTagsAndChildModel() {
        assertContains(DrawableXmlCatalog.childrenOf(null), "shape")
        assertContains(DrawableXmlCatalog.childrenOf(null), "vector")
        assertContains(DrawableXmlCatalog.childrenOf(null), "selector")
        assertContains(DrawableXmlCatalog.childrenOf("shape"), "gradient")
        assertContains(DrawableXmlCatalog.childrenOf("shape"), "corners")
        assertContains(DrawableXmlCatalog.childrenOf("selector"), "item")
        assertContains(DrawableXmlCatalog.childrenOf("vector"), "path")
    }

    @Test
    fun attributesAndValueHints() {
        val shapeAttr = DrawableXmlCatalog.attribute("shape", "android:shape")
        assertTrue(shapeAttr != null && "oval" in shapeAttr.enumValues)

        val solidColor = DrawableXmlCatalog.attribute("solid", "android:color")
        assertTrue(solidColor != null && ResourceType.COLOR in solidColor.resourceTypes)

        // The selector <item> offers state flags + a @drawable reference.
        val itemAttrs = DrawableXmlCatalog.attributesFor("item").map { it.name }
        assertContains(itemAttrs, "android:state_pressed")
        assertContains(itemAttrs, "android:drawable")

        val pathData = DrawableXmlCatalog.attribute("path", "android:pathData")
        assertTrue(pathData != null)
    }
}
