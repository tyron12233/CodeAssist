package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.android.tools.profgen.*
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.tasks.PackageAndroidArtifact
import com.tyron.builder.internal.utils.fromDisallowChanges
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.packaging.DexFileComparator
import com.tyron.builder.packaging.DexFileNameSupplier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Task that transforms a human readable art profile into a binary form version that can be shipped
 * inside an APK or a Bundle.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ART_PROFILE, secondaryTaskCategories = [TaskCategory.COMPILATION])
abstract class CompileArtProfileTask: NonIncrementalTask() {

    @get: [InputFiles Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val mergedArtProfile: RegularFileProperty

    @get: [InputFiles PathSensitive(PathSensitivity.RELATIVE)]
    abstract val dexFolders: ConfigurableFileCollection

    @get: [InputFiles Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val featuresDexFolders: ConfigurableFileCollection

    @get: [InputFiles Optional PathSensitive(PathSensitivity.RELATIVE)]
    abstract val obfuscationMappingFile: RegularFileProperty

    @get: OutputFile
    abstract val binaryArtProfile: RegularFileProperty

    @get: OutputFile
    abstract val binaryArtProfileMetadata: RegularFileProperty

    abstract class CompileArtProfileWorkAction:
            WorkAction<CompileArtProfileWorkAction.Parameters> {

        abstract class Parameters : WorkParameters {
            abstract val mergedArtProfile: RegularFileProperty
            abstract val dexFolders: ConfigurableFileCollection
            abstract val obfuscationMappingFile: RegularFileProperty
            abstract val binaryArtProfileOutputFile: RegularFileProperty
            abstract val binaryArtProfileMetadataOutputFile: RegularFileProperty
        }

        override fun execute() {
            val diagnostics = Diagnostics {
                    error -> throw RuntimeException("Error parsing baseline-prof.txt : $error")
            }
            val humanReadableProfile = HumanReadableProfile(
                parameters.mergedArtProfile.get().asFile,
                diagnostics
            ) ?: throw RuntimeException(
                "Merged ${SdkConstants.FN_ART_PROFILE} cannot be parsed successfully."
            )

            val supplier = DexFileNameSupplier()
            val artProfile = ArtProfile(
                    humanReadableProfile,
                    if (parameters.obfuscationMappingFile.isPresent) {
                        ObfuscationMap(parameters.obfuscationMappingFile.get().asFile)
                    } else {
                        ObfuscationMap.Empty
                    },
                    //need to rename dex files with sequential numbers the same way [DexIncrementalRenameManager] does
                    parameters.dexFolders.asFileTree.files.sortedWith(DexFileComparator()).map {
                        DexFile(it.inputStream(), supplier.get())
                    }
            )
            // the P compiler is always used, the server side will transcode if necessary.
            parameters.binaryArtProfileOutputFile.get().asFile.outputStream().use {
                artProfile.save(it, ArtProfileSerializer.V0_1_0_P)
            }

            // create the metadata.
            parameters.binaryArtProfileMetadataOutputFile.get().asFile.outputStream().use {
                artProfile.save(it, ArtProfileSerializer.METADATA_0_0_2)
            }
        }
    }

    override fun doTaskAction() {
        // if we do not have a merged human readable profile, just return.
        if (!mergedArtProfile.isPresent || !mergedArtProfile.get().asFile.exists()) return

        workerExecutor.noIsolation().submit(CompileArtProfileWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.mergedArtProfile.set(mergedArtProfile)
            it.dexFolders.from(dexFolders)
            it.obfuscationMappingFile.set(obfuscationMappingFile)
            it.binaryArtProfileOutputFile.set(binaryArtProfile)
            it.binaryArtProfileMetadataOutputFile.set(binaryArtProfileMetadata)
        }
    }

    class CreationAction(
            creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<CompileArtProfileTask, ApkCreationConfig>(creationConfig) {

        override val name: String
            get() = creationConfig.computeTaskName("compile", "ArtProfile")
        override val type: Class<CompileArtProfileTask>
            get() = CompileArtProfileTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<CompileArtProfileTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                    taskProvider,
                    CompileArtProfileTask::binaryArtProfile
            ).on(InternalArtifactType.BINARY_ART_PROFILE)

            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                CompileArtProfileTask::binaryArtProfileMetadata
            ).on(InternalArtifactType.BINARY_ART_PROFILE_METADATA)
        }

        override fun configure(task: CompileArtProfileTask) {
            super.configure(task)
            task.mergedArtProfile.setDisallowChanges(
                    creationConfig.artifacts.get(
                            InternalArtifactType.MERGED_ART_PROFILE
                    )
            )
            task.dexFolders.fromDisallowChanges(
                    PackageAndroidArtifact.CreationAction.getDexFolders(creationConfig)
            )

            PackageAndroidArtifact.CreationAction.getFeatureDexFolder(
                    creationConfig,
                    task.project.path
            )?.let {
                task.featuresDexFolders.from(it)
            }
            task.featuresDexFolders.disallowChanges()

            task.obfuscationMappingFile.setDisallowChanges(
                    creationConfig.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            )
        }
    }
}
