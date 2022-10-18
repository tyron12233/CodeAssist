package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.models.Versions
import com.tyron.builder.model.v2.models.Versions.Version
import java.io.Serializable

data class VersionsImpl(
    override val agp: String,
    override val versions: Map<String, Version>,
): Versions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}

data class VersionImpl(
    override val major: Int,
    override val minor: Int
): Version, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
