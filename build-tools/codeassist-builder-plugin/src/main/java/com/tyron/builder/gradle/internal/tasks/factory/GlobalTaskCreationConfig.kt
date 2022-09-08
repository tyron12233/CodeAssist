package com.tyron.builder.gradle.internal.tasks.factory

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.*
import com.tyron.builder.core.LibraryRequest
import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import com.tyron.builder.gradle.internal.dsl.LanguageSplitOptions
import com.tyron.builder.gradle.internal.packaging.JarCreatorType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.BaseServices
import com.tyron.builder.internal.packaging.ApkCreatorType
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

/**
 * Creation config for global tasks that are not variant-based.
 *
 * This gives access to a few select objects that may be useful.
 *
 * IMPORTANT: it must not give access to the whole extension as it is too dangerous. We need to
 * control that is accessible to global task (DSL elements that are global) and what isn't (DSL
 * elements that are configurable per-variant). Giving access directly to the DSL removes this
 * safety net and reduce maintainability in the future when things become configurable per-variant.
 */
interface GlobalTaskCreationConfig: BootClasspathConfig {

    // Global DSL Elements

    val compileSdkHashString: String
//    val buildToolsRevision: Revision
    val ndkVersion: String?
    val ndkPath: String?

    val productFlavorCount: Int
    val productFlavorDimensionCount: Int

    val assetPacks: Set<String>

    val dynamicFeatures: Set<String>
    val hasDynamicFeatures: Boolean
        get() = dynamicFeatures.isNotEmpty()

    val aidlPackagedList: Collection<String>?
    val bundleOptions: Bundle
    val compileOptions: CompileOptions
    val compileOptionsIncremental: Boolean?
    val composeOptions: ComposeOptions
    val dataBinding: DataBinding
//    val deviceProviders: List<DeviceProvider>
    val externalNativeBuild: ExternalNativeBuild
    val installationOptions: Installation
    val libraryRequests: Collection<LibraryRequest>
    val lintOptions: Lint
    val prefab: Set<PrefabPackagingOptions>
    val resourcePrefix: String?
    val splits: Splits
    val testCoverage: TestCoverage
//    val testOptions: TestOptions
//    val testServers: List<TestServer>
//    val transforms: List<Transform>
//    val transformsDependencies: List<List<Any>>

    // processed access to some DSL values

    val namespacedAndroidResources: Boolean
    val testOptionExecutionEnum: com.tyron.builder.model.TestOptions.Execution?
    val legacyLanguageSplitOptions: LanguageSplitOptions

    /** the same as [prefab] but returns an empty set on unsupported variants */
    val prefabOrEmpty: Set<PrefabPackagingOptions>

    val hasNoBuildTypeMinified: Boolean

    val manifestArtifactType: InternalArtifactType<Directory>

    // Internal Objects

    val globalArtifacts: ArtifactsImpl
    val services: BaseServices

    val createdBy: String

    val asmApiVersion: Int

    /**
     * Queries the given configuration for platform attributes from the jar(s) in it.
     *
     * This extract platform attributes from the jars via an Artifact Transform. This is meant to
     * process android.jar
     */
    val platformAttrs: FileCollection

    val localCustomLintChecks: FileCollection

    val versionedSdkLoader: Provider<SdkComponentsBuildService.VersionedSdkLoader>

//    val versionedNdkHandler: SdkComponentsBuildService.VersionedNdkHandler

    // configurations that may need to be accessible
    val lintPublish: Configuration
    val lintChecks: Configuration

    val jarCreatorType: JarCreatorType

    val apkCreatorType: ApkCreatorType

    // Options from the settings plugin
//    val settingsOptions: SettingsOptions
}
