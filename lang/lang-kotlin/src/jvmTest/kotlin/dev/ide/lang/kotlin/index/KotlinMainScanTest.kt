package dev.ide.lang.kotlin.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Semantic (PSI parse) detection of runnable `main` entry points in Kotlin source — no regex. */
class KotlinMainScanTest {

    @Test fun topLevelMain() {
        assertEquals(listOf("com.example.MainKt" to false), KotlinMainScan.scan("Main.kt", "package com.example\nfun main() {}"))
    }

    @Test fun topLevelMainWithArgs() {
        assertEquals(listOf("com.example.MainKt" to false), KotlinMainScan.scan("Main.kt", "package com.example\nfun main(args: Array<String>) {}"))
    }

    @Test fun fileJvmNameFacade() {
        assertEquals(listOf("com.example.Boot" to false), KotlinMainScan.scan("Main.kt", "@file:JvmName(\"Boot\")\npackage com.example\nfun main() {}"))
    }

    @Test fun instanceMain() {
        assertEquals(listOf("com.example.Test" to true), KotlinMainScan.scan("Test.kt", "package com.example\nclass Test { fun main() {} }"))
    }

    @Test fun jvmStaticObjectMain() {
        assertEquals(listOf("com.example.O" to false), KotlinMainScan.scan("O.kt", "package com.example\nobject O { @JvmStatic fun main() {} }"))
    }

    @Test fun companionJvmStaticMain() {
        assertEquals(
            listOf("com.example.A" to false),
            KotlinMainScan.scan("A.kt", "package com.example\nclass A { companion object { @JvmStatic fun main(a: Array<String>) {} } }"),
        )
    }

    @Test fun objectMainWithoutJvmStaticNotDetected() {
        assertTrue(
            KotlinMainScan.scan("O.kt", "package com.example\nobject O { fun main() {} }").isEmpty(),
            "an object `main` without @JvmStatic is not a JVM entry point",
        )
    }

    @Test fun instanceMainNeedsNoArgConstructor() {
        assertTrue(
            KotlinMainScan.scan("Test.kt", "package com.example\nclass Test(val x: Int) { fun main() {} }").isEmpty(),
            "a class with a required-arg constructor can't be instantiated to run",
        )
    }

    @Test fun abstractClassInstanceMainIgnored() {
        assertTrue(KotlinMainScan.scan("Test.kt", "package com.example\nabstract class Test { fun main() {} }").isEmpty())
    }

    @Test fun extensionMainIgnored() {
        assertTrue(KotlinMainScan.scan("Ext.kt", "package com.example\nfun String.main() {}").isEmpty())
    }

    @Test fun hitsPointAtEachMainsName() {
        val text = """
            package com.example

            fun main() {}

            object Tool { @JvmStatic fun main(args: Array<String>) {} }

            class Host { companion object { @JvmStatic fun main() {} } }
        """.trimIndent()
        val hits = KotlinMainScan.hits("App.kt", text)
        assertEquals(listOf("com.example.AppKt", "com.example.Tool", "com.example.Host"), hits.map { it.fqn })
        hits.forEach { assertEquals("main", text.substring(it.offset, it.offset + 4)) }
        assertEquals(3, hits.map { text.substring(0, it.offset).count { c -> c == '\n' } }.distinct().size)
    }
}
