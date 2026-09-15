package dev.codeassist.ndk

import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey

/**
 * A module's native configuration, as a `[ndk]` table in its `module.toml`.
 *
 * This is the whole build configuration, declared rather than scripted. There is no CMakeLists to parse and
 * no second build system to drive: the plugin reads these fields and calls clang, which is the only reason
 * a C++ build can happen on a phone at all without shipping CMake and ninja alongside the compiler.
 *
 * Everything has a default that builds something sensible, so a module that names the facet and nothing else
 * still compiles `src/main/cpp` into a shared library for this device's ABI.
 */
data class NdkFacet(
    /** Directories holding the module's C and C++ sources, relative to the module. */
    val sourceDirs: List<String> = listOf("src/main/cpp"),

    /**
     * The name of the library produced, without the `lib` prefix or the `.so` suffix, matching what
     * `System.loadLibrary` is given.
     */
    val libraryName: String = "native-lib",

    /**
     * ABIs to build for.
     *
     * One by default, and the device's own, because that is what the packaged toolchain can target: the
     * compiler ships with a single backend to keep the plugin installable. Naming more here is what a build
     * for release would want and what the toolchain would have to be rebuilt for.
     */
    val abis: List<String> = listOf("arm64-v8a"),

    /** The Android API level to compile against; the default matches the IDE's own floor. */
    val minSdk: Int = 26,

    val cppStandard: String = "c++17",

    /**
     * The standard `.c` files are compiled to, as `-std` takes it.
     *
     * Separate from [cppStandard] because clang refuses the other language's value outright: a `.c` compiled
     * with `-std=c++17` fails with "invalid argument ... not allowed with 'C'", and a module with one C file
     * in it (the NDK's own `android_native_app_glue.c`, for instance) would stop building.
     */
    val cStandard: String = "c17",

    /** `c++_shared` or `c++_static`. Shared is the default because the plugin packages that one. */
    val stl: String = "c++_shared",

    /** Optimization level passed to clang, as written (`-O2`, `-Oz`, `-O0`). */
    val optimization: String = "-O2",

    /** Extra flags, appended after everything the facet derives, so they win. */
    val compilerFlags: List<String> = emptyList(),

    /** Libraries to link, named the way `-l` takes them: `log`, `android`, `GLESv2`. */
    val linkLibraries: List<String> = listOf("log"),

    /**
     * Build this module as a **NativeActivity**: an app with no Java at all, whose entry point is
     * `android_main` and whose manifest names `android.app.NativeActivity`.
     *
     * When set, the toolchain's packaged `android_native_app_glue` is compiled in and its header directory is
     * put on the include path. That glue is source rather than a library -- the NDK ships it to be compiled
     * into each app -- so there is nothing to link and nothing to find at runtime; it simply has to be built
     * alongside the module's own sources, which is what this switches on.
     *
     * The library name matters more here than elsewhere: the platform loads it by the name in the manifest's
     * `android.app.lib_name`, so [libraryName] and that value are one setting in two files.
     */
    val nativeActivity: Boolean = false,
) : Facet {
    override val key get() = KEY

    companion object {
        /**
         * Reference identity, like every [FacetKey]: the facet and its codec must name this same `val`, and
         * two keys sharing an id would still be two different keys.
         */
        val KEY = FacetKey<NdkFacet>("ndk")
    }
}

/**
 * Persists [NdkFacet] as the `[ndk]` table of `module.toml`.
 *
 * Decoding is forgiving on purpose: this table is meant to be edited by hand (there is no Module Settings
 * tab a plugin can add), so a missing key falls back to the default and a mistyped one is ignored rather
 * than failing the project's load. A value that is present but the wrong shape is the one case worth being
 * strict about, and even then it degrades to the default rather than throwing, because the alternative is a
 * project that will not open.
 */
object NdkFacetCodec : FacetCodec<NdkFacet> {
    override val key = NdkFacet.KEY
    override val tomlTable = "ndk"

    override fun encode(facet: NdkFacet): Map<String, Any?> = mapOf(
        "sourceDirs" to facet.sourceDirs,
        "libraryName" to facet.libraryName,
        "abis" to facet.abis,
        "minSdk" to facet.minSdk,
        "cppStandard" to facet.cppStandard,
        "cStandard" to facet.cStandard,
        "stl" to facet.stl,
        "optimization" to facet.optimization,
        "compilerFlags" to facet.compilerFlags,
        "linkLibraries" to facet.linkLibraries,
        "nativeActivity" to facet.nativeActivity,
    )

    override fun decode(values: Map<String, Any?>): NdkFacet {
        val defaults = NdkFacet()
        return NdkFacet(
            sourceDirs = values.strings("sourceDirs", defaults.sourceDirs),
            libraryName = values.string("libraryName", defaults.libraryName),
            abis = values.strings("abis", defaults.abis),
            minSdk = values.int("minSdk", defaults.minSdk),
            cppStandard = values.string("cppStandard", defaults.cppStandard),
            cStandard = values.string("cStandard", defaults.cStandard),
            stl = values.string("stl", defaults.stl),
            optimization = values.string("optimization", defaults.optimization),
            compilerFlags = values.strings("compilerFlags", defaults.compilerFlags),
            linkLibraries = values.strings("linkLibraries", defaults.linkLibraries),
            nativeActivity = values.bool("nativeActivity", defaults.nativeActivity),
        )
    }

    private fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
        is Boolean -> v
        // A hand-edited file often quotes it, and refusing `"true"` would be pedantry.
        is String -> v.trim().lowercase().toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun Map<String, Any?>.string(key: String, default: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() } ?: default

    private fun Map<String, Any?>.int(key: String, default: Int): Int = when (val v = this[key]) {
        is Number -> v.toInt()
        // TOML gives a number, but a hand-edited file often quotes it, and refusing that would be pedantry.
        is String -> v.trim().toIntOrNull() ?: default
        else -> default
    }

    /**
     * A list of strings, tolerating a single bare string where a list was meant.
     *
     * An ABSENT key falls back to the default; a key that is present and empty does not. Those are
     * different statements: `linkLibraries = []` says to link nothing, and answering `["log"]` to it would
     * mean the one setting a user cannot express is the one they wrote down.
     */
    private fun Map<String, Any?>.strings(key: String, default: List<String>): List<String> {
        if (key !in this) return default
        return when (val v = this[key]) {
            is List<*> -> v.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            is String -> v.split(',').map(String::trim).filter(String::isNotEmpty)
            else -> default
        }
    }
}
