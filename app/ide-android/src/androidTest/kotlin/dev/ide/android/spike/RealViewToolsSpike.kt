package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.core.IdeServicesBackend
import dev.ide.model.impl.rootPath
import dev.ide.preview.PreviewRequest
import dev.ide.preview.PreviewViewNode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-view preview of design-time (`tools:`) attributes and data-binding layouts, as Android Studio's preview
 * shows them: `tools:text` (including `@tools:sample/` data) and `tools:visibility` stand in for the real
 * attributes, `tools:listitem` fills a RecyclerView and a ListView, and a `<layout>` data-binding file renders its
 * view root with each binding's `default=` (or its `tools:` value). The data-binding file is on disk while another
 * layout is previewed too, so the preview's merged copy of the project's layouts must compile with it.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.RealViewToolsSpike
 *     adb logcat -d -s VmTools ide.preview
 */
@RunWith(AndroidJUnit4::class)
class RealViewToolsSpike {

    private fun log(m: String) { Log.i("VmTools", m); println(m) }

    private val row = """
        <?xml version="1.0" encoding="utf-8"?>
        <TextView xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools"
            android:id="@+id/row_text"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            tools:text="@tools:sample/cities" />
    """.trimIndent()

    private val main = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools"
            android:id="@+id/root"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical"
            tools:context=".MainActivity">

            <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="Runtime title" tools:text="@tools:sample/full_names" />

            <TextView android:id="@+id/hidden" android:layout_width="match_parent" android:layout_height="40dp"
                android:text="Only at runtime" tools:visibility="gone" />

            <androidx.recyclerview.widget.RecyclerView android:id="@+id/list" android:layout_width="match_parent"
                android:layout_height="wrap_content" tools:listitem="@layout/row" tools:itemCount="3" />

            <ListView android:id="@+id/plain" android:layout_width="match_parent" android:layout_height="200dp"
                tools:listitem="@layout/row" tools:itemCount="2" />
        </LinearLayout>
    """.trimIndent()

    private val bound = """
        <?xml version="1.0" encoding="utf-8"?>
        <layout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">
            <data>
                <variable name="user" type="com.example.User" />
            </data>
            <LinearLayout android:id="@+id/bound_root" android:layout_width="match_parent"
                android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:id="@+id/bound_name" android:layout_width="wrap_content"
                    android:layout_height="wrap_content" android:text="@{user.name, default=Jane}" />
                <TextView android:id="@+id/bound_city" android:layout_width="wrap_content"
                    android:layout_height="wrap_content" android:text="@{user.city}" tools:text="Paris" />
            </LinearLayout>
        </layout>
    """.trimIndent()

    private fun find(n: PreviewViewNode, id: String): PreviewViewNode? =
        if (n.id == id) n else n.children.firstNotNullOfOrNull { find(it, id) }

    private fun text(n: PreviewViewNode?) = n?.properties?.firstOrNull { it.name == "text" }?.value

    private fun dump(n: PreviewViewNode, depth: Int = 0) {
        log("${"  ".repeat(depth)}${n.simpleName} id=${n.id} [${n.left},${n.top} ${n.width}x${n.height}] text=${text(n)}")
        n.children.forEach { dump(it, depth + 1) }
    }

    @Test
    fun designTimeAttributesListItemsAndDataBinding() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = AndroidIde.createProjectManager(ctx)
        val services = pm.create("android-material-you", emptyMap())
        val backend = IdeServicesBackend(initial = services)
        val failures = ArrayList<String>()
        fun check(ok: Boolean, what: String) { log((if (ok) "  ok   " else "  FAIL ") + what); if (!ok) failures += what }
        try {
            backend.deps.startPendingDependencyResolution()
            val deadline = System.currentTimeMillis() + 180_000
            Thread.sleep(2_000)
            while (backend.deps.depsState.value.resolving && System.currentTimeMillis() < deadline) Thread.sleep(1_000)

            val layouts = File(services.store.rootPath.toFile(), "app/src/main/res/layout")
            File(layouts, "row.xml").writeText(row)
            File(layouts, "activity_bound.xml").writeText(bound)
            val mainFile = File(layouts, "activity_main.xml").apply { writeText(main) }

            fun render(file: File, xml: String): PreviewViewNode? {
                services.updateDocument(file.toPath(), xml)
                val r = services.layoutPreview(
                    file.toPath(), xml,
                    PreviewRequest(widthPx = 1080, heightPx = 2140, density = 2.75f, realViews = true),
                )
                val problems = r?.problems?.map { it.message }.orEmpty() + r?.resourceErrors?.map { it.message }.orEmpty()
                log("=== ${file.name}: rendered=${r?.renderedNativeImage != null} problems=$problems")
                check(r?.renderedNativeImage != null && problems.isEmpty(), "${file.name} renders: $problems")
                return r?.viewTree?.also { dump(it) }
            }

            render(mainFile, main)?.let { tree ->
                check(text(find(tree, "title")) == "Jane Doe", "tools:text sample: ${text(find(tree, "title"))}")
                val hidden = find(tree, "hidden")
                check(hidden != null && hidden.width == 0 && hidden.height == 0, "tools:visibility=gone: ${hidden?.width}x${hidden?.height}")
                val list = find(tree, "list")
                check(list?.children?.size == 3, "RecyclerView items: ${list?.children?.size}")
                check(list?.children?.all { it.height == 132 } == true, "RecyclerView item height: ${list?.children?.map { it.height }}")
                check(list?.children?.firstOrNull()?.let { text(it) } == "San Francisco", "item tools:text: ${list?.children?.firstOrNull()?.let { text(it) }}")
                val plain = find(tree, "plain")
                check(plain?.children?.size == 2, "ListView items: ${plain?.children?.size}")
            }
            render(File(layouts, "activity_bound.xml"), bound)?.let { tree ->
                check(find(tree, "bound_root") != null, "data binding view root rendered")
                check(text(find(tree, "bound_name")) == "Jane", "binding default: ${text(find(tree, "bound_name"))}")
                check(text(find(tree, "bound_city")) == "Paris", "tools over binding: ${text(find(tree, "bound_city"))}")
            }
        } finally {
            runCatching { services.close() }
        }
        log("failures (${failures.size}):\n" + failures.joinToString("\n"))
        assertTrue("design-time preview mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
