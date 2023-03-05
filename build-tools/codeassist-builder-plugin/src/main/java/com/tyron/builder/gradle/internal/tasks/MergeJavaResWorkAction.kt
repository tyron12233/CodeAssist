package com.tyron.builder.gradle.internal.tasks

import com.android.utils.FileUtils
import com.tyron.builder.files.KeyedFileCache
import com.tyron.builder.files.SerializableInputChanges
import com.tyron.builder.gradle.internal.InternalScope
import com.tyron.builder.gradle.internal.packaging.ParsedPackagingOptions
import com.tyron.builder.merge.IncrementalFileMergerInput
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * [ProfileAwareWorkAction] to merge java resources
 */
abstract class MergeJavaResWorkAction : WorkAction<MergeJavaResWorkAction.Params> {
    override fun execute() {
        val isIncremental = parameters.incremental.get()
        val outputFile = parameters.outputFile.get().asFile
        val incrementalStateFile = parameters.incrementalStateFile.asFile.get()
        if (!isIncremental) {
            FileUtils.deleteIfExists(outputFile)
            FileUtils.deleteIfExists(incrementalStateFile)
        }
        val cacheDir = parameters.cacheDir.asFile.get().also { FileUtils.mkdirs(it) }

        val zipCache = KeyedFileCache(cacheDir, KeyedFileCache::fileNameKey)
        val cacheUpdates = mutableListOf<Runnable>()
        @Suppress("DEPRECATION") // Legacy support
        val scopeMap = mutableMapOf<IncrementalFileMergerInput, com.tyron.builder.api.transform.QualifiedContent.ScopeType>()

        @Suppress("DEPRECATION") // Legacy support
        val inputMap = mutableMapOf<File, com.tyron.builder.api.transform.QualifiedContent.ScopeType>()
        @Suppress("DEPRECATION") // Legacy support
        run {
            parameters.projectJavaRes.forEach { inputMap[it] = com.tyron.builder.api.transform.QualifiedContent.Scope.PROJECT }
            parameters.subProjectJavaRes.forEach { inputMap[it] = com.tyron.builder.api.transform.QualifiedContent.Scope.SUB_PROJECTS }
            parameters.externalLibJavaRes.forEach { inputMap[it] = com.tyron.builder.api.transform.QualifiedContent.Scope.EXTERNAL_LIBRARIES }
        }
        parameters.featureJavaRes.forEach { inputMap[it] = InternalScope.FEATURES }

        val inputs =
            toInputs(
                inputMap,
                parameters.changedInputs.orNull,
                zipCache,
                cacheUpdates,
                !isIncremental,
                scopeMap
            )

        val mergeJavaResDelegate =
            MergeJavaResourcesDelegate(
                inputs,
                outputFile,
                scopeMap,
                ParsedPackagingOptions(
                    parameters.excludes.get(),
                    parameters.pickFirsts.get(),
                    parameters.merges.get()
                ),
                incrementalStateFile,
                isIncremental,
                parameters.noCompress.get()
            )
        mergeJavaResDelegate.run()
        cacheUpdates.forEach(Runnable::run)
    }

    abstract class Params : WorkParameters {
        abstract val projectJavaRes: ConfigurableFileCollection
        abstract val subProjectJavaRes: ConfigurableFileCollection
        abstract val externalLibJavaRes: ConfigurableFileCollection
        abstract val featureJavaRes: ConfigurableFileCollection
        abstract val outputFile: RegularFileProperty
        abstract val incrementalStateFile: RegularFileProperty
        abstract val incremental: Property<Boolean>
        abstract val cacheDir: DirectoryProperty
        abstract val changedInputs: Property<SerializableInputChanges>
        abstract val noCompress: ListProperty<String>
        abstract val excludes: SetProperty<String>
        abstract val pickFirsts: SetProperty<String>
        abstract val merges: SetProperty<String>
    }
}
