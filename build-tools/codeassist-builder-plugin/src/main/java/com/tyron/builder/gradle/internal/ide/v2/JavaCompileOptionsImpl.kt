package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.JavaCompileOptions
import java.io.Serializable

/**
 * Implementation of [JavaCompileOptions] for serialization via the Tooling API.
 */
data class JavaCompileOptionsImpl(
    override val encoding: String,
    override val sourceCompatibility: String,
    override val targetCompatibility: String,
    override val isCoreLibraryDesugaringEnabled: Boolean
) : JavaCompileOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
