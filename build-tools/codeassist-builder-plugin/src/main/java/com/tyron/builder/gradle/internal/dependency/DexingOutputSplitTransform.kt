package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.android.SdkConstants
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Transform dexes & keep rules bundle from dexing artifact transform into dexes or keep rules
 * depending on the [DexingOutputSplitTransform.Parameters]
 */
@DisableCachingByDefault
abstract class DexingOutputSplitTransform : TransformAction<DexingOutputSplitTransform.Parameters> {

    enum class DexOutput {
        DEX,
        KEEP_RULES,
    }

    interface Parameters: GenericTransformParameters {
        @get:Input
        val dexOutput: Property<DexOutput>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val primaryInput: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        if (parameters.dexOutput.get() == DexOutput.DEX) {
            outputs.dir(File(primaryInput.get().asFile, DEX_DIR_NAME))
        } else {
            val keepRulesFile = File(primaryInput.get().asFile, KEEP_RULES_FILE_NAME)
            if (!keepRulesFile.exists()) {
                keepRulesFile.createNewFile()
            }
            outputs.file(keepRulesFile)
        }
    }
}

fun registerDexingOutputSplitTransform(dependencyHandler: DependencyHandler) {
    // In release builds we can shrink the core java libraries as part of the D8 pipeline,
    // even if R8 is not used.
    registerTransformWithOutputType(dependencyHandler, DexingOutputSplitTransform.DexOutput.DEX)
    registerTransformWithOutputType(dependencyHandler, DexingOutputSplitTransform.DexOutput.KEEP_RULES)
}


private fun registerTransformWithOutputType(
    dependencyHandler: DependencyHandler,
    dexOutput: DexingOutputSplitTransform.DexOutput
) {
    dependencyHandler.registerTransform(DexingOutputSplitTransform::class.java) { spec ->
        spec.parameters { parameters ->
            parameters.dexOutput.set(dexOutput)
        }
        spec.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, AndroidArtifacts.ArtifactType.DEX_AND_KEEP_RULES.type)
        if (dexOutput == DexingOutputSplitTransform.DexOutput.DEX) {
            spec.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, AndroidArtifacts.ArtifactType.DEX.type)
        } else {
            spec.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, AndroidArtifacts.ArtifactType.KEEP_RULES.type)
        }
    }
}

const val DEX_DIR_NAME = SdkConstants.FD_DEX
const val KEEP_RULES_FILE_NAME = "keep_rules"
