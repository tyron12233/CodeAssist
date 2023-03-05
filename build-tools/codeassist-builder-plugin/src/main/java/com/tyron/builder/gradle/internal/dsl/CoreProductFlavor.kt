package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.model.ProductFlavor
import org.gradle.api.Named

/**
 * A product flavor with addition properties for building with Gradle plugin. @Deprecated do not
 * use. Use a more specific type instead
 */
@Deprecated("Use com.tyron.builder.api.dsl.ProductFlavor instead")
interface CoreProductFlavor : ProductFlavor, Named {
    val ndkConfig: CoreNdkOptions
    val externalNativeBuildOptions: CoreExternalNativeBuildOptions
    val javaCompileOptions: JavaCompileOptions
    val shaders: CoreShaderOptions
}