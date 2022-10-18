package com.tyron.builder.gradle.internal.dependency

import com.android.SdkConstants
import com.android.SdkConstants.FD_RES
import com.android.SdkConstants.FD_RES_VALUES
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.xml.AndroidManifestParser
import com.tyron.builder.gradle.internal.res.runAapt2Compile
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.services.registerAaptService
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Nested
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@CacheableTransform
abstract class AarResourcesCompilerTransform :
    TransformAction<AarResourcesCompilerTransform.Parameters> {

    interface Parameters : GenericTransformParameters {
        @get:Nested
        val aapt2: Aapt2Input
    }

    @get:Classpath
    @get:InputArtifact
    abstract val primaryInput: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        val inputFile = primaryInput.get().asFile
        val manifest = inputFile.resolve(SdkConstants.FN_ANDROID_MANIFEST_XML)
        val outputDir = transformOutputs.dir(getPackage(manifest.toPath()))
        outputDir.mkdirs()

        val resourceDir = File(inputFile, FD_RES)

        val resourceFolders = if (resourceDir.exists()) {
            resourceDir.listFiles { dir, name ->
                dir.isDirectory && !name.startsWith(FD_RES_VALUES)
            }
        } else {
            arrayOf<File>()
        }

        val requestList = ArrayList<CompileResourceRequest>()
        resourceFolders?.forEach { folder ->
            folder?.listFiles()?.forEach {
                // TODO(b/130160921): Add compile options
                requestList.add(CompileResourceRequest(it, outputDir))
            }
        }

        val aapt2ServiceKey = parameters.aapt2.registerAaptService()
        // TODO(b/152323103) errorFormatMode should be implicit
        runAapt2Compile(parameters.aapt2, requestList, false)
    }

    private fun getPackage(manifest: Path): String =
        BufferedInputStream(Files.newInputStream(manifest)).use {
            AndroidManifestParser.parse(it).`package`
        }
}