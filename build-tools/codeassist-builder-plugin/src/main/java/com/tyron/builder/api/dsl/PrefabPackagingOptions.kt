package com.tyron.builder.api.dsl
import org.gradle.api.Incubating

/**
 * Options for including [Prefab](https://google.github.io/prefab/) packages in AARs.
 */
@Incubating
interface PrefabPackagingOptions {
    /**
     * The name of the library from the external native build to include in the AAR.
     *
     * The name of the library must match the name of the library used by the external native build.
     * For an ndk-build project, this is the LOCAL_MODULE option. For a CMake project, this is the
     * name of the target.
     *
     * This name will be the name of the module in the prefab package, and the package name will be
     * the name of the gradle project.
     */
    var name: String

    /**
     * Path to a directory containing headers to export to dependents of this module.
     *
     * Note that any file in this directory will be included.
     *
     * If not set, no headers will be exported for this library.
     */
    var headers: String?

    /**
     * The name of the library file, if it does not match the convention of lib<name>.so or
     * lib<name>.a.
     *
     * This option can be used to specify the name of the library file if it does not match the name
     * of the library in the build system. For example, if a CMake target is named "mylib" but the
     * name of the library file was overridden to "mylib.so" rather than the default of
     * "libmylib.so" using OUTPUT_NAME, libraryName should be set to "mylib".
     *
     * If this option is null, the default convention of lib<name> will be used.
     */
    var libraryName: String?

    /**
     * True if the library is header only and contains no library files.
     *
     * This value defaults to false.
     */
    var headerOnly: Boolean
}
