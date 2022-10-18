package com.tyron.builder.gradle.internal.dependency

import com.android.SdkConstants
import com.tyron.builder.dexing.getSortedRelativePathsInJar
import com.android.ide.common.resources.usage.getResourcesFromDirectory
import com.android.ide.common.symbols.SymbolIo
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File

/** This transform outputs a directory containing a file listing the contents of this AAR's
 *  resource_symbols.txt which is in r-def form [SymbolIo.readRDef]). */
@CacheableTransform
abstract class CollectResourceSymbolsTransform : TransformAction<GenericTransformParameters> {

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputAndroidResArtifact: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        writeListToFile(
            transformOutputs.file(SdkConstants.FN_RESOURCE_SYMBOLS),
            getResourcesFromDirectory(inputAndroidResArtifact.get().asFile)
        )
    }
}

/** This transform outputs a directory containing a file called 'classes.txt' listing all
 * classes in the inputArtifact JAR. Each line in 'classes.txt' is a java class name of the form
 * 'com/example/MyClass.class'.
 */
@CacheableTransform
abstract class CollectClassesTransform : TransformAction<GenericTransformParameters> {

    @get:Classpath
    @get:InputArtifact
    abstract val inputJarArtifact: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        writeListToFile(
            transformOutputs.file(SdkConstants.FN_CLASS_LIST),
            getSortedRelativePathsInJar(inputJarArtifact.get().asFile) {
                    path -> path.endsWith(SdkConstants.DOT_CLASS)
            }
        )
    }
}

/** Write collection element by element to the outputFile. */
internal fun writeListToFile(outputFile: File, list: Collection<String>): File =
    outputFile.apply {
        writeText(list.joinToString(separator = "\n"))
    }
