package dev.ide.core

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.xml.XmlIncrementalParser
import dev.ide.lang.xml.XmlParsedFile
import dev.ide.platform.ContentHash
import dev.ide.preview.PreviewViewNode
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Aligning the REAL-view captured [PreviewViewNode] tree back to the live XML source (stamping each node's
 * `sourceOffset`) — id-anchored + structural, transparent to `<merge>`, robust to AppCompat/Material view
 * substitution and framework-synthesized container internals. Pure over the parsed DOM + a hand-built tree.
 */
class LayoutSourceMapperTest {

    private fun parse(xml: String): XmlParsedFile = XmlIncrementalParser().parseFull(Doc(xml)) as XmlParsedFile

    private fun view(cls: String, id: String? = null, vararg kids: PreviewViewNode) =
        PreviewViewNode(cls, id, 0, 0, 0, 0, emptyList(), kids.toList())

    @Test
    fun mapsRootAndChildrenThroughDecorAndSubstitution() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical">
              <TextView android:id="@+id/title" android:text="Hi"/>
              <Button android:text="Go"/>
            </LinearLayout>
        """.trimIndent()
        // Decor → content → the layout root; the Button inflates as a substituted MaterialButton (order kept).
        val tree = view(
            "com.android.internal.policy.DecorView", null,
            view(
                "android.widget.FrameLayout", "content",
                view(
                    "android.widget.LinearLayout", null,
                    view("androidx.appcompat.widget.AppCompatTextView", "title"),
                    view("com.google.android.material.button.MaterialButton", null),
                ),
            ),
        )
        val stamped = LayoutSourceMapper.stamp(tree, parse(xml))
        val content = stamped.children[0]
        val linear = content.children[0]

        assertNull(stamped.sourceOffset, "decor is chrome, not source")
        assertNull(content.sourceOffset, "the framework content frame is chrome, not source")
        assertEquals(xml.indexOf("<LinearLayout"), linear.sourceOffset)
        assertEquals(xml.indexOf("<TextView"), linear.children[0].sourceOffset)
        // Structural mapping survives the Button→MaterialButton substitution (position preserved).
        assertEquals(xml.indexOf("<Button"), linear.children[1].sourceOffset)
    }

    @Test
    fun idMapsInsideSynthesizedContainerButNotItsInternals() {
        val xml = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
              <Toolbar android:id="@+id/bar"/>
            </FrameLayout>
        """.trimIndent()
        val tree = view(
            "android.widget.FrameLayout", "content",
            view(
                "android.widget.FrameLayout", null,
                view(
                    "androidx.appcompat.widget.Toolbar", "bar",
                    view("android.widget.TextView", null),  // framework-synthesized internal
                    view("android.widget.ImageButton", null),
                ),
            ),
        )
        val stamped = LayoutSourceMapper.stamp(tree, parse(xml))
        val frame = stamped.children[0]
        val toolbar = frame.children[0]

        assertEquals(xml.indexOf("<FrameLayout"), frame.sourceOffset)
        assertEquals(xml.indexOf("<Toolbar"), toolbar.sourceOffset)
        // The Toolbar's synthesized children have no source element (its source tag has no child tags).
        assertNull(toolbar.children[0].sourceOffset)
        assertNull(toolbar.children[1].sourceOffset)
    }

    @Test
    fun mergeRootIsTransparent() {
        val xml = """
            <merge xmlns:android="http://schemas.android.com/apk/res/android">
              <TextView android:id="@+id/a"/>
              <TextView android:id="@+id/b"/>
            </merge>
        """.trimIndent()
        // A <merge> inflates its children directly into the window content.
        val tree = view(
            "android.widget.FrameLayout", "content",
            view("android.widget.TextView", "a"),
            view("android.widget.TextView", "b"),
        )
        val stamped = LayoutSourceMapper.stamp(tree, parse(xml))
        val kids = stamped.children
        assertEquals(xml.indexOf("<TextView"), kids[0].sourceOffset)
        assertEquals(xml.indexOf("<TextView", xml.indexOf("<TextView") + 1), kids[1].sourceOffset)
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = FakeFile("res/layout/a.xml")
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private class FakeFile(override val path: String) : VirtualFile {
        override val name: String get() = path.substringAfterLast('/')
        override val isDirectory: Boolean = false
        override val exists: Boolean = true
        override val length: Long = 0
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash("")
        override fun readBytes(): ByteArray = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
