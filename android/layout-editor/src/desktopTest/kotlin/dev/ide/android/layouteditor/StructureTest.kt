package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutViewNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureTest {

    private fun apply(text: String, edits: List<LayoutEdit>): String {
        var out = text
        for (e in edits.sortedByDescending { it.start }) out = out.substring(0, e.start) + e.newText + out.substring(e.end)
        return out
    }

    private val linear = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:orientation="vertical">
            <TextView android:id="@+id/a" />
            <TextView android:id="@+id/b" />
            <FrameLayout android:id="@+id/box">
                <View android:id="@+id/inner" />
            </FrameLayout>
        </LinearLayout>
    """.trimIndent()

    @Test
    fun insertGoesOnItsOwnLineAtTheSiblingsIndent() {
        val doc = parseTestLayout(linear)
        val out = apply(linear, insertElementEdits(doc, doc.root!!, 1, "Button", listOf("android:id" to "@+id/button")))
        val again = parseTestLayout(out)
        assertEquals(listOf("a", "button", "b", "box"), again.root!!.children.map { it.id })
        assertTrue("\n    <Button\n        android:id=\"@+id/button\" />\n    <TextView android:id=\"@+id/b\" />" in out, out)
    }

    @Test
    fun insertIntoASelfClosingParentOpensIt() {
        val text = "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />"
        val doc = parseTestLayout(text)
        val out = apply(text, insertElementEdits(doc, doc.root!!, 0, "View", listOf("android:id" to "@+id/v")))
        assertEquals(listOf("v"), parseTestLayout(out).root!!.children.map { it.id })
        assertTrue(out.endsWith("</FrameLayout>"), out)
    }

    @Test
    fun anAppAttributeDeclaresItsNamespaceOnce() {
        val doc = parseTestLayout(linear)
        val out = apply(linear, insertElementEdits(doc, doc.root!!, 3, "View", listOf("app:layout_constraintTop_toTopOf" to "parent")))
        assertEquals(1, Regex("xmlns:app=").findAll(out).count(), out)
        assertTrue(parseTestLayout(out).root!!.attribute("xmlns:app") != null)
    }

    @Test
    fun moveReparentsAndReindents() {
        val doc = parseTestLayout(linear)
        val a = doc.root!!.children[0]
        val box = doc.root!!.children[2]
        val out = apply(linear, moveElementEdits(doc, a, box, 1, null, 2f))
        val again = parseTestLayout(out)
        assertEquals(listOf("b", "box"), again.root!!.children.map { it.id })
        assertEquals(listOf("inner", "a"), again.root!!.children[1].children.map { it.id })
        assertTrue("\n        <TextView android:id=\"@+id/a\" />" in out, out)
    }

    @Test
    fun moveWithinTheSameParentReorders() {
        val doc = parseTestLayout(linear)
        val b = doc.root!!.children[1]
        val out = apply(linear, moveElementEdits(doc, b, doc.root!!, 0, null, 2f))
        assertEquals(listOf("b", "a", "box"), parseTestLayout(out).root!!.children.map { it.id })
        // Next to itself is no move.
        assertTrue(moveElementEdits(doc, b, doc.root!!, 2, null, 2f).isEmpty())
        // Into its own subtree is refused.
        val box = doc.root!!.children[2]
        assertTrue(moveElementEdits(doc, box, box.children[0], 0, null, 2f).isEmpty())
    }

    @Test
    fun leavingAConstraintLayoutDropsItsConstraints() {
        val text = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto">
                <androidx.constraintlayout.widget.ConstraintLayout android:id="@+id/c">
                    <Button
                        android:id="@+id/go"
                        app:layout_constraintTop_toTopOf="parent"
                        android:text="Go" />
                </androidx.constraintlayout.widget.ConstraintLayout>
            </LinearLayout>
        """.trimIndent()
        val doc = parseTestLayout(text)
        val go = doc.root!!.children[0].children[0]
        val out = apply(text, moveElementEdits(doc, go, doc.root!!, 1, null, 2f))
        val moved = parseTestLayout(out).root!!.children[1]
        assertEquals("go", moved.id)
        assertNull(moved.attribute("app:layout_constraintTop_toTopOf"))
        assertEquals("Go", moved.attribute("android:text")?.value)
    }

    @Test
    fun dropTargetPicksTheContainerAndTheIndexByPosition() {
        val doc = parseTestLayout(linear)
        val root = doc.root!!
        val (a, b, box) = root.children
        val tree = LayoutViewNode(
            "LinearLayout", null, 0, 0, 100, 300, root.start,
            children = listOf(
                LayoutViewNode("TextView", "a", 0, 0, 100, 50, a.start),
                LayoutViewNode("TextView", "b", 0, 50, 100, 100, b.start),
                LayoutViewNode("FrameLayout", "box", 0, 100, 100, 200, box.start),
            ),
        )
        val groups = setOf("LinearLayout", "FrameLayout")
        val between = dropTargetAt(tree, doc, 50f, 60f, { it.tag in groups })!!
        assertEquals(root, between.parent)
        assertEquals(1, between.index)
        assertEquals(50f, between.line!![1])
        val intoBox = dropTargetAt(tree, doc, 50f, 150f, { it.tag in groups })!!
        assertEquals(box, intoBox.parent)
        // A view being moved is not a target for itself.
        val moving = dropTargetAt(tree, doc, 50f, 150f, { it.tag in groups }, moving = box)!!
        assertEquals(root, moving.parent)
    }

    @Test
    fun newViewsGetAnIdASizeAndAPlaceInAConstraintLayout() {
        val text = "<androidx.constraintlayout.widget.ConstraintLayout><Button android:id=\"@+id/button\"/></androidx.constraintlayout.widget.ConstraintLayout>"
        val doc = parseTestLayout(text)
        val area = LayoutViewNode("ConstraintLayout", null, 0, 0, 200, 200, 0)
        val target = DropTarget(doc.root!!, 1, area, null, 33f, 81f)
        val attrs = defaultAttributes("Button", false, target, setOf("button"), 2f).toMap()
        assertEquals("@+id/button2", attrs["android:id"])
        assertEquals("Button", attrs["android:text"])
        assertEquals("parent", attrs["app:layout_constraintStart_toStartOf"])
        assertEquals("16dp", attrs["android:layout_marginStart"])
        assertEquals("40dp", attrs["android:layout_marginTop"])
        assertEquals("vertical", defaultAttributes("LinearLayout", true, null, emptySet(), 2f).toMap()["android:orientation"])
    }

    @Test
    fun aFullSingleChildContainerTakesNoSecondChild() {
        val text = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical">
                <HorizontalScrollView android:id="@+id/scroll">
                    <TextView android:id="@+id/content" />
                </HorizontalScrollView>
                <TextView android:id="@+id/other" />
            </LinearLayout>
        """.trimIndent()
        val doc = parseTestLayout(text)
        val root = doc.root!!
        val scroll = root.children[0]
        val content = scroll.children[0]
        val other = root.children[1]
        val tree = LayoutViewNode(
            "LinearLayout", null, 0, 0, 100, 300, root.start,
            children = listOf(
                LayoutViewNode("HorizontalScrollView", "scroll", 0, 0, 100, 100, scroll.start,
                    children = listOf(LayoutViewNode("TextView", "content", 0, 0, 100, 40, content.start))),
                LayoutViewNode("TextView", "other", 0, 100, 100, 150, other.start),
            ),
        )
        val groups = setOf("LinearLayout", "HorizontalScrollView")
        // Over the scroll view's empty area: not into it (it has its child), so into the LinearLayout around it.
        val t = dropTargetAt(tree, doc, 50f, 70f, { it.tag in groups })!!
        assertEquals(root, t.parent)
        assertTrue(!acceptsChild(scroll))
        // Moving its own child within it is fine, and an empty one takes a child.
        assertTrue(acceptsChild(scroll, moving = content))
        val empty = parseTestLayout("<ScrollView/>").root!!
        assertTrue(acceptsChild(empty))
        // Moving the other view in through the tree: refused.
        val rows = mapOf(scroll to androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 44f), content to androidx.compose.ui.geometry.Rect(0f, 44f, 100f, 88f))
        assertNull(treeDropTarget(doc, rows, androidx.compose.ui.geometry.Offset(50f, 22f), { it.tag in groups }, other), "inside the scroll view")
        assertNull(treeDropTarget(doc, rows, androidx.compose.ui.geometry.Offset(50f, 84f), { it.tag in groups }, other), "beside its child")
    }
}
