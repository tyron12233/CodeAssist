package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.model.BuildType
import org.gradle.api.provider.Property

/**
 * A build type with addition properties for building with Gradle plugin.
 */
@Deprecated("Use a more specific type instead")
interface CoreBuildType : BuildType {
    val ndkConfig: CoreNdkOptions?
    val externalNativeBuildOptions: CoreExternalNativeBuildOptions?
    val javaCompileOptions: JavaCompileOptions
    val shaders: CoreShaderOptions

    @get:Deprecated("Use {@link AndroidResourcesCreationConfig#useResourceShrinker()} instead. ")
    val isShrinkResources: Boolean

    @get:Deprecated("Use {@link VariantScope#getCodeShrinker()} instead. ")
    val isUseProguard: Boolean?

    val isCrunchPngs: Boolean?

    @get:Deprecated("Can go away once {@link AaptOptions#cruncherEnabled} is removed. ")
    val isCrunchPngsDefault: Boolean

    fun getIsDefault(): Property<Boolean>
}
