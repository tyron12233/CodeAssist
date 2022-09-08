package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.ProjectInfo
import java.io.Serializable

data class ProjectInfoImpl(
    override val buildType: String?,
    override val productFlavors: Map<String, String>,
    override val attributes: Map<String, String>,
    override val capabilities: List<String>,
    override val buildId: String,
    override val projectPath: String,
    override val isTestFixtures: Boolean
) : ProjectInfo, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }

    fun computeKey(): String {
        val builder = StringBuilder()
        builder.append(buildId).append("|").append(projectPath).append("|")
        if (buildType != null) builder.append(buildType).append("|")
        if (productFlavors.isNotEmpty()) builder.append(productFlavors.toKeyComponent()).append("|")
        builder.append(attributes.toKeyComponent()).append("|")
        builder.append(capabilities.sorted().joinToString())
        return builder.toString()
    }

    private fun Map<String, String>.toKeyComponent() =
        entries.sortedBy { it.key }.joinToString { "${it.key}>${it.value}" }
}
