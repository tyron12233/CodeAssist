package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The string path arithmetic the model runs on, on both kinds of root. A Windows path used to read as relative
 * (it does not start with `/`), so resolving one absolute path against another appended it
 * (`C:/proj/app/C:/Users/...`) and every build and index pass on Windows failed with "Illegal char <:>".
 */
class PathNamesTest {

    @Test
    fun unixPathsAreUnchanged() {
        assertEquals("/a/c", normalizePath("/a/./b/../c/"))
        assertEquals("/", normalizePath("/"))
        assertEquals("/b", normalizePath("/../b"))
        assertEquals("../b", normalizePath("a/../../b"))
        assertEquals("/Users/x", normalizePath("//Users/x"), "a doubled separator still collapses")
        assertEquals("/a/b", resolvePath("/a", "b"))
        assertEquals("/c", resolvePath("/a", "/c"))
        assertEquals("/a", parentPath("/a/b"))
        assertEquals("/", parentPath("/a"))
        assertNull(parentPath("/"))
        assertEquals("b", fileName("/a/b"))
        assertEquals("b", relativizePath("/a", "/a/b"))
        assertTrue(isAbsolutePath("/a"))
        assertFalse(isAbsolutePath("a/b"))
    }

    @Test
    fun aWindowsDriveIsARoot() {
        assertEquals("C:/Users/me/proj", normalizePath("C:\\Users\\me\\proj\\"))
        assertEquals("C:/b", normalizePath("C:\\a\\..\\..\\b"), "climbing past the drive is discarded")
        assertEquals("C:/", normalizePath("C:"))
        assertTrue(isAbsolutePath("C:\\Users"))
        assertTrue(isAbsolutePath("d:/x"))
        assertFalse(isAbsolutePath("a:b"), "a drive-relative or odd name is not a root")
    }

    @Test
    fun anAbsoluteWindowsPathReplacesTheBase() {
        assertEquals(
            "C:/Users/me/.codeassist/caches/x.jar",
            resolvePath("C:/Users/me/proj/app", "C:\\Users\\me\\.codeassist\\caches\\x.jar"),
        )
        assertEquals("C:/Users/me/proj/app/src", resolvePath("C:\\Users\\me\\proj", "app/src"))
    }

    @Test
    fun parentsAndNamesStopAtTheDrive() {
        assertEquals("C:/Users", parentPath("C:/Users/me"))
        assertEquals("C:/", parentPath("C:/Users"))
        assertNull(parentPath("C:/"))
        assertEquals("me", fileName("C:\\Users\\me"))
        assertEquals("", fileName("C:/"))
        assertEquals("app/src", relativizePath("C:/proj", "C:\\proj\\app\\src"))
        assertEquals("", relativizePath("C:/", "C:/"))
        assertEquals("x", relativizePath("C:/", "C:/x"))
    }
}
