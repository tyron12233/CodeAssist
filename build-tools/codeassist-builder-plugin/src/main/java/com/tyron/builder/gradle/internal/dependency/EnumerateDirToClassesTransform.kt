package com.tyron.builder.gradle.internal.dependency


import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * A Gradle Artifact [TransformAction] that enumerates the classes in each module,
 * so they can be checked for duplicates in CheckDuplicateClassesTask
 */
@CacheableTransform
abstract class EnumerateClassesTransform : TransformAction<GenericTransformParameters> {
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputArtifact
    abstract val classesJar: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        val inputFile = classesJar.get().asFile
        val fileName = inputFile.nameWithoutExtension
        val enumeratedClasses = transformOutputs.file(fileName)
        EnumerateClassesDelegate().run(
            inputFile,
            enumeratedClasses
        )
    }
}