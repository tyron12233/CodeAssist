package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.io.nonClosing
import com.android.utils.PathUtils
import com.google.common.collect.ImmutableList
import com.google.common.io.ByteStreams
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.gradle.internal.res.runAapt2Compile
import com.tyron.builder.gradle.internal.services.Aapt2DaemonServiceKey
import com.tyron.builder.gradle.internal.services.getErrorFormatMode
import com.tyron.builder.gradle.internal.services.registerAaptService
import com.tyron.builder.internal.aapt.AaptOptions
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.aapt.v2.Aapt2RenamingConventions
import org.apache.commons.io.output.TeeOutputStream
import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Artifact transform with dependencies to auto-namespace AARs.
 *
 * At a high level this means resolving all resource references that are not namespaced, and
 * rewriting them to be namespaced. See [NamespaceRewriter].
 */
@CacheableTransform
abstract class AutoNamespaceTransform : TransformAction<AutoNamespaceParameters> {

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val preProcessedAarDir: Provider<FileSystemLocation>

    @get:InputArtifactDependencies
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val preprocessedDependencies: FileCollection

    override fun transform(outputs: TransformOutputs) {
        try {
            val output = outputs.file(getOutputFileName())
            val preprocessedAar: File = preProcessedAarDir.get().asFile
                .resolve(AutoNamespacePreProcessTransform.PREPROCESSED_AAR_FILE_NAME)
            ZipFile(preprocessedAar).use { inputAar ->
                ZipOutputStream(output.outputStream().buffered()).use { outputAar ->
                    val resApk = inputAar.getEntry(SdkConstants.FN_RESOURCE_STATIC_LIBRARY)
                    if (resApk != null) {
                        // Already namespaced!
                        copyNamespacedAar(inputAar, outputAar)
                    } else {
                        val tempDir = Files.createTempDirectory("auto_namespace")
                        try {
                            autoNamespaceAar(
                                inputAar,
                                outputAar,
                                preprocessedDependencies.files,
                                tempDir
                            )
                        } finally {
                            PathUtils.deleteRecursivelyIfExists(tempDir)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            throw IOException("Failed to auto-namespace ${preProcessedAarDir.get().asFile.name}", e)
        }
    }

    private fun copyNamespacedAar(
        inputAar: ZipFile,
        outputAar: ZipOutputStream
    ) {
        for (entry in inputAar.entries()) {
            when {
                entry.name == SdkConstants.FN_R_DEF_TXT -> {
                    // Only used for auto-namespacing other libraries on top of this one,
                    // not part of the actual AAR format.
                }
                else -> {
                    outputAar.putNextEntry(ZipEntry(entry.name))
                    ByteStreams.copy(inputAar.getInputStream(entry), outputAar)
                }
            }
        }
    }

    private fun getOutputFileName() =
        preProcessedAarDir.get().asFile.name + ".aar"

    private fun autoNamespaceAar(
        inputAar: ZipFile,
        outputAar: ZipOutputStream,
        dependencies: Set<File>,
        tempDir: Path
    ) {
        val resToCompileDir = tempDir.resolve("res_to_compile")
            .also { Files.createDirectory(it) }
        val manifestFile = tempDir.resolve("AndroidManifest.xml")
        val compiledResDir = tempDir.resolve("compiled_res")
            .also { Files.createDirectory(it) }
        val aaptIntermediateDir = tempDir.resolve("aapt_intermediate")
            .also { Files.createDirectory(it) }
        val partialRDir = tempDir.resolve("partialR")
            .also { Files.createDirectory(it) }
        val staticLibApk = tempDir.resolve("staticLib.apk")
        val generatedPublicXmlDir = tempDir.resolve("generated_public_xml")
            .also { Files.createDirectory(it) }
        val compiledPublicXmlDir = tempDir.resolve("compiled_public_xml")
            .also { Files.createDirectory(it) }
        val requestList = ArrayList<CompileResourceRequest>()

        // Read the symbol tables from this AAR and the dependencies to enable the namespaced
        // rewriter to resolve symbols.
        val localTableEntry = inputAar.getEntry(SdkConstants.FN_R_DEF_TXT)
        val localTable = SymbolIo.readRDefFromInputStream(
            preProcessedAarDir.get().asFile.toString(),
            inputAar.getInputStream(localTableEntry)
        )

        val symbolTables = ImmutableList.builder<SymbolTable>().apply {
            add(localTable)
            for (dependency in dependencies) {
                add(
                    SymbolIo.readRDefFromZip(
                        dependency.toPath().resolve(
                            AutoNamespacePreProcessTransform.PREPROCESSED_AAR_FILE_NAME
                        )
                    )
                )
            }
        }.build()

        val rewriter =
            NamespaceRewriter(symbolTables, Logging.getLogger(AutoNamespaceTransform::class.java))

        for (entry in inputAar.entries()) {
            when {
                entry.name == SdkConstants.FN_R_DEF_TXT -> {
                    // Only used for auto-namespacing other libraries on top of this one,
                    // not part of the actual AAR format.
                }
                entry.name == SdkConstants.FN_RESOURCE_TEXT -> {
                    // Regenerated below to be namespaced, as the compilation R class is
                    // generated from this
                }
                isJar(entry.name) -> {
                    outputAar.putNextEntry(ZipEntry(entry.name))
                    rewriter.rewriteJar(inputAar.getInputStream(entry), outputAar)
                }
                entry.name == SdkConstants.FN_ANDROID_MANIFEST_XML -> {
                    // TODO: separate manifest XML.
                    outputAar.putNextEntry(ZipEntry(entry.name))
                    // Write to aar and to file for AAPT2.
                    Files.newOutputStream(manifestFile).buffered().use { file ->
                        rewriter.rewriteManifest(
                            inputAar.getInputStream(entry),
                            TeeOutputStream(file, outputAar),
                            preProcessedAarDir
                        )
                    }
                }
                entry.name.startsWith("res/") -> {
                    outputAar.putNextEntry(ZipEntry(entry.name))
                    val outFile = resToCompileDir.resolve(entry.name)
                    Files.createDirectories(outFile.parent)
                    Files.newOutputStream(outFile).buffered().use { outputStream ->
                        rewriter.rewriteAarResource(
                            entry.name,
                            inputAar.getInputStream(entry),
                            outputStream
                        )
                    }
                    val partialRFile = partialRDir.resolve(
                        "${Aapt2RenamingConventions.compilationRename(outFile.toFile())}-R.txt"
                    ).toFile()
                    // TODO: Compilation using new java implementation once done?
                    // TODO(146340124): Pseudolocalization?
                    requestList.add(
                        CompileResourceRequest(
                            inputFile = outFile.toFile(),
                            outputDirectory = compiledResDir.toFile(),
                            partialRFile = partialRFile
                        )
                    )
                }
                else -> {
                    outputAar.putNextEntry(ZipEntry(entry.name))
                    ByteStreams.copy(inputAar.getInputStream(entry), outputAar)
                }
            }
        }

        val publicTxtInputStream: InputStream? =
            inputAar.getEntry(SdkConstants.FN_PUBLIC_TXT)?.let { inputAar.getInputStream(it) }
        val publicXml = rewriter.generatePublicFile(publicTxtInputStream, generatedPublicXmlDir)
        requestList.add(
            CompileResourceRequest(
                inputFile = publicXml.toFile(),
                outputDirectory = compiledPublicXmlDir.toFile()
            )
        )

        // TODO: Performance: This is single threaded (but multiple AARs could be being
        //       auto-namespaced in parallel), investigate whether it can be improved.
        // TODO(b/152323103) errorFormatMode should be implicit
        runAapt2Compile(parameters.aapt2, requestList, false)

        linkAndroidResources(
            manifestFile,
            compiledResDir,
            compiledPublicXmlDir,
            staticLibApk,
            aaptIntermediateDir,
            outputAar
        )

        generateRTxt(requestList, localTable, outputAar)
    }

    private fun linkAndroidResources(
        manifestFile: Path,
        compiledResDir: Path,
        compiledPublicXmlDir: Path,
        staticLibApk: Path,
        aaptIntermediateDir: Path,
        outputAar: ZipOutputStream
    ) {
        if (!Files.isRegularFile(manifestFile)) {
            throw IOException("manifest file $manifestFile does not exist")
        }

        val request = AaptPackageConfig(
            androidJarPath = null,
            manifestFile = manifestFile.toFile(),
            options = AaptOptions(),
            resourceDirs = ImmutableList.of(
                compiledResDir.toFile(),
                preProcessedAarDir.get().asFile.resolve(AutoNamespacePreProcessTransform.PRECOMPILED_RES_DIR_NAME),
                compiledPublicXmlDir.toFile()
            ),
            staticLibrary = true,
            resourceOutputApk = staticLibApk.toFile(),
            componentType = ComponentTypeImpl.LIBRARY,
            mergeOnly = true,
            intermediateDir = aaptIntermediateDir.toFile()
        )

        // TODO(b/152323103) this should be implicit
        val aapt2ServiceKey: Aapt2DaemonServiceKey = parameters.aapt2.registerAaptService()
        runAapt2Link(aapt2ServiceKey, request, parameters.aapt2.getErrorFormatMode())

        outputAar.putNextEntry(ZipEntry(SdkConstants.FN_RESOURCE_STATIC_LIBRARY))
        Files.copy(staticLibApk, outputAar)
    }

    private fun generateRTxt(
        requestList: ArrayList<CompileResourceRequest>,
        localTable: SymbolTable,
        outputAar: ZipOutputStream
    ) {
        val partialRFiles =
            preProcessedAarDir.get().asFile.resolve(AutoNamespacePreProcessTransform.PRECOMPILED_RES_PARTIAL_R_DIR_NAME)
                .listFiles()?.toMutableList() ?: mutableListOf<File>()
        requestList.mapNotNullTo(partialRFiles) { it.partialRFile }

        val resources = SymbolTable.mergePartialTables(partialRFiles, localTable.tablePackage)

        // This is deliberately a non transitive R.txt file.
        // AARs generated from namespaced projects will have non-transitive R.txt, so the
        // auto-namespacing pipeline simulates that.
        // This has the nice property of enabling us to generate the compilation R class in the
        // same way in the namespaced and the non-namespaced pipeline.
        outputAar.putNextEntry(ZipEntry(SdkConstants.FN_RESOURCE_TEXT))
        outputAar.nonClosing().bufferedWriter().use { writer ->
            SymbolIo.writeForAar(resources, writer)
        }
    }

    companion object {
        fun isJar(entryName: String): Boolean =
            entryName == SdkConstants.FN_CLASSES_JAR ||
                    entryName == SdkConstants.FN_API_JAR ||
                    (entryName.startsWith("libs/") && entryName.endsWith(SdkConstants.DOT_JAR))

    }
}
