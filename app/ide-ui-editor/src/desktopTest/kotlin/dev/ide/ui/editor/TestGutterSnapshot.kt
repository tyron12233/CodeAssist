package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.TestResultMemory
import dev.ide.ui.backend.UiDecorationTint
import dev.ide.ui.backend.UiGutterMark
import dev.ide.ui.backend.UiTestCase
import dev.ide.ui.backend.UiTestRun
import dev.ide.ui.backend.UiTestStatus
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNG of test run marks in the real [CodeEditor]: never run, passed and failed, and the class's
 *  mark carrying the worst of its tests. Visual only, dropped at [OUT_DIR]. */
class TestGutterSnapshot {
    private val cls = "com.example.CalcTest"
    private val source = """
        package com.example

        class CalcTest {
            @Test fun adds() {}
            @Test fun divides() {}
            @Test fun parses() {}
        }
    """.trimIndent()

    private fun mark(line: Int, target: String) = UiGutterMark(
        line, "run", UiDecorationTint.Success,
        actionId = TestOutput.GUTTER_ACTION_PREFIX + TestOutput.taskId("app", listOf(target)),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderTestMarks() {
        TestResultMemory.record(
            7001, "app",
            UiTestRun(listOf(UiTestCase(cls, "adds", UiTestStatus.Passed), UiTestCase(cls, "divides", UiTestStatus.Failed)), finished = true),
        )
        val session = EditorSession(source, languageFor("CalcTest.kt"), TextRange(0)).apply {
            applyDecorations(
                emptyList(),
                listOf(mark(2, cls), mark(3, "$cls#adds"), mark(4, "$cls#divides"), mark(5, "$cls#parses")),
                emptyList(),
            )
        }
        val scene = ImageComposeScene(width = 760, height = 360, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    CodeEditor("CalcTest.kt", remember { session }, PreviewBackend, Modifier.fillMaxSize())
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(32_000_000L)
            val out = File("$OUT_DIR/test-gutter.png").apply { parentFile?.mkdirs() }
            out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }.absolutePath
    }
}
