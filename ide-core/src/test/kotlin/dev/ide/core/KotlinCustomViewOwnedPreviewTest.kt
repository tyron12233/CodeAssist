package dev.ide.core

import dev.ide.preview.PreviewRequest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The owned (cross-platform) layout preview compiles only Java, so a Kotlin `View` subclass can't be
 * instrumented — it used to fail with the cryptic "not a bridged View subclass". It must now report a clear,
 * actionable message pointing at the real-view path instead. (No real-view runtime is wired in tests, so
 * `layoutPreview` takes the owned path.)
 */
class KotlinCustomViewOwnedPreviewTest {

    private val root = createTempDirectory("kt-custom-view")
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
    fun kotlinCustomViewGetsClearOwnedFallbackMessage() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(s)

        // A Kotlin-only custom View (a `.kt`, no `.java`) in the app's own source.
        val srcDir = root.resolve("app/src/main/java/com/example/app")
        Files.createDirectories(srcDir)
        Files.writeString(
            srcDir.resolve("TestView.kt"),
            """
            package com.example.app
            import android.content.Context
            import android.util.AttributeSet
            import android.view.View
            class TestView(context: Context, attrs: AttributeSet? = null) : View(context, attrs)
            """.trimIndent(),
        )

        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        val text = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <com.example.app.TestView
                    android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </LinearLayout>
        """.trimIndent()

        val result = s.layoutPreview(layout, text, PreviewRequest(1080, 1920, 2.0f, showChrome = false))
        requireNotNull(result) { "owned preview should render" }
        val messages = result.problems.map { it.message }
        assertTrue(
            messages.any { "Kotlin custom view" in it && "Real views" in it },
            "expected a clear Kotlin custom-view notice; got $messages",
        )
        assertTrue(
            messages.none { "not a bridged View subclass" in it },
            "the confusing bridged-subclass error must be gone; got $messages",
        )
    }
}
