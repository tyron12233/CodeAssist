package dev.ide.ui.screens

import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind.INPUT
import dev.ide.ui.backend.ConsoleChunkKind.OUTPUT
import dev.ide.ui.backend.ConsoleChunkKind.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleLinesTest {

    private fun texts(vararg chunks: ConsoleChunk, max: Int = MAX_CONSOLE_LINE_CHARS) = consoleLines(chunks.toList(), max).map { it.text }

    @Test
    fun splitsOnNewlinesWithoutATrailingEmptyLine() {
        assertEquals(listOf("a", "", "b"), texts(ConsoleChunk("a\n\nb\n", OUTPUT)))
        assertEquals(listOf("a", "partial"), texts(ConsoleChunk("a\npartial", OUTPUT)))
    }

    @Test
    fun aPromptAndItsEchoedInputShareALine() {
        val lines = consoleLines(listOf(
            ConsoleChunk("Name? ", OUTPUT), ConsoleChunk("World\n", INPUT), ConsoleChunk("Hi\n", OUTPUT), ConsoleChunk("\nexit 0\n", SYSTEM),
        ))
        assertEquals(listOf("Name? World", "Hi", "", "exit 0"), lines.map { it.text })
        assertEquals(listOf(OUTPUT, INPUT), lines[0].parts.map { it.kind })
    }

    @Test
    fun aLineSpanningChunksStaysOneLine() {
        assertEquals(listOf("hello world", "x"), texts(ConsoleChunk("hel", OUTPUT), ConsoleChunk("lo world\nx", OUTPUT)))
        assertEquals(1, consoleLines(listOf(ConsoleChunk("hel", OUTPUT), ConsoleChunk("lo\n", OUTPUT)))[0].parts.size)
    }

    @Test
    fun dropsCarriageReturns() {
        assertEquals(listOf("a", "b"), texts(ConsoleChunk("a\r", OUTPUT), ConsoleChunk("\nb\r\n", OUTPUT)))
    }

    @Test
    fun hardWrapsAnOverlongLine() {
        assertEquals(listOf("abcd", "efgh", "ij", "k"), texts(ConsoleChunk("abcdefghij\nk", OUTPUT), max = 4))
        assertEquals(listOf("abcd", "efgh"), texts(ConsoleChunk("ab", OUTPUT), ConsoleChunk("cdefgh\n", INPUT), max = 4))
    }

    @Test
    fun aMegabyteTranscriptIsCutQuickly() {
        val text = buildString { for (i in 1..20_000) append("line $i: the quick brown fox jumps over the dog\n") }
        val chunks = text.chunked(8192).map { ConsoleChunk(it, OUTPUT) }
        val lines = consoleLines(chunks)
        assertEquals(20_000, lines.size)
        assertEquals("line 20000: the quick brown fox jumps over the dog", lines.last().text)
    }
}
