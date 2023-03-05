package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants.FN_ANDROID_MANIFEST_XML
import com.android.SdkConstants.FN_RESOURCE_STATIC_LIBRARY
import com.android.SdkConstants.FN_R_DEF_TXT
import com.tyron.builder.gradle.internal.packaging.JarCreatorFactory
import com.tyron.builder.gradle.internal.res.runAapt2Compile
import com.tyron.builder.gradle.internal.services.getErrorFormatMode
import com.tyron.builder.gradle.internal.services.registerAaptService
import com.tyron.builder.internal.aapt.v2.Aapt2RenamingConventions
import com.tyron.builder.packaging.JarCreator
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.ide.common.symbols.parseAarZipEntry
import com.android.ide.common.xml.AndroidManifestParser
import com.android.utils.FileUtils
import com.android.utils.SdkUtils.endsWithIgnoreCase
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Artifact transform without dependencies to pre-process AARs ready for auto-namespacing.
 *
 * This should do as much work as possible without dependencies so that work can be re-used even
 * when the dependency graph is different between projects. This includes:
 *
 *  *  Compiling all raw and non-xml resources with AAPT2.
 *  *  Parsing the res/ directory inside the AAR and creates a package aware library defined symbol file.
 *     The difference between a simple package aware symbol file and package aware library defined
 *     symbol file file is that the first one contains all resources from this AAR and its dependencies
 *     while the second one contains only those resources that were defined in this AAR.
 *  *  Removing the packaged non-namespaced resources for namespaced AARs, as they are not needed
 *     for the namespaced pipeline.
 */
@CacheableTransform
abstract class AutoNamespacePreProcessTransform : TransformAction<AutoNamespaceParameters> {

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputAar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        if (!inputAar.get().asFile.extension.equals("aar", ignoreCase = true)) {
            /**
             * Jars are "deleted" here, so [AutoNamespaceTransform] can resolve its
             * InputArtifactDependencies in the presence of jars that depend on AARs.
             */
            return
        }
        ZipFile(inputAar.get().asFile).use { inputAar ->
            val packageName = AndroidManifestParser.parse(
                inputAar.getInputStream(inputAar.getEntry(FN_ANDROID_MANIFEST_XML))
            ).`package`
            val outputDir = outputs.dir(packageName)
            val tempDir = outputDir.resolve("tmp")
            Files.createDirectory(tempDir.toPath())
            try {
                preProcessAar(
                    tempDir = tempDir,
                    packageName = packageName,
                    inputAar = inputAar,
                    outputDir = outputDir
                )
            } catch (e: Throwable) {
                throw IOException("Failed to auto-namespace ${this.inputAar.get().asFile.name}", e)
            } finally {
                FileUtils.deleteRecursivelyIfExists(tempDir)
            }
        }
    }

    private fun preProcessAar(
        tempDir: File,
        packageName: String,
        inputAar: ZipFile,
        outputDir: File
    ) {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val alreadyNamespaced = inputAar.getEntry(FN_RESOURCE_STATIC_LIBRARY) != null

        val resToCompileDir = tempDir.resolve("res_to_compile")
            .also { Files.createDirectory(it.toPath()) }
        val compileRequestList = mutableListOf<CompileResourceRequest>()

        val outputZip = outputDir.resolve(PREPROCESSED_AAR_FILE_NAME)
        val compiledResDir = outputDir.resolve(PRECOMPILED_RES_DIR_NAME)
            .also { Files.createDirectories(it.toPath()) }
        val partialRDir = outputDir.resolve(PRECOMPILED_RES_PARTIAL_R_DIR_NAME)
            .also { Files.createDirectories(it.toPath()) }
        val symbolTableParseErrorLogger: (Exception) -> Unit = {
            Logging.getLogger(this.javaClass)
                .log(LogLevel.INFO, "Failed to parse resource in AAR $inputAar", it)
        }

        JarCreatorFactory.make(jarFile = outputZip.toPath()).use { out: JarCreator ->
            out.setCompressionLevel(Deflater.NO_COMPRESSION)
            val symbolTableBuilder = SymbolTable.builder().tablePackage(packageName)
            for (entry in inputAar.entries()) {
                when {
                    entry.isDirectory -> {
                    }
                    entry.name.startsWith("res/") -> {
                        // TODO(b/139525286): Performance: Could the defined symbol table be
                        //  directly read from the proto Resource table for namespaced AARs?
                        //  (It's still needed for namespaced AARs as there may be non-namespaced
                        //  AARs that depend on them, and the defined resources are needed to
                        //  auto-namespace things correctly)
                        symbolTableBuilder.parseAarZipEntry(documentBuilder, symbolTableParseErrorLogger, entry.name) {
                            inputAar.getInputStream(entry)
                        }
                        // Only keep the existing res directory for non-namespaced AARs.
                        if (!alreadyNamespaced) {
                            if (endsWithIgnoreCase(entry.name, ".xml") && !entry.name.startsWith("res/raw")) {
                                // Needs namespacing, copy in to the output zip.
                                out.addEntry(entry.name, inputAar.getInputStream(entry))
                            } else {
                                // Precompile resource that don't need to be auto-namespaced.
                                compileRequestList.add(
                                    createCompileRequest(
                                        name = entry.name,
                                        inputStream = inputAar.getInputStream(entry),
                                        resToCompileDir = resToCompileDir,
                                        partialRDir = partialRDir,
                                        compiledResDir = compiledResDir
                                    )
                                )
                            }
                        }
                    }
                    else -> {
                        out.addEntry(entry.name, inputAar.getInputStream(entry))
                    }
                }
            }
            ByteArrayOutputStream().use { os ->
                SymbolIo.writeRDef(symbolTableBuilder.build(), os)
                out.addEntry(FN_R_DEF_TXT, os.toByteArray().inputStream())
            }
        }
        compileResources(compileRequestList)
    }

    private fun createCompileRequest(
        name: String,
        inputStream: InputStream,
        resToCompileDir: File,
        partialRDir: File,
        compiledResDir: File
    ): CompileResourceRequest {
        val tempFile = resToCompileDir.resolve(name)
        val partialRFile = partialRDir.resolve(
            "${Aapt2RenamingConventions.compilationRename(tempFile)}-R.txt"
        )
        Files.createDirectories(tempFile.toPath().parent)
        Files.copy(inputStream, tempFile.toPath())
        return CompileResourceRequest(
            inputFile = tempFile,
            outputDirectory = compiledResDir,
            partialRFile = partialRFile,
            isPngCrunching = true
            // Always crunch for the moment to avoid having that as an input to the transform.
        )
    }

    private fun compileResources(requestList: MutableList<CompileResourceRequest>) {
        // TODO: Performance: Investigate if this should be multi-threaded?
        runAapt2Compile(parameters.aapt2, requestList, false)
    }

    companion object {
        const val PREPROCESSED_AAR_FILE_NAME = "preprocessed_aar.zip"
        const val PRECOMPILED_RES_DIR_NAME = "precompiled_res"
        const val PRECOMPILED_RES_PARTIAL_R_DIR_NAME = "partial_symbol_table"
    }
}
