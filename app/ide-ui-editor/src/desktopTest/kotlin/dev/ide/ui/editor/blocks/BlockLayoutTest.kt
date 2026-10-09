package dev.ide.ui.editor.blocks

import androidx.compose.ui.geometry.Offset
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.editor.sampleFile
import dev.ide.ui.editor.typedSampleFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The block canvas's pure layers: the layout pass, the drop-target search and its rules, the outline, and
 * the text the view writes. A fake measurer (7px per character) stands in for real fonts.
 */
class BlockLayoutTest {

    private val g = BlockGeometry(1f)
    private val measure = TextMeasure { text, role -> MText(text, role, text.length * 7f, 14f) }

    private fun methodOf(root: UiBlockNode): UiBlockNode = findFirst(root) { it.label == "method" }!!

    private fun page(sample: Pair<UiBlockNode, String>, hidden: Hidden? = null, gap: Gap? = null): CanvasLayout {
        val (root, src) = sample
        return BlockLayouter(measure, g, hidden = hidden, gap = gap).layoutFunction(methodOf(root), src, emptyList())
    }

    @Test
    fun functionPageStacksItsBodyUnderTheHat() {
        val layout = page(typedSampleFile())
        val hat = layout.stacks.single().blocks.single()
        assertEquals(LKind.Hat, hat.kind)
        val body = assertNotNull(hat.hatBody)
        assertEquals(7, body.blocks.size, "three declarations, a println, a chain, an if, a while")
        assertEquals(body.offsets.sorted(), body.offsets, "statements stack downward")
        for (i in 1 until body.blocks.size) assertEquals(body.offsets[i - 1] + body.blocks[i - 1].h, body.offsets[i], 0.01f)
        val ifBlock = body.blocks.first { it.node?.label == "if" }
        assertEquals(LKind.CBlock, ifBlock.kind)
        assertTrue(ifBlock.sections.any { it is LMouth && it.body.blocks.size == 1 }, "the if's body holds the println")
    }

    @Test
    fun nearestTargetPicksTheClosestGap() {
        val layout = page(typedSampleFile())
        val index = CanvasIndex(layout)
        val targets = index.stackTargets()
        val shape = DragShape(isValue = false, height = 30f, terminal = false, mouth = null)
        val body = index.bodies.first { it.body.blocks.size == 7 }
        val third = body.origin + Offset(0f, body.body.offsets[2])
        val hit = nearestTarget(targets, shape, third + Offset(6f, 4f), threshold = 56f) as DropTarget.Stack
        assertEquals(2, hit.index, "dropping onto the third statement's top inserts before it")
        assertNull(nearestTarget(targets, shape, third + Offset(0f, 2000f), threshold = 56f), "nothing within reach")
    }

    @Test
    fun aTerminalRunOnlyGoesLastAndNothingFollowsOne() {
        val (root, src) = sampleFile()
        val layout = BlockLayouter(measure, g).layoutFunction(methodOf(root), src, emptyList())
        val index = CanvasIndex(layout)
        val targets = index.stackTargets().filterIsInstance<DropTarget.Stack>()
        val mainBody = index.bodies.maxByOrNull { it.body.blocks.size }!!
        // The method ends in `return`: the slot after it is marked, and a plain run may not land there.
        val afterReturn = targets.single { it.ownerId == mainBody.body.ownerId && it.atEnd }
        assertTrue(afterReturn.afterTerminal)
        val plain = DragShape(isValue = false, height = 20f, terminal = false, mouth = null)
        assertTrue(nearestTarget(listOf(afterReturn), plain, afterReturn.anchor, 56f) == null)
        // A `return` being dragged may only go at the end of a list.
        val terminal = DragShape(isValue = false, height = 20f, terminal = true, mouth = null)
        val middle = targets.first { !it.atEnd && it.ownerId == mainBody.body.ownerId }
        assertNull(nearestTarget(listOf(middle), terminal, middle.anchor, 56f))
    }

    @Test
    fun aGapOpensRoomBeforeTheTargetEntry() {
        val sample = typedSampleFile()
        val plain = page(sample).stacks.single().blocks.single().hatBody!!
        val gapped = page(sample, gap = Gap(DocRef.File, plain.ownerId, plain.slotIndex, 1, 40f)).stacks.single().blocks.single().hatBody!!
        assertEquals(plain.offsets[0], gapped.offsets[0])
        assertEquals(plain.offsets[1] + 40f, gapped.offsets[1], 0.01f)
        assertEquals(plain.offsets[1], gapped.gapY!!, 0.01f)
    }

    @Test
    fun aHiddenRunKeepsTheRealIndicesOfWhatRemains() {
        val sample = typedSampleFile()
        val body = page(sample).stacks.single().blocks.single().hatBody!!
        val ids = body.blocks.subList(1, 3).mapNotNull { it.id }.toSet()
        val hiddenBody = page(sample, hidden = Hidden(DocRef.File, ids)).stacks.single().blocks.single().hatBody!!
        assertEquals(listOf(0, 3, 4, 5, 6), hiddenBody.indices)
        assertEquals(7, hiddenBody.total)
        // No wrap target over a list a run is being lifted out of.
        assertTrue(CanvasIndex(page(sample, hidden = Hidden(DocRef.File, ids))).stackTargets().none { it is DropTarget.Wrap && it.count == 7 })
    }

    @Test
    fun socketsTakeOnlyCompatibleValues() {
        assertTrue(accepts(ValueShape.Boolean, ValueShape.Boolean))
        assertTrue(accepts(ValueShape.Boolean, ValueShape.Unknown))
        assertTrue(!accepts(ValueShape.Boolean, ValueShape.Number))
        assertTrue(accepts(ValueShape.Unknown, ValueShape.Text))
        assertTrue(!accepts(ValueShape.Type, ValueShape.Unknown))
        val index = CanvasIndex(page(typedSampleFile()))
        assertTrue(index.socketTargets().isNotEmpty())
    }

    @Test
    fun outlineListsTheClassAndItsFunction() {
        val (root, src) = typedSampleFile()
        val outline = buildOutline(root, src, kotlin = false)
        val group = outline.groups.single()
        assertEquals("Typed", group.title)
        assertEquals("demo", group.functions.single().name)
        assertEquals(7, group.functions.single().statements)
    }

    @Test
    fun scratchStacksRoundTrip() {
        val stacks = listOf(
            ScratchStack(1, "A/Function:run", 10f, 20.5f, false, "foo();\nbar(\"x y\");"),
            ScratchStack(2, "", 0f, 0f, true, "a + b"),
        )
        val back = ScratchCodec.decode(ScratchCodec.encode(stacks))
        assertEquals(stacks.map { it.copy(key = 0) }, back.map { it.copy(key = 0) })
        assertEquals(emptyList(), ScratchCodec.decode("not a stacks file"))
    }

    @Test
    fun snippetsWrapAndUnwrap() {
        for (kotlin in listOf(true, false)) for (value in listOf(true, false)) {
            val w = SnippetWrap(kotlin)
            assertEquals("x()", w.unwrap(w.wrap("x()", value), value))
        }
        assertNull(SnippetWrap(true).unwrap("fun broken", false))
    }

    @Test
    fun functionHeadersRoundTripThroughTheForm() {
        val kt = parseFunctionHeader("private suspend fun load(id: Int, name: String): User", kotlin = true)!!
        assertEquals("load", kt.name)
        assertEquals(listOf(ParamSpec("id", "Int"), ParamSpec("name", "String")), kt.params)
        assertEquals("private suspend fun load(id: Int, name: String): User", functionHeader(kt, kotlin = true))
        val java = parseFunctionHeader("public static int sum(int[] xs, int from)", kotlin = false)!!
        assertEquals("sum", java.name)
        assertEquals("public static int sum(int[] xs, int from)", functionHeader(java, kotlin = false))
        assertEquals("@Composable\nfun Greeting(name: String)", functionHeader(FunctionSpec("@Composable", "", "Greeting", listOf(ParamSpec("name", "String")), "Unit"), true))
    }

    @Test
    fun declarationsAreWrittenPerLanguage() {
        assertEquals("private var count: Int = 0", variableCode(VariableSpec("private", "var", "count", "Int", "0"), kotlin = true))
        assertEquals("val name = \"x\"", variableCode(VariableSpec("", "val", "name", "", "\"x\""), kotlin = true))
        assertEquals("private int count = 0;", variableCode(VariableSpec("private", "", "count", "int", "0"), kotlin = false))
        assertEquals("if (a) {\n}", withoutBody("if (a) {\n$BODY\n}"))
        assertEquals("a();\nif (x) {\n    b();\n}", dedent("a();\n        if (x) {\n            b();\n        }", "        "))
    }
}
