package com.tyron.builder.gradle.internal.dependency

import com.google.common.io.Files
import com.tyron.builder.aar.AarExtractor
import com.android.SdkConstants.FD_JARS
import com.android.SdkConstants.FN_CLASSES_JAR
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.util.internal.GFileUtils
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Transform that extracts an AAR file into a directory.  */
@DisableCachingByDefault
abstract class ExtractAarTransform: TransformAction<GenericTransformParameters> {

    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputArtifact
    abstract val primaryInput: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        //TODO(b/162813654) record transform execution span
        val inputFile = primaryInput.get().asFile
        val name = Files.getNameWithoutExtension(inputFile.name)
        val outputDir = outputs.dir(name)
        GFileUtils.mkdirs(outputDir)
        val aarExtractor = AarExtractor()
        aarExtractor.extract(inputFile, outputDir)

        // Verify that we have a classes.jar, if we don't just create an empty one.
        val classesJar = File(File(outputDir, FD_JARS), FN_CLASSES_JAR)
        if (!classesJar.exists()) {
            try {
                Files.createParentDirs(classesJar)
                FileOutputStream(classesJar).use { out ->
                    // FileOutputStream above is the actual OS resource that will get closed,
                    // JarOutputStream writes the bytes or an empty jar in it.
                    val jarOutputStream = JarOutputStream(BufferedOutputStream(out), Manifest())
                    jarOutputStream.close()
                }
            } catch (e: IOException) {
                throw RuntimeException("Cannot create missing classes.jar", e)
            }
        }
    }
}