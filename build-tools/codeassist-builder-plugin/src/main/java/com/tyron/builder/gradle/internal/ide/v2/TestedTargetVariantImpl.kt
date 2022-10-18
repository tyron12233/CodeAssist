package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.TestedTargetVariant
import java.io.Serializable

/**
 * Implementation of [TestedTargetVariant] for serialization via the Tooling API.
 */
data class TestedTargetVariantImpl(
    override val targetProjectPath: String,
    override val targetVariant: String
) : TestedTargetVariant, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
