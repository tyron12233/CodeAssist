package com.tyron.builder.gradle.internal.tasks.databinding

import android.databinding.tool.CompilerArguments
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.*
import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.CommandLineArgumentProvider

// KAPT resolving artifacts at configuration time was fixed in version 1.5.20. For versions older
// than that we need to workaround the issue by merging R files in a separate task.
val KAPT_FIX_KOTLIN_VERSION: KotlinVersion = KotlinVersion(1, 5, 20)

/**
 * Arguments passed to data binding. This class mimics the [CompilerArguments] class except that it
 * also implements [CommandLineArgumentProvider] for input/output annotations.
 */
@Suppress("MemberVisibilityCanBePrivate")
class DataBindingCompilerArguments constructor(

    @get:Input
    val incremental: Boolean,

    @get:Input
    val artifactType: CompilerArguments.Type,

    @get:Input
    val packageName: Provider<String>,

    @get:Input
    val minApi: Int,

    /**
     * The API versions file from the platform being compiled against.
     *
     * Historically this was distributed in platform-tools. It has been moved to platforms, so it
     * is versioned now. (There was some overlap, so this is available in platforms since platform
     * api 26, and was removed in the platform-tools several years later in 31.x)
     *
     * This will not be present if the compile-sdk version is less than 26 (a fallback to
     * platform-tools would not help for users that update their SDK, as it is removed in recent
     * platform-tools)
     *
     * Data binding will fall back to a fixed api versions file shipped as a java resource in the
     * unusual case of compiling against an older version.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    val apiVersionsFile: Provider<RegularFile>,

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val dependencyArtifactsDir: Provider<Directory>,

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val layoutInfoDir: Provider<Directory>,

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val classLogDir: Provider<Directory>,

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val baseFeatureInfoDir: Provider<Directory>,

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val featureInfoDir: Provider<Directory>,

    @get:OutputDirectory
    val aarOutDir: Provider<Directory>,

    @get:Optional
    @get:OutputFile
    val exportClassListOutFile: Provider<RegularFile>,

    @get:Input
    val enableDebugLogs: Boolean,

    // We don't set this as an @Input because: (1) it doesn't affect the results of data binding
    // processing, and (2) its value is changed between an Android Studio build and a command line
    // build; by not setting it as @Input, we allow the users to get incremental/UP-TO-DATE builds
    // when switching between the two modes (see https://issuetracker.google.com/80555723).
    @get:Internal
    val printEncodedErrorLogs: Boolean,

    @get:Input
    val isTestVariant: Boolean,

    @get:Input
    val isEnabledForTests: Boolean,

    @get:Input
    val isEnableV2: Boolean,

    @get:Input
    var isNonTransitiveR: Boolean,

    // TODO(183423660): Remove after KAPT issue is fixed.
    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val mergedDependenciesRFile: Provider<RegularFile>?,

    @get:Optional
    @get:InputFiles
    @get:Classpath
    val dependenciesLocalRFiles: FileCollection?,

    // Package-aware R.txt file for the given module. Instead of actual package it will contain the
    // keyword "local". Additionally, first line is a comment. For generating references to this
    // local R the default package of this module
    // should be used.
    // See [com.android.ide.common.symbols.SymbolIo] for read/write instructions.
    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val localRFile: Provider<RegularFile>?,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val arguments = CompilerArguments(
            incremental = incremental,
            artifactType = artifactType,
            modulePackage = packageName.get(),
            minApi = minApi,
            apiFile = apiVersionsFile.get().asFile,
            dependencyArtifactsDir = dependencyArtifactsDir.get().asFile,
            layoutInfoDir = layoutInfoDir.get().asFile,
            classLogDir = classLogDir.get().asFile,
            baseFeatureInfoDir = baseFeatureInfoDir.orNull?.asFile,
            featureInfoDir = featureInfoDir.orNull?.asFile,
            aarOutDir = aarOutDir.get().asFile,
            exportClassListOutFile = exportClassListOutFile.orNull?.asFile,
            enableDebugLogs = enableDebugLogs,
            printEncodedErrorLogs = printEncodedErrorLogs,
            isTestVariant = isTestVariant,
            isEnabledForTests = isEnabledForTests,
            isEnableV2 = isEnableV2,
            localR = localRFile?.orNull?.asFile,
            dependenciesRFiles = dependenciesLocalRFiles?.files?.toList(),
            mergedDependenciesRFile = mergedDependenciesRFile?.orNull?.asFile
        ).toMap()

        // Don't need to sort the returned list as the order shouldn't matter to Gradle.
        // Also don't need to escape the key and value strings as they will be passed as-is to
        // the Java compiler.
        return arguments.map { entry -> "-A${entry.key}=${entry.value}" }
    }

    companion object {

        @JvmStatic
        fun createArguments(
            creationConfig: ComponentCreationConfig,
            enableDebugLogs: Boolean,
            printEncodedErrorLogs: Boolean,
            isKaptPluginApplied: Boolean,
            projectKotlinVersion: KotlinVersion?
        ): DataBindingCompilerArguments {
            val artifacts = creationConfig.artifacts

            val isNonTransitiveR = creationConfig.services.projectOptions[BooleanOption.NON_TRANSITIVE_R_CLASS]

            val localRFile =
                    if (isNonTransitiveR)
                        artifacts.get(InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST)
                    else null

            // TODO(183423660): Re-enable this fully and removed merged dependencies R file after
            //  KAPT min version is higher or equal to 1.5.20.
            val kaptWorkaroundNeeded =
                    isKaptPluginApplied
                            && projectKotlinVersion != null
                            && projectKotlinVersion < KAPT_FIX_KOTLIN_VERSION

            val dependenciesLocalRFiles =
                    if (isNonTransitiveR && !kaptWorkaroundNeeded)
                        creationConfig.variantDependencies.getArtifactFileCollection(
                                AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.ALL,
                                AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME)
                    else null

            val mergedDependenciesRFile =
                    if (isNonTransitiveR && kaptWorkaroundNeeded)
                        artifacts.get(InternalArtifactType.MERGED_DEPENDENCIES_SYMBOL_LIST)
                    else null

            return DataBindingCompilerArguments(
                incremental = creationConfig.services.projectOptions
                    .get(BooleanOption.ENABLE_INCREMENTAL_DATA_BINDING),
                artifactType = getModuleType(creationConfig),
                packageName = creationConfig.namespace,
                minApi = creationConfig.minSdkVersion.apiLevel,
                apiVersionsFile = creationConfig.global.versionedSdkLoader.flatMap { it.apiVersionsFile },
                dependencyArtifactsDir = artifacts.get(DATA_BINDING_DEPENDENCY_ARTIFACTS),
                layoutInfoDir = artifacts.get(getLayoutInfoArtifactType(creationConfig)),
                classLogDir = artifacts.get(DATA_BINDING_BASE_CLASS_LOG_ARTIFACT),
                baseFeatureInfoDir = artifacts.get(
                    FEATURE_DATA_BINDING_BASE_FEATURE_INFO
                ),
                featureInfoDir = artifacts.get(FEATURE_DATA_BINDING_FEATURE_INFO),
                // Note that aarOurDir and exportClassListOutFile below are outputs. In the usual
                // pattern, they need to be wired as producers of the corresponding artifacts
                // through AGP Artifacts API. However, since the actual task that will produce these
                // artifacts is not known at this point (it could be either JavaCompile or Kapt),
                // using the Artifacts API is not possible.
                //
                // Instead, we wire them when JavaCompile or Kapt is registered, and here we'll just
                // get the artifacts' locations.
                aarOutDir = artifacts.get(DATA_BINDING_ARTIFACT),
                exportClassListOutFile = artifacts.get(DATA_BINDING_EXPORT_CLASS_LIST),
                enableDebugLogs = enableDebugLogs,
                printEncodedErrorLogs = printEncodedErrorLogs,
                isTestVariant = creationConfig.componentType.isTestComponent,
                isEnabledForTests = creationConfig.global.dataBinding.isEnabledForTests,
                isEnableV2 = true,
                isNonTransitiveR = isNonTransitiveR,
                mergedDependenciesRFile = mergedDependenciesRFile,
                dependenciesLocalRFiles = dependenciesLocalRFiles,
                localRFile = localRFile
            )
        }

        /**
         * Returns the module type of a variant. If it is a testing variant, return the module type
         * of the tested variant.
         */
        @JvmStatic
        fun getModuleType(creationConfig: ComponentCreationConfig): CompilerArguments.Type {
            val component = (creationConfig as? TestComponentCreationConfig)?.mainVariant
                ?: creationConfig

            return if (component.componentType.isAar) {
                CompilerArguments.Type.LIBRARY
            } else {
                if (component.componentType.isBaseModule) {
                    CompilerArguments.Type.APPLICATION
                } else {
                    CompilerArguments.Type.FEATURE
                }
            }
        }

        /**
         * Returns the appropriate artifact type of the layout info directory so that it does not
         * trigger unnecessary computations (see bug 133092984 and 110412851).
         */
        @JvmStatic
        fun getLayoutInfoArtifactType(creationConfig: ComponentCreationConfig): InternalArtifactType<Directory> {
            return if (creationConfig.componentType.isAar) {
                DATA_BINDING_LAYOUT_INFO_TYPE_PACKAGE
            } else {
                DATA_BINDING_LAYOUT_INFO_TYPE_MERGE
            }
        }
    }
}
