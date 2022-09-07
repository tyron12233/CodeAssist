package com.tyron.builder.gradle.internal

import com.android.SdkConstants.FD_RES_VALUES
import com.tyron.builder.api.variant.impl.DirectoryEntry
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.tasks.ProcessApplicationManifest
import com.tyron.builder.core.BuilderConstants
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceSet
import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.internal.utils.fromDisallowChanges
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.Incremental
import java.io.File

abstract class DependencyResourcesComputer {

    /**
     * Each source set within this project is recorded separately as an input.
     *
     * This allows us to:
     * 1. Preserve the order between sourcesets. It doesn't work to flatten these
     * to a single [PathSensitive.RELATIVE] input, as that would ignore ordering.
     * 2. Account for multiple source files with the same name. It doesn't work to use the
     * order-preserving [org.gradle.api.tasks.Classpath], as it ignores duplicate files from
     * fingerprinting.
    */
    abstract class ResourceSourceSetInput {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        @get:Incremental
        @get:IgnoreEmptyDirectories
        abstract val sourceDirectories: ConfigurableFileCollection
    }

    /** Local resources from within this project */
    @get:Nested
    abstract val resources: MapProperty<String, ResourceSourceSetInput>

    @get:Internal
    abstract val libraries: Property<ArtifactCollection>

    /** Resources from dependencies (e.g. in apps and tests) */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Incremental
    abstract val librarySourceSets: ConfigurableFileCollection

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedResOutputDir: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val microApkResDirectory: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraGeneratedResFolders: ConfigurableFileCollection

    @get:Input
    abstract val validateEnabled: Property<Boolean>

    private fun addLibraryResources(
        libraries: ArtifactCollection?,
        resourceSetList: MutableList<ResourceSet>,
        resourceArePrecompiled: Boolean,
        aaptEnv: String?
    ) {
        // add at the beginning since the libraries are less important than the folder based
        // resource sets.
        // get the dependencies first
        libraries?.let {
            val libArtifacts = it.artifacts

            // the order of the artifact is descending order, so we need to reverse it.
            for (artifact in libArtifacts) {
                val resourceSet = ResourceSet(
                    ProcessApplicationManifest.getArtifactName(artifact),
                    ResourceNamespace.RES_AUTO, null,
                    validateEnabled.get(),
                    aaptEnv
                )
                resourceSet.isFromDependency = true
                resourceSet.addSource(artifact.file)

                if (resourceArePrecompiled) {
                    // For values resources we impose stricter rules different from aapt so they need to go
                    // through the merging step.
                    resourceSet.setAllowedFolderPrefix(FD_RES_VALUES)
                }

                // add to 0 always, since we need to reverse the order.
                resourceSetList.add(0, resourceSet)
            }
        }
    }

    /**
     * Computes resource sets for merging, if [precompileDependenciesResources] flag is enabled we
     * filter out the non-values resources as it's precompiled and is consumed directly in the
     * linking step.
     */
    @JvmOverloads
    fun compute(
        precompileDependenciesResources: Boolean = false,
        aaptEnv: String?,
        renderscriptResOutputDir: Provider<Directory>
    ): List<ResourceSet> {
        val sourceFolderSets = getResSet(resources.get().mapValues { it.value.sourceDirectories }, aaptEnv)
        var size = sourceFolderSets.size
        libraries.orNull?.let {
            size += it.artifacts.size
        }

        val resourceSetList = ArrayList<ResourceSet>(size)

        addLibraryResources(
            libraries.orNull,
            resourceSetList,
            precompileDependenciesResources,
            aaptEnv
        )

        // add the folder based next
        resourceSetList.addAll(sourceFolderSets)

        // We add the generated folders to the main set
        val generatedResFolders = mutableListOf<File>()

        if (renderscriptResOutputDir.isPresent) {
            generatedResFolders.add(renderscriptResOutputDir.get().asFile)
        }

        generatedResFolders.addAll(generatedResOutputDir.files)
        generatedResFolders.addAll(extraGeneratedResFolders.files)
        generatedResFolders.addAll(microApkResDirectory.files)

        // add the generated files to the main set.
        if (sourceFolderSets.isNotEmpty()) {
            val mainResourceSet = sourceFolderSets[0]
            assert(
                mainResourceSet.configName == BuilderConstants.MAIN ||
                        // The main source set will not be included when building app android test
                        mainResourceSet.configName == BuilderConstants.ANDROID_TEST
            )
            mainResourceSet.addSources(generatedResFolders)
        }

        return resourceSetList
    }

    private fun getResSet(
        resourcesMap: Map<String, FileCollection>, aaptEnv: String?)
    : List<ResourceSet> {
        return resourcesMap.map {
            val resourceSet = ResourceSet(
                it.key, ResourceNamespace.RES_AUTO, null, validateEnabled.get(), aaptEnv)
            resourceSet.addSources(it.value.files)
            resourceSet
        }
    }

    fun initFromVariantScope(
        creationConfig: ComponentCreationConfig,
        microApkResDir: FileCollection,
        libraryDependencies: ArtifactCollection?,
        relativeLocalResources: Boolean,
    ) {
        val projectOptions = creationConfig.services.projectOptions
        val services = creationConfig.services

        validateEnabled.setDisallowChanges(!projectOptions.get(BooleanOption.DISABLE_RESOURCE_VALIDATION))
        libraryDependencies?.let {
            this.libraries.set(it)
            this.librarySourceSets.from(it.artifactFiles)
        }
        this.libraries.disallowChanges()
        this.librarySourceSets.disallowChanges()

        addResourceSets(
            creationConfig.sources.res.getLocalSourcesAsFileCollection().get(),
            relativeLocalResources
        ) {
            services.newInstance(ResourceSourceSetInput::class.java)
        }
        resources.disallowChanges()

        // Add the user added generated directories to the extraGeneratedResFolders.
        // this should be cleaned up once the old variant API is removed.
        creationConfig.sources.res.getVariantSources().get().forEach { directoryEntries ->
            directoryEntries.directoryEntries
                .filter {
                    it.isUserAdded && it.isGenerated
                }
                .forEach {
                    extraGeneratedResFolders.from(
                        it.asFiles(
                            creationConfig.services::directoryProperty
                        )
                    )
                }
        }

        creationConfig.oldVariantApiLegacySupport?.variantData?.extraGeneratedResFolders?.let {
            extraGeneratedResFolders.from(it)
        }
        extraGeneratedResFolders.disallowChanges()

        if (creationConfig.artifacts.get(InternalArtifactType.GENERATED_RES).isPresent) {
            generatedResOutputDir.fromDisallowChanges(
                creationConfig.artifacts.get(InternalArtifactType.GENERATED_RES)
            )
        }

        if (creationConfig.taskContainer.generateApkDataTask != null) {
            microApkResDirectory.from(microApkResDir)
        }
    }

    @VisibleForTesting
    fun addResourceSets(resourcesMap: Map<String, FileCollection>, relative: Boolean, blockFactory: () -> ResourceSourceSetInput) {
        resourcesMap.forEach{(name, fileCollection) ->
            resources.put(name, blockFactory().also {
                it.sourceDirectories.fromDisallowChanges(fileCollection)
            })
        }
    }
}
