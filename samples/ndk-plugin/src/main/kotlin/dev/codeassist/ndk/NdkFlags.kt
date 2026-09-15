package dev.codeassist.ndk

import java.nio.file.Path

/**
 * The flags a module's C and C++ are compiled with, in ONE place, because the editor and the build have to
 * agree on them.
 *
 * They are the same compiler, so a flag the build passes and the editor does not shows up as an error that
 * is only in the editor -- a NativeActivity project reports `'android_native_app_glue.h' file not found` on
 * line 2 of a file that builds perfectly, which reads as the plugin being broken rather than as a missing
 * `-I`.
 */
internal object NdkFlags {

    /** What the editor asks clang with: everything that changes what PARSES, and nothing about codegen. */
    fun editor(facet: NdkFacet, toolchain: NdkToolchain, cpp: Boolean): List<String> = buildList {
        add("-std=" + if (cpp) facet.cppStandard else facet.cStandard)
        // The glue is a header the module includes and a source it compiles, both from the packaged
        // directory: without this the editor cannot see the header the build compiles against.
        if (facet.nativeActivity) toolchain.nativeAppGlueDir?.let { add("-I$it") }
        // Last, so a user's own flag wins over anything derived above.
        addAll(facet.compilerFlags)
    }

    /** What the build adds on top: position independence and the optimization level. */
    fun build(facet: NdkFacet, toolchain: NdkToolchain, cpp: Boolean): List<String> =
        listOf("-fPIC", facet.optimization) + editor(facet, toolchain, cpp)

    // -----------------------------------------------------------------------------------------------
    // Reaching the facet from a place the host does not hand a Module
    // -----------------------------------------------------------------------------------------------

    private val byModuleDir = LinkedHashMap<String, NdkFacet>()

    /**
     * Record [facet] as the configuration for everything under [moduleDir].
     *
     * Completion needs these flags and cannot get them: `CompletionParams` carries the document, the caret
     * and the scope, but no `Module`, so a contributor has no way to ask which module its file belongs to
     * (diagnostics, through `AnalysisTarget.module`, does). This is the seam between the two -- the
     * diagnostics provider runs on every open C or C++ file, so by the time a popup is asked for in one, its
     * module's facet has been seen.
     *
     * A miss is not a failure: [facetFor] answers null and the caller uses the defaults, which is what a
     * module carrying no facet would give anyway.
     */
    @Synchronized
    fun remember(moduleDir: String, facet: NdkFacet) {
        byModuleDir[moduleDir.trimEnd('/')] = facet
    }

    /** The facet of the module owning [file], or null when no module containing it has been seen. */
    @Synchronized
    fun facetFor(file: Path): NdkFacet? {
        val path = file.toString()
        // Longest prefix wins, so a nested module is not shadowed by the project root it sits in.
        return byModuleDir.entries
            .filter { path.startsWith(it.key + "/") }
            .maxByOrNull { it.key.length }
            ?.value
    }
}
