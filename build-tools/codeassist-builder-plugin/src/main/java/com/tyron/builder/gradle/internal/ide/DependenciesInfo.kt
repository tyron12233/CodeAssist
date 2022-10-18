package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.DependenciesInfo
import java.io.Serializable

data class DependenciesInfoImpl(
    override val includeInApk: Boolean,
    override val includeInBundle: Boolean
): DependenciesInfo, Serializable {
    companion object {
        private const val serialVersionUID = 1L;
    }
}