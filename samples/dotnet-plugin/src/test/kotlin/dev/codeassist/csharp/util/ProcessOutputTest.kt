package dev.codeassist.csharp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8ChunksTest {

    @Test
    fun `ascii passes through`() {
        val bytes = "hello".toByteArray()
        assertEquals("hello", Utf8Chunks().decode(bytes, bytes.size))
    }

    @Test
    fun `only the first length bytes are read`() {
        val bytes = "hello".toByteArray()
        assertEquals("he", Utf8Chunks().decode(bytes, 2))
    }

    @Test
    fun `a character split across two chunks is carried over`() {
        val euro = "€".toByteArray() // three bytes
        val chunks = Utf8Chunks()
        assertEquals("", chunks.decode(euro.copyOfRange(0, 1), 1))
        assertEquals("", chunks.decode(euro.copyOfRange(1, 2), 1))
        assertEquals("€", chunks.decode(euro.copyOfRange(2, 3), 1))
    }

    @Test
    fun `text after a split character is kept`() {
        val bytes = "a€b".toByteArray()
        val chunks = Utf8Chunks()
        val first = chunks.decode(bytes, 2) // 'a' and the first byte of the euro sign
        val second = chunks.decode(bytes.copyOfRange(2, bytes.size), bytes.size - 2)
        assertEquals("a€b", first + second)
    }

    @Test
    fun `finish replaces an unfinished character`() {
        val euro = "€".toByteArray()
        val chunks = Utf8Chunks()
        chunks.decode(euro, 2)
        assertEquals("�", chunks.finish())
    }

    @Test
    fun `finish with nothing pending is empty`() {
        assertEquals("", Utf8Chunks().finish())
    }

}

class LineSplitterTest {

    private val lines = ArrayList<String>()
    private val splitter = LineSplitter { lines += it }

    @Test
    fun `complete lines are logged at once`() {
        splitter.append("one\ntwo\n")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `an unfinished line waits for its newline`() {
        splitter.append("par")
        assertEquals(emptyList<String>(), lines)
        splitter.append("tial\n")
        assertEquals(listOf("partial"), lines)
    }

    @Test
    fun `carriage returns are trimmed`() {
        splitter.append("one\r\ntwo\r\n")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `a blank line is logged as empty`() {
        splitter.append("a\n\nb\n")
        assertEquals(listOf("a", "", "b"), lines)
    }

    @Test
    fun `flush logs the last line that had no newline`() {
        splitter.append("done")
        splitter.flush()
        assertEquals(listOf("done"), lines)
    }

    @Test
    fun `flush with nothing pending logs nothing`() {
        splitter.append("one\n")
        splitter.flush()
        assertEquals(listOf("one"), lines)
    }

    @Test
    fun `flush clears the pending line`() {
        splitter.append("done")
        splitter.flush()
        splitter.flush()
        assertEquals(listOf("done"), lines)
    }
}
