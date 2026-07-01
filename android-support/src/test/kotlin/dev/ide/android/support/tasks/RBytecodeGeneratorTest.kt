package dev.ide.android.support.tasks

import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RBytecodeGenerator] turns an aapt2 `R.java` into loadable `R` bytecode — the int constants, the multi-line
 * `R.styleable` `int[]` arrays, and the per-attr index constants must all survive, so app/library code linking
 * against them resolves the same ids it would from a javac/ecj-compiled `R`.
 */
class RBytecodeGeneratorTest {

    @Test
    fun generatesLoadableRWithConstantsAndStyleableArrays() {
        val dir = Files.createTempDirectory("rbytecode")
        try {
            val rJava = dir.resolve("R.java")
            Files.write(rJava, """
                /* AUTO-GENERATED FILE. DO NOT MODIFY. */
                package com.example.app;

                public final class R {
                  public static final class string {
                    public static final int app_name=0x7f0e0000;
                    public static final int hello=0x7f0e0001;
                  }
                  public static final class attr {
                    public static final int dividerVisible=0x7f010000;
                  }
                  public static final class styleable {
                    public static final int[] SearchView={
                      0x7f010000, 0x01010034, 0x7f010001
                    };
                    public static final int SearchView_dividerVisible=0;
                    public static final int SearchView_android_text=1;
                    public static final int SearchView_extra=2;
                  }
                }
            """.trimIndent().toByteArray())

            val rJar = dir.resolve("R.jar")
            val count = RBytecodeGenerator.writeJar(listOf(rJava), rJar)
            assertEquals(4, count, "R + R\$string + R\$attr + R\$styleable")
            assertTrue(Files.exists(rJar), "R.jar emitted")

            URLClassLoader(arrayOf(rJar.toUri().toURL()), null).use { cl ->
                val str = cl.loadClass("com.example.app.R\$string")
                assertEquals(0x7f0e0000, str.getField("app_name").getInt(null))
                assertEquals(0x7f0e0001, str.getField("hello").getInt(null))

                val attr = cl.loadClass("com.example.app.R\$attr")
                assertEquals(0x7f010000, attr.getField("dividerVisible").getInt(null))

                val st = cl.loadClass("com.example.app.R\$styleable")
                val arr = st.getField("SearchView").get(null) as IntArray
                assertEquals(listOf(0x7f010000, 0x01010034, 0x7f010001), arr.toList(), "multi-line int[] survives")
                assertEquals(0, st.getField("SearchView_dividerVisible").getInt(null))
                assertEquals(1, st.getField("SearchView_android_text").getInt(null))
                assertEquals(2, st.getField("SearchView_extra").getInt(null))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
