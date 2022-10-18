package com.tyron.builder.api.component.impl

import com.android.SdkConstants
import com.android.utils.appendCapitalized
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.component.impl.features.AndroidResourcesCreationConfigImpl
import com.tyron.builder.api.component.impl.features.AssetsCreationConfigImpl
import com.tyron.builder.api.component.impl.features.ResValuesCreationConfigImpl
import com.tyron.builder.api.variant.Component
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.JavaCompilation
import com.tyron.builder.api.variant.VariantOutputConfiguration
import com.tyron.builder.api.variant.impl.*
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.features.AndroidResourcesCreationConfig
import com.tyron.builder.gradle.internal.component.features.AssetsCreationConfig
import com.tyron.builder.gradle.internal.component.features.InstrumentationCreationConfig
import com.tyron.builder.gradle.internal.component.features.ResValuesCreationConfig
import com.tyron.builder.gradle.internal.component.legacy.OldVariantApiLegacySupport
import com.tyron.builder.gradle.internal.core.ProductFlavor
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.PublishableComponentDslInfo
import com.tyron.builder.gradle.internal.dependency.AndroidAttributes
import com.tyron.builder.gradle.internal.dependency.VariantDependencies
import com.tyron.builder.gradle.internal.dependency.getProvidedClasspath
import com.tyron.builder.gradle.internal.pipeline.TransformManager
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.*
import com.tyron.builder.gradle.internal.publishing.PublishedConfigSpec
import com.tyron.builder.gradle.internal.publishing.PublishingSpecs.Companion.getVariantPublishingSpec
import com.tyron.builder.gradle.internal.scope.*
import com.tyron.builder.gradle.internal.scope.BuildArtifactSpec.Companion.get
import com.tyron.builder.gradle.internal.scope.BuildArtifactSpec.Companion.has
import com.tyron.builder.gradle.internal.services.TaskCreationServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.testFixtures.testFixturesClassifier
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import com.tyron.builder.gradle.internal.variant.VariantPathHelper
import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.internal.file.DefaultFilePropertyFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import java.util.function.Predicate
import java.util.stream.Collectors

abstract class ComponentImpl<DslInfoT: ComponentDslInfo>(
    open val componentIdentity: ComponentIdentity,
    final override val buildFeatures: BuildFeatureValues,
    protected val dslInfo: DslInfoT,
    final override val variantDependencies: VariantDependencies,
    override val variantSources: VariantSources,
    override val paths: VariantPathHelper,
    override val artifacts: ArtifactsImpl,
    private val variantData: BaseVariantData? = null,
    override val taskContainer: MutableTaskContainer,
    override val transformManager: TransformManager,
    protected val internalServices: VariantServices,
    final override val services: TaskCreationServices,
    final override val global: GlobalTaskCreationConfig,
) : Component, ComponentCreationConfig, ComponentIdentity by componentIdentity {

    // ---------------------------------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------------------------------
    override val namespace: Provider<String> =
        internalServices.providerOf(
            type = String::class.java,
            value = dslInfo.namespace
        )
//
//    override fun <ParamT : InstrumentationParameters> transformClassesWith(
//        classVisitorFactoryImplClass: Class<out AsmClassVisitorFactory<ParamT>>,
//        scope: InstrumentationScope,
//        instrumentationParamsConfig: (ParamT) -> Unit
//    ) {
//        instrumentation.transformClassesWith(
//            classVisitorFactoryImplClass,
//            scope,
//            instrumentationParamsConfig
//        )
//    }
//
//    override fun setAsmFramesComputationMode(mode: FramesComputationMode) {
//        instrumentation.setAsmFramesComputationMode(mode)
//    }

    override val javaCompilation: JavaCompilation =
        JavaCompilationImpl(
            dslInfo.javaCompileOptionsSetInDSL,
            buildFeatures.dataBinding,
            internalServices)

    override val sources: SourcesImpl by lazy {
        SourcesImpl(
            DefaultSourcesProviderImpl(this, variantSources),
            internalServices.projectInfo.projectDirectory,
            internalServices,
            variantSources.variantSourceProvider,
        ).also { sourcesImpl ->
            // add all source sets extra directories added by the user
            variantSources.customSourceList.forEach{ (_, srcEntries) ->
                srcEntries.forEach { customSourceDirectory ->
                    sourcesImpl.extras.maybeCreate(customSourceDirectory.sourceTypeName).also {
                        (it as FlatSourceDirectoriesImpl).addSource(
                            FileBasedDirectoryEntryImpl(
                                customSourceDirectory.sourceTypeName,
                                customSourceDirectory.directory,
                            )
                        )
                    }
                }
            }
        }
    }

//    override val instrumentation: Instrumentation
//        get() = instrumentationCreationConfig.instrumentation

    override val compileClasspath: FileCollection by lazy {
        getJavaClasspath(
            ConsumedConfigType.COMPILE_CLASSPATH,
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            generatedBytecodeKey = null
        )
    }

    override val compileConfiguration = variantDependencies.compileClasspath

    override val runtimeConfiguration = variantDependencies.runtimeClasspath

    override val annotationProcessorConfiguration =
        variantDependencies.annotationProcessorConfiguration

    // ---------------------------------------------------------------------------------------------
    // INTERNAL API
    // ---------------------------------------------------------------------------------------------

    // this is technically a public API for the Application Variant (only)
    override val outputs: VariantOutputList
        get() = VariantOutputList(variantOutputs.toList())

    override val componentType: ComponentType
        get() = dslInfo.componentType

    override val dirName: String
        get() = paths.dirName

    override val baseName: String
        get() = paths.baseName

    override val productFlavorList: List<ProductFlavor> = dslInfo.componentIdentity.productFlavors.map {
        ProductFlavor(it.first, it.second)
    }

    // ---------------------------------------------------------------------------------------------
    // Private stuff
    // ---------------------------------------------------------------------------------------------

    private val variantOutputs = mutableListOf<VariantOutputImpl>()

    override fun addVariantOutput(
        variantOutputConfiguration: VariantOutputConfiguration,
        outputFileName: String?
    ) {
        variantOutputs.add(
            VariantOutputImpl(
                createVersionCodeProperty(),
                createVersionNameProperty(),
                internalServices.newPropertyBackingDeprecatedApi(Boolean::class.java, true),
                variantOutputConfiguration,
                variantOutputConfiguration.baseName(this),
                variantOutputConfiguration.fullName(this),
                internalServices.newPropertyBackingDeprecatedApi(
                    String::class.java,
                    outputFileName
                        ?: paths.getOutputFileName(
                            internalServices.projectInfo.getProjectBaseName(),
                            variantOutputConfiguration.baseName(this)
                        ),
                )
            )
        )
    }

    // default impl for variants that don't actually have versionName
    protected open fun createVersionNameProperty(): Property<String?> {
        val stringValue: String? = null
        return internalServices.nullablePropertyOf(String::class.java, stringValue).also {
            it.disallowChanges()
        }
    }

    // default impl for variants that don't actually have versionCode
    protected open fun createVersionCodeProperty() : Property<Int?> {
        val intValue: Int? = null
        return internalServices.nullablePropertyOf(Int::class.java, intValue).also {
            it.disallowChanges()
        }
    }

    override fun computeTaskName(prefix: String): String =
        prefix.appendCapitalized(name)

    override fun computeTaskName(prefix: String, suffix: String): String =
        prefix.appendCapitalized(name, suffix)

    // -------------------------
    // File location computation. Previously located in VariantScope, these are here
    // temporarily until we fully move away from them.

    // Precomputed file paths.
    final override fun getJavaClasspath(
        configType: ConsumedConfigType,
        classesType: AndroidArtifacts.ArtifactType,
        generatedBytecodeKey: Any?
    ): FileCollection {
        var mainCollection = variantDependencies
            .getArtifactFileCollection(configType, ArtifactScope.ALL, classesType)
        oldVariantApiLegacySupport?.let {
            mainCollection = mainCollection.plus(
                it.variantData.getGeneratedBytecode(generatedBytecodeKey)
            )
        }
        // Add R class jars to the front of the classpath as libraries might also export
        // compile-only classes. This behavior is verified in CompileRClassFlowTest
        // While relying on this order seems brittle, it avoids doubling the number of
        // files on the compilation classpath by exporting the R class separately or
        // and is much simpler than having two different outputs from each library, with
        // and without the R class, as AGP publishing code assumes there is exactly one
        // artifact for each publication.
        mainCollection =
            internalServices.fileCollection(
                *listOfNotNull(
                    androidResourcesCreationConfig?.getCompiledRClasses(configType),
                    buildConfigCreationConfig?.compiledBuildConfig,
                    getCompiledManifest(),
                    mainCollection
                ).toTypedArray()
            )
        return mainCollection
    }

    override val providedOnlyClasspath: FileCollection by lazy {
        getProvidedClasspath(
            compileClasspath = variantDependencies.getArtifactCollection(
                ConsumedConfigType.COMPILE_CLASSPATH,
                ArtifactScope.ALL,
                AndroidArtifacts.ArtifactType.CLASSES_JAR
            ),
            runtimeClasspath = variantDependencies.getArtifactCollection(
                ConsumedConfigType.RUNTIME_CLASSPATH,
                ArtifactScope.ALL,
                AndroidArtifacts.ArtifactType.CLASSES_JAR
            )
        )
    }

    /** Publish intermediate artifacts in the BuildArtifactsHolder based on PublishingSpecs.  */
    override fun publishBuildArtifacts() {
        for (outputSpec in getVariantPublishingSpec(componentType).outputs) {
            val buildArtifactType = outputSpec.outputType

            // Gradle only support publishing single file.  Therefore, unless Gradle starts
            // supporting publishing multiple files, PublishingSpecs should not contain any
            // OutputSpec with an appendable ArtifactType.
            if (has(buildArtifactType) && get(buildArtifactType).appendable) {
                throw RuntimeException(
                    "Appendable ArtifactType '${buildArtifactType.name()}' cannot be published."
                )
            }
            val artifactProvider = artifacts.get(buildArtifactType)
            if (artifactProvider is DefaultFilePropertyFactory.DefaultRegularFileVar) {
                artifactProvider.extra = buildArtifactType
            }
            val artifactContainer = artifacts.getArtifactContainer(buildArtifactType)
            if (!artifactContainer.needInitialProducer().get()) {
                val isPublicationConfigs =
                    outputSpec.publishedConfigTypes.any { it.isPublicationConfig }

                if (isPublicationConfigs) {
                    val components = (dslInfo as PublishableComponentDslInfo).publishInfo!!.components
                    for(component in components) {
                        publishIntermediateArtifact(
                            artifactProvider,
                            outputSpec.artifactType,
                            outputSpec.publishedConfigTypes.map {
                                PublishedConfigSpec(it, component) }.toSet(),
                            outputSpec.libraryElements?.let {
                                internalServices.named(LibraryElements::class.java, it)
                            }
                        )
                    }
                } else {
                    publishIntermediateArtifact(
                        artifactProvider,
                        outputSpec.artifactType,
                        outputSpec.publishedConfigTypes.map { PublishedConfigSpec(it) }.toSet(),
                        outputSpec.libraryElements?.let {
                            internalServices.named(LibraryElements::class.java, it)
                        }
                    )
                }
            }
        }
    }

    private fun getCompiledManifest(): FileCollection {
        val manifestClassRequired = dslInfo.componentType.requiresManifest &&
                services.projectOptions[BooleanOption.GENERATE_MANIFEST_CLASS]
        val isTest = dslInfo.componentType.isForTesting
        val isAar = dslInfo.componentType.isAar
        return if (manifestClassRequired && !isAar && !isTest) {
            internalServices.fileCollection(artifacts.get(InternalArtifactType.COMPILE_MANIFEST_JAR))
        } else {
            internalServices.fileCollection()
        }
    }

    override val modelV1LegacySupport = ModelV1LegacySupportImpl(dslInfo)

    override val oldVariantApiLegacySupport: OldVariantApiLegacySupport? by lazy {
        OldVariantApiLegacySupportImpl(
            this,
            dslInfo,
            variantData!!
        )
    }

    override val assetsCreationConfig: AssetsCreationConfig by lazy {
        AssetsCreationConfigImpl(
            dslInfo.androidResourcesDsl!!,
            internalServices
        ) { androidResourcesCreationConfig }
    }

    override val androidResourcesCreationConfig: AndroidResourcesCreationConfig? by lazy {
        if (buildFeatures.androidResources) {
            AndroidResourcesCreationConfigImpl(
                this,
                dslInfo,
                dslInfo.androidResourcesDsl!!,
                internalServices,
            )
        } else {
            null
        }
    }

    override val resValuesCreationConfig: ResValuesCreationConfig? by lazy {
        if (buildFeatures.resValues) {
            ResValuesCreationConfigImpl(
                dslInfo.androidResourcesDsl!!,
                internalServices
            )
        } else {
            null
        }
    }

    override val instrumentationCreationConfig: InstrumentationCreationConfig? by lazy<InstrumentationCreationConfig?> {
//        InstrumentationCreationConfigImpl(
//            this,
//            internalServices
//        )
        null
    }


    /**
     * Returns the direct (i.e., non-transitive) local file dependencies matching the given
     * predicate
     *
     * @return a non null, but possibly empty FileCollection
     * @param filePredicate the file predicate used to filter the local file dependencies
     */
    override fun computeLocalFileDependencies(filePredicate: Predicate<File>): FileCollection {
        val configuration = variantDependencies.runtimeClasspath

        // Get a list of local file dependencies. There is currently no API to filter the
        // files here, so we need to filter it in the return statement below. That means that if,
        // for example, filePredicate filters out all files but jars in the return statement, but an
        // AarProducerTask produces an aar, then the returned FileCollection contains only jars but
        // still has AarProducerTask as a dependency.
        val dependencies =
            Callable<Collection<SelfResolvingDependency>> {
                configuration
                    .allDependencies
                    .stream()
                    .filter { it: Dependency? -> it is SelfResolvingDependency }
                    .filter { it: Dependency? -> it !is ProjectDependency }
                    .map { it: Dependency -> it as SelfResolvingDependency }
                    .collect(
                        ImmutableList.toImmutableList()
                    )
            }

        // Create a file collection builtBy the dependencies.  The files are resolved later.
        return internalServices.fileCollection(
            Callable<Collection<File>> {
                dependencies.call().stream()
                    .flatMap { it: SelfResolvingDependency ->
                        it
                            .resolve()
                            .stream()
                    }
                    .filter(filePredicate)
                    .collect(Collectors.toList())
            })
            .builtBy(dependencies)
    }

    /**
     * Returns the packaged local Jars
     *
     * @return a non null, but possibly empty set.
     */
    override fun computeLocalPackagedJars(): FileCollection =
        computeLocalFileDependencies { file ->
            file
                .name
                .lowercase(Locale.US)
                .endsWith(SdkConstants.DOT_JAR)
        }

    override fun getArtifactName(name: String) = name

    /**
     * Publish an intermediate artifact.
     *
     * @param artifact Provider of File or FileSystemLocation to be published.
     * @param artifactType the artifact type.
     * @param configSpecs the PublishedConfigSpec.
     * @param libraryElements the artifact's library elements
     */
    private fun publishIntermediateArtifact(
        artifact: Provider<out FileSystemLocation>,
        artifactType: AndroidArtifacts.ArtifactType,
        configSpecs: Set<PublishedConfigSpec>,
        libraryElements: LibraryElements?
    ) {
        Preconditions.checkState(configSpecs.isNotEmpty())
        for (configSpec in configSpecs) {
            val config = variantDependencies.getElements(configSpec)
            val configType = configSpec.configType
            if (config != null) {
                if (configType.isPublicationConfig) {
                    var classifier: String? = null
                    val isSourcePublication = configType == PublishedConfigType.SOURCE_PUBLICATION
                    val isJavaDocPublication =
                        configType == PublishedConfigType.JAVA_DOC_PUBLICATION
                    if (configSpec.isClassifierRequired) {
                        classifier = if (isSourcePublication) {
                            componentIdentity.name + "-" + DocsType.SOURCES
                        } else if (isJavaDocPublication) {
                            componentIdentity.name + "-" + DocsType.JAVADOC
                        } else {
                            componentIdentity.name
                        }
                    } else if (componentType.isTestFixturesComponent) {
                        classifier = testFixturesClassifier
                    } else if (isSourcePublication) {
                        classifier = DocsType.SOURCES
                    } else if (isJavaDocPublication) {
                        classifier = DocsType.JAVADOC
                    }
                    publishArtifactToDefaultVariant(config, artifact, artifactType, classifier)
                } else {
                    publishArtifactToConfiguration(
                        config,
                        artifact,
                        artifactType,
                        AndroidAttributes(null, libraryElements)
                    )
                }
            }
        }
    }
}