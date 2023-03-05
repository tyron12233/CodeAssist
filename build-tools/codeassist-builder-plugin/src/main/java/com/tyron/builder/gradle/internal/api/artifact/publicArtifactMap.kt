package com.tyron.builder.gradle.internal.api.artifact

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.scope.BuildArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import java.lang.RuntimeException
import kotlin.reflect.KClass

/**
 * Utility class for [Artifact]
 */

private val publicArtifactMap : Map<String, KClass<out Artifact<*>>> =
        SingleArtifact::class.sealedSubclasses.associateBy {
                it.objectInstance?.name() ?: throw RuntimeException("No instance")
        }

private val sourceArtifactMap : Map<String, KClass<out Artifact<*>>> =
        SourceArtifactType::class.sealedSubclasses.associateBy {
                it.objectInstance?.name() ?: "class"
        }
private val buildArtifactMap : Map<String, KClass<out Artifact<*>>> =
        BuildArtifactType::class.sealedSubclasses.associateBy {
                it.objectInstance?.name() ?:"class"
        }
private val internalArtifactMap : Map<String, KClass<out Artifact<*>>> =
        InternalArtifactType::class.sealedSubclasses.associateBy {
                it.objectInstance?.name() ?: throw RuntimeException("No instance")
        }

/**
 * Return the enum of [Artifact] base on the name.
 *
 * The typical implementation of valueOf in an enum class cannot be used because there are
 * multiple implementations of [Artifact].  For this to work, the name of all
 * [Artifact] must be unique across all implementations.
 */
fun String.toArtifactType() : Artifact<*> =
    publicArtifactMap[this]?.objectInstance ?:
            sourceArtifactMap[this]?.objectInstance ?:
            buildArtifactMap[this]?.objectInstance  ?:
            internalArtifactMap[this]?.objectInstance ?:
            throw IllegalArgumentException("'$this' is not a value ArtifactType.")
