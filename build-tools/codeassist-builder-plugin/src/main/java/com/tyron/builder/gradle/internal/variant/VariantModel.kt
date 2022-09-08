package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.gradle.errors.SyncIssueReporter
import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.model.v2.ide.ProjectType
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Configuration object for the model builder. This contains everything that they need, and nothing
 * else.
 *
 * This will contain variant information, and their inputs. It can also compute the default variant
 * to be used during sync.
 *
 * It will contain some global DSL elements that needs to be access to put them in the model.
 *
 * Finally, this contains some utility objects, like ProjectOptions
 */
interface VariantModel {
    val projectType: ProjectType
    val projectTypeV1: Int

    val inputs: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>

    /**
     * the main variants. This is the output of the plugin (apk, aar, etc...) and does not
     * include the test components (android test, unit test)
     */
    val variants: List<VariantCreationConfig>

    /**
     * the test components (android test, unit test)
     */
    val testComponents: List<TestComponentCreationConfig>

    val defaultVariant: String?

    val buildFeatures: BuildFeatureValues

    // utility objects and methods

    val syncIssueReporter: SyncIssueReporter

    val projectOptions: ProjectOptions

    val mockableJarArtifact: FileCollection

    val filteredBootClasspath: Provider<List<RegularFile>>

    val versionedSdkLoader: Provider<SdkComponentsBuildService.VersionedSdkLoader>

    val globalArtifacts: ArtifactsImpl
}