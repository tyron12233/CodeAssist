package com.tyron.builder.gradle.internal.dependency
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault

/**
 * Transform from one artifact type to another artifact type without changing the artifact's
 * contents.
 */
@DisableCachingByDefault
abstract class IdentityTransform : TransformAction<IdentityTransform.Parameters> {

    interface Parameters : GenericTransformParameters {

        /**
         * Whether to create an empty output directory if the input file/directory does not exist.
         *
         * Example use case: A Java library subproject without any classes publishes but does not
         * create the classes directory, but we still need to transform it.
         */
        @get:Input
        @get:Optional // false by default if not set
        val acceptNonExistentInputFile: Property<Boolean>
    }

    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        val logger = Logging.getLogger(IdentityTransform::class.java)
        val input = inputArtifact.get().asFile

        logger.info("Transforming ${input.name} using IdentityTransform")

        when {
            input.isDirectory -> transformOutputs.dir(input)
            input.isFile -> transformOutputs.file(input)
            parameters.acceptNonExistentInputFile.getOrElse(false) -> transformOutputs.dir("empty")
            else -> throw IllegalArgumentException(
                "File/directory does not exist: ${input.absolutePath}")
        }
    }
}