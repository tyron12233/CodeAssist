package com.tyron.builder.internal.aapt

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.tyron.builder.core.ComponentType
import java.io.File
import java.io.Serializable

/**
 * Configuration for an `aapt package` or an `aapt2 link` operation.
 */
data class AaptPackageConfig(
    val manifestFile: File,
    val options: AaptOptions,
    val androidJarPath: String?,
    val componentType: ComponentType,
    val sourceOutputDir: File? = null,
    val resourceOutputApk: File,
    val librarySymbolTableFiles: ImmutableCollection<File> = ImmutableList.of(),
    val localSymbolTableFile: File? = null,
    val symbolOutputDir: File? = null,
    val verbose: Boolean = false,
    val resourceDirs: ImmutableList<File> = ImmutableList.of(),
    val proguardOutputFile: File? = null,
    val mainDexListProguardOutputFile: File? = null,
    val splits: ImmutableCollection<String>? = null,
    val customPackageForR: String? = null,
    val preferredDensity: String? = null,
    val resourceConfigs: ImmutableSet<String> = ImmutableSet.of(),
    val generateProtos: Boolean = false,
    val imports: ImmutableList<File> = ImmutableList.of(),
    val mergeOnly: Boolean = false,
    val packageId: Int? = null,
    val allowReservedPackageId: Boolean = false,
    val dependentFeatures: ImmutableCollection<File> = ImmutableList.of(),
    val listResourceFiles: Boolean = false,
    val staticLibrary: Boolean = false,
    val staticLibraryDependencies: ImmutableList<File> = ImmutableList.of(),
    val intermediateDir: File? = null,
    val useConditionalKeepRules: Boolean = false,
    val useMinimalKeepRules: Boolean = false,
    val useFinalIds: Boolean = true,
    val excludeSources: Boolean = false,
    val emitStableIdsFile: File? = null,
    val consumeStableIdsFile: File? = null,
    val mergeBlameDirectory: File? = null,
    val manifestMergeBlameFile: File? = null,
    val identifiedSourceSetMap: Map<String, String> = emptyMap()
) : Serializable {

    init {
        if (!mergeOnly && androidJarPath == null) {
            throw IllegalStateException(
                "Android jar path must be provided except in merge-only " +
                        "(auto namespace transform) case."
            )
        }
    }

    fun isStaticLibrary() = staticLibrary
    fun isListResourceFiles() = listResourceFiles

    /** Builder used to create a [AaptPackageConfig] from java.  */
    class Builder {

        private var manifestFile: File? = null
        private var options: AaptOptions? = null
        private var sourceOutputDir: File? = null
        private var resourceOutputApk: File? = null
        private var librarySymbolTableFiles: ImmutableCollection<File> = ImmutableSet.of()
        private var localSymbolTableFile: File? = null
        private var symbolOutputDir: File? = null
        private var isVerbose: Boolean = false
        private var resourceDirsBuilder: ImmutableList.Builder<File> = ImmutableList.builder()
        private var proguardOutputFile: File? = null
        private var mainDexListProguardOutputFile: File? = null
        private var splits: ImmutableCollection<String>? = null
        private var customPackageForR: String? = null
        private var preferredDensity: String? = null
        private var androidJarPath: String? = null
        private var resourceConfigs: ImmutableSet<String> = ImmutableSet.of()
        private var isGenerateProtos: Boolean = false
        private var componentType: ComponentType? = null
        private var imports: ImmutableList<File> = ImmutableList.of()
        private var packageId: Int? = null
        private var allowReservedPackageId: Boolean = false
        private var dependentFeatures: ImmutableCollection<File> = ImmutableList.of()
        private var listResourceFiles: Boolean = false
        private var staticLibrary: Boolean = false
        private var staticLibraryDependencies: ImmutableList<File> = ImmutableList.of()
        private var intermediateDir: File? = null
        private var useConditionalKeepRules: Boolean = false
        private var useMinimalKeepRules: Boolean = false
        private var useFinalIds: Boolean = true
        private var excludeSources: Boolean = false
        private var emitStableIdsFile: File? = null
        private var consumeStableIdsFile: File? = null
        private var mergeBlameDirectory: File? = null
        private var manifestMergeBlameFile: File? = null
        private var identifiedSourceSetMap: Map<String, String> = emptyMap()
        /**
         * Creates a new [AaptPackageConfig] from the data already placed in the builder.
         *
         * @return the new config
         */
        fun build(): AaptPackageConfig {
            return AaptPackageConfig(
                manifestFile = manifestFile!!,
                options = options!!,
                androidJarPath = androidJarPath!!,
                sourceOutputDir = sourceOutputDir,
                resourceOutputApk = resourceOutputApk!!,
                librarySymbolTableFiles = librarySymbolTableFiles,
                localSymbolTableFile = localSymbolTableFile,
                symbolOutputDir = symbolOutputDir,
                verbose = isVerbose,
                resourceDirs = resourceDirsBuilder.build(),
                proguardOutputFile = proguardOutputFile,
                mainDexListProguardOutputFile = mainDexListProguardOutputFile,
                splits = splits,
                customPackageForR = customPackageForR,
                preferredDensity = preferredDensity,
                resourceConfigs = resourceConfigs,
                generateProtos = isGenerateProtos,
                componentType = componentType!!,
                imports = imports,
                packageId = packageId,
                allowReservedPackageId = allowReservedPackageId,
                dependentFeatures = dependentFeatures,
                listResourceFiles = listResourceFiles,
                staticLibrary = staticLibrary,
                staticLibraryDependencies = staticLibraryDependencies,
                intermediateDir = intermediateDir,
                useConditionalKeepRules = useConditionalKeepRules,
                useMinimalKeepRules = useMinimalKeepRules,
                useFinalIds = useFinalIds,
                excludeSources = excludeSources,
                emitStableIdsFile = emitStableIdsFile,
                consumeStableIdsFile = consumeStableIdsFile,
                mergeBlameDirectory = mergeBlameDirectory,
                manifestMergeBlameFile = manifestMergeBlameFile,
                identifiedSourceSetMap = identifiedSourceSetMap
            )
        }

        fun setManifestFile(manifestFile: File): Builder {
            if (!manifestFile.isFile) {
                throw IllegalArgumentException(
                        "Manifest file '"
                                + manifestFile.absolutePath
                                + "' is not a readable file. ")
            }
            this.manifestFile = manifestFile
            return this
        }

        fun setOptions(options: AaptOptions): Builder {
            this.options = options
            return this
        }

        fun setSourceOutputDir(sourceOutputDir: File?): Builder {
            this.sourceOutputDir = sourceOutputDir
            return this
        }

        fun setSymbolOutputDir(symbolOutputDir: File?): Builder {
            this.symbolOutputDir = symbolOutputDir
            return this
        }

        fun setLibrarySymbolTableFiles(libraries: Set<File>?): Builder {
            if (libraries == null) {
                this.librarySymbolTableFiles = ImmutableSet.of()
            } else {
                this.librarySymbolTableFiles = ImmutableSet.copyOf(libraries)
            }

            return this
        }

        fun setLocalSymbolTableFile(localSymbolTableFile: File?): Builder {
            this.localSymbolTableFile = localSymbolTableFile
            return this
        }

        fun setResourceOutputApk(resourceOutputApk: File?): Builder {
            this.resourceOutputApk = resourceOutputApk
            return this
        }

        fun addResourceDir(resourceDir: File?): Builder {
            if (resourceDir != null && !resourceDir.isDirectory) {
                throw IllegalArgumentException("Path '" + resourceDir.absolutePath
                        + "' is not a readable directory.")
            }

            this.resourceDirsBuilder.add(resourceDir!!)
            return this
        }

        fun addResourceDirectories(resourceDirectories: Iterable<File?>): Builder {
            resourceDirectories.forEach { addResourceDir(it) }
            return this
        }

        fun setProguardOutputFile(proguardOutputFile: File?): Builder {
            this.proguardOutputFile = proguardOutputFile
            return this
        }

        fun setMainDexListProguardOutputFile(mainDexListProguardOutputFile: File?): Builder {
            this.mainDexListProguardOutputFile = mainDexListProguardOutputFile
            return this
        }

        fun setSplits(splits: Collection<String>?): Builder {
            if (splits == null) {
                this.splits = null
            } else {
                this.splits = ImmutableList.copyOf(splits)
            }

            return this
        }

        fun setPreferredDensity(preferredDensity: String?): Builder {
            this.preferredDensity = preferredDensity
            return this
        }

        fun setAndroidTarget(androidJar: File): Builder {
            this.androidJarPath = androidJar.absolutePath
            return this
        }

        fun setAndroidJarPath(androidJarPath: String): Builder {
            this.androidJarPath = androidJarPath
            return this
        }

        fun setResourceConfigs(resourceConfigs: Collection<String>): Builder {
            this.resourceConfigs = ImmutableSet.copyOf(resourceConfigs)
            return this
        }

        fun setComponentType(componentType: ComponentType): Builder {
            this.componentType = componentType
            return this
        }

        fun setCustomPackageForR(packageForR: String?): Builder {
            this.customPackageForR = packageForR
            return this
        }

        fun setImports(imports: Collection<File>): Builder {
            this.imports = ImmutableList.copyOf(imports)
            return this
        }

        fun setPackageId(packageId: Int?): Builder {
            this.packageId = packageId
            return this
        }

        fun setDependentFeatures(dependentFeatures: Collection<File>): Builder {
            this.dependentFeatures = ImmutableSet.copyOf(dependentFeatures)
            return this
        }

        fun setStaticLibraryDependencies(libraries: ImmutableList<File>): Builder {
            this.staticLibraryDependencies = libraries
            return this
        }

        fun setIntermediateDir(intermediateDir: File): Builder {
            this.intermediateDir = intermediateDir
            return this
        }

        /**
         * Allows the use of a reserved package ID. This should on be used for packages with a
         * pre-O min-sdk
         */
        fun setAllowReservedPackageId(value: Boolean): Builder {
            this.allowReservedPackageId = value
            return this
        }

        fun setUseConditionalKeepRules(value: Boolean): Builder {
            this.useConditionalKeepRules = value
            return this
        }

        fun setUseMinimalKeepRules(value: Boolean): Builder {
            this.useMinimalKeepRules = value
            return this
        }

        fun setUseFinalIds(value: Boolean): Builder {
            this.useFinalIds = value
            return this
        }

        fun setExcludeSources(value: Boolean): Builder {
            this.excludeSources = value
            return this
        }

        fun setEmitStableIdsFile(value: File?): Builder {
            this.emitStableIdsFile = value
            return this
        }

        fun setConsumeStableIdsFile(value: File?): Builder {
            this.consumeStableIdsFile = value
            return this
        }

        fun setGenerateProtos(value: Boolean): Builder {
            this.isGenerateProtos = value
            return this
        }

        fun setMergeBlameDirectory(mergeBlameDirectory: File?): Builder {
            this.mergeBlameDirectory = mergeBlameDirectory
            return this
        }

        fun setManifestMergeBlameFile(manifestMergeBlameFile: File?): Builder {
            this.manifestMergeBlameFile = manifestMergeBlameFile
            return this
        }

        fun setIdentifiedSourceSetMap(identifiedSourceSetMap: Map<String, String>) : Builder {
            this.identifiedSourceSetMap = identifiedSourceSetMap
            return this
        }
    }
}