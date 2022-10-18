package com.tyron.builder.gradle.internal.dependency

import com.google.common.base.MoreObjects
import com.google.common.collect.ImmutableList
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactScope
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.ANDROID_RES
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.APK
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.APKS_FROM_BUNDLE
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.BASE_MODULE_LINT_MODEL
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.FEATURE_DEX
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.FEATURE_NAME
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.FEATURE_SHRUNK_JAVA_RES
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.LINT_MODEL
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.ArtifactType.PACKAGED_DEPENDENCIES
import com.tyron.builder.gradle.internal.publishing.PublishedConfigSpec
import com.tyron.builder.gradle.options.ProjectOptions
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.specs.Spec

interface ResolutionResultProvider {
    fun getResolutionResult(configType: ConsumedConfigType): ResolutionResult
}
/**
 * Object that represents the dependencies of variant.
 *
 * The dependencies are expressed as composite Gradle configuration objects that extends
 * all the configuration objects of the "configs".
 *
 * It optionally contains the dependencies for a test config for the given config.
 */
class VariantDependencies internal constructor(
    private val variantName: String,
    private val componentType: ComponentType,
    val compileClasspath: Configuration,
    val runtimeClasspath: Configuration,
    private val sourceSetRuntimeConfigurations: Collection<Configuration>,
    val sourceSetImplementationConfigurations: Collection<Configuration>,
    private val elements: Map<PublishedConfigSpec, Configuration>,
    private val providedClasspath: Configuration,
    val annotationProcessorConfiguration: Configuration,
    private val reverseMetadataValuesConfiguration: Configuration?,
    val wearAppConfiguration: Configuration?,
    private val testedVariant: VariantCreationConfig?,
    private val project: Project,
    private val projectOptions: ProjectOptions,
    isSelfInstrumenting: Boolean,
): ResolutionResultProvider {

    // Never exclude artifacts for self-instrumenting, test-only modules.
    private val avoidExcludingArtifacts = componentType.isSeparateTestProject && isSelfInstrumenting

    init {
        check(!componentType.isTestComponent || testedVariant != null) {
            "testedVariantDependencies null for test component"
        }
    }

    fun getIncomingRuntimeDependencies(): Collection<Dependency> {
        val builder = ImmutableList.builder<Dependency>()
        for (classpath in sourceSetRuntimeConfigurations) {
            builder.addAll(classpath.incoming.dependencies)
        }
        return builder.build()
    }

    fun getElements(configSpec: PublishedConfigSpec): Configuration? {
        return elements[configSpec]
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this).add("name", variantName).toString()
    }

    override fun getResolutionResult(configType: ConsumedConfigType): ResolutionResult = when (configType) {
        ConsumedConfigType.COMPILE_CLASSPATH -> compileClasspath.incoming.resolutionResult
        ConsumedConfigType.RUNTIME_CLASSPATH -> runtimeClasspath.incoming.resolutionResult
        else -> throw RuntimeException("Unsupported ConsumedConfigType value: $configType")
    }

    @JvmOverloads
    fun getArtifactFileCollection(
        configType: ConsumedConfigType,
        scope: AndroidArtifacts.ArtifactScope,
        artifactType: AndroidArtifacts.ArtifactType,
        attributes: AndroidAttributes? = null
    ): FileCollection = getArtifactCollection(
        configType, scope, artifactType, attributes
    ).artifactFiles

    @JvmOverloads
    fun getArtifactCollection(
        configType: ConsumedConfigType,
        scope: ArtifactScope,
        artifactType: AndroidArtifacts.ArtifactType,
        attributes: AndroidAttributes? = null
    ): ArtifactCollection {
        var artifacts =
            computeArtifactCollection(configType, scope, artifactType, attributes)

        if (configType == ConsumedConfigType.RUNTIME_CLASSPATH
            && isArtifactTypeExcluded(artifactType)
        ) {
            val excludedDirectories = computeArtifactCollection(
                ConsumedConfigType.PROVIDED_CLASSPATH,
                ArtifactScope.PROJECT,
                PACKAGED_DEPENDENCIES,
                null
            ).artifactFiles
            artifacts = FilteredArtifactCollection(
                FilteringSpec(artifacts, excludedDirectories, project.objects)
            )
        }

        if (!configType.needsTestedComponents() || !componentType.isTestComponent) {
            return artifacts
        }

        // get the matching file collection for the tested variant, if any.
        if (testedVariant == null) {
            return artifacts
        }

        // For artifact that should not be duplicated between test APk and tested APK (e.g. classes)
        // we remove duplicates from test APK. More specifically, for androidTest variants for base
        // and dynamic features, we need to remove artifacts that are already packaged in the tested
        // variant. Also, we remove artifacts already packaged in base/features that the tested
        // feature depends on.
        if (!componentType.isApk) {
            // Don't filter unit tests.
            return artifacts
        }

        if (configType != ConsumedConfigType.RUNTIME_CLASSPATH) {
            // Only filter runtime classpath.
            return artifacts
        }

        if (testedVariant.componentType.isAar) {
            // Don't filter test APKs for library projects, as there is no tested APK.
            return artifacts
        }

        if (!isArtifactTypeSubtractedForInstrumentationTests(artifactType)) {
            return artifacts
        }

        if (testedVariant.componentType.isDynamicFeature) {
            // If we're in an androidTest for a dynamic feature we need to filter out artifacts from
            // the base and dynamic features this dynamic feature depends on.
            val excludedDirectories = testedVariant
                .variantDependencies
                .computeArtifactCollection(
                    ConsumedConfigType.PROVIDED_CLASSPATH,
                    ArtifactScope.PROJECT,
                    PACKAGED_DEPENDENCIES,
                    null
                )
                .artifactFiles
            artifacts = FilteredArtifactCollection(
                FilteringSpec(artifacts, excludedDirectories, project.objects)
            )
        }

        val testedArtifactCollection = testedVariant
            .variantDependencies
            .getArtifactCollection(configType, scope, artifactType, attributes)
        artifacts =
            SubtractingArtifactCollection(artifacts, testedArtifactCollection, project.objects)
        return artifacts
    }

    private fun isArtifactTypeExcluded(artifactType: AndroidArtifacts.ArtifactType): Boolean {
        return when {
            avoidExcludingArtifacts -> false
            componentType.isDynamicFeature ->
                artifactType != PACKAGED_DEPENDENCIES
                        && artifactType != APKS_FROM_BUNDLE
                        && artifactType != FEATURE_DEX
                        && artifactType != FEATURE_NAME
                        && artifactType != FEATURE_SHRUNK_JAVA_RES
                        && artifactType != LINT_MODEL
                        && artifactType != BASE_MODULE_LINT_MODEL
            componentType.isSeparateTestProject ->
                isArtifactTypeSubtractedForInstrumentationTests(artifactType)
            else -> false
        }
    }

    private fun getConfiguration(configType: ConsumedConfigType): Configuration {
        return when (configType) {
            ConsumedConfigType.COMPILE_CLASSPATH -> compileClasspath
            ConsumedConfigType.RUNTIME_CLASSPATH -> runtimeClasspath
            ConsumedConfigType.PROVIDED_CLASSPATH -> providedClasspath
            ConsumedConfigType.ANNOTATION_PROCESSOR -> annotationProcessorConfiguration
            ConsumedConfigType.REVERSE_METADATA_VALUES ->
                checkNotNull(reverseMetadataValuesConfiguration) {
                    "reverseMetadataValuesConfiguration is null"
                }
        }
    }

    fun getArtifactCollectionForToolingModel(
        configType: ConsumedConfigType,
        scope: ArtifactScope,
        artifactType: AndroidArtifacts.ArtifactType
    ): ArtifactCollection {
        return computeArtifactCollection(configType, scope, artifactType, null)
    }

    private fun computeArtifactCollection(
        configType: ConsumedConfigType,
        scope: ArtifactScope,
        artifactType: AndroidArtifacts.ArtifactType,
        attributes: AndroidAttributes?
    ): ArtifactCollection {
        checkComputeArtifactCollectionArguments(configType, scope, artifactType)

        val configuration = getConfiguration(configType)
        val attributesAction =
            Action { container: AttributeContainer ->
                container.attribute(AndroidArtifacts.ARTIFACT_TYPE, artifactType.type)
                attributes?.addAttributesToContainer(container)
            }
        val filter = getComponentFilter(scope)
        val lenientMode =
            projectOptions[BooleanOption.IDE_BUILD_MODEL_ONLY] || projectOptions[BooleanOption.IDE_BUILD_MODEL_ONLY_V2]

        return configuration
            .incoming
            .artifactView { config: ArtifactView.ViewConfiguration ->
                config.attributes(attributesAction)
                filter?.let { config.componentFilter(it) }
                // TODO somehow read the unresolved dependencies?
                config.lenient(lenientMode)
            }
            .artifacts
    }

    companion object {

        const val CONFIG_NAME_COMPILE = "compile"
        const val CONFIG_NAME_PUBLISH = "publish"
        const val CONFIG_NAME_APK = "apk"
        const val CONFIG_NAME_PROVIDED = "provided"
        const val CONFIG_NAME_WEAR_APP = "wearApp"
        const val CONFIG_NAME_ANDROID_APIS = "androidApis"
        const val CONFIG_NAME_ANNOTATION_PROCESSOR = "annotationProcessor"
        const val CONFIG_NAME_API = "api"
        const val CONFIG_NAME_COMPILE_ONLY = "compileOnly"
        const val CONFIG_NAME_IMPLEMENTATION = "implementation"
        const val CONFIG_NAME_RUNTIME_ONLY = "runtimeOnly"
        const val CONFIG_NAME_APPLICATION = "application"
        const val CONFIG_NAME_LINTCHECKS = "lintChecks"
        const val CONFIG_NAME_LINTPUBLISH = "lintPublish"
        const val CONFIG_NAME_TESTED_APKS = "testedApks"
        const val CONFIG_NAME_CORE_LIBRARY_DESUGARING = "coreLibraryDesugaring"

        @Deprecated("")
        const val CONFIG_NAME_FEATURE = "feature"

        private fun isArtifactTypeSubtractedForInstrumentationTests(
            artifactType: AndroidArtifacts.ArtifactType
        ): Boolean {
            return (artifactType != ANDROID_RES && artifactType != COMPILED_DEPENDENCIES_RESOURCES)
        }

        private fun checkComputeArtifactCollectionArguments(
            configType: ConsumedConfigType,
            scope: ArtifactScope,
            artifactType: AndroidArtifacts.ArtifactType
        ) {
            when (artifactType) {
                PACKAGED_DEPENDENCIES ->
                    check(
                        configType == ConsumedConfigType.PROVIDED_CLASSPATH
                                || configType == ConsumedConfigType.REVERSE_METADATA_VALUES
                    ) {
                        "Packaged dependencies must only be requested from the PROVIDED_CLASSPATH or REVERSE_METADATA_VALUES"
                    }
                else -> {
                    // No validation
                }
            }
            when (configType) {
                ConsumedConfigType.PROVIDED_CLASSPATH ->
                    check(artifactType == PACKAGED_DEPENDENCIES || artifactType == APK) {
                        "Provided classpath must only be used for from the PACKAGED_DEPENDENCIES and APKS"
                    }
                else -> {
                    // No validation
                }
            }
        }

        private fun getComponentFilter(scope: ArtifactScope): Spec<ComponentIdentifier>? {
            return when (scope) {
                ArtifactScope.ALL -> null
                ArtifactScope.EXTERNAL ->
                    // since we want both Module dependencies and file based dependencies in this case
                    // the best thing to do is search for non ProjectComponentIdentifier.
                    Spec { it !is ProjectComponentIdentifier }
                ArtifactScope.PROJECT -> Spec { it is ProjectComponentIdentifier }
                ArtifactScope.REPOSITORY_MODULE -> Spec { it is ModuleComponentIdentifier }
                ArtifactScope.FILE -> Spec {
                    !(it is ProjectComponentIdentifier || it is ModuleComponentIdentifier)
                }
            }
        }
    }
}
