package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.models.BuildMap
import java.io.File
import java.io.Serializable

data class BuildMapImpl(
    override val buildIdMap: Map<String, File>
): BuildMap, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
