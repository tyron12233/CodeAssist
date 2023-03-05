package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.DependenciesInfo
import java.io.Serializable

/**
 * Implementation of [DependenciesInfo] for serialization via the Tooling API.
 */
data class DependenciesInfoImpl(
    override val includeInApk: Boolean,
    override val includeInBundle: Boolean
) : DependenciesInfo, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
