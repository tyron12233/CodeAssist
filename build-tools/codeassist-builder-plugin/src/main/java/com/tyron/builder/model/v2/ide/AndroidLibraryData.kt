package com.tyron.builder.model.v2.ide

import java.io.File

/**
 * Data for Android external Libraries
 */
interface AndroidLibraryData {

    /**
     * The location of the manifest file.
     */
    val manifest: File

    /**
     * The list of jar files for compilation.
     */
    val compileJarFiles: List<File>

    /**
     * The list of jar files for runtime/packaging.
     */
    val runtimeJarFiles: List<File>

    /**
     * The android resource folder.
     *
     * The folder may not exist.
     */
    val resFolder: File

    /**
     * The namespaced resources static library (res.apk).
     */
    val resStaticLibrary: File

    /**
     * The assets folder.
     *
     * The folder may not exist.
     */
    val assetsFolder: File

    /**
     * The jni libraries folder.
     *
     * The folder may not exist.
     */
    val jniFolder: File

    /**
     * The AIDL import folder
     *
     * The folder may not exist.
     */
    val aidlFolder: File

    /**
     * The RenderScript import folder
     *
     * The folder may not exist.
     */
    val renderscriptFolder: File

    /**
     * The proguard file rule.
     *
     * The file may not exist.
     */
    val proguardRules: File

    /**
     * the zip file with external annotations
     *
     * The file may not exist.
     */
    val externalAnnotations: File

    /**
     * The file listing the public resources
     *
     * The file may not exist.
     */
    val publicResources: File

    /**
     * The symbol list file
     *
     * The file may not exist.
     */
    val symbolFile: File
}
