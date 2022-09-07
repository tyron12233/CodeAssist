package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.extension.impl.VariantApiOperationsRegistrar
import com.tyron.builder.api.variant.*
import com.tyron.builder.api.variant.impl.SourcesImpl
import com.tyron.builder.api.variant.impl.VariantOutputList
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.component.features.*
import com.tyron.builder.gradle.internal.component.legacy.ModelV1LegacySupport
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport
import com.tyron.builder.gradle.internal.core.ProductFlavor
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import java.io.File
import java.util.function.Predicate

/**
 * Base of the interfaces used internally to access *PropertiesImpl object.
 *
 * This allows a graph hierarchy rather than a strict tree, in order to have multiple
 * supertype and make some tasks receive a generic type that does not fit the actual
 * implementation hierarchy (see for instance ApkCreationConfig)
 */
interface ComponentCreationConfig : ComponentIdentity {
    // ---------------------------------------------------------------------------------------------
    // BASIC INFO
    // ---------------------------------------------------------------------------------------------
    val dirName: String
    val baseName: String
    val componentType: ComponentType
    val description: String
    val productFlavorList: List<ProductFlavor>
    fun computeTaskName(prefix: String, suffix: String): String
    fun computeTaskName(prefix: String): String

    // ---------------------------------------------------------------------------------------------
    // NEEDED BY ALL COMPONENTS
    // ---------------------------------------------------------------------------------------------

    // needed by resource compilation/link
    val applicationId: Provider<String>
    val namespace: Provider<String>
    val debuggable: Boolean
    val supportedAbis: Set<String>

    val minSdkVersion: AndroidVersion
    val targetSdkVersion: AndroidVersion
    val targetSdkVersionOverride: AndroidVersion?

    // ---------------------------------------------------------------------------------------------
    // OPTIONAL FEATURES
    // ---------------------------------------------------------------------------------------------

    val assetsCreationConfig: AssetsCreationConfig?
    val androidResourcesCreationConfig: AndroidResourcesCreationConfig?
    val resValuesCreationConfig: ResValuesCreationConfig?
    val buildConfigCreationConfig: BuildConfigCreationConfig?
    val instrumentationCreationConfig: InstrumentationCreationConfig?
    val manifestPlaceholdersCreationConfig: ManifestPlaceholdersCreationConfig?

    // TODO figure out whether these properties are needed by all
    // TODO : remove as it is now in Variant.
    // ---------------------------------------------------------------------------------------------
    val outputs: VariantOutputList

    // ---------------------------------------------------------------------------------------------
    // INTERNAL DELEGATES
    // ---------------------------------------------------------------------------------------------
    val buildFeatures: BuildFeatureValues
    val variantDependencies: VariantDependencies
    val artifacts: ArtifactsImpl
    val sources: SourcesImpl
    val taskContainer: MutableTaskContainer
    val transformManager: TransformManager
    val paths: VariantPathHelper
    val services: TaskCreationServices

    /**
     * DO NOT USE, this is still present to support ModelBuilder v1 code that should be deleted
     * soon. Instead, use [sources] API.
     */

    val variantSources: VariantSources


    /**
     * Access to the global task creation configuration
     */
    val global: GlobalTaskCreationConfig

    // ---------------------------------------------------------------------------------------------
    // INTERNAL HELPERS
    // ---------------------------------------------------------------------------------------------

    /**
     * Get the compile classpath for compiling sources in this component
     */
    fun getJavaClasspath(
        configType: AndroidArtifacts.ConsumedConfigType,
        classesType: AndroidArtifacts.ArtifactType,
        generatedBytecodeKey: Any? = null
    ): FileCollection

    val compileClasspath: FileCollection

    val providedOnlyClasspath: FileCollection

    val javaCompilation: JavaCompilation

    fun addVariantOutput(
        variantOutputConfiguration: VariantOutputConfiguration,
        outputFileName: String? = null
    )

    fun computeLocalFileDependencies(filePredicate: Predicate<File>): FileCollection

    fun computeLocalPackagedJars(): FileCollection

    /**
     * Returns the artifact name modified depending on the component type.
     */
    fun getArtifactName(name: String): String

    /** Publish intermediate artifacts in the BuildArtifactsHolder based on PublishingSpecs.  */
    fun publishBuildArtifacts()
//
    fun <T: Component> createUserVisibleVariantObject(
    projectServices: ProjectServices,
    operationsRegistrar: VariantApiOperationsRegistrar<out CommonExtension<*, *, *, *>, out VariantBuilder, out Variant>,
    stats: Any?
    ): T

    // ---------------------------------------------------------------------------------------------
    // LEGACY SUPPORT
    // ---------------------------------------------------------------------------------------------

    @Deprecated("DO NOT USE, this is just for model v1 legacy support")
    val modelV1LegacySupport: ModelV1LegacySupport

    @Deprecated("DO NOT USE, this is just for old variant API legacy support")
    val oldVariantApiLegacySupport: OldVariantApiLegacySupport?
}
