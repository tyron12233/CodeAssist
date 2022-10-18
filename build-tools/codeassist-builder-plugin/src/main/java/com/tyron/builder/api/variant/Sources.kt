package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Provides access to all source directories for a [Variant].
 *
 * since 7.2
 */
@Incubating
interface Sources {

    /**
     * Access to the Java source folders.
     */
    val java: SourceDirectories.Flat?

    /**
     * Access ot the Kotlin source folders.
     */
    val kotlin: SourceDirectories.Flat?

    /**
     * Access to the Android resources sources folders.
     */
    val res: SourceDirectories.Layered?

    /**
     * Access to the Java-style resources sources folders.
     */
    val resources: SourceDirectories.Flat?

    /**
     * Access to the Android assets sources folders.
     */
    val assets: SourceDirectories.Layered?

    /**
     * Access to the JNI libraries folders
     */
    val jniLibs: SourceDirectories.Layered?

    /**
     * Access to the shaders sources folders if [com.android.build.api.dsl.BuildFeatures.shaders]
     * is true otherwise null
     */
    val shaders: SourceDirectories.Layered?

    /**
     * Access to the machine learning models folders.
     */
    val mlModels: SourceDirectories.Layered?

    /**
     * Access to the aidl sources folders if [com.android.build.api.dsl.BuildFeatures.aidl]
     * is true otherwise null
     */
    val aidl: SourceDirectories.Flat?

    /**
     * Access to the renderscript sources folders if
     * [com.android.build.api.dsl.BuildFeatures.renderScript] is true otherwise null.
     */
    @get:Deprecated("renderscript is deprecated and will be removed in a future release.")
    val renderscript: SourceDirectories.Flat?

    /**
     * Access (and potentially creates) a new [Flat] for a custom source type that can
     * be referenced by its [name].
     *
     * The first caller will create the new instance, other callers with the same [name] will get
     * the same instance returned. Any callers can obtain the final list of the folders registered
     * under this custom source type by calling [Flat.all].
     *
     * These sources directories are attached to the variant and will be visible to Android Studio.
     */
    fun getByName(name: String): SourceDirectories.Flat
}