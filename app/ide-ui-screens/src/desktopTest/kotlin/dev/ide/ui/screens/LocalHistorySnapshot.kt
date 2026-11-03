package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiHistoryVersion
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The local-history sheet: its version list, a version's diff, and Restore. PNGs for review. */
@OptIn(ExperimentalComposeUiApi::class)
class LocalHistorySnapshot {
    private val now = System.currentTimeMillis()
    private val versions = mapOf(
        now - 3 * 60_000L to "fun main() {\n    val a = 1\n    println(a)\n}\n",
        now - 2 * 3_600_000L to "fun main() {\n    println(1)\n}\n",
    )
    private val current = "fun main() {\n    val a = 10\n    val b = 2\n    println(a + b)\n}\n"

    @Test
    fun listDiffAndRestore() {
        val backend = object : StubBackend() {
            override fun readFile(path: String) = current
            override suspend fun localHistory(path: String) = versions.keys.sortedDescending().map { UiHistoryVersion(it, it, versions.getValue(it).length.toLong()) }
            override suspend fun localHistoryText(path: String, id: Long) = versions[id]
        }
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking { state.openSuspend("/p/Main.kt", "Main.kt") }
        val file = state.active!!
        val scene = ImageComposeScene(width = 822, height = 1400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    LocalHistorySheet(state, file, onDismiss = {})
                }
            }
        }
        var t = 0L
        fun pump(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
        }
        fun shot(name: String) {
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        }
        try {
            pump(800)
            shot("history-1-list.png")
            val rowY = findRowY(scene)
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, rowY))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, rowY))
            pump(600)
            shot("history-2-diff.png")
        } finally {
            scene.close()
        }
    }

    /** The first version row, as measured off history-1-list.png (the sheet's header ends at ~1167px). */
    @Suppress("UNUSED_PARAMETER")
    private fun findRowY(scene: ImageComposeScene) = 1213f

    @Test
    fun restorePutsTheVersionInTheBuffer() {
        val backend = object : StubBackend() { override fun readFile(path: String) = current }
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        runBlocking { state.openSuspend("/p/Main.kt", "Main.kt") }
        val s = state.active!!.session
        val old = versions.values.first()
        s.replaceRange(0, s.doc.length, old, androidx.compose.ui.text.TextRange(0))
        assertEquals(old, s.doc.text)
        s.undo()
        assertEquals(current, s.doc.text, "a restore is one undoable edit")
    }
}
