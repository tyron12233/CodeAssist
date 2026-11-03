package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The symbol bar: which keys it shows for a file, where its trackpad puts the caret, and its gestures driven by
 * real pointer events (tap, flick up, hold-and-drag). Also writes PNGs of the bar for review.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SymbolBarTest {

    @Test
    fun theShippedDefaultYieldsToTheLanguageSetButACustomizedOneStays() {
        val kotlin = effectiveSymbolKeys(DEFAULT_SYMBOL_KEYS, "/p/Main.kt")
        assertEquals("{", kotlin.first { it.action == null }.insert)
        assertTrue(kotlin.any { it.insert == "->" } && kotlin.any { it.insert == "?." })
        assertEquals("<", effectiveSymbolKeys(emptyList(), "/p/res/layout/main.xml").first { it.action == null }.insert)
        assertEquals(";", effectiveSymbolKeys(DEFAULT_SYMBOL_KEYS, "/p/Main.java").first { it.action == null }.insert)
        val mine = listOf(UiSymbolKey("Tab", "", pinned = true, action = CustomizationActions.TAB), UiSymbolKey("$", "$"))
        assertEquals(mine, effectiveSymbolKeys(mine, "/p/Main.kt"))
    }

    @Test
    fun noLanguageSetRepeatsAKeyOrShowsWhatItsAlternateAlreadyTypes() {
        for (path in listOf("a.kt", "a.java", "a.xml", "a.txt")) {
            val inserts = languageSymbolKeys(path).filter { it.action == null }.map { it.insert }
            assertEquals(inserts.distinct(), inserts, "$path repeats a key")
            val alternates = inserts.mapNotNull { SYMBOL_ALTERNATES[it] }.toSet()
            // `;` and `:` are each other's alternate on purpose: both are common enough to want a key of their own.
            val wasted = inserts.filter { it in alternates && SYMBOL_ALTERNATES[it] !in inserts }
            assertEquals(emptyList(), wasted, "$path has a key its neighbour already flicks to")
        }
    }

    // ---- gestures through real pointer events ----

    private class Rig(keys: List<UiSymbolKey>) {
        val typed = ArrayList<String>()
        val actions = ArrayList<String>()
        val moves = ArrayList<Pair<Float, Float>>()
        var began = 0
        var ended = 0
        var t = 0L
        val scene = ImageComposeScene(width = 822, height = 300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg), contentAlignment = Alignment.BottomCenter) {
                    EditorSymbolBar(
                        onSymbol = { typed += it }, onAction = { actions += it }, symbols = keys,
                        trackpad = object : SymbolBarTrackpad {
                            override fun begin() { began++ }
                            override fun move(columns: Float, lines: Float) { moves += columns to lines }
                            override fun end() { ended++ }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Real time passes too: the key's hold timeout is a coroutine timeout, not a frame count.
        fun frames(ms: Long) {
            repeat((ms / 16).toInt()) { Thread.sleep(16); t += 16_000_000L; scene.render(t) }
        }

        fun press(p: Offset) { scene.sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch, timeMillis = t / 1_000_000); frames(32) }
        fun move(p: Offset) { scene.sendPointerEvent(PointerEventType.Move, p, type = PointerType.Touch, timeMillis = t / 1_000_000); frames(16) }
        fun release(p: Offset) { scene.sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch, timeMillis = t / 1_000_000); frames(32) }

        fun shot(name: String) {
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        }
    }

    // At density 2 the bar is the bottom 92px; its keys are 76px tall caps, the first symbol after Tab.
    private val barY = 300f - 46f
    private val firstSymbol = Offset(8f + 80f + 8f + 40f, barY)

    @Test
    fun aTapTypesAFlickUpTypesTheAlternateAndAHoldThenDragMovesTheCaret() {
        val rig = Rig(languageSymbolKeys("Main.kt"))
        try {
            rig.frames(100)
            rig.shot("symbolbar-kotlin.png")

            rig.press(firstSymbol); rig.release(firstSymbol)
            assertEquals(listOf("{"), rig.typed, "a tap types the key")

            rig.press(firstSymbol)
            rig.move(firstSymbol + Offset(0f, -20f))
            rig.move(firstSymbol + Offset(0f, -50f))
            rig.frames(250) // let the glyph's fly-in settle
            rig.shot("symbolbar-flick.png")
            rig.release(firstSymbol + Offset(0f, -50f))
            assertEquals(listOf("{", "}"), rig.typed, "a flick up types the alternate")

            rig.press(firstSymbol)
            rig.frames(700) // past the long-press timeout
            rig.move(firstSymbol + Offset(40f, 0f))
            rig.shot("symbolbar-trackpad.png")
            rig.move(firstSymbol + Offset(80f, 0f))
            rig.release(firstSymbol + Offset(80f, 0f))
            assertEquals(listOf("{", "}"), rig.typed, "the hold typed nothing")
            assertEquals(1 to 1, rig.began to rig.ended, "one floating cursor, let go on release")
            val columns = rig.moves.sumOf { it.first.toDouble() }
            assertTrue(columns in 4.0..5.0, "80px at 18px a column moves it continuously by ~4.4; got $columns")
        } finally {
            rig.scene.close()
        }
    }

    @Test
    fun aSidewaysDragScrollsInsteadOfTyping() {
        val rig = Rig(languageSymbolKeys("Main.kt"))
        try {
            rig.frames(100)
            rig.press(firstSymbol + Offset(200f, 0f))
            for (dx in 1..10) rig.move(firstSymbol + Offset(200f - dx * 20f, 0f))
            rig.release(firstSymbol)
            assertEquals(emptyList(), rig.typed)
            assertEquals(emptyList(), rig.moves)
        } finally {
            rig.scene.close()
        }
    }

    @Test
    fun renderLanguagesAndTheMoreRow() {
        for ((name, keys, expanded) in listOf(
            Triple("symbolbar-xml.png", languageSymbolKeys("strings.xml"), false),
            Triple("symbolbar-more.png", languageSymbolKeys("Main.kt"), true),
        )) {
            val scene = ImageComposeScene(width = 822, height = 300, density = Density(2f)) {
                CodeAssistTheme(dark = true) {
                    Box(Modifier.fillMaxSize().background(Ide.colors.editorBg), contentAlignment = Alignment.BottomCenter) {
                        EditorSymbolBar(
                            onSymbol = {}, onAction = {}, symbols = keys, showDiagnosticJump = true,
                            initiallyExpanded = expanded, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            try {
                var t = 0L
                repeat(6) { t += 16_000_000L; scene.render(t) }
                val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            } finally {
                scene.close()
            }
        }
    }
}
