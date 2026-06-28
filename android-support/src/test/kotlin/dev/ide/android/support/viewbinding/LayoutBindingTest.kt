package dev.ide.android.support.viewbinding

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ViewBinding model — the shape both the editor's synthetic binding classes and the build's generated
 * `.java` are derived from. Covers the AGP-faithful rules: class/field naming, `LayoutInflater`-style tag →
 * type resolution, config-variant id union, `<include>` typing, `tools:viewBindingIgnore`, and `<merge>` roots.
 */
class LayoutBindingTest {

    private fun resWith(vararg layouts: Pair<String, String>): Path {
        val res = createTempDirectory("vb-res")
        val layoutDir = res.resolve("layout")
        Files.createDirectories(layoutDir)
        for ((name, xml) in layouts) layoutDir.resolve("$name.xml").writeText(xml)
        return res
    }

    @Test
    fun `class and field names follow AGP rules`() {
        assertEquals("ActivityMainBinding", LayoutBindingModel.bindingClassName("activity_main"))
        assertEquals("FragmentBinding", LayoutBindingModel.bindingClassName("fragment"))
        assertEquals("fooBar", LayoutBindingModel.fieldName("foo_bar"))
        assertEquals("title", LayoutBindingModel.fieldName("title"))
        assertEquals("myTextView2", LayoutBindingModel.fieldName("my_text_view2"))
    }

    @Test
    fun `tags resolve to fully-qualified view types`() {
        assertEquals("android.widget.TextView", LayoutTagResolver.resolve("TextView"))
        assertEquals("android.widget.LinearLayout", LayoutTagResolver.resolve("LinearLayout"))
        assertEquals("android.view.View", LayoutTagResolver.resolve("View"))
        assertEquals("android.view.SurfaceView", LayoutTagResolver.resolve("SurfaceView"))
        assertEquals("android.webkit.WebView", LayoutTagResolver.resolve("WebView"))
        assertEquals("androidx.appcompat.widget.Toolbar", LayoutTagResolver.resolve("androidx.appcompat.widget.Toolbar"))
    }

    @Test
    fun `fields are derived from android-id with correct types`() {
        val res = resWith(
            "activity_main" to """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:id="@+id/root_layout">
                    <TextView android:id="@+id/title_text"/>
                    <com.example.MyView android:id="@+id/custom"/>
                    <Button/>
                </LinearLayout>
            """.trimIndent(),
        )
        val binding = LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").single()
        assertEquals("ActivityMainBinding", binding.simpleName)
        assertEquals("com.example.app.databinding", binding.packageName)
        assertEquals("android.widget.LinearLayout", binding.rootViewType)
        val byName = binding.fields.associateBy { it.name }
        // The Button has no id, so it gets no field; the other three do.
        assertEquals(setOf("rootLayout", "titleText", "custom"), byName.keys)
        assertEquals("android.widget.TextView", byName["titleText"]!!.viewType)
        assertEquals("com.example.MyView", byName["custom"]!!.viewType)
        assertEquals("title_text", byName["titleText"]!!.resId) // raw id kept for the R.id lookup
    }

    @Test
    fun `viewBindingIgnore on the root suppresses the binding`() {
        val res = resWith(
            "ignored" to """
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    tools:viewBindingIgnore="true">
                    <TextView android:id="@+id/x"/>
                </FrameLayout>
            """.trimIndent(),
        )
        assertTrue(LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").isEmpty())
    }

    @Test
    fun `merge root types as View`() {
        val res = resWith(
            "merge_layout" to """
                <merge xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:id="@+id/label"/>
                </merge>
            """.trimIndent(),
        )
        val binding = LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").single()
        assertEquals("android.view.View", binding.rootViewType)
        assertEquals("label", binding.fields.single().name)
    }

    @Test
    fun `include of an in-module layout exposes its binding`() {
        val res = resWith(
            "content" to """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"/>
            """.trimIndent(),
            "activity_main" to """
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <include android:id="@+id/content_panel" layout="@layout/content"/>
                    <include android:id="@+id/external_panel" layout="@layout/not_here"/>
                </FrameLayout>
            """.trimIndent(),
        )
        val main = LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").single { it.layoutName == "activity_main" }
        val byName = main.fields.associateBy { it.name }
        // A known included layout → its binding; an unknown one degrades to a plain View.
        assertEquals("com.example.app.databinding.ContentBinding", byName["contentPanel"]!!.viewType)
        assertEquals(BindingFieldKind.BINDING, byName["contentPanel"]!!.kind)
        assertEquals("android.view.View", byName["externalPanel"]!!.viewType)
    }

    @Test
    fun `config variants union their ids`() {
        val res = createTempDirectory("vb-res-variants")
        for ((dir, extra) in listOf("layout" to "phone_only", "layout-land" to "land_only")) {
            val d = res.resolve(dir); Files.createDirectories(d)
            d.resolve("activity_main.xml").writeText(
                """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:id="@+id/shared"/>
                    <TextView android:id="@+id/$extra"/>
                </LinearLayout>
                """.trimIndent(),
            )
        }
        val binding = LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").single()
        assertEquals(setOf("shared", "phoneOnly", "landOnly"), binding.fields.map { it.name }.toSet())
    }

    @Test
    fun `generated java has the expected shape`() {
        val binding = BindingClass(
            layoutName = "activity_main",
            simpleName = "ActivityMainBinding",
            packageName = "com.example.app.databinding",
            rootViewType = "android.widget.LinearLayout",
            fields = listOf(BindingField("title", "title", "android.widget.TextView")),
        )
        val java = ViewBindingJavaSource.emit(binding, "com.example.app")
        assertTrue("package com.example.app.databinding;" in java)
        assertTrue("public final class ActivityMainBinding implements androidx.viewbinding.ViewBinding" in java)
        assertTrue("public final android.widget.TextView title;" in java)
        assertTrue("public android.widget.LinearLayout getRoot()" in java)
        assertTrue("inflate(android.view.LayoutInflater inflater)" in java)
        assertTrue("inflate(android.view.LayoutInflater inflater, android.view.ViewGroup parent, boolean attachToParent)" in java)
        assertTrue("bind(android.view.View rootView)" in java)
        // References the module R for the layout + each id.
        assertTrue("com.example.app.R.layout.activity_main" in java)
        assertTrue("com.example.app.R.id.title" in java)
    }

    @Test
    fun `non-layout resource dirs are ignored`() {
        val res = createTempDirectory("vb-res-values")
        val values = res.resolve("values"); Files.createDirectories(values)
        values.resolve("strings.xml").writeText("<resources><string name=\"app\">x</string></resources>")
        assertTrue(LayoutBindingModel.bindingsFor(listOf(res), "com.example.app").isEmpty())
    }

    @Test
    fun `blank namespace yields the bare databinding package`() {
        assertEquals("databinding", LayoutBindingModel.packageName(""))
        assertEquals("com.x.databinding", LayoutBindingModel.packageName("com.x"))
        assertNull(LayoutBindingModel.fromLayouts("x", emptyList(), "com.x"))
    }
}
