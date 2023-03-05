package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.LibraryInfo
import java.io.Serializable

data class LibraryInfoImpl(
    override val buildType: String?,
    override val productFlavors: Map<String, String>,
    override val attributes: Map<String, String>,
    override val capabilities: List<String>,
    override val group: String,
    override val name: String,
    override val version: String,
    override val isTestFixtures: Boolean
) : LibraryInfo, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }

    fun computeKey(): String {
        val builder = StringBuilder()
        builder.append(group).append("|").append(name).append("|").append(version).append('|')
        if (buildType != null) builder.append(buildType).append("|")
        if (productFlavors.isNotEmpty()) builder.append(productFlavors.toKeyComponent()).append("|")
        builder.append(attributes.toKeyComponent()).append("|")
        builder.append(capabilities.sorted().joinToString())
        return builder.toString()
    }

    private fun Map<String, String>.toKeyComponent() =
        entries.sortedBy { it.key }.joinToString { "${it.key}>${it.value}" }

}
