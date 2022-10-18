package com.tyron.builder.gradle.internal.dependency

import com.android.SdkConstants.*
import com.android.ide.common.xml.AndroidManifestParser
import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.common.symbols.rTxtToSymbolTable
import com.tyron.builder.packaging.JarCreator
import com.tyron.builder.packaging.JarMerger
import com.tyron.builder.symbols.exportToCompiledJava
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.nio.file.Path
import java.util.zip.Deflater.NO_COMPRESSION
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A Gradle Artifact [TransformAction] from a processed AAR to a single classes JAR file.
 */
@CacheableTransform
abstract class AarToClassTransform : TransformAction<AarToClassTransform.Params> {

    interface Params : TransformParameters {
        /**
         * If set, add a generated R class jar from the R.txt to the compile classpath jar.
         *
         * Only has effect if [forCompileUse] is also set.
         */
        @get:Input
        val generateRClassJar: Property<Boolean>

        /** If set, return the compile classpath, otherwise return the runtime classpath. */
        @get:Input
        val forCompileUse: Property<Boolean>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputAarFile: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {

        ZipFile(inputAarFile.get().asFile).use { inputAar ->
            val useSuffix = if (parameters.forCompileUse.get()) "api" else "runtime"
            val outputFileName =
                "${inputAarFile.get().asFile.nameWithoutExtension}-$useSuffix$DOT_JAR"
            val outputJar = transformOutputs.file(outputFileName).toPath()
            mergeJars(
                outputJar,
                inputAar,
                parameters.forCompileUse.get(),
                parameters.generateRClassJar.get()
            )
        }
    }

    companion object {
        @VisibleForTesting
        internal fun mergeJars(
            outputJar: Path,
            inputAar: ZipFile,
            forCompileUse: Boolean,
            generateRClassJar: Boolean
        ) {
            val ignoreFilter = if (forCompileUse) {
                JarMerger.allIgnoringDuplicateResources()
            } else {
                JarMerger.CLASSES_ONLY
            }
            JarMerger(outputJar, ignoreFilter).use { outputApiJar ->
                // NO_COMPRESSION because the resulting jar isn't packaged into final APK or AAR
                outputApiJar.setCompressionLevel(NO_COMPRESSION)
                if (forCompileUse) {
                    if (generateRClassJar) {
                        generateRClassJarFromRTxt(outputApiJar, inputAar)
                    }
                    val apiJAr = inputAar.getEntry(FN_API_JAR)
                    if (apiJAr != null) {
                        inputAar.copyEntryTo(apiJAr, outputApiJar)
                        return
                    }
                }

                inputAar.copyAllClassesJarsTo(outputApiJar)
            }
        }

        private const val LIBS_FOLDER_SLASH = "$LIBS_FOLDER/"

        private fun ZipFile.copyAllClassesJarsTo(outputApiJar: JarMerger) {
            entries()
                .asSequence()
                .filter(::isClassesJar)
                .forEach { copyEntryTo(it, outputApiJar) }
        }

        private fun ZipFile.copyEntryTo(entry: ZipEntry, outputApiJar: JarMerger) {
            getInputStream(entry).use { outputApiJar.addJar(it) }
        }

        private fun generateRClassJarFromRTxt(
            outputApiJar: JarCreator,
            inputAar: ZipFile
        ) {
            val manifest = inputAar.getEntry(FN_ANDROID_MANIFEST_XML)
            val pkg = inputAar.getInputStream(manifest).use {
                AndroidManifestParser.parse(it).`package`
            }
            val rTxt = inputAar.getEntry(FN_RESOURCE_TEXT) ?: return
            val symbols = inputAar.getInputStream(rTxt).use { rTxtToSymbolTable(it, pkg) }
            exportToCompiledJava(symbols, outputApiJar)
        }

        private fun isClassesJar(entry: ZipEntry): Boolean {
            val name = entry.name
            return name == FN_CLASSES_JAR ||
                    (name.startsWith(LIBS_FOLDER_SLASH) && name.endsWith(DOT_JAR))
        }
    }
}