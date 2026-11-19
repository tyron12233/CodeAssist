package dev.ide.embedded

import dev.ide.core.IdeServices
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.remote.RemoteEditor
import java.io.File
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The editor's completion and diagnostics as the iOS app asks for them: from the engine in the embedded host,
 * through nothing but the static call and the bytes either way.
 */
class EmbeddedEditorTest {

    private val editor = RemoteEditor.Client { EmbeddedLayoutHost.call(it) }

    @Test
    fun completionAndDiagnosticsAnswerFromTheRealEngineAcrossTheChannel() {
        withTempDir("ide-embedded-editor") { dir ->
            assertEquals("No project is open in the embedded backend.",
                assertFailsWith<IllegalStateException> { editor.complete("x.java", "", 0) }.message)

            IdeServices.bootstrapDemo(dir).close()
            assertEquals("", EmbeddedLayoutHost.open(dir.toString(), System.getProperty("layoutlib.home"), File(System.getProperty("render.out"), "cache").path))
            try {
                val source = dir.toFile().walkTopDown().first { it.name == "MyChart.java" }.toPath()
                val text = source.readText()
                val body = text.indexOf('{', text.indexOf("class MyChart")) + 1

                // A statement the compiler rejects, in a field initializer.
                val broken = text.substring(0, body) + "\n    int wrong = \"text\";" + text.substring(body)
                val errors = editor.analyze(source.toString(), broken).filter { it.severity == UiSeverity.Error }
                assertTrue(errors.any { broken.substring(it.startOffset, it.endOffset).contains("text") }, "errors: $errors")
                assertTrue(editor.analyze(source.toString(), text).none { it.severity == UiSeverity.Error }, "the file as it is has none")

                // A type half typed. The engine indexes the platform as the project opens, and offers what it has.
                val typed = text.substring(0, body) + "\n    Strin" + text.substring(body)
                val offset = body + "\n    Strin".length
                var labels = emptyList<String>()
                val deadline = System.nanoTime() + 120_000_000_000L
                while ("String" !in labels && System.nanoTime() < deadline) {
                    val result = editor.complete(source.toString(), typed, offset)
                    labels = result.items.map { it.label }
                    if ("String" in labels) {
                        assertEquals("Strin", typed.substring(result.replaceStart, result.replaceEnd))
                        assertTrue(result.items.first { it.label == "String" }.insertText.startsWith("String"))
                    } else Thread.sleep(500)
                }
                assertTrue("String" in labels, "completion offered: ${labels.take(20)}")
            } finally {
                EmbeddedLayoutHost.close()
            }
        }
    }
}
