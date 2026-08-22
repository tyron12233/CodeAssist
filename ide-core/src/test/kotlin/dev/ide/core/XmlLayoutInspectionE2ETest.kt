package dev.ide.core

import dev.ide.analysis.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The layout inspections end-to-end through the real engine: the `xml-analysis` plugin → the Android element
 * catalog ([AndroidXmlTagChecker], fed by the same custom-view scan completion uses) → the diagnostics the
 * editor shows. What the unit tests can't prove is exactly what breaks in practice: that a REAL project's
 * layout comes back clean while a typo'd or missing element is flagged.
 */
class XmlLayoutInspectionE2ETest {

    private val root = createTempDirectory("xml-inspect")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun awaitIndexed(ide: IdeServices, timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun flagsAMisspelledElementAndAMissingClassButNotARealLayout() {
        val ide = IdeServices.bootstrapDemo(root).also { services = it }
        // A project-source custom view: it must resolve as an element, like completion offers it.
        val srcDir = root.resolve("app/src/main/java/com/example/app")
        Files.createDirectories(srcDir)
        Files.writeString(
            srcDir.resolve("MyButton.java"),
            """
            package com.example.app;
            import android.content.Context;
            import android.widget.Button;
            public class MyButton extends Button {
                public MyButton(Context c) { super(c); }
            }
            """.trimIndent(),
        )
        ide.reindex()
        awaitIndexed(ide)

        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        val ns = """xmlns:android="http://schemas.android.com/apk/res/android""""

        // The element check is index-gated on purpose (a cold index must never flag a real custom view), so an
        // incremental rebuild that lands mid-analyze legitimately returns nothing: wait it out and retry.
        fun codes(xml: String, expect: String? = null): List<Diagnostic> {
            var last = emptyList<Diagnostic>()
            repeat(20) {
                awaitIndexed(ide)
                last = runBlocking { ide.analyzeDiagnostics(layout, xml) }
                if (expect == null || last.any { it.code == expect }) return last
                Thread.sleep(100)
            }
            return last
        }

        // A layout of real elements (framework widgets + the project's own view) is clean.
        val clean = """
            <LinearLayout $ns
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/app_name"/>
                <com.example.app.MyButton
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"/>
            </LinearLayout>
        """.trimIndent()
        val cleanDiags = codes(clean)
        assertTrue(
            cleanDiags.none { it.code == "android.unknownTag" || it.code == "android.illegalChild" },
            "a layout of real elements must be clean: ${cleanDiags.map { "${it.code}: ${it.message}" }}",
        )

        // A misspelled framework widget is flagged, and the fix renames it to the real one.
        val typoHit = codes(clean.replace("<TextView", "<TextVeiw"), "android.unknownTag")
            .single { it.code == "android.unknownTag" }
        assertTrue("TextVeiw" in typoHit.message && "TextView" in typoHit.message, typoHit.message)
        assertTrue(typoHit.fixes.any { it.title == "Change tag to TextView" }, typoHit.fixes.map { it.title }.toString())

        // A fully-qualified class that isn't on the classpath is flagged too.
        val missing = codes(clean.replace("com.example.app.MyButton", "com.example.app.NotAView"), "android.unknownTag")
        assertTrue(
            missing.any { it.code == "android.unknownTag" && "NotAView" in it.message },
            missing.map { "${it.code}: ${it.message}" }.toString(),
        )

        // A child under a plain View can't be inflated (the parent is not a ViewGroup).
        val badChild = """
            <LinearLayout $ns
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content">
                    <Button
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"/>
                </TextView>
            </LinearLayout>
        """.trimIndent()
        val nested = codes(badChild, "android.illegalChild")
        assertTrue(
            nested.any { it.code == "android.illegalChild" && "Button" in it.message },
            nested.map { "${it.code}: ${it.message}" }.toString(),
        )
    }

    /** The demo project's own resources are the false-positive canary: every shipped res file must be free of
     *  the structural / element / values inspections exactly as written. */
    @Test
    fun theDemoProjectsOwnResourcesAreClean() {
        val ide = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(ide)
        val newCodes = setOf(
            "android.unknownTag", "android.illegalChild", "android.includeWithoutLayout",
            "android.mergeNotRoot", "android.fragmentWithoutClass", "android.duplicateId",
            "android.resourceMissingName", "android.duplicateResource",
        )
        val resFiles = listOf(
            "app/src/main/res/layout/activity_main.xml",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values/colors.xml",
            "app/src/main/res/values/themes.xml",
            "app/src/main/res/values-night/themes.xml",
            "app/src/main/res/values/attrs.xml",
        )
        for (rel in resFiles) {
            val file = root.resolve(rel)
            if (!Files.exists(file)) continue
            val found = runBlocking { ide.analyzeDiagnostics(file, Files.readString(file)) }
                .filter { it.code in newCodes }
            assertTrue(found.isEmpty(), "$rel should be clean: ${found.map { "${it.code}: ${it.message}" }}")
        }
    }
}
