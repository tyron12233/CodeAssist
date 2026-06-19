package dev.ide.lang.jdt

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.SourceDocExternalizer
import dev.ide.index.SourceDocValue
import dev.ide.lang.jdt.index.JavaSourceDocIndex
import dev.ide.platform.ContentHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Java source-doc index extracts per-type real parameter NAMES + javadoc from an attached Java source
 * archive ([IndexOrigin.LIBRARY_SOURCE]), keyed by owner FQN; the shared codec round-trips the value.
 */
class JavaSourceDocIndexTest {

    @Test
    fun extractsNamesAndJavadoc() {
        val src = """
            package com.example;
            /** A widget. */
            public class Widget {
                /** Sets the text.
                 *  @param message the text */
                public void setText(String message, int flags) {}
            }
        """.trimIndent()
        val byFqn = JavaSourceDocIndex.index(Input("com/example/Widget.java", src))
        val widget = byFqn["com.example.Widget"] ?: error("no com.example.Widget; got ${byFqn.keys}")
        val setText = widget.first { it.name == "setText" }
        assertEquals(listOf("message", "flags"), setText.names)
        assertEquals(2, setText.arity)
        assertTrue(setText.doc?.contains("Sets the text") == true, "method javadoc; got ${setText.doc}")
        assertTrue(widget.any { it.name.isEmpty() && it.doc?.contains("A widget") == true }, "class javadoc as the empty-name entry")
    }

    @Test
    fun onlyAcceptsJavaSourceUnits() {
        assertTrue(JavaSourceDocIndex.inputFilter.accepts(Input("a/B.java", "")))
        assertTrue(!JavaSourceDocIndex.inputFilter.accepts(Input("a/B.kt", "")))
    }

    @Test
    fun codecRoundTrips() {
        val v = SourceDocValue("setText", 2, listOf("message", "flags"), "Sets the text.")
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { SourceDocExternalizer.write(it, v) }
        assertEquals(v, DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { SourceDocExternalizer.read(it) })
        val n = SourceDocValue("", -1, emptyList(), null)
        val b2 = ByteArrayOutputStream(); DataOutputStream(b2).use { SourceDocExternalizer.write(it, n) }
        assertEquals(n, DataInputStream(ByteArrayInputStream(b2.toByteArray())).use { SourceDocExternalizer.read(it) })
    }

    private class Input(override val unitName: String, private val src: String) : IndexInput {
        override val origin = IndexOrigin.LIBRARY_SOURCE
        override val contentHash = ContentHash("")
        override val sourcePath: Path? = null
        override fun bytes() = src.toByteArray()
        override fun text() = src
        override fun dom() = null
    }
}
