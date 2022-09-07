package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.gradle.internal.api.artifact.SourceArtifactType

/**
 * Specification to define supported features of [BuildArtifactType]
 */
data class BuildArtifactSpec(
        val type : Artifact<*>,
        val appendable : Boolean,
        val replaceable: Boolean) {
    companion object {
        private val specMap = mapOf(
                //   type                                      appendable           replaceable
            spec(BuildArtifactType.JAVAC_CLASSES,          true,       true),
            spec(BuildArtifactType.JAVA_COMPILE_CLASSPATH, true,       false),
            spec(SourceArtifactType.ANDROID_RESOURCES,     true,       true),
            spec(InternalArtifactType.BASE_MODULE_METADATA,false,      true)
        )

        fun spec(type : Artifact<*>, appendable : Boolean, replaceable: Boolean) =
            type to BuildArtifactSpec(
                type,
                appendable,
                replaceable
            )

        fun get(type : Artifact<*>) =
                specMap[type]
                        ?: throw RuntimeException("Specification is not defined for type '$type'.")

        fun has(type : Artifact<*>) = specMap.containsKey(type)
    }
}
