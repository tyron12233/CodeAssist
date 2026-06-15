package dev.ide.block.impl

import dev.ide.block.BlockNode
import dev.ide.block.BlockRef
import dev.ide.block.BlockSlot
import dev.ide.block.BlockTree
import dev.ide.block.Delete
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.ValueKind
import dev.ide.block.defaultSerialize
import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The projection engine over the real JDT DOM (the tree the IDE actually uses): a Java file projects
 * into a block tree, and block edits compile to surgical [DocumentEdit]s that leave untouched
 * code — including comments — byte-for-byte intact.
 */
class BlockProjectionTest {

    private val engine = BlockProjectionEngine.withJava()

    @Test
    fun projectsMethodBodyAsStatementList() {
        val tree = project(
            """
            class A {
                void m() {
                    int x = 1;
                    foo();
                }
            }
            """.trimIndent(),
        )
        // The file projects under a single "file" root mirroring the compilation unit.
        assertEquals("file", tree.root.label)
        assertEquals(NodeKind.COMPILATION_UNIT, tree.root.kind)

        // There is a method block, and its body is ONE multiple STATEMENT slot (foldable, insertable).
        val method = tree.find { it.kind == NodeKind.METHOD_DECL }
        assertNotNull(method, "expected a method block")
        val body = method.descendants().first { it.kind == NodeKind.BLOCK }
        val stmts = body.slots.single { it.multiple && it.category == SlotCategory.STATEMENT }
        assertEquals(2, stmts.children.size, "body should hold two statements")

        // A name leaf carries its exact source token.
        val name = tree.find { it.kind == NodeKind.NAME_REF && it.fields.any { f -> f.text == "x" } }
        assertNotNull(name, "expected a NAME_REF block for x")
    }

    @Test
    fun unmappedNodesCollapseToEditableTextSlots() {
        // A lambda is NOT in the mapped set → it must collapse to an editable opaque block, not vanish.
        val tree = project(
            """
            class A {
                Runnable r = () -> System.out.println("hi");
            }
            """.trimIndent(),
        )
        val opaque = tree.find { b -> b.fields.any { it.editable && it.role == "code" && "->" in it.text } }
        assertNotNull(opaque, "the lambda should survive as an editable opaque block")
    }

    @Test
    fun setFieldRenamesExactlyOneTokenAndKeepsComments() {
        val src = """
            class A {
                void m() {
                    int x = 1; // keep this comment
                    foo();
                }
            }
        """.trimIndent()
        val tree = project(src)
        val name = tree.find { it.kind == NodeKind.NAME_REF && it.fields.any { f -> f.text == "x" } }!!
        val edits = engine.computeEdit(tree, src, SetField(BlockRef(name.id), role = "name", text = "count"))
        val result = applyEdits(src, edits)

        assertEquals(1, edits.size)
        assertTrue("int count = 1;" in result, "the declaration was renamed")
        assertTrue("// keep this comment" in result, "the trailing comment survived")
        assertTrue("foo();" in result, "the following statement is untouched")
    }

    @Test
    fun deleteStatementRemovesItAndPreservesNeighbours() {
        val src = """
            class A {
                void m() {
                    int x = 1; // keep me
                    foo();
                }
            }
        """.trimIndent()
        val tree = project(src)
        val foo = tree.find { it.kind == NodeKind("ExpressionStatement") }!!
        val result = applyEdits(src, engine.computeEdit(tree, src, Delete(BlockRef(foo.id))))

        assertTrue("foo()" !in result, "the deleted statement is gone")
        assertTrue("int x = 1; // keep me" in result, "the kept statement and its comment survive verbatim")
        // The blank line the deletion would leave is swallowed (no dangling indented empty line).
        assertTrue("\n\n        }" !in result && "    \n" !in result, "no orphaned blank line: $result")
    }

    @Test
    fun replaceSlotWithTextRewritesJustThatSpan() {
        val src = """
            class A {
                void m() {
                    if (a > 0) {
                        go();
                    }
                }
            }
        """.trimIndent()
        val tree = project(src)
        val ifBlock = tree.find { it.kind == NodeKind("IfStatement") }!!
        // slots[0] is the condition (carved between `if (` and `) `).
        val edits = engine.computeEdit(tree, src, ReplaceWithText(SlotRef(ifBlock.id, slotIndex = 0), text = "b < 10"))
        val result = applyEdits(src, edits)

        assertTrue("if (b < 10)" in result, "the condition was replaced: $result")
        assertTrue("go();" in result, "the then-branch is untouched")
    }

    @Test
    fun blockAtMapsCaretToTheDeepestBlock() {
        val src = """
            class A {
                void m() {
                    int x = 1;
                }
            }
        """.trimIndent()
        val tree = project(src)
        val caret = src.indexOf("x = 1") // the `x` reference
        val at = tree.blockAt(caret)
        assertNotNull(at)
        assertTrue(caret in at.range, "the block at the caret contains it")
    }

    @Test
    fun systemOutPrintlnCollapsesToOneCallBlock() {
        val tree = project(
            """
            class A {
                void m() {
                    System.out.println("hi");
                }
            }
            """.trimIndent(),
        )
        val call = tree.find { it.kind == NodeKind.METHOD_CALL }
        assertNotNull(call, "expected a call block")
        // The pure-name receiver collapses into the header: dimmed qualifier + the method name.
        assertEquals("System.out", call.fields.first { it.role == "qualifier" }.text)
        assertEquals("println", call.fields.first { it.role == "name" }.text)
        val args = call.slots.filter { it.category == SlotCategory.ARGUMENT }
        assertEquals(1, args.size, "exactly one argument slot")
        assertEquals(ValueKind.STRING, args.single().children.single().valueKind, "a string literal produces STRING")
        assertTrue(
            tree.root.descendants().none { it.kind == NodeKind.MEMBER_ACCESS },
            "the qualifier must not decompose into member_access blocks",
        )
        assertEquals("System.out.println(\"hi\")", defaultSerialize(call), "the collapsed parts round-trip the source")
    }

    @Test
    fun fluentChainFlattensToSegments() {
        val tree = project(
            """
            class A {
                void m(StringBuilder sb, String x, String y) {
                    sb.append(x).append(y);
                }
            }
            """.trimIndent(),
        )
        val calls = tree.root.descendants().filter { it.kind == NodeKind.METHOD_CALL }.toList()
        assertEquals(1, calls.size, "the chain flattens into ONE call block")
        val call = calls.single()
        assertEquals("sb", call.fields.first { it.role == "qualifier" }.text)
        assertEquals("append", call.fields.first { it.role == "name" }.text)
        assertEquals("append", call.fields.first { it.role == "name1" }.text)
        assertEquals(2, call.slots.count { it.category == SlotCategory.ARGUMENT }, "one slot per argument")
        assertEquals("sb.append(x).append(y)", defaultSerialize(call), "segments + chrome reconstruct the source")
    }

    @Test
    fun chainMethodRenameIsSurgical() {
        val src = """
            class A {
                void m(StringBuilder sb, String x, String y) {
                    sb.append(x).append(y); // keep this comment
                }
            }
        """.trimIndent()
        val tree = project(src)
        val call = tree.find { it.kind == NodeKind.METHOD_CALL }!!
        val edits = engine.computeEdit(tree, src, SetField(BlockRef(call.id), role = "name1", text = "appendCodePoint"))
        val result = applyEdits(src, edits)

        assertEquals(1, edits.size)
        assertEquals(src.replace(".append(y)", ".appendCodePoint(y)"), result, "ONLY the second segment's name token changed")
    }

    @Test
    fun ifConditionSlotExpectsBoolean() {
        val tree = project(
            """
            class A {
                void m(boolean flag) {
                    if (flag) {
                        go();
                    }
                }
            }
            """.trimIndent(),
        )
        val ifBlock = tree.find { it.kind == NodeKind("IfStatement") }!!
        // The condition is a bare name_ref (UNKNOWN produced kind) — the POSITION still expects a boolean.
        assertEquals(ValueKind.BOOLEAN, ifBlock.slots.first().valueKind, "an if condition slot expects BOOLEAN")
    }

    @Test
    fun literalAndDeclKindsInferred() {
        val tree = project(
            """
            class A {
                void m() {
                    int n = 1;
                    String s = "x";
                    boolean b = true;
                }
            }
            """.trimIndent(),
        )
        // The slot holding each initializer literal, found via the literal leaf's token text.
        fun initializerSlot(token: String): BlockSlot {
            val owner = tree.find { b -> b.slots.any { s -> s.children.any { c -> c.fields.any { it.role == "literal" && it.text == token } } } }
            assertNotNull(owner, "expected a block owning the $token initializer slot")
            return owner.slots.first { s -> s.children.any { c -> c.fields.any { it.text == token } } }
        }
        // The slot expects the DECLARED type's kind; the literal leaf produces its token's kind.
        assertEquals(ValueKind.NUMBER, initializerSlot("1").valueKind)
        assertEquals(ValueKind.STRING, initializerSlot("\"x\"").valueKind)
        assertEquals(ValueKind.BOOLEAN, initializerSlot("true").valueKind)
        assertEquals(ValueKind.NUMBER, initializerSlot("1").children.single().valueKind)
        assertEquals(ValueKind.STRING, initializerSlot("\"x\"").children.single().valueKind)
        assertEquals(ValueKind.BOOLEAN, initializerSlot("true").children.single().valueKind)
    }

    @Test
    fun chainCollapseRoundTripsByteForByte() {
        val src = """
            class A {
                void m(StringBuilder sb, String x, String y) {
                    sb.append(x).append(y); // keep this comment
                }
            }
        """.trimIndent()
        val tree = project(src)
        val call = tree.find { it.kind == NodeKind.METHOD_CALL }!!
        val firstArg = call.slots.indexOfFirst { it.category == SlotCategory.ARGUMENT }
        val edits = engine.computeEdit(tree, src, ReplaceWithText(SlotRef(call.id, slotIndex = firstArg), text = "x.trim()"))
        val result = applyEdits(src, edits)

        assertEquals(src.replace("append(x)", "append(x.trim())"), result, "exactly the first argument span changed")
    }

    // ---- helpers ----

    private fun project(src: String): BlockTree = engine.project(parse(src))

    private fun parse(src: String): ParsedFile {
        val file = StubFile("/src/A.java", src)
        return analyzer().incrementalParser.parseFull(Snapshot(file, 1, src))
    }

    private fun analyzer(): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = listOf(StubFile("/src"))
            override val classpath: ClasspathSnapshot = EmptyClasspath
            override val bootClasspath: ClasspathSnapshot = bootOf(System.getProperty("java.home"))
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    /** Apply [edits] to [text] (descending offset, so earlier offsets stay valid). */
    private fun applyEdits(text: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.offset }) {
            sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        }
        return sb.toString()
    }

    private fun BlockTree.find(p: (BlockNode) -> Boolean): BlockNode? =
        root.descendants().firstOrNull(p)

    private fun BlockNode.descendants(): Sequence<BlockNode> = sequence {
        yield(this@descendants)
        for (s in slots) for (c in s.children) yieldAll(c.descendants())
    }

    // --- minimal DOM plumbing (mirrors lang-jdt TestSupport) ---

    private class StubFile(override val path: String, private val content: String = "") : VirtualFile {
        override val name get() = path.substringAfterLast('/')
        override val isDirectory = false
        override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }

    private object EmptyClasspath : ClasspathSnapshot {
        override val entries: List<ClasspathEntry> = emptyList()
        override fun fingerprint() = ContentHash("")
    }

    private fun bootOf(vararg paths: String) = object : ClasspathSnapshot {
        override val entries = paths.map { ClasspathEntry(StubFile(it), ClasspathEntryKind.SDK_BOOTCLASSPATH) }
        override fun fingerprint() = ContentHash(paths.joinToString())
    }

    private class Snapshot(
        override val file: VirtualFile,
        override val version: Long,
        override val text: CharSequence,
    ) : DocumentSnapshot {
        override fun length() = text.length
    }
}
