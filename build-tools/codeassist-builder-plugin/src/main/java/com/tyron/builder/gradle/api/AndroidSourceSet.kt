package com.tyron.builder.gradle.api
import groovy.lang.Closure

/**
 * An AndroidSourceSet represents a logical group of Java, aidl and RenderScript sources
 * as well as Android and non-Android (Java-style) resources.
 */
@Deprecated("Use  com.tyron.builder.api.dsl.AndroidSourceSet")
interface AndroidSourceSet: com.tyron.builder.api.dsl.AndroidSourceSet {

    override fun getName(): String

    override val resources: AndroidSourceDirectorySet
    fun resources(configureClosure: Closure<*>): AndroidSourceSet

    override val java: AndroidSourceDirectorySet
    fun java(configureClosure: Closure<*>): AndroidSourceSet

    /**
     * Returns the name of the compile configuration for this source set.
     */
    @get:Deprecated("use {@link #getImplementationConfigurationName()}")
    val compileConfigurationName: String

    /**
     * Returns the name of the runtime configuration for this source set.
     */
    @get:Deprecated("use {@link #getRuntimeOnlyConfigurationName()}")
    val packageConfigurationName: String

    /**
     * Returns the name of the compiled-only configuration for this source set.
     */
    @get:Deprecated("use {@link #getCompileOnlyConfigurationName()}")
    val providedConfigurationName: String

    override val manifest: AndroidSourceFile

    /**
     * Configures the location of the Android Manifest for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceFile] which contains the
     * manifest.
     *
     * @param configureClosure The closure to use to configure the Android Manifest.
     * @return this
     */
    fun manifest(configureClosure: Closure<*>): AndroidSourceSet

    override val res: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android Resources for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the resources.
     *
     * @param configureClosure The closure to use to configure the Resources.
     * @return this
     */
    fun res(configureClosure: Closure<*>): AndroidSourceSet

    override val assets: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android Assets for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the assets.
     *
     * @param configureClosure The closure to use to configure the Assets.
     * @return this
     */
    fun assets(configureClosure: Closure<*>): AndroidSourceSet

    override val aidl: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android AIDL source for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the AIDL source.
     *
     * @param configureClosure The closure to use to configure the AIDL source.
     * @return this
     */
    fun aidl(configureClosure: Closure<*>): AndroidSourceSet

    override val renderscript: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android RenderScript source for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the Renderscript source.
     *
     * @param configureClosure The closure to use to configure the Renderscript source.
     * @return this
     */
    fun renderscript(configureClosure: Closure<*>): AndroidSourceSet

    /**
     * The Android JNI source directory for this source set.
     * @deprecated This is unused and will be removed in AGP 8.0
     */
    @Deprecated("Unused")
    override val jni: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android JNI source for this set.
     *
     *
     * @param configureClosure The closure to use to configure the JNI source.
     * @return this
     *
     * @deprecated This is unused and will be removed in AGP 8.0
     */
    @Deprecated("Unused")
    fun jni(configureClosure: Closure<*>): AndroidSourceSet

    override val jniLibs: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android JNI libs for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the JNI libs.
     *
     * @param configureClosure The closure to use to configure the JNI libs.
     * @return this
     */
    fun jniLibs(configureClosure: Closure<*>): AndroidSourceSet

    override val shaders: AndroidSourceDirectorySet

    /**
     * Configures the location of the Android shaders for this set.
     *
     *
     * The given closure is used to configure the [AndroidSourceDirectorySet]
     * which contains the shaders.
     *
     * @param configureClosure The closure to use to configure the shaders.
     * @return this
     */
    fun shaders(configureClosure: Closure<*>): AndroidSourceSet

    override val mlModels: AndroidSourceDirectorySet

    /**
     * Configures the location of the machine learning models for this set.
     *
     * <p>The given closure is used to configure the {@link AndroidSourceDirectorySet} which
     * contains the ml models.
     *
     * @param configureClosure The closure to use to configure the ml models.
     * @return this
     */
    fun mlModels(configureClosure: Closure<*>): AndroidSourceSet

    override fun setRoot(path: String): AndroidSourceSet
}