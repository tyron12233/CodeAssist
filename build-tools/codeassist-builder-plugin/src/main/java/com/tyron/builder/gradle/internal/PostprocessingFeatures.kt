package com.tyron.builder.gradle.internal

/** Describes actions that we should do at bytecode postProcessing time.  */
data class PostprocessingFeatures(
        val isRemoveUnusedCode: Boolean,
        val isObfuscate: Boolean,
        val isOptimize: Boolean)