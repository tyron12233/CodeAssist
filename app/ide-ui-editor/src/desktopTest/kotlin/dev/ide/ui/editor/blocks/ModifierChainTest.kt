package dev.ide.ui.editor.blocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading, writing and drawing Compose `Modifier` chains. */
class ModifierChainTest {

    @Test
    fun chainsParseIntoLinksAndWriteBack() {
        val text = "Modifier.width(100.dp)\n        .height(100.dp)\n        .background(Color.Red, CutCornerShape(4))"
        val chain = assertNotNull(parseModifierChain(text, "        "))
        assertEquals(listOf("width", "height", "background"), chain.links.map { it.name })
        assertEquals("Color.Red, CutCornerShape(4)", chain.links[2].args)
        assertTrue(chain.multiline)
        assertEquals(text, chain.code())
        val one = assertNotNull(parseModifierChain("modifier.padding(8.dp).clickable { onTap() }"))
        assertEquals(ModifierLink("clickable", null, " onTap() "), one.links[1])
        assertEquals("modifier.padding(8.dp).clickable { onTap() }", one.code())
        assertNull(parseModifierChain("Modifier + other"))
        assertNull(parseModifierChain("other.padding(1.dp)"))
    }

    @Test
    fun valuesAreRecognized() {
        assertEquals(0xFFFF0000, colorOf("Color.Red"))
        assertEquals(0xFF6200EE, colorOf("Color(0xFF6200EE)"))
        assertEquals(0xFF336699, colorOf("Color(0x336699)"))
        assertEquals(0xFFFF0000, colorOf("Color.Red.copy(alpha = 0.5f)"))
        assertNull(colorOf("MaterialTheme.colorScheme.primary"))
        assertEquals(Glyph.ShapeCut, shapeGlyphOf("CutCornerShape(4)"))
        assertEquals(Glyph.ShapeCircle, shapeGlyphOf("CircleShape"))
        assertEquals(Glyph.Padding, glyphFor("padding"))
        assertEquals(Glyph.Generic, glyphFor("semantics"))
    }

    @Test
    fun theSchematicAppliesLinksOutsideIn() {
        val chain = parseModifierChain("Modifier.size(100.dp).padding(10.dp).background(Color.Red).border(2.dp, Color.Blue)")!!
        val s = schematicOf(chain)
        assertEquals(100f, s.width); assertEquals(100f, s.height)
        val fill = s.ops.filterIsInstance<SchematicOp.Fill>().single()
        assertEquals(10f, fill.x); assertEquals(80f, fill.w, "the background paints inside the padding")
        assertEquals(0xFF0000FF, s.ops.filterIsInstance<SchematicOp.Stroke>().single().argb)
        assertTrue(schematicOf(parseModifierChain("Modifier.fillMaxWidth()")!!).fillsWidth)
    }
}
