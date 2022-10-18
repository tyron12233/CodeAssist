package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.api.BaseVariant
import java.io.File
import com.tyron.builder.model.ProductFlavor as GradleToolingModelProductFlavor

/**
 * These interfaces exist to make the merged flavor collections mutable in the post-tasks variant API
 * when used from kotlin.
 *
 * Workaround for https://issuetracker.google.com/155318103
 */
interface InternalBaseVariant: BaseVariant {
    override fun getMergedFlavor(): MergedFlavor
    interface MergedFlavor: GradleToolingModelProductFlavor {
        override val testInstrumentationRunnerArguments: MutableMap<String, String>
        override val manifestPlaceholders: MutableMap<String, Any>
        override val resourceConfigurations: MutableCollection<String>
        override val proguardFiles: MutableList<File>
        override val consumerProguardFiles: MutableList<File>
        override val testProguardFiles: MutableList<File>
    }
}

