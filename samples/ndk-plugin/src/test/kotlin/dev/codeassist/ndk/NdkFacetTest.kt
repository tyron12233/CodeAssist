package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `[ndk]` table, read and written.
 *
 * This file is edited by hand — a plugin cannot add a Module Settings tab — so decoding has to survive what
 * people actually type, and the cases below are the ones that would otherwise silently change what gets
 * built.
 */
class NdkFacetTest {

    @Test
    fun `a full facet round-trips`() {
        val facet = NdkFacet(
            sourceDirs = listOf("src/main/cpp", "src/main/native"),
            libraryName = "engine",
            abis = listOf("arm64-v8a", "x86_64"),
            minSdk = 24,
            cppStandard = "c++20",
            cStandard = "c11",
            stl = "c++_static",
            optimization = "-Oz",
            compilerFlags = listOf("-fno-exceptions", "-DNDEBUG"),
            linkLibraries = listOf("log", "android"),
            nativeActivity = true,
        )
        assertEquals(facet, NdkFacetCodec.decode(NdkFacetCodec.encode(facet)))
    }

    /**
     * The two standards are separate keys because clang refuses the other language's value outright, and a
     * module can hold both languages: a NativeActivity compiles the NDK's `android_native_app_glue.c`
     * alongside its own C++.
     */
    @Test
    fun `the C and C++ standards are read independently`() {
        val decoded = NdkFacetCodec.decode(mapOf("cStandard" to "c99"))
        assertEquals("c99", decoded.cStandard)
        assertEquals(NdkFacet().cppStandard, decoded.cppStandard)
    }

    @Test
    fun `an empty table decodes to the defaults`() {
        assertEquals(NdkFacet(), NdkFacetCodec.decode(emptyMap()))
    }

    @Test
    fun `a missing key keeps its default while the others are read`() {
        val decoded = NdkFacetCodec.decode(mapOf("libraryName" to "engine"))
        assertEquals("engine", decoded.libraryName)
        assertEquals(NdkFacet().cppStandard, decoded.cppStandard)
        assertEquals(NdkFacet().sourceDirs, decoded.sourceDirs)
    }

    /**
     * The one that would otherwise be impossible to say. An absent key means "use the default", but an
     * empty list means "link nothing", and collapsing the two would make the default the only option.
     */
    @Test
    fun `an explicitly empty list is honoured, not replaced by the default`() {
        assertEquals(emptyList<String>(), NdkFacetCodec.decode(mapOf("linkLibraries" to emptyList<String>())).linkLibraries)
        assertEquals(listOf("log"), NdkFacetCodec.decode(emptyMap()).linkLibraries, "absent is still the default")
    }

    @Test
    fun `a quoted boolean is accepted for nativeActivity`() {
        assertTrue(NdkFacetCodec.decode(mapOf("nativeActivity" to "true")).nativeActivity)
        assertTrue(NdkFacetCodec.decode(mapOf("nativeActivity" to true)).nativeActivity)
        assertEquals(false, NdkFacetCodec.decode(mapOf("nativeActivity" to "yes")).nativeActivity)
    }

    @Test
    fun `a bare string is accepted where a list was meant`() {
        val decoded = NdkFacetCodec.decode(mapOf("abis" to "arm64-v8a, x86_64"))
        assertEquals(listOf("arm64-v8a", "x86_64"), decoded.abis)
    }

    @Test
    fun `a quoted number is accepted for minSdk`() {
        assertEquals(21, NdkFacetCodec.decode(mapOf("minSdk" to "21")).minSdk)
        assertEquals(21, NdkFacetCodec.decode(mapOf("minSdk" to 21L)).minSdk)
    }

    /** A project that will not open is a worse answer than one that builds with a default. */
    @Test
    fun `a value of the wrong shape falls back rather than throwing`() {
        val decoded = NdkFacetCodec.decode(
            mapOf("minSdk" to "twenty-one", "abis" to 42, "cppStandard" to "")
        )
        assertEquals(NdkFacet().minSdk, decoded.minSdk)
        assertEquals(NdkFacet().abis, decoded.abis)
        assertEquals(NdkFacet().cppStandard, decoded.cppStandard)
    }

    @Test
    fun `blank entries in a list are dropped`() {
        assertEquals(
            listOf("src/main/cpp"),
            NdkFacetCodec.decode(mapOf("sourceDirs" to listOf("src/main/cpp", "", "   "))).sourceDirs,
        )
    }

    @Test
    fun `the codec claims the ndk table and the facet's own key`() {
        assertEquals("ndk", NdkFacetCodec.tomlTable)
        assertEquals(NdkFacet.KEY, NdkFacetCodec.key)
        assertEquals(NdkFacet.KEY, NdkFacet().key, "a facet must report the key its codec is registered for")
    }

    @Test
    fun `encode writes every key the decoder reads`() {
        val encoded = NdkFacetCodec.encode(NdkFacet())
        val expected = setOf(
            "sourceDirs", "libraryName", "abis", "minSdk",
            "cppStandard", "cStandard", "stl", "optimization", "compilerFlags", "linkLibraries", "nativeActivity",
        )
        assertEquals(expected, encoded.keys, "a key written but not read (or vice versa) loses a setting on save")
    }
}

/**
 * Splitting a module-relative directory into the source set that owns it.
 *
 * `addSourceRoot` builds `<src/SET>/<dirName>`, so the path has to be taken apart rather than passed whole,
 * and a project laid out unconventionally still has to land somewhere sensible.
 */
class NdkSourceRootsTest {

    @Test
    fun `a conventional path splits into its source set and name`() {
        assertEquals("main" to "cpp", NdkSourceRoots.split("src/main/cpp"))
        assertEquals("debug" to "cpp", NdkSourceRoots.split("src/debug/cpp"))
    }

    @Test
    fun `a nested directory keeps the rest of its path as the name`() {
        assertEquals("main" to "cpp/engine", NdkSourceRoots.split("src/main/cpp/engine"))
    }

    /** `native/` and `jni/` are both common; neither belongs to a source set, so both attach to main. */
    @Test
    fun `a directory outside src belongs to main`() {
        assertEquals("main" to "native", NdkSourceRoots.split("native"))
        assertEquals("main" to "jni/src", NdkSourceRoots.split("jni/src"))
    }

    @Test
    fun `leading and trailing slashes do not change the answer`() {
        assertEquals("main" to "cpp", NdkSourceRoots.split("/src/main/cpp/"))
    }
}
