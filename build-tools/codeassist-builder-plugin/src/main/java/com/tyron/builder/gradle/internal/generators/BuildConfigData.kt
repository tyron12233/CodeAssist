package com.tyron.builder.gradle.internal.generators

import com.tyron.builder.api.variant.BuildConfigField
import java.io.Serializable
import java.nio.file.Path

class BuildConfigData private constructor(
        val outputPath: Path,
        val namespace: String,
        val buildConfigName: String,
        val buildConfigFields: Map<String, BuildConfigField<out Serializable>>
) {

    class Builder(
            private var outputPath: Path? = null,
            private var namespace: String? = null,
            private var buildConfigName: String = "BuildConfig",
            private val buildConfigFields: MutableMap<String, BuildConfigField<out Serializable>> = mutableMapOf()
    ) {

        fun setOutputPath(outputPath: Path) =
                apply { this.outputPath = outputPath }

        fun setNamespace(namespace: String) =
                apply { this.namespace = namespace }

        fun setBuildConfigName(buildConfigName: String) =
                apply { this.buildConfigName = buildConfigName }

        @JvmOverloads
        fun addStringField(name: String, value: String, comment: String? = null) = apply {
            buildConfigFields[name] = BuildConfigField("String", """"$value"""", comment)
        }

        @JvmOverloads
        fun addIntField(name: String, value: Int, comment: String? = null) = apply {
            buildConfigFields[name] = BuildConfigField("int", value, comment)
        }

        @JvmOverloads
        fun addBooleanField(name: String, value: Boolean, comment: String? = null) = apply {
            buildConfigFields[name] = BuildConfigField("boolean", value, comment)
        }

        @JvmOverloads
        fun addLongField(name: String, value: Long, comment: String? = null) = apply {
            buildConfigFields[name] = BuildConfigField("long", value, comment)
        }

        fun addItem(name: String, field: BuildConfigField<out Serializable>) = apply {
            buildConfigFields[name] = field
        }

        fun build() = BuildConfigData(
            outputPath ?: error("outputPath is required."),
            namespace ?: error("namespace is required."),
            buildConfigName,
            buildConfigFields
        )
    }
}
