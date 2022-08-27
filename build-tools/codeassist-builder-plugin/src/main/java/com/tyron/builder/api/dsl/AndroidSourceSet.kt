package com.tyron.builder.api.dsl

import org.gradle.api.Action
import org.gradle.api.Incubating
import org.gradle.api.Named

/**
 * An AndroidSourceSet represents a logical group of Java, aidl and RenderScript sources
 * as well as Android and non-Android (Java-style) resources.
 */
interface AndroidSourceSet : Named {

    /** Returns the name of this source set. */
    @Incubating
    override fun getName(): String

    /** The Java source for this source-set */
    val java: AndroidSourceDirectorySet
    /** The Java source for this source-set */
    fun java(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Kotlin source for this source-set */
    val kotlin: AndroidSourceDirectorySet
    /** The Java source for this source-set */
    fun kotlin(action: Action<AndroidSourceDirectorySet>)

    /** The Java-style resources for this source-set */
    val resources: AndroidSourceDirectorySet
    /** The Java-style resources for this source-set */
    fun resources(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android Manifest file for this source-set. */
    val manifest: AndroidSourceFile
    /** The Android Manifest file for this source-set. */
    fun manifest(action: AndroidSourceFile.() -> Unit)

    /** The Android Resources directory for this source-set. */
    val res: AndroidSourceDirectorySet
    /** The Android Resources directory for this source-set. */
    fun res(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android Assets directory for this source set.*/
    val assets: AndroidSourceDirectorySet
    /** The Android Assets directory for this source set.*/
    fun assets(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android AIDL source directory for this source set. */
    val aidl: AndroidSourceDirectorySet
    /** The Android AIDL source directory for this source set. */
    fun aidl(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android RenderScript source directory for this source set. */
    val renderscript: AndroidSourceDirectorySet
    /** The Android RenderScript source directory for this source set. */
    fun renderscript(action: AndroidSourceDirectorySet.() -> Unit)

    /**
     * The Android JNI source directory for this source set.
     * @deprecated This is unused and will be removed in AGP 8.0
     */
    @Deprecated("Unused")
    @get:Incubating
    val jni: AndroidSourceDirectorySet
    /**
     * The Android JNI source directory for this source set.
     * @deprecated This is unused and will be removed in AGP 8.0
     */
    @Deprecated("Unused")
    @Incubating
    fun jni(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android JNI libs directory for this source-set */
    val jniLibs: AndroidSourceDirectorySet
    /** The Android JNI libs directory for this source-set */
    fun jniLibs(action: AndroidSourceDirectorySet.() -> Unit)

    /** The Android shaders directory for this source set. */
    val shaders: AndroidSourceDirectorySet

    /** The Android shaders directory for this source set. */
    fun shaders(action: AndroidSourceDirectorySet.() -> Unit)

    /** The machine learning models directory for this source set. */
    val mlModels: AndroidSourceDirectorySet

    /** The machine learning models directory for this source set. */
    fun mlModels(action: AndroidSourceDirectorySet.() -> Unit)

    /** Returns the name of the api configuration for this source set.  */
    @get:Incubating
    val apiConfigurationName: String

    /**
     * Returns the name of the compileOnly configuration for this source set.
     */
    @get:Incubating
    val compileOnlyConfigurationName: String

    /**
     * Returns the name of the implementation configuration for this source set.
     */
    @get:Incubating
    val implementationConfigurationName: String

    /**
     * Returns the name of the implementation configuration for this source set.
     */
    @get:Incubating
    val runtimeOnlyConfigurationName: String

    /**
     * Returns the name of the wearApp configuration for this source set.
     */
    @get:Incubating
    val wearAppConfigurationName: String

    /**
     * Returns the name of the annotation processing tool classpath for this source set.
     */
    @get:Incubating
    val annotationProcessorConfigurationName: String

    /**
     * Sets the root of the source sets to a given path.
     *
     * All entries of the source-set are located under this root directory.
     *
     * This method has a return value for legacy reasons.
     *
     * @param path the root directory path to use.
     */
    @Incubating
    fun setRoot(path: String): Any
}