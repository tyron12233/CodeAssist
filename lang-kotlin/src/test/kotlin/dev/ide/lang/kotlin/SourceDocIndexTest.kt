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

    /** No KDoc anywhere → nothing to add over `@Metadata` → the file is skipped entirely (empty result). */
    @Test
    fun skipsFilesWithNoKdoc() {
        val src = """
            package com.example
            class Plain {
                fun a(x: Int) {}
                fun b() = 42
            }
        """.trimIndent()
        assertTrue(KotlinSourceDocIndex.index(Input("com/example/Plain.kt", src)).isEmpty())
    }

    /** Generic argument commas (`Map<String, Int>`) must NOT be counted as parameter separators. */
    @Test
    fun aritySkipsCommasInsideGenerics() {
        val src = """
            package p
            class C {
                /** doc */
                fun f(m: Map<String, Int>, x: Int, cb: (Int, Int) -> Unit) {}
            }
        """.trimIndent()
        val f = KotlinSourceDocIndex.index(Input("p/C.kt", src))["p.C"]!!.first { it.name == "f" }
        assertEquals(3, f.arity)
        assertEquals(listOf("m", "x", "cb"), f.names)
    }

    /** Nested types get their own FQN bucket; the enclosing class body closing must pop the nesting. */
    @Test
    fun nestedTypesAndClassDoc() {
        val src = """
            package p
            /** Outer doc. */
            class Outer {
                /** Inner doc. */
                class Inner {
                    /** m doc */
                    fun m(a: Int) {}
                }
                /** sibling doc */
                fun sibling() {}
            }
        """.trimIndent()
        val byFqn = KotlinSourceDocIndex.index(Input("p/Outer.kt", src))
        assertTrue(byFqn["p.Outer"]!!.any { it.name.isEmpty() && it.doc?.contains("Outer doc") == true }, "Outer class doc")
        assertTrue(byFqn["p.Outer.Inner"]!!.any { it.name.isEmpty() && it.doc?.contains("Inner doc") == true }, "Inner class doc")
        assertTrue(byFqn["p.Outer.Inner"]!!.any { it.name == "m" }, "Inner.m member")
        val sib = byFqn["p.Outer"]!!.first { it.name == "sibling" }
        assertEquals(0, sib.arity)
        // `sibling` must be attributed to Outer, NOT Outer.Inner (nesting popped after Inner's body).
        assertTrue(byFqn["p.Outer.Inner"]!!.none { it.name == "sibling" }, "sibling leaked into Inner")
    }

    /** A companion object's members key under `Outer.Companion` (matching the binary `Outer$Companion` FQN). */
    @Test
    fun companionObjectMembers() {
        val src = """
            package p
            class Host {
                companion object {
                    /** create doc */
                    fun create(id: String): Host = Host()
                }
            }
        """.trimIndent()
        val create = KotlinSourceDocIndex.index(Input("p/Host.kt", src))["p.Host.Companion"]!!.first { it.name == "create" }
        assertEquals(listOf("id"), create.names)
        assertTrue(create.doc?.contains("create doc") == true)
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
