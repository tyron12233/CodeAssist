package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.ProguardFilesProvider
import com.tyron.builder.gradle.internal.dsl.PostProcessingBlock
import java.io.File

/**
 * This is an implementation of PostProcessingOptions interface for the new (block) DSL
 */
class PostProcessingBlockOptions(
    private val postProcessingBlock: PostProcessingBlock,
    private val isTestComponent: Boolean
) : PostProcessingOptions, ProguardFilesProvider by postProcessingBlock {
    override fun getDefaultProguardFiles(): List<File> = emptyList()

    // If the new DSL block is not used, all these flags need to be in the config files.
    override fun getPostprocessingFeatures(): PostprocessingFeatures = PostprocessingFeatures(
        postProcessingBlock.isRemoveUnusedCode,
        postProcessingBlock.isObfuscate,
        postProcessingBlock.isOptimizeCode)

    override fun codeShrinkerEnabled(): Boolean {
        // For testing code, we only run ProGuard/R8 if main code is obfuscated.
        return if (isTestComponent) {
            postProcessingBlock.isObfuscate
        } else {
            postProcessingBlock.isRemoveUnusedCode
                    || postProcessingBlock.isObfuscate
                    || postProcessingBlock.isOptimizeCode
        }
    }

    override fun resourcesShrinkingEnabled(): Boolean = postProcessingBlock.isRemoveUnusedResources

    override fun hasPostProcessingConfiguration() = true
}
