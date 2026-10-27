package dev.ide.android.support.preview

import dev.ide.android.support.PreviewLayoutRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewLayoutRewriterTest {

    private val androidAttrs = setOf("text", "visibility", "src", "tag", "hint", "orientation", "background")

    private fun rewrite(xml: String) = PreviewLayoutRewriter.rewrite(xml.trimIndent(), androidAttrs)

    private fun assertSameLines(before: String, after: String) =
        assertEquals(before.trimIndent().count { it == '\n' }, after.count { it == '\n' }, "line count changed:\n$after")

    @Test
    fun designTimeAttributesStandInForTheRealOnes() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                android:layout_width="match_parent" android:layout_height="match_parent"
                tools:context=".MainActivity">
                <TextView android:id="@+id/a" android:text="Real" tools:text="Design" />
                <TextView android:id="@+id/b" tools:text="Added" tools:visibility="gone" />
                <TextView android:id="@+id/c" tools:notAnAttribute="x" />
            </LinearLayout>
        """
        val out = rewrite(xml)
        assertTrue("android:text=\"Design\" tools:text=\"Design\"" in out, out)
        assertTrue("android:text=\"Added\"" in out && "android:visibility=\"gone\"" in out, out)
        assertFalse("android:notAnAttribute" in out, "an unknown attribute must not be invented: $out")
        assertFalse("android:context" in out, "tools:context is an instruction, not a shadow: $out")
        assertSameLines(xml, out)
    }

    @Test
    fun sampleDataBecomesFixedValues() {
        val out = rewrite(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <TextView tools:text="@tools:sample/full_names" />
                <ImageView tools:src="@tools:sample/avatars" />
                <TextView tools:text="@tools:sample/unknown_set" />
            </FrameLayout>
            """
        )
        assertTrue("android:text=\"Jane Doe\"" in out, out)
        assertTrue("android:src=\"@android:drawable/sym_def_app_icon\"" in out, out)
        assertFalse("@tools:sample/unknown_set\" android:" in out || "android:text=\"@tools:sample" in out, out)
    }

    @Test
    fun listItemTravelsInTheTagAndKeepsTheOriginalTag() {
        val out = rewrite(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                xmlns:tools="http://schemas.android.com/tools">
                <androidx.recyclerview.widget.RecyclerView android:id="@+id/list"
                    tools:listitem="@layout/row" tools:itemCount="3" tools:layoutManager="GridLayoutManager" />
                <ListView android:tag="mine" tools:listitem="@android:layout/simple_list_item_1" />
            </FrameLayout>
            """
        )
        assertTrue("android:tag=\"codeassist.tools:listitem=@layout/row;itemCount=3\"" in out, out)
        assertTrue("app:layoutManager=\"GridLayoutManager\"" in out, out)
        assertTrue("android:tag=\"codeassist.tools:listitem=@android:layout/simple_list_item_1;itemCount=;tag=mine\"" in out, out)
    }

    @Test
    fun dataBindingLayoutIsUnwrappedAndKeepsDefaults() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <layout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <data>
                    <variable name="user" type="com.example.User" />
                </data>
                <LinearLayout android:layout_width="match_parent" android:layout_height="match_parent">
                    <TextView android:text="@{user.name, default=Jane}" />
                    <TextView android:text="@{user.city}" tools:text="Paris" />
                    <TextView android:text="@{user.age}" />
                </LinearLayout>
            </layout>
        """
        val out = rewrite(xml)
        assertFalse("<layout" in out || "</layout>" in out || "<data" in out || "<variable" in out, out)
        assertTrue("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:tools=" in out, out)
        assertTrue("android:text=\"Jane\"" in out, out)
        assertTrue("android:text=\"Paris\"" in out, "a design-time value wins over the binding: $out")
        assertFalse("@{" in out, "a binding with no default is removed: $out")
        assertSameLines(xml, out)
    }

    @Test
    fun aLayoutWithoutToolsOrBindingsIsUntouched() {
        val xml = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- <TextView tools:text="in a comment" /> -->
                <TextView android:text="Hi" />
            </FrameLayout>
        """.trimIndent()
        assertEquals(xml, PreviewLayoutRewriter.rewrite(xml, androidAttrs))
    }
}
