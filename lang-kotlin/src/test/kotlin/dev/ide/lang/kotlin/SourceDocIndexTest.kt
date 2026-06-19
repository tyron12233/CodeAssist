package dev.ide.lang.kotlin

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.platform.ContentHash
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin source-doc index extracts per-class real parameter NAMES + KDoc from an attached Kotlin source
 * archive ([IndexOrigin.LIBRARY_SOURCE]), keyed by the declaring class FQN.
 */
class SourceDocIndexTest {

    @Test
    fun extractsNamesAndKdoc() {
        val src = """
            package com.example
            class Greeter {
                /** Greets [who]. */
                fun greet(who: String, loud: Boolean) {}
            }
        """.trimIndent()
        val byFqn = KotlinSourceDocIndex.index(Input("com/example/Greeter.kt", src))
        val greeter = byFqn["com.example.Greeter"] ?: error("no com.example.Greeter; got ${byFqn.keys}")
        val greet = greeter.first { it.name == "greet" }
        assertEquals(listOf("who", "loud"), greet.names)
        assertEquals(2, greet.arity)
        assertTrue(greet.doc?.contains("Greets") == true, "method KDoc; got ${greet.doc}")
    }

    @Test
    fun onlyAcceptsKotlinSourceUnits() {
        assertTrue(KotlinSourceDocIndex.inputFilter.accepts(Input("a/B.kt", "")))
        assertTrue(!KotlinSourceDocIndex.inputFilter.accepts(Input("a/B.java", "")))
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
