package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.PackagingOptions
import java.io.Serializable

/**
 * Implementation of [PackagingOptions] for serialization via the Tooling API
 */
data class PackagingOptionsImpl(
    override val excludes: Set<String>,
    override val pickFirsts: Set<String>,
    override val merges: Set<String>,
    override val doNotStrip: Set<String>
) : PackagingOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
