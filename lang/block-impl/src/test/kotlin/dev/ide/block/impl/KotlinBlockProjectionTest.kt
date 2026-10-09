package dev.ide.block.impl

import dev.ide.block.BlockNode
import dev.ide.block.BlockRef
import dev.ide.block.BlockTemplate
import dev.ide.block.BlockTree
import dev.ide.block.InsertArgument
import dev.ide.block.InsertTemplate
import dev.ide.block.RemoveArgument
import dev.ide.block.MoveRange
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.ValueKind
import dev.ide.block.defaultSerialize
import dev.ide.lang.dom.KotlinNodeKinds
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.testkit.InMemoryVirtualFile
import dev.ide.testkit.TestDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin mapping over the real Kotlin neutral DOM, with the Java mapping registered beside it (as in the
 * IDE), so the per-language mapping choice is exercised too.
 */
class KotlinBlockProjectionTest {

    private val engine = BlockProjectionEngine(listOf(JavaBlockMapping, KotlinBlockMapping))

    private val screen = """
        package demo

        val count = 0

        fun render(items: List<String>) {
            if (items.isEmpty()) {
                log("empty")
            } else log("some")
            items.forEach { s ->
                println(s)
            }
            Column(modifier = Modifier) {
                Text("hi")
            }
            when (count) {
                1 -> log("one")
                else -> {}
            }
        }
    """.trimIndent()

    @Test
    fun everyStatementRoundTripsItsSource() {
        // A list slot serializes its entries without the text between them, so compare statement by statement.
        val tree = project(screen)
        for (stmt in bodyOf(tree, "render").slots.single { it.multiple }.children) {
            assertEquals(screen.substring(stmt.range.start, stmt.range.end), defaultSerialize(stmt))
        }
    }

    @Test
    fun functionBodyIsAStatementList() {
        val tree = project(screen)
        val body = bodyOf(tree, "render")
        assertEquals(4, body.slots.single { it.multiple }.children.size, "if, forEach, Column, when")
    }

    @Test
    fun ifCarriesItsConditionAndBodiesDirectly() {
        val tree = project(screen)
        val ifBlock = tree.find { it.kind == KotlinNodeKinds.IF }!!
        assertEquals("if", ifBlock.label)
        val slots = ifBlock.slots
        assertEquals(ValueKind.BOOLEAN, slots[0].valueKind, "the condition expects a boolean")
        assertEquals(SlotCategory.STATEMENT, slots[1].category, "the then-branch is a body")
        assertEquals(NodeKind.BLOCK, slots[1].children.single().kind)
        assertEquals(SlotCategory.STATEMENT, slots[2].category, "a brace-less else is still a body")
    }

    @Test
    fun trailingLambdaIsABodySlotOfTheCall() {
        val tree = project(screen)
        val column = tree.find { it.kind == NodeKind.METHOD_CALL && it.fields.any { f -> f.text == "Column" } }!!
        assertEquals("call", column.label)
        val body = column.slots.single { it.category == SlotCategory.STATEMENT }.children.single()
        val stmts = body.slots.single { it.multiple }.children
        assertEquals("Text(\"hi\")", defaultSerialize(stmts.single()))
        assertEquals(1, column.slots.count { it.category == SlotCategory.ARGUMENT }, "the named argument's value")

        val forEach = tree.find { it.kind == NodeKind.METHOD_CALL && it.fields.any { f -> f.text == "forEach" } }!!
        assertEquals("items", forEach.fields.first { it.role == "qualifier" }.text, "the receiver collapses into the header")
    }

    @Test
    fun whenHasOneClausePerEntry() {
        val tree = project(screen)
        val whenBlock = tree.find { it.kind == KotlinNodeKinds.WHEN }!!
        val entries = whenBlock.slots.filter { it.category == SlotCategory.DECLARATION }.map { it.children.single() }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.label == "case" })
        assertTrue(entries.all { e -> e.slots.last().category == SlotCategory.STATEMENT }, "each branch ends in its body")
    }

    @Test
    fun namesAndLiteralsAreEditableTokens() {
        val tree = project(screen)
        val count = tree.find { it.kind == NodeKind.NAME_REF && it.fields.any { f -> f.text == "count" } }!!
        assertTrue(count.fields.single().editable && count.fields.single().role == "name")
        val one = tree.find { it.kind == NodeKind.LITERAL && it.fields.any { f -> f.text == "1" } }!!
        assertTrue(one.fields.single().editable)
    }

    @Test
    fun declarationsExposeKeywordAndName() {
        val tree = project(screen)
        val prop = tree.find { it.kind == KotlinNodeKinds.PROPERTY }!!
        assertEquals("val", prop.fields.first { it.role == "keyword" }.text)
        assertEquals("count", prop.fields.first { it.role == "declname" }.text)
        val fn = tree.find { it.kind == NodeKind.METHOD_DECL }!!
        assertEquals("render", fn.fields.first { it.role == "declname" }.text)
    }

    @Test
    fun insertIntoAnEmptyLambdaOpensIt() {
        val src = """
            fun f() {
                run {}
            }
        """.trimIndent()
        val tree = project(src)
        val run = tree.find { it.kind == NodeKind.METHOD_CALL && it.fields.any { f -> f.text == "run" } }!!
        val body = run.slots.single { it.category == SlotCategory.STATEMENT }.children.single()
        val slot = body.slots.indexOfFirst { it.multiple }
        val edits = engine.computeEdit(tree, src, InsertTemplate(SlotRef(body.id, slot, 0), BlockTemplate("x", SlotCategory.STATEMENT, "go()")))
        assertEquals(src.replace("run {}", "run {\n        go()\n    }"), applyEdits(src, edits))
    }

    @Test
    fun moveRangeIntoALambdaReindents() {
        val src = """
            fun f() {
                Column {
                    Text("a")
                }
                Text("b")
                Text("c")
            }
        """.trimIndent()
        val tree = project(src)
        val b = tree.find { it.kind == NodeKind.METHOD_CALL && defaultSerialize(it) == "Text(\"b\")" }!!
        val column = tree.find { it.kind == NodeKind.METHOD_CALL && it.fields.any { f -> f.text == "Column" } }!!
        val inner = column.slots.single { it.category == SlotCategory.STATEMENT }.children.single()
        val slot = inner.slots.indexOfFirst { it.multiple }
        val edits = engine.computeEdit(tree, src, MoveRange(BlockRef(b.id), 2, SlotRef(inner.id, slot, 1)))
        val expected = """
            fun f() {
                Column {
                    Text("a")
                    Text("b")
                    Text("c")
                }
            }
        """.trimIndent()
        assertEquals(expected, applyEdits(src, edits))
    }

    @Test
    fun kotlinArgumentsInsertAndRemoveIncludingNamedOnes() {
        val src = """
            fun f() {
                Text()
                Text("hi", fontSize = 18.sp, color = Color.Red)
                run { go() }
            }
        """.trimIndent()
        val tree = project(src)
        fun call(prefix: String) = tree.find { it.kind == NodeKind.METHOD_CALL && defaultSerialize(it).startsWith(prefix) }!!
        val empty = call("Text()")
        assertEquals(src.replace("Text()", "Text(\"a\")"), applyEdits(src, engine.computeEdit(tree, src, InsertArgument(BlockRef(empty.id), 0, 0, "\"a\""))))
        val full = call("Text(\"hi\"")
        val args = full.slots.withIndex().filter { it.value.category == SlotCategory.ARGUMENT }.map { it.index }
        // A named argument goes with its name and one comma.
        assertEquals(src.replace(", fontSize = 18.sp", ""), applyEdits(src, engine.computeEdit(tree, src, RemoveArgument(BlockRef(full.id), args[1]))))
        assertEquals(src.replace(", color = Color.Red", ""), applyEdits(src, engine.computeEdit(tree, src, RemoveArgument(BlockRef(full.id), args[2]))))
        assertEquals(src.replace("Color.Red)", "Color.Red, maxLines = 1)"), applyEdits(src, engine.computeEdit(tree, src, InsertArgument(BlockRef(full.id), 0, 3, "maxLines = 1"))))
        // A segment written without parentheses (a trailing lambda only) gets them.
        val run = call("run {")
        assertEquals(src.replace("run { go() }", "run(x) { go() }"), applyEdits(src, engine.computeEdit(tree, src, InsertArgument(BlockRef(run.id), 0, 0, "x"))))
    }

    // ---- helpers ----

    private fun project(src: String): BlockTree {
        val file = InMemoryVirtualFile("/src/Main.kt", src)
        return engine.project(KotlinIncrementalParser().parseFull(TestDocument(src, file)))
    }

    private fun bodyOf(tree: BlockTree, name: String): BlockNode {
        val fn = tree.find { it.kind == NodeKind.METHOD_DECL && it.fields.any { f -> f.role == "declname" && f.text == name } }
        assertNotNull(fn, "expected function $name")
        return fn.descendants().first { it.kind == NodeKind.BLOCK }
    }

    private fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    private fun BlockTree.find(p: (BlockNode) -> Boolean): BlockNode? = root.descendants().firstOrNull(p)

    private fun BlockNode.descendants(): Sequence<BlockNode> = sequence {
        yield(this@descendants)
        for (s in slots) for (c in s.children) yieldAll(c.descendants())
    }
}
