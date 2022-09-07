package com.tyron.builder.api.component.impl

import com.google.common.collect.ImmutableMap
import com.tyron.builder.api.attributes.ProductFlavorAttr
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.AnnotationProcessor
import com.tyron.builder.api.variant.BuildConfigField
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.api.AnnotationProcessorOptions
import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.gradle.internal.DependencyConfigurator
import com.tyron.builder.gradle.internal.VariantManager
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.core.dsl.ApkProducingComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.MultiVariantComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.impl.ComponentDslInfoImpl
import com.tyron.builder.gradle.internal.dependency.ArtifactCollectionWithExtraArtifact
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.PublishingSpecs.Companion.getVariantPublishingSpec
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.services.BaseServices
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.process.CommandLineArgumentProvider
import java.io.Serializable

class OldVariantApiLegacySupportImpl(
    private val component: ComponentCreationConfig,
    private val dslInfo: ComponentDslInfo,
    override val variantData: BaseVariantData
): OldVariantApiLegacySupport {

    override val buildTypeObj: BuildType
        get() = (dslInfo as ComponentDslInfoImpl).buildTypeObj
    override val productFlavorList: List<ProductFlavor>
        get() = (dslInfo as MultiVariantComponentDslInfo).productFlavorList
    override val mergedFlavor: MergedFlavor
        get() = (dslInfo as ComponentDslInfoImpl).mergedFlavor
    override val dslSigningConfig: com.tyron.builder.gradle.internal.dsl.SigningConfig? =
        (dslInfo as? ApkProducingComponentDslInfo)?.signingConfig

    /**
     * The old variant API runs after the new variant API, yet we need to make sure that whatever
     * method used by the users (old variant API or new variant API), we end up with the same
     * storage so the information is visible to both old and new variant API users.
     *
     * The new Variant API does not allow for reading (at least not without doing an explicit
     * [org.gradle.api.provider.Provider.get] call. However, the old variant API was providing
     * reading access.
     *
     * In order to use the same storage, an implementation of the old variant objects (List and Map)
     * need to be proxied to the new Variant API storage (ListProperty and MapProperty). It is not
     * possible to do the reverse proxy'ing since the storage must be able to store
     * [org.gradle.api.provider.Provider<T>] which a plain java List cannot do.
     *
     * When the user reads information using the old variant API, there is no choice but doing a
     * [org.gradle.api.provider.Provider.get] call which can fail during old variant API execution
     * since some of these providers can be obtained from a Task execution.
     *
     * Therefore, only allow access to the old variant API when the compatibility flag is set.
     */
    class JavaCompileOptionsForOldVariantAPI(
        private val services: BaseServices,
        private val annotationProcessor: AnnotationProcessor
    ): JavaCompileOptions {
        // Initialize the wrapper instance that will be returned on each call.
        private val _annotationProcessorOptions = object: AnnotationProcessorOptions {
            private val _classNames = MutableListBackedUpWithListProperty(
                annotationProcessor.classNames,
            "AnnotationProcessorOptions.classNames")
            private val _arguments = MutableMapBackedUpWithMapProperty(
                annotationProcessor.arguments,
                "AnnotationProcessorOptions.arguments",
            )

            override fun getClassNames(): MutableList<String> = _classNames

            override fun getArguments(): MutableMap<String, String> = _arguments

            override fun getCompilerArgumentProviders(): MutableList<CommandLineArgumentProvider> =
                annotationProcessor.argumentProviders
        }
        override val annotationProcessorOptions: AnnotationProcessorOptions
            get() {
                if (!services.projectOptions.get(BooleanOption.ENABLE_LEGACY_API)) {
                    services.issueReporter
                        .reportError(
                            IssueReporter.Type.GENERIC,
                            RuntimeException(
            """
            Access to deprecated legacy com.tyron.builder.gradle.api.BaseVariant.getJavaCompileOptions requires compatibility mode for Property values in new com.tyron.builder.api.variant.AnnotationProcessorOptions
            ${BooleanOption.ENABLE_LEGACY_API}
            """.trimIndent()
                            )
                        )
                    // return default value during sync
                    return object: AnnotationProcessorOptions {
                        override fun getClassNames(): MutableList<String> = mutableListOf()

                        override fun getArguments(): MutableMap<String, String> = mutableMapOf()

                        override fun getCompilerArgumentProviders(): MutableList<CommandLineArgumentProvider>  = mutableListOf()
                    }
                }
                return _annotationProcessorOptions
            }
    }

    override val oldVariantApiJavaCompileOptions: JavaCompileOptions =
        JavaCompileOptionsForOldVariantAPI(
            component.services,
            component.javaCompilation.annotationProcessor
        )


    override fun getJavaClasspathArtifacts(
        configType: AndroidArtifacts.ConsumedConfigType,
        classesType: AndroidArtifacts.ArtifactType,
        generatedBytecodeKey: Any?
    ): ArtifactCollection {
        val mainCollection =
            component.variantDependencies.getArtifactCollection(
                configType,
                AndroidArtifacts.ArtifactScope.ALL,
                classesType
            )
        val extraArtifact = component.services.provider {
            variantData.getGeneratedBytecode(generatedBytecodeKey)
        }
        val combinedCollection = component.services.fileCollection(
            mainCollection.artifactFiles, extraArtifact
        )
        val extraCollection = ArtifactCollectionWithExtraArtifact.makeExtraCollection(
            mainCollection,
            combinedCollection,
            extraArtifact,
            component.services.projectInfo.path
        )

        return (component as? TestComponentCreationConfig)?.onTestedVariant { testedVariant ->
            // This is required because of http://b/150500779. Kotlin Gradle plugin relies on
            // TestedComponentIdentifierImpl being present in the returned artifact collection, as
            // artifacts with that identifier type are added to friend paths to kotlinc invocation.
            // Because jar containing all classes of the main artifact is in the classpath when
            // compiling test, we need to add TestedComponentIdentifierImpl artifact with that file.
            // This is needed when compiling test variants that access internal members.
            val internalArtifactType = getVariantPublishingSpec(testedVariant.componentType)
                .getSpec(classesType, configType.publishedTo)!!.outputType

            @Suppress("USELESS_CAST") // Explicit cast needed here.
            val testedAllClasses: Provider<FileCollection> =
                component.services.provider {
                    component.services.fileCollection(
                        testedVariant.artifacts.get(internalArtifactType)
                    ) as FileCollection
                }
            val combinedCollectionForTest = component.services.fileCollection(
                combinedCollection, testedAllClasses, testedAllClasses
            )

            ArtifactCollectionWithExtraArtifact.makeExtraCollectionForTest(
                extraCollection,
                combinedCollectionForTest,
                testedAllClasses,
                component.services.projectInfo.path,
                null
            )
        } ?: extraCollection
    }

    private var allRawAndroidResources: ConfigurableFileCollection? = null

    override fun getAllRawAndroidResources(component: ComponentCreationConfig): FileCollection {
        if (allRawAndroidResources != null) {
            return allRawAndroidResources!!
        }
        allRawAndroidResources = component.services.fileCollection()

        allRawAndroidResources!!.from(
            component.variantDependencies
                .getArtifactCollection(
                    AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.ALL,
                    AndroidArtifacts.ArtifactType.ANDROID_RES
                )
                .artifactFiles
        )

        allRawAndroidResources!!.from(
            component.services.fileCollection(
                variantData.extraGeneratedResFolders
            ).builtBy(listOfNotNull(variantData.extraGeneratedResFolders.builtBy))
        )

        component.taskContainer.generateApkDataTask?.let {
            allRawAndroidResources!!.from(component.artifacts.get(InternalArtifactType.MICRO_APK_RES))
        }

        allRawAndroidResources!!.from(component.sources.res.getVariantSources().map { allRes ->
            allRes.map { directoryEntries ->
                directoryEntries.directoryEntries
                    .map { it.asFiles(component.services::directoryProperty) }
            }
        })
        return allRawAndroidResources!!
    }

    override fun addBuildConfigField(type: String, key: String, value: Serializable, comment: String?) {
        component.buildConfigCreationConfig?.buildConfigFields?.put(
            key, BuildConfigField(type, value, comment)
        )
    }

    override fun handleMissingDimensionStrategy(
        dimension: String,
        alternatedValues: List<String>
    ) {

        // First, setup the requested value, which isn't the actual requested value, but
        // the variant name, modified
        val requestedValue = VariantManager.getModifiedName(component.name)
        val attributeKey = ProductFlavorAttr.of(dimension)
        val attributeValue: ProductFlavorAttr = component.services.named(
            ProductFlavorAttr::class.java, requestedValue
        )

        component.variantDependencies.compileClasspath.attributes.attribute(attributeKey, attributeValue)
        component.variantDependencies.runtimeClasspath.attributes.attribute(attributeKey, attributeValue)
        component.variantDependencies
            .annotationProcessorConfiguration
            .attributes
            .attribute(attributeKey, attributeValue)

        // then add the fallbacks which contain the actual requested value
        DependencyConfigurator.addFlavorStrategy(
            component.services.dependencies.attributesSchema,
            dimension,
            ImmutableMap.of(requestedValue, alternatedValues)
        )
    }
}
