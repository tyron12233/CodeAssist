package dev.ide.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.ide.block.BlockEdit
import dev.ide.block.BlockId
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockRef
import dev.ide.block.BlockTemplate
import dev.ide.block.Delete
import dev.ide.block.DeleteRange
import dev.ide.block.InsertTemplate
import dev.ide.block.Move
import dev.ide.block.MoveRange
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.Wrap
import dev.ide.block.WrapRange
import dev.ide.block.impl.BlockProjectionEngine
import dev.ide.block.impl.JavaBlockMapping
import dev.ide.block.impl.KotlinBlockMapping
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.testkit.InMemoryVirtualFile
import dev.ide.testkit.TestDocument
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.editor.blocks.BlockGeometry
import dev.ide.ui.editor.blocks.BlockSite
import dev.ide.ui.editor.blocks.CanvasIndex
import dev.ide.ui.editor.blocks.DocRef
import dev.ide.ui.editor.blocks.DropTarget
import dev.ide.ui.editor.blocks.FunctionSpec
import dev.ide.ui.editor.blocks.MText
import dev.ide.ui.editor.blocks.OutlineFunction
import dev.ide.ui.editor.blocks.PaletteTemplate
import dev.ide.ui.editor.blocks.ParamSpec
import dev.ide.ui.editor.blocks.TextMeasure
import dev.ide.ui.editor.blocks.VariableSpec
import dev.ide.ui.editor.blocks.BODY
import dev.ide.ui.editor.core.EditorSession

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The block editor's gesture-to-edit flow, end to end over real parsed Kotlin and the real projection engine:
 * a lifted run is dropped on a connection, on empty canvas (becoming a scratch stack) or back again, a palette
 * template is inserted or wrapped around a body, and the forms add declarations. What is asserted is the
 * buffer's text afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockEditorFlowTest {

    private val path = "/project/src/Main.kt"

    private val source = """
        package demo

        val count = 0

        fun render(ready: Boolean) {
            first()
            second()
            if (ready) {
                inside(count)
            }
        }
    """.trimIndent()

    @Test
    fun moveAStatementIntoAnotherBody() = flow { m ->
        val fn = function(m, "render")
        val inside = placedStatement(m, fn, "inside(count)")
        val drag = m.startDrag(CanvasIndex.Hit.Block(inside), index(m, fn), inside.rect.topLeft)!!
        val target = stackTargets(m, fn, drag).firstStack { it.ownerId == bodyIdOf(m, "render") && it.index == 0 }
        m.drop(drag, target, Offset.Zero, trash = false, fn)
        settle(m)
        assertTrue(text(m).contains("{\n    inside(count)\n    first()"), text(m))
        assertTrue(text(m).contains("if (ready) {\n    }") || text(m).contains("if (ready) {}") || text(m).contains("if (ready) {\n\n    }"), text(m))
    }

    @Test
    fun aLooseDropMakesAScratchStackAndItComesBack() = flow { m ->
        var fn = function(m, "render")
        val second = placedStatement(m, fn, "second()")
        val drag = m.startDrag(CanvasIndex.Hit.Block(second), index(m, fn), second.rect.topLeft)!!
        // `second()` lifts with everything below it.
        m.drop(drag, null, Offset(600f, 40f), trash = false, fn)
        settle(m)
        assertTrue("second()" !in text(m) && "if (ready)" !in text(m), text(m))
        assertEquals(1, m.scratch.size)
        assertTrue(m.scratch.single().text.startsWith("second()\nif (ready) {"), m.scratch.single().text)

        // Drag the whole scratch stack back to the end of the function.
        fn = function(m, "render")
        val layout = m.layoutPage(fn, null, null)
        val idx = CanvasIndex(layout)
        val top = idx.blocks.first { it.site.doc is DocRef.Scratch && (it.site as? BlockSite.InList)?.index == 0 }
        val back = m.startDrag(CanvasIndex.Hit.Block(top), idx, top.rect.topLeft)!!
        val end = stackTargets(m, fn, back).firstStack { it.doc == DocRef.File && it.ownerId == bodyIdOf(m, "render") && it.atEnd }
        m.drop(back, end, Offset.Zero, trash = false, fn)
        settle(m)
        assertEquals(source, text(m), "the stack went back where it came from")
        assertTrue(m.scratch.isEmpty())
    }

    @Test
    fun aPaletteTemplateInsertsAndWraps() = flow { m ->
        var fn = function(m, "render")
        val drag = templateDrag(m, PaletteTemplate("repeat(3) {\n$BODY\n}", false))
        val end = stackTargets(m, fn, drag).firstStack { it.ownerId == bodyIdOf(m, "render") && it.atEnd }
        m.drop(drag, end, Offset.Zero, trash = false, fn)
        settle(m)
        assertTrue(text(m).contains("    }\n    repeat(3) {\n    }\n}"), text(m))

        fn = function(m, "render")
        val wrapper = templateDrag(m, PaletteTemplate("try {\n$BODY\n} catch (e: Exception) {\n}", false))
        val wrap = stackTargets(m, fn, wrapper).filterIsInstance<DropTarget.Wrap>().first()
        m.drop(wrapper, wrap, Offset.Zero, trash = false, fn)
        settle(m)
        assertTrue(text(m).contains("fun render(ready: Boolean) {\n    try {\n        first()\n        second()"), text(m))
        assertTrue(text(m).contains("    } catch (e: Exception) {\n    }\n}"), text(m))
    }

    @Test
    fun aValueMovesBetweenSockets() = flow { m ->
        val fn = function(m, "render")
        val idx = index(m, fn)
        val count = idx.blocks.first { it.site is BlockSite.InSocket && it.block.node?.let { n -> text(m).substring(n.start, n.end) } == "count" }
        val drag = m.startDrag(CanvasIndex.Hit.Block(count), idx, count.rect.topLeft)!!
        val shape = drag.shape
        val sockets = CanvasIndex(m.layoutPage(fn, dev.ide.ui.editor.blocks.Hidden(DocRef.File, setOf(count.block.id!!)), null)).socketTargets()
            .filterIsInstance<DropTarget.Socket>()
        // The `if` condition holds `ready`; drop `count` onto the empty socket it leaves... into `ready`'s socket.
        val condition = sockets.first { s -> s.shape == dev.ide.ui.editor.blocks.ValueShape.Boolean }
        assertTrue(shape.isValue)
        m.drop(drag, condition, Offset.Zero, trash = false, fn)
        settle(m)
        assertTrue(text(m).contains("if (count) {\n        inside()"), text(m))
    }

    @Test
    fun formsAddAFunctionAndAVariable() = flow { m ->
        val outline = m.outline!!
        m.saveFunction(outline, FunctionFormState(null, null, ""), FunctionSpec("", "private", "load", listOf(ParamSpec("id", "Int")), "String"), "")
        settle(m)
        assertTrue(text(m).trimEnd().endsWith("}\nprivate fun load(id: Int): String {\n}"), text(m))
        assertEquals("load", m.openName, "the new function opens")

        m.saveVariable(m.outline!!, VariableFormState(null, ""), VariableSpec("", "var", "total", "Int", "1"), "")
        settle(m)
        assertTrue(text(m).contains("val count = 0\nvar total: Int = 1\n"), text(m))
    }

    @Test
    fun formFieldsCompleteFromTheEngineAndCarryTheirImport() = runTest {
        val session = EditorSession(source, languageFor("Main.kt"))
        // Completion answers like the Kotlin engine does at a type position: a class plus its auto-import.
        val backend = object : EngineBackend() {
            override suspend fun complete(path: String, text: String, offset: Int): dev.ide.ui.backend.UiCompletionResult {
                var start = offset
                while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                val import = UiTextEdit(text.indexOf("\n\n") + 1, text.indexOf("\n\n") + 1, "import android.os.Bundle\n")
                val items = listOf(
                    dev.ide.ui.backend.UiCompletionItem("Bundle", "Bundle", null, container = "android.os", kind = dev.ide.ui.backend.UiCompletionKind.Class, sortPriority = 0, additionalEdits = listOf(import)),
                    dev.ide.ui.backend.UiCompletionItem("bundleOf", "bundleOf()", null, kind = dev.ide.ui.backend.UiCompletionKind.Method, sortPriority = 0),
                )
                return dev.ide.ui.backend.UiCompletionResult(items, start, offset)
            }
        }
        val m = BlockEditorModel(path, session, backend, this)
        m.measure = TextMeasure { t, role -> MText(t, role, t.length * 7f, 14f) }
        m.geometry = BlockGeometry(1f)
        m.reproject()
        val types = m.suggest(dev.ide.ui.editor.blocks.FieldKind.Type, "Bun", "")
        assertEquals(listOf("Bundle"), types.map { it.text }, "a type field only takes types")
        assertEquals("android.os", types.single().detail)
        val values = m.suggest(dev.ide.ui.editor.blocks.FieldKind.Value, "bun", "")
        assertTrue(values.any { it.text == "bundleOf()" })

        m.saveVariable(m.outline!!, VariableFormState(null, ""), VariableSpec("", "var", "state", "Bundle", ""), "", types.single().extra)
        advanceUntilIdle(); m.reproject(); advanceUntilIdle()
        assertTrue(text(m).contains("import android.os.Bundle\n"), text(m))
        assertTrue(text(m).contains("val count = 0\nvar state: Bundle\n"), text(m))
    }

    @Test
    fun aLocalVariableGoesAtTheTopOfTheOpenFunction() = flow { m ->
        val fn = function(m, "render")
        m.saveVariable(m.outline!!, VariableFormState(null, dev.ide.ui.editor.blocks.LOCAL_PLACE), VariableSpec("private", "var", "taps", "Int", "0"), dev.ide.ui.editor.blocks.LOCAL_PLACE)
        settle(m)
        assertTrue(text(m).contains("fun render(ready: Boolean) {\n    var taps: Int = 0\n    first()"), text(m))
        // The local is now offered as get/set blocks on the page's palette.
        val vars = m.paletteEntries(dev.ide.ui.editor.blocks.PaletteCategory.Variables, m.outline!!, function(m, "render")).map { it.template.text }
        assertTrue("taps" in vars && "val name = 0" in vars, vars.toString())
    }

    @Test
    fun namesAndModifiersAreSuggested() = flow { m ->
        assertEquals(listOf("bundle"), dev.ide.ui.editor.blocks.nameSuggestions("Bundle?", "").map { it.text })
        assertEquals(listOf("users", "userList", "list"), dev.ide.ui.editor.blocks.nameSuggestions("List<User>", "").map { it.text })
        assertEquals(listOf("state"), dev.ide.ui.editor.blocks.nameSuggestions("SavedState", "st").map { it.text })
        val mods = m.suggest(dev.ide.ui.editor.blocks.FieldKind.Modifier, "private o", "")
        assertTrue(mods.any { it.text == "private open " } && mods.any { it.text == "private override " }, mods.map { it.text }.toString())
    }

    @Test
    fun aModifierChainOpensItsSheetAndSavesBack() = runTest {
        val src = """
            package demo

            fun Screen() {
                Card(modifier = Modifier.width(100.dp)
                    .height(100.dp)
                    .background(Color.Red, CutCornerShape(4))) {
                    Greeting("hi")
                }
            }
        """.trimIndent()
        val session = EditorSession(src, languageFor("Main.kt"))
        val m = BlockEditorModel(path, session, EngineBackend(), this)
        m.measure = TextMeasure { t, role -> MText(t, role, t.length * 7f, 14f) }
        m.geometry = BlockGeometry(1f)
        m.reproject()
        val fn = function(m, "Screen")
        val idx = index(m, fn)
        val card = idx.blocks.first { it.block.modifierChain }
        // `100.dp` reads as a number with a unit, not as a member access.
        assertTrue(idx.blocks.any { it.block.kind == dev.ide.ui.editor.blocks.LKind.Literal && it.block.shape == dev.ide.ui.editor.blocks.ValueShape.Number })
        m.tap(CanvasIndex.Hit.Block(card), idx, fn)
        val sheet = assertNotNull(m.modifierSheet)
        assertEquals(listOf("width", "height", "background"), sheet.chain.links.map { it.name })
        // Drop the height, add padding, save.
        val edited = sheet.chain.copy(links = sheet.chain.links.filter { it.name != "height" } + dev.ide.ui.editor.blocks.ModifierLink("padding", "16.dp"))
        m.modifierSheet = null
        m.edit(sheet.doc, listOf(UiBlockEdit.ReplaceSlot(sheet.ownerId, sheet.slotIndex, edited.code())))
        advanceUntilIdle(); m.reproject(); advanceUntilIdle()
        assertTrue(text(m).contains("Card(modifier = Modifier.width(100.dp)\n        .background(Color.Red, CutCornerShape(4))\n        .padding(16.dp)) {"), text(m))
    }

    @Test
    fun anEmptyCallShowsItsParametersAndTakesThem() = runTest {
        val src = """
            package demo

            fun Screen() {
                Text()
                Text("hi", fontSize = 18.sp)
            }
        """.trimIndent()
        val session = EditorSession(src, languageFor("Main.kt"))
        // Signature help as the Kotlin engine answers it for Compose's Text.
        val backend = object : EngineBackend() {
            override suspend fun signatureHelp(path: String, text: String, offset: Int) = dev.ide.ui.backend.UiSignatureHelp(listOf(
                dev.ide.ui.backend.UiSignature(
                    "Text(...)",
                    listOf("text: String", "modifier: Modifier = \u2026", "fontSize: TextUnit = \u2026", "maxLines: Int = \u2026").map { dev.ide.ui.backend.UiSignatureParam(it) },
                ),
            ))
        }
        val m = BlockEditorModel(path, session, backend, this)
        m.measure = TextMeasure { t, role -> MText(t, role, t.length * 7f, 14f) }
        m.geometry = BlockGeometry(1f)
        m.reproject()
        val fn = function(m, "Screen")
        m.loadSignatures(fn)
        var idx = index(m, fn)
        // `Text()` folds its three optional parameters behind one chip; tapping it unfolds them, `−` folds again.
        fun chips(kind: dev.ide.ui.editor.blocks.ArgChipKind) = idx.actions.filter { (it.item as? dev.ide.ui.editor.blocks.LArgChip)?.kind == kind }
        fun holes() = idx.actions.mapNotNull { it.item as? dev.ide.ui.editor.blocks.LArgHole }.map { it.name }
        val more = chips(dev.ide.ui.editor.blocks.ArgChipKind.More).first()
        assertEquals("+3", (more.item as dev.ide.ui.editor.blocks.LArgChip).text.text)
        assertTrue("maxLines" !in holes(), "optional parameters start folded")
        m.tap(CanvasIndex.Hit.Action(more), idx, fn)
        idx = index(m, fn)
        assertTrue(holes().containsAll(listOf("modifier", "fontSize", "maxLines")), holes().toString())
        m.tap(CanvasIndex.Hit.Action(chips(dev.ide.ui.editor.blocks.ArgChipKind.Fewer).first()), idx, fn)
        idx = index(m, fn)
        assertTrue("maxLines" !in holes())
        val empty = idx.blocks.first { it.site is BlockSite.InList && it.block.node?.let { n -> text(m).substring(n.start, n.end) } == "Text()" }
        m.tap(CanvasIndex.Hit.Block(empty), idx, fn)
        val sel = assertNotNull(m.selected)
        val sheet = assertNotNull(m.propertiesOf(sel))
        assertEquals(listOf("modifier", "fontSize", "maxLines"), sheet.optional.map { it.param.name })
        m.pickProperty(sheet, sheet.optional.first { it.param.name == "maxLines" })
        val target = assertNotNull(m.editing)
        // The new hole is on the page, where the inline editor opens.
        assertNotNull(m.editRect(index(m, fn)))
        m.commit(target, "2", emptyList())
        settle(m)
        assertTrue(text(m).contains("    Text(maxLines = 2)\n"), text(m))

        // Lifting an argument out removes it with its comma; a later parameter stays a named argument.
        val fn2 = function(m, "Screen")
        m.loadSignatures(fn2)
        val idx2 = index(m, fn2)
        val hi = idx2.blocks.first { it.site is BlockSite.InSocket && it.block.node?.let { n -> text(m).substring(n.start, n.end) } == "\"hi\"" }
        val drag = m.startDrag(CanvasIndex.Hit.Block(hi), idx2, hi.rect.topLeft)!!
        m.drop(drag, null, Offset.Zero, trash = true, fn2)
        settle(m)
        assertTrue(text(m).contains("    Text(fontSize = 18.sp)\n"), text(m))
    }

    @Test
    fun aScopedLambdaOffersWhatItsReceiverAdds() = runTest {
        val src = """
            package demo

            fun Screen() {
                Column {
                    Text("a")
                }
                Box(modifier = Modifier.clickable {
                    tap()
                })
            }
        """.trimIndent()
        val session = EditorSession(src, languageFor("Main.kt"))
        val backend = object : EngineBackend() {
            override suspend fun signatureHelp(path: String, text: String, offset: Int): dev.ide.ui.backend.UiSignatureHelp {
                val callee = Regex("(\\w+)\\($").find(text.substring(0, offset))?.groupValues?.get(1)
                val params = when (callee) {
                    "Column" -> listOf("modifier: Modifier = \u2026", "content: @Composable ColumnScope.() -> Unit")
                    "clickable" -> listOf("enabled: Boolean = \u2026", "onClick: () -> Unit")
                    else -> return dev.ide.ui.backend.UiSignatureHelp(emptyList())
                }
                return dev.ide.ui.backend.UiSignatureHelp(listOf(dev.ide.ui.backend.UiSignature("$callee(...)", params.map { dev.ide.ui.backend.UiSignatureParam(it) })))
            }

            // Inside Column's lambda the receiver adds `weight` and `align`; both places see `Text`.
            override suspend fun complete(path: String, text: String, offset: Int): dev.ide.ui.backend.UiCompletionResult {
                fun item(label: String) = dev.ide.ui.backend.UiCompletionItem(label, "$label()", null, kind = dev.ide.ui.backend.UiCompletionKind.Method, sortPriority = 0)
                val inside = "Text(\"a\")" in text.substring(0, offset)
                val items = listOf(item("Text(text: String)")) + if (inside) listOf(item("align(alignment: Alignment.Horizontal)"), item("weight(weight: Float)")) else emptyList()
                return dev.ide.ui.backend.UiCompletionResult(items, offset, offset)
            }
        }
        val m = BlockEditorModel(path, session, backend, this)
        m.measure = TextMeasure { t, role -> MText(t, role, t.length * 7f, 14f) }
        m.geometry = BlockGeometry(1f)
        m.reproject()
        val fn = function(m, "Screen")
        m.loadSignatures(fn)
        assertEquals("ColumnScope", dev.ide.ui.editor.blocks.lambdaReceiver("@Composable ColumnScope.() -> Unit"))
        assertEquals(null, dev.ide.ui.editor.blocks.lambdaReceiver("() -> Unit"))

        // Column's mouth carries the scope handle; the unscoped clickable lambda does not.
        var idx = index(m, fn)
        val scopes = idx.actions.filter { (it.item as? dev.ide.ui.editor.blocks.LArgChip)?.kind == dev.ide.ui.editor.blocks.ArgChipKind.Scope }
        assertEquals(1, scopes.size)
        m.tap(CanvasIndex.Hit.Action(scopes.single()), idx, fn)
        advanceUntilIdle()
        assertTrue(m.scopePickerOpen)
        assertEquals("ColumnScope", m.scopeFocus?.receiver)
        assertEquals(listOf("align(\u2588)", "weight(\u2588)").map { it.replace("\u2588", "") }, m.scopeFunctions?.map { dev.ide.ui.editor.blocks.withoutBody(it.text).replace(Regex("\\(.*\\)"), "()") })
        m.insertInScope(m.scopeFunctions!!.first { it.text.startsWith("weight") })
        settle(m)
        assertTrue(text(m).contains("    Column {\n        Text(\"a\")\n        weight("), text(m))
        assertTrue(!m.scopePickerOpen)

        // The clickable lambda is a C-shaped row on the modifier, with a mouth to drop into and a chip to open it.
        val fn2 = function(m, "Screen")
        m.loadSignatures(fn2)
        idx = index(m, fn2)
        val modifier = idx.blocks.first { it.block.modifierChain && it.block.kind == dev.ide.ui.editor.blocks.LKind.CBlock }
        assertTrue(idx.blocks.any { b -> b.block.node?.let { n -> text(m).substring(n.start, n.end) } == "tap()" && b.rect.top > modifier.rect.top })
        val open = idx.actions.first { (it.item as? dev.ide.ui.editor.blocks.LArgChip)?.kind == dev.ide.ui.editor.blocks.ArgChipKind.Open }
        m.tap(CanvasIndex.Hit.Action(open), idx, fn2)
        assertEquals(1, m.lambdaPages.size)
        assertNotNull(m.openLambda())
        val page = index(m, fn2)
        val hat = page.blocks.first { it.block.kind == dev.ide.ui.editor.blocks.LKind.Hat }
        assertTrue(page.blocks.any { b -> b.block.node?.let { n -> text(m).substring(n.start, n.end) } == "tap()" && b.rect.top >= hat.rect.top })
        assertTrue(page.blocks.none { b -> b.block.node?.let { n -> text(m).substring(n.start, n.end) } == "Text(\"a\")" }, "the lambda page shows only the lambda")
        m.closePage()
        assertEquals(0, m.lambdaPages.size)
        assertEquals("Screen", m.openName, "back from a lambda page returns to its function")
    }

    // ---- harness ----

    private fun flow(body: suspend TestScope.(BlockEditorModel) -> Unit) = runTest {
        val session = EditorSession(source, languageFor("Main.kt"))
        val m = BlockEditorModel(path, session, EngineBackend(), this)
        m.measure = TextMeasure { t, role -> MText(t, role, t.length * 7f, 14f) }
        m.geometry = BlockGeometry(1f)
        m.reproject()
        body(m)
    }

    private suspend fun TestScope.settle(m: BlockEditorModel) {
        advanceUntilIdle()
        m.reproject()
        advanceUntilIdle()
    }

    private fun text(m: BlockEditorModel) = m.projectedText

    private fun function(m: BlockEditorModel, name: String): OutlineFunction =
        assertNotNull(m.outline!!.functions.firstOrNull { it.name == name }).also { m.open(it) }

    private fun index(m: BlockEditorModel, fn: OutlineFunction) = CanvasIndex(m.layoutPage(fn, null, null))

    private fun placedStatement(m: BlockEditorModel, fn: OutlineFunction, src: String) =
        index(m, fn).blocks.first { it.site is BlockSite.InList && it.block.node?.let { n -> text(m).substring(n.start, n.end) } == src }

    private fun bodyIdOf(m: BlockEditorModel, fn: String): String =
        dev.ide.ui.editor.blocks.bodyOf(m.outline!!.functions.first { it.name == fn }.node)!!.ownerId

    /** The targets for [drag] from the page laid out without what it lifted, as the canvas computes them. */
    private fun stackTargets(m: BlockEditorModel, fn: OutlineFunction, drag: dev.ide.ui.editor.blocks.ActiveDrag): List<DropTarget> {
        val hidden = when (val p = drag.payload) {
            is dev.ide.ui.editor.blocks.DragPayload.Run -> dev.ide.ui.editor.blocks.Hidden(p.doc, p.ids)
            else -> null
        }
        return CanvasIndex(m.layoutPage(fn, hidden, null)).stackTargets().filterIsInstance<DropTarget>()
            .let { all -> all.filterIsInstance<DropTarget.Stack>() + all.filterIsInstance<DropTarget.Wrap>() }
    }

    private fun List<DropTarget>.firstStack(p: (DropTarget.Stack) -> Boolean): DropTarget.Stack =
        filterIsInstance<DropTarget.Stack>().first(p)

    private suspend fun TestScope.templateDrag(m: BlockEditorModel, t: PaletteTemplate): dev.ide.ui.editor.blocks.ActiveDrag {
        m.paletteFor(m.outline!!, m.outline!!.functions.first())
        advanceUntilIdle()
        val entry = m.paletteEntries(dev.ide.ui.editor.blocks.PaletteCategory.Control, m.outline!!, m.outline!!.functions.first()).firstOrNull { it.template == t }
            ?: run {
                // Not one of the stock templates: project it the way the palette's search does.
                m.searchPalette(t.text); advanceUntilIdle(); m.searchResults.first { it.template == t }
            }
        val projected = assertNotNull(entry.body ?: entry.value?.let { null }, "template ${t.text} projects")
        return dev.ide.ui.editor.blocks.ActiveDrag(
            dev.ide.ui.editor.blocks.DragPayload.Template(t), projected, null, dev.ide.ui.editor.blocks.dragShapeOf(projected, null), Offset.Zero,
        )
    }
}

/** A backend whose block service is the real engine over parsed Kotlin (what the IDE's BlockBackend does). */
internal typealias EngineBackendForTests = EngineBackend

internal open class EngineBackend : StubBackend() {
    private val engine = BlockProjectionEngine(listOf(JavaBlockMapping, KotlinBlockMapping))

    private fun parse(path: String, text: String) =
        KotlinIncrementalParser().parseFull(TestDocument(text, InMemoryVirtualFile(path, text)))

    override fun blocksEnabled(): Boolean = true

    override suspend fun projectBlocks(path: String, text: String): UiBlockNode? = toUi(engine.project(parse(path, text)).root)

    override suspend fun applyBlockEdit(path: String, text: String, edit: UiBlockEdit): List<UiTextEdit> {
        fun ref(id: String) = BlockRef(BlockId(id))
        fun tpl(t: String) = BlockTemplate("t", SlotCategory.STATEMENT, t.replace(UiBlockEdit.BODY_MARKER, BlockTemplate.PLACEHOLDER))
        val e: BlockEdit = when (edit) {
            is UiBlockEdit.SetField -> SetField(ref(edit.blockId), edit.role, edit.text)
            is UiBlockEdit.ReplaceSlot -> ReplaceWithText(SlotRef(BlockId(edit.blockId), edit.slotIndex), edit.text)
            is UiBlockEdit.DeleteBlock -> Delete(ref(edit.blockId))
            is UiBlockEdit.InsertTemplate -> InsertTemplate(SlotRef(BlockId(edit.ownerBlockId), edit.slotIndex, edit.index), tpl(edit.text))
            is UiBlockEdit.WrapInIf -> Wrap(ref(edit.blockId), tpl("if (true) {\n${UiBlockEdit.BODY_MARKER}\n}"))
            is UiBlockEdit.MoveBlock -> Move(ref(edit.blockId), SlotRef(BlockId(edit.toOwnerBlockId), edit.toSlotIndex, edit.toIndex))
            is UiBlockEdit.MoveRange -> MoveRange(ref(edit.blockId), edit.count, SlotRef(BlockId(edit.toOwnerBlockId), edit.toSlotIndex, edit.toIndex))
            is UiBlockEdit.DeleteRange -> DeleteRange(ref(edit.blockId), edit.count)
            is UiBlockEdit.InsertArgument -> dev.ide.block.InsertArgument(ref(edit.blockId), edit.link, edit.index, edit.text)
            is UiBlockEdit.RemoveArgument -> dev.ide.block.RemoveArgument(ref(edit.blockId), edit.slotIndex)
            is UiBlockEdit.WrapRange -> WrapRange(ref(edit.blockId), edit.count, tpl(edit.template))
        }
        val tree = engine.project(parse(path, text))
        return engine.computeEdit(tree, text, e).map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) }
    }

    private val stacks = HashMap<String, String?>()
    override suspend fun loadScratchStacks(path: String): String? = stacks[path]
    override suspend fun saveScratchStacks(path: String, data: String?) { stacks[path] = data }

    private fun toUi(b: BlockNode): UiBlockNode = UiBlockNode(
        b.id.value, b.kind.id, b.label, b.kind.id, b.range.start, b.range.end,
        b.parts.map { p ->
            when (p) {
                is BlockPart.Field -> UiBlockPart.Field(p.field.role, p.field.text, p.field.editable, p.field.range?.start ?: -1, p.field.range?.end ?: -1)
                is BlockPart.Slot -> UiBlockPart.Slot(p.slot.category.name, p.slot.multiple, p.slot.range.start, p.slot.range.end, p.slot.children.map(::toUi), p.slot.valueKind.name.lowercase())
            }
        },
        b.valueKind.name.lowercase(),
    )
}

/**
 * The whole Blocks view over real parsed Kotlin, rendered to PNGs (outline, a function page, the palette)
 * for eyeballing. Not an assertion; output in `<tmpdir>/codeassist-snapshots`.
 */
class BlockEditorScreenSnapshot {
    private val source = """
        package demo

        import androidx.compose.runtime.Composable

        val count = 0
        var total: Int = 1

        fun render(items: List<String>, ready: Boolean) {
            if (ready && items.isNotEmpty()) {
                log("some")
            } else log("none")
            items.forEach { s ->
                println(s)
            }
            when (count) {
                1 -> log("one")
                else -> {}
            }
            val n = if (count > 0) count * 2 else 0
            return
        }

        class Screen(val name: String) {
            init { log("hi") }
            @Composable
            fun Content() {
                Column(modifier = Modifier) {
                    Text("Hello")
                    Button(onClick = {}) {
                        Text("Go")
                    }
                }
            }
        }
    """.trimIndent()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun renderScreens() {
        val session = EditorSession(source, languageFor("Main.kt"))
        val scene = androidx.compose.ui.ImageComposeScene(width = 822, height = 1600, density = androidx.compose.ui.unit.Density(2f)) {
            dev.ide.ui.theme.CodeAssistTheme(dark = true) {
                BlockEditor("/project/src/Main.kt", session, EngineBackend(), androidx.compose.ui.Modifier.fillMaxSize())
            }
        }
        fun settle(ms: Long = 900) { val end = System.currentTimeMillis() + ms; var t = 0L; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(30) } }
        fun shot(name: String) {
            val png = scene.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            java.io.File(OUT, name).writeBytes(png)
        }
        fun tap(xDp: Float, yDp: Float) {
            val p = Offset(xDp * 2, yDp * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, p)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, p)
            settle(400)
        }
        try {
            settle(1500)
            shot("screen-outline.png")
            tap(150f, 64f)           // the first function row
            settle()
            shot("screen-page.png")
            tap(388f, 23f)           // the palette toggle, top right
            settle(2500)
            shot("screen-palette.png")
            tap(388f, 23f)           // close it again

            // A real gesture: mouse-drag `println(s)` out of the forEach lambda onto empty canvas.
            val pressed = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
            fun send(type: androidx.compose.ui.input.pointer.PointerEventType, xDp: Float, yDp: Float, buttons: androidx.compose.ui.input.pointer.PointerButtons) =
                scene.sendPointerEvent(type, Offset(xDp * 2, yDp * 2), type = androidx.compose.ui.input.pointer.PointerType.Mouse, buttons = buttons)
            send(androidx.compose.ui.input.pointer.PointerEventType.Press, 46f, 280f, pressed)
            for (i in 1..20) { send(androidx.compose.ui.input.pointer.PointerEventType.Move, 46f + i * 10f, 280f + i * 20f, pressed); scene.render(0) }
            shot("screen-dragging.png")
            send(androidx.compose.ui.input.pointer.PointerEventType.Release, 246f, 680f, androidx.compose.ui.input.pointer.PointerButtons())
            settle(1500)
            shot("screen-dropped.png")
            val text = session.doc.text
            check("println(s)" !in text) { "println(s) should have left the file:\n$text" }
            check("items.forEach { s ->" in text) { text }
        } finally {
            scene.close()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun renderWideOutline() {
        val activity = """
            package com.example.myapp

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.compose.runtime.Composable

            val greeting = "Hello"
            private var launches: Int = 0

            class MainActivity : ComponentActivity() {
                private val tag = "Main"

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        Greeting("Android")
                    }
                }
            }

            @Composable
            fun Greeting(name: String, modifier: Modifier = Modifier) {
                Text(text = "Hello ${'$'}name!", modifier = modifier)
            }

            @Preview(showBackground = true)
            @Composable
            fun CardPreview() {
                Greeting("Preview")
            }
        """.trimIndent()
        val session = EditorSession(activity, languageFor("MainActivity.kt"))
        val scene = androidx.compose.ui.ImageComposeScene(width = 1500, height = 1000, density = androidx.compose.ui.unit.Density(2f)) {
            dev.ide.ui.theme.CodeAssistTheme(dark = true) {
                BlockEditor("/project/src/MainActivity.kt", session, EngineBackend(), androidx.compose.ui.Modifier.fillMaxSize())
            }
        }
        try {
            val end = System.currentTimeMillis() + 1500; var t = 0L
            while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(30) }
            java.io.File(OUT, "screen-outline-wide.png").writeBytes(scene.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun renderModifierCardAndSheet() {
        val src = """
            package demo

            @Composable
            fun Greeting(name: String) {
                Card(modifier = Modifier.width(100.dp)
                    .height(100.dp)
                    .padding(8.dp)
                    .background(Color.Red, CutCornerShape(4))) {
                    Greeting("jsjds")
                }
            }
        """.trimIndent()
        val session = EditorSession(src, languageFor("Main.kt"))
        val scene = androidx.compose.ui.ImageComposeScene(width = 822, height = 900, density = androidx.compose.ui.unit.Density(2f)) {
            dev.ide.ui.theme.CodeAssistTheme(dark = true) {
                BlockEditor("/project/src/Main.kt", session, EngineBackend(), androidx.compose.ui.Modifier.fillMaxSize())
            }
        }
        fun settle(ms: Long) { val end = System.currentTimeMillis() + ms; var t = 0L; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(30) } }
        fun tap(xDp: Float, yDp: Float) {
            val p = Offset(xDp * 2, yDp * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, p)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, p)
            settle(500)
        }
        try {
            settle(1500)
            tap(150f, 64f)
            settle(800)
            java.io.File(OUT, "screen-modifier-card.png").writeBytes(scene.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }

        val chain = dev.ide.ui.editor.blocks.parseModifierChain("Modifier.width(100.dp)\n    .height(100.dp)\n    .padding(8.dp)\n    .background(Color.Red, CutCornerShape(4))\n    .border(2.dp, Color.White, CutCornerShape(4))")!!
        val sheet = androidx.compose.ui.ImageComposeScene(width = 1000, height = 1500, density = androidx.compose.ui.unit.Density(2f)) {
            dev.ide.ui.theme.CodeAssistTheme(dark = true) {
                dev.ide.ui.editor.blocks.ModifierSheet(chain, "", { _, _, _ -> emptyList() }, { _, _ -> }, {})
            }
        }
        try {
            val end = System.currentTimeMillis() + 800; var t = 0L
            while (System.currentTimeMillis() < end) { sheet.render(t); t += 16_000_000L; Thread.sleep(30) }
            java.io.File(OUT, "screen-modifier-sheet.png").writeBytes(sheet.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        } finally { sheet.close() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun switchingToCodeAndBackKeepsTheOpenPage() {
        val session = EditorSession(source, languageFor("Main.kt"))
        val backend = EngineBackend()
        var showBlocks by androidx.compose.runtime.mutableStateOf(true)
        val scene = androidx.compose.ui.ImageComposeScene(width = 822, height = 1200, density = androidx.compose.ui.unit.Density(2f)) {
            dev.ide.ui.theme.CodeAssistTheme(dark = true) {
                // The tab's view switch: the Blocks view leaves composition while Code shows.
                if (showBlocks) BlockEditor("/project/src/Main.kt", session, backend, androidx.compose.ui.Modifier.fillMaxSize())
                else androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize())
            }
        }
        fun settle(ms: Long) { val end = System.currentTimeMillis() + ms; var t = 0L; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(30) } }
        try {
            settle(1500)
            val p = Offset(150f * 2, 64f * 2)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, p)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, p)
            settle(600)
            val opened = (session.viewStates["blocks"] as BlockEditorModel).openKey
            assertNotNull(opened, "a function page is open")
            showBlocks = false; settle(300)
            showBlocks = true; settle(900)
            assertEquals(opened, (session.viewStates["blocks"] as BlockEditorModel).openKey, "the same page is open after the round trip")
            java.io.File(OUT, "screen-after-switch.png").writeBytes(scene.render(0).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private companion object {
        val OUT = java.io.File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}

