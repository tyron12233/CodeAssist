package com.tyron.builder.gradle.internal.res.namespaced

import com.tyron.builder.gradle.internal.dependency.GenericTransformParameters
import com.tyron.builder.gradle.internal.services.Aapt2Input
import org.gradle.api.tasks.Nested

/** Parameters common to auto-namespacing transforms */
interface AutoNamespaceParameters: GenericTransformParameters {
    @get:Nested
    val aapt2: Aapt2Input
}