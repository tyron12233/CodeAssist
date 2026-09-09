package dev.ide.core

import dev.ide.preview.PreviewRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/** Repro harness for the reported hang when rendering a Kotlin custom view. Each step runs under a watchdog. */
class CustomViewHangReproTest {

    private val root = createTempDirectory("hang-repro")
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

    /** Run [block] on a thread; fail (with its stack) if it doesn't finish within [ms]. */
    private fun watchdog(name: String, ms: Long, block: () -> Unit) {
        val err = arrayOfNulls<Throwable>(1)
        val t = Thread({ try { block() } catch (e: Throwable) { err[0] = e } }, "repro-$name")
        t.isDaemon = true
        t.start()
        t.join(ms)
        if (t.isAlive) {
            val dump = t.stackTrace.joinToString("\n") { "    at $it" }
            fail("STEP '$name' HUNG (> ${ms}ms). Stack:\n$dump")
        }
        err[0]?.let { throw it }
    }

    @Test
    fun kotlinCustomViewFullFlowDoesNotHang() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(s)

        val pkgDir = root.resolve("app/src/main/java/com/example/game2048")
        Files.createDirectories(pkgDir)
        val ktSrc = """
            package com.example.game2048

            import android.view.View
            import android.content.Context
            import android.util.AttributeSet

            class TestView(
                context: Context,
                attrs: AttributeSet? = null
            ) : View(context, attrs) {
            }
        """.trimIndent()

        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">

                <com.example.game2048.TestView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                     />

            </LinearLayout>
        """.trimIndent()

        // Mirror the user's flow: save the .kt (fires the incremental source reindex + generation bump).
        watchdog("save-kt", 30_000) { s.save(pkgDir.resolve("TestView.kt"), ktSrc) }
        Thread.sleep(500) // let the async reindex settle

        watchdog("analyze-xml", 30_000) { s.analyze(layout, xml); runBlocking { s.analyzeDiagnostics(layout, xml) } }
        watchdog("complete-tag", 30_000) {
            val off = xml.indexOf("com.example.game2048.TestView") + 5
            runBlocking { s.complete(layout, xml, off) }
        }
        watchdog("owned-render", 60_000) {
            s.layoutPreview(layout, xml, PreviewRequest(1080, 1920, 2.0f, showChrome = false))
        }
        // Re-render a few times (the UI re-fetches) to catch a render-loop / cache-thrash.
        watchdog("owned-render-x5", 60_000) {
            repeat(5) { s.layoutPreview(layout, xml, PreviewRequest(1080, 1920, 2.0f, showChrome = false)) }
        }
    }
}
