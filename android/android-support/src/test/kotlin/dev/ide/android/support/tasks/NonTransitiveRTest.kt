package dev.ide.android.support.tasks

import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Non-transitive R (AGP 8's default): the app's own R stays complete, while each dependency library's R holds only
 * what its own R.txt lists, with the final ids the app's link assigned. Before, aapt2's --extra-packages gave every
 * one of a Compose app's ~50 library packages the whole table, and dexing those classes took seconds on a phone.
 */
class NonTransitiveRTest {
    @Test
    fun aLibraryRHoldsOnlyItsOwnSymbolsWithTheAppsFinalIds() {
        val dir = Files.createTempDirectory("ntr")
        val rJava = dir.resolve("gen/com/example/app/R.java").also { Files.createDirectories(it.parent) }
        Files.write(rJava, """
            package com.example.app;
            public final class R {
              public static final class attr {
                public static final int colorPrimary=0x7f010001;
                public static final int fontFamily=0x7f010002;
              }
              public static final class string {
                public static final int app_name=0x7f020001;
              }
            }
        """.trimIndent().toByteArray())
        val appRTxt = dir.resolve("R.txt").also {
            Files.write(it, listOf(
                "int attr colorPrimary 0x7f010001",
                "int attr fontFamily 0x7f010002",
                "int string app_name 0x7f020001",
                "int[] styleable FontFamily { 0x7f010002 }",
                "int styleable FontFamily_fontFamily 0",
            ))
        }
        // The font library declares fontFamily and its styleable; nothing about colorPrimary or app_name.
        val libRTxt = dir.resolve("lib-R.txt").also {
            Files.write(it, listOf("int attr fontFamily 0x0", "int[] styleable FontFamily { 0x0 }", "int styleable FontFamily_fontFamily 0"))
        }
        val jar = dir.resolve("R.jar")
        RBytecodeGenerator.writeAppJar(listOf(rJava), appRTxt, listOf("androidx.core.font" to libRTxt), jar)

        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { cl ->
            val appAttr = cl.loadClass("com.example.app.R\$attr")
            assertEquals(0x7f010001, appAttr.getField("colorPrimary").getInt(null), "the app's R stays complete")
            val libAttr = cl.loadClass("androidx.core.font.R\$attr")
            assertEquals(0x7f010002, libAttr.getField("fontFamily").getInt(null), "the library's symbol, with the app's final id")
            assertTrue(Modifier.isFinal(libAttr.getField("fontFamily").modifiers), "final, so a switch on it compiles")
            assertFalse(libAttr.fields.any { it.name == "colorPrimary" }, "nothing the library doesn't declare")
            val styleable = cl.loadClass("androidx.core.font.R\$styleable")
            assertEquals(listOf(0x7f010002), (styleable.getField("FontFamily").get(null) as IntArray).toList())
            assertFalse(runCatching { cl.loadClass("androidx.core.font.R\$string") }.isSuccess, "no string class at all")
        }
    }
}
