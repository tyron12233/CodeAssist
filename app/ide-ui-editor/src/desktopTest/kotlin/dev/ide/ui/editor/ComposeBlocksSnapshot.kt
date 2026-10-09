package dev.ide.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiSignatureParam
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Compose code as blocks, rendered to PNGs: a counter screen (property rows, short values, composable
 * coloring, trailing lambdas) and an annotated-string builder (a builder lambda as a value). Signature help
 * answers with Compose's real parameter lists. Output in `<tmpdir>/codeassist-snapshots`.
 */
class ComposeBlocksSnapshot {

    private val counter = """
        package demo

        @Composable
        fun CounterScreen() {
            var count by remember { mutableStateOf(0) }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "Count: ${'$'}count", style = MaterialTheme.typography.headlineMedium)
                    Button(onClick = { count++ }) {
                        Text("Increment")
                    }
                }
            }
        }
    """.trimIndent()

    private val annotated = """
        package com.example.compose

        @Composable
        fun AnnotatedTextExample() {
            Text(
                text = buildAnnotatedString {
                    append("This is an example of ")
                    withStyle(
                        style = SpanStyle(
                            color = Color.Blue,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append("AnnotatedString")
                    }
                    append(" using SpanStyles.")
                },
                modifier = Modifier.padding(16.dp),
                fontSize = 16.sp
            )
        }
    """.trimIndent()

    @Test
    fun renderCounter() = render(counter, "compose-counter.png")

    @Test
    fun renderAnnotated() = render(annotated, "compose-annotated.png")

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(src: String, name: String) {
        val session = EditorSession(src, languageFor("Main.kt"))
        val scene = ImageComposeScene(width = 1700, height = 1100, density = Density(2f)) {
            CodeAssistTheme(dark = true) { BlockEditor("/project/src/Main.kt", session, ComposeSignatures(), Modifier.fillMaxSize()) }
        }
        fun settle(ms: Long) { val end = System.currentTimeMillis() + ms; var t = 0L; while (System.currentTimeMillis() < end) { scene.render(t); t += 16_000_000L; Thread.sleep(30) } }
        try {
            settle(1500)
            val p = Offset(150f * 2, 64f * 2)
            scene.sendPointerEvent(PointerEventType.Press, p)
            scene.sendPointerEvent(PointerEventType.Release, p)
            settle(1500)
            File(OUT, name).writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private companion object {
        val OUT = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}

/** The engine backend, answering signature help with Compose's parameter lists by callee name. */
private class ComposeSignatures : EngineBackendForTests() {
    private val sigs = mapOf(
        "Text" to listOf("text: String", "modifier: Modifier = …", "color: Color = …", "fontSize: TextUnit = …", "fontWeight: FontWeight? = …", "style: TextStyle = …"),
        "Surface" to listOf("modifier: Modifier = …", "color: Color = …", "contentColor: Color = …", "content: @Composable () -> Unit"),
        "Column" to listOf("modifier: Modifier = …", "verticalArrangement: Arrangement.Vertical = …", "horizontalAlignment: Alignment.Horizontal = …", "content: @Composable ColumnScope.() -> Unit"),
        "Button" to listOf("onClick: () -> Unit", "modifier: Modifier = …", "enabled: Boolean = …", "content: @Composable RowScope.() -> Unit"),
        "fillMaxSize" to listOf("fraction: Float = …"),
        "padding" to listOf("all: Dp"),
        "SpanStyle" to listOf("color: Color = …", "fontSize: TextUnit = …", "fontWeight: FontWeight? = …"),
        "withStyle" to listOf("style: SpanStyle", "block: AnnotatedString.Builder.() -> R"),
        "buildAnnotatedString" to listOf("builder: AnnotatedString.Builder.() -> Unit"),
        "append" to listOf("text: String"),
        "mutableStateOf" to listOf("value: T"),
        "remember" to listOf("calculation: () -> T"),
    )

    override suspend fun signatureHelp(path: String, text: String, offset: Int): UiSignatureHelp? {
        // The callee: the identifier before the `(` that encloses the offset.
        var depth = 0
        var i = offset - 1
        while (i >= 0) {
            when (text[i]) { ')' -> depth++; '(' -> if (depth == 0) break else depth-- }
            i--
        }
        if (i < 0) return null
        var j = i
        while (j > 0 && text[j - 1].isLetterOrDigit()) j--
        val params = sigs[text.substring(j, i)] ?: return null
        return UiSignatureHelp(listOf(UiSignature("", params.map { UiSignatureParam(it.replace("…", "…")) })))
    }
}
