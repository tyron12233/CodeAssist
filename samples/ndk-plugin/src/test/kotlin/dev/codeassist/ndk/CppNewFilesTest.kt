package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CppNewFilesTest {

    @Test
    fun aClassIsAHeaderAndASourceThatIncludesIt() {
        val files = CppNewFiles.cppClass(CppNewFiles.className("Widget"))
        assertEquals(listOf("Widget.h", "Widget.cpp"), files.map { it.first })
        assertTrue("class Widget {" in files[0].second)
        assertTrue("#include \"Widget.h\"" in files[1].second)
    }

    @Test
    fun aClassNameMustBeAnIdentifier() {
        assertEquals("Widget", CppNewFiles.className(" Widget.h "))
        assertThrows(IllegalArgumentException::class.java) { CppNewFiles.className("2d") }
        assertThrows(IllegalArgumentException::class.java) { CppNewFiles.className("my-class") }
        assertThrows(IllegalArgumentException::class.java) { CppNewFiles.className("") }
    }

    @Test
    fun aSourceKeepsAnExtensionItAlreadyHas() {
        assertEquals("engine.cpp", CppNewFiles.source("engine").first)
        assertEquals("glue.c", CppNewFiles.source("glue.c").first)
        assertTrue(CppNewFiles.source("glue.c").second.startsWith("#include <stdio.h>"))
        assertEquals("api.hpp", CppNewFiles.header("api.hpp").first)
        assertEquals("api.h", CppNewFiles.header("api").first)
        assertThrows(IllegalArgumentException::class.java) { CppNewFiles.source("a/b") }
    }

    @Test
    fun nativeDirectoriesAreNamedOrAlreadyHoldCpp(@TempDir tmp: File) {
        assertTrue(CppNewFiles.isNativeDirectory("/p/app/src/main/cpp"))
        assertTrue(CppNewFiles.isNativeDirectory("/p/app/src/main/cpp/engine"))
        val java = File(tmp, "src/main/java/com/example").apply { mkdirs() }
        assertFalse(CppNewFiles.isNativeDirectory(java.path))
        File(java, "bridge.cpp").writeText("")
        assertTrue(CppNewFiles.isNativeDirectory(java.path))
    }
}
