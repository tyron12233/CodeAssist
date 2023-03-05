package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.ProguardFilesProvider
import java.io.File

/**
 * This is a common interface to get post-processing options from DSL, no matter which DSL we use
 * old one or a new (block) one
 */
interface PostProcessingOptions : ProguardFilesProvider {
    fun getDefaultProguardFiles(): List<File>

    fun getPostprocessingFeatures(): PostprocessingFeatures?

    fun codeShrinkerEnabled(): Boolean

    fun resourcesShrinkingEnabled(): Boolean

    fun hasPostProcessingConfiguration(): Boolean
}
