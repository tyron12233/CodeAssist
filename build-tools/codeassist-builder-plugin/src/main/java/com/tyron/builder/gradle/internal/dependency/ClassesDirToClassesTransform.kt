package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.dexing.isJarFile
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ClassesDirFormat.CONTAINS_CLASS_FILES_ONLY
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ClassesDirFormat.CONTAINS_SINGLE_JAR
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Transform from [AndroidArtifacts.ArtifactType.CLASSES_DIR] to
 * [AndroidArtifacts.ArtifactType.CLASSES].
 */
@DisableCachingByDefault
abstract class ClassesDirToClassesTransform : TransformAction<GenericTransformParameters> {

    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        when (getClassesDirFormat(input)) {
            CONTAINS_SINGLE_JAR -> {
                outputs.file(input.listFiles()!![0])
            }
            CONTAINS_CLASS_FILES_ONLY -> {
                outputs.dir(input)
            }
        }
    }
}

fun getClassesDirFormat(classesDir: File): AndroidArtifacts.ClassesDirFormat {
    check(classesDir.isDirectory) { "Not a directory: ${classesDir.path}"}
    val filesInDir = classesDir.listFiles()!!
    return if (filesInDir.size == 1 && isJarFile(filesInDir[0])) {
        CONTAINS_SINGLE_JAR
    } else {
        CONTAINS_CLASS_FILES_ONLY
    }
}