package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.AndroidVersion
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.JavaCompilation
import com.tyron.builder.api.variant.VariantOutputConfiguration
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.component.features.BuildConfigCreationConfig
import com.tyron.builder.gradle.internal.component.features.ResValuesCreationConfig
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.BuildFeatureValues
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.internal.variant.VariantPathHelper
import com.tyron.builder.plugin.builder.ProductFlavor
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import java.io.File
import java.util.function.Predicate

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

    /**
     * Access to the global task creation configuration
     */
    val global: GlobalTaskCreationConfig


    // OPTIONAL FEATURES
    // ---------------------------------------------------------------------------------------------

//    val assetsCreationConfig: AssetsCreationConfig?
//    val androidResourcesCreationConfig: AndroidResourcesCreationConfig?
    val resValuesCreationConfig: ResValuesCreationConfig?
    val buildConfigCreationConfig: BuildConfigCreationConfig?
//    val instrumentationCreationConfig: InstrumentationCreationConfig?
//    val manifestPlaceholdersCreationConfig: ManifestPlaceholdersCreationConfig?
//
//    // TODO figure out whether these properties are needed by all
//    // TODO : remove as it is now in Variant.
//    // ---------------------------------------------------------------------------------------------
//    val outputs: VariantOutputList


    // ---------------------------------------------------------------------------------------------
    // INTERNAL DELEGATES
    // ---------------------------------------------------------------------------------------------
    val artifacts: ArtifactsImpl
    val variantDependencies: VariantDependencies
    val paths: VariantPathHelper
    val buildFeatures: BuildFeatureValues
//    val sources: SourcesImpl
    val taskContainer: MutableTaskContainer
    val services: TaskCreationServices

//    /**
//     * DO NOT USE, this is still present to support ModelBuilder v1 code that should be deleted
//     * soon. Instead, use [sources] API.
//     */
//
//    val variantSources: VariantSources

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

    val packageJacocoRuntime: Boolean

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

    val needsJavaResStreams: Boolean
}
