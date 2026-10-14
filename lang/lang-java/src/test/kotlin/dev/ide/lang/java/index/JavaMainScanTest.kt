package dev.ide.lang.java.index

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where each Java `main` sits, for the editor's run marks, and that the index's view of the file is unchanged. */
class JavaMainScanTest {

    @Test
    fun hitsPointAtEachMainsName() {
        val text = """
            package com.example;

            public class App {
                public static void main(String[] args) {}

                static class Nested {
                    public static void main(String... args) {}
                }
            }
        """.trimIndent()
        val hits = JavaMainScan.hits(text)
        assertEquals(listOf("com.example.App", "com.example.App\$Nested"), hits.map { it.fqn })
        hits.forEach { assertEquals("main", text.substring(it.offset, it.offset + 4)) }
        assertEquals(listOf("com.example.App" to false, "com.example.App\$Nested" to false), JavaMainScan.scan(text))
    }

    @Test
    fun instanceMainIsAHitToo() {
        val text = "class Tool {\n    void main() {}\n}"
        val hit = JavaMainScan.hits(text).single()
        assertEquals("Tool", hit.fqn)
        assertEquals(true, hit.instance)
        assertEquals(text.indexOf("main"), hit.offset)
    }
}
