package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ModelSyncFile
import com.tyron.builder.model.v2.ide.AndroidGradlePluginProjectFlags
import com.tyron.builder.model.v2.ide.JavaCompileOptions
import com.tyron.builder.model.v2.ide.Variant
import com.tyron.builder.model.v2.ide.ViewBindingOptions
import com.tyron.builder.model.v2.models.AndroidProject
import java.io.File
import java.io.Serializable

/**
 * Implementation of [AndroidProject] for serialization via the Tooling API.
 */
data class AndroidProjectImpl(
    override val namespace: String,
    override val androidTestNamespace: String?,
    override val testFixturesNamespace: String?,
    override val variants: Collection<Variant>,
    override val javaCompileOptions: JavaCompileOptions,
    override val resourcePrefix: String?,
    override val dynamicFeatures: Collection<String>?,
    override val viewBindingOptions: ViewBindingOptions?,
    override val flags: AndroidGradlePluginProjectFlags,
    override val lintChecksJars: List<File>,
    override val modelSyncFiles: List<ModelSyncFile>,
) : AndroidProject, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
