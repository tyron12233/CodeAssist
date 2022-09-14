package com.tyron.builder.gradle.internal

import com.google.common.collect.Maps
import com.tyron.builder.api.attributes.AgpVersionAttr
import com.tyron.builder.api.attributes.BuildTypeAttr.Companion.ATTRIBUTE
import com.tyron.builder.api.attributes.ProductFlavorAttr
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.dependency.*
import com.tyron.builder.gradle.internal.dependency.ModelArtifactCompatibilityRule.Companion.setUp
import com.tyron.builder.gradle.internal.dsl.*
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.res.namespaced.AutoNamespacePreProcessTransform
import com.tyron.builder.gradle.internal.res.namespaced.AutoNamespaceTransform
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.internal.tasks.factory.BootClasspathConfig
import com.tyron.builder.gradle.internal.utils.getDesugarLibConfig
import com.tyron.builder.gradle.internal.variant.ComponentInfo
import com.tyron.builder.gradle.internal.variant.VariantInputModel
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.ActionConfiguration
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformSpec
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.AttributesSchema
import org.gradle.api.attributes.Usage
import org.gradle.api.internal.artifacts.ArtifactAttributes

/**
 * configures the dependencies for a set of variant inputs.
 */
class DependencyConfigurator(
    private val project: Project,
    private val projectServices: ProjectServices
) {
    fun configureDependencySubstitutions(): DependencyConfigurator {
        // If Jetifier is enabled, replace old support libraries with AndroidX.
        if (projectServices.projectOptions.get(BooleanOption.ENABLE_JETIFIER)) {
            TODO("""Jetifier is not yet supported. If you don't need it you can disable it 
                |in the top level gradle.properties file by setting the property
                | ${BooleanOption.ENABLE_JETIFIER.propertyName} to false.
            """.trimMargin())
//            replaceOldSupportLibraries(
//                project,
//                // Inline the property name for a slight memory improvement (so that the JVM doesn't
//                // create a new string every time this code is executed, which could be many when
//                // there are many subprojects).
//                reasonToReplace = "android.enableJetifier=true")
        }
        return this
    }

    fun configureCodeAssistTransformers(): DependencyConfigurator {
        return this
    }

    fun configureDependencyChecks(): DependencyConfigurator {
        val useAndroidX = projectServices.projectOptions.get(BooleanOption.USE_ANDROID_X)
        val enableJetifier = projectServices.projectOptions.get(BooleanOption.ENABLE_JETIFIER)

        when {
            !useAndroidX && !enableJetifier -> {
                project.configurations.all { configuration ->
                    if (configuration.isCanBeResolved) {
//                        configuration.incoming.afterResolve(
//                            AndroidXDependencyCheck.AndroidXDisabledJetifierDisabled(
//                                project, configuration.name, projectServices.issueReporter
//                            )
//                        )
                    }
                }
            }
            useAndroidX && !enableJetifier -> {
                project.configurations.all { configuration ->
                    if (configuration.isCanBeResolved) {
//                        configuration.incoming.afterResolve(
//                            AndroidXDependencyCheck.AndroidXEnabledJetifierDisabled(
//                                project, configuration.name, projectServices.issueReporter
//                            )
//                        )
                    }
                }
            }
        }

        return this
    }

    fun configureGeneralTransforms(
        namespacedAndroidResources: Boolean,
    ): DependencyConfigurator {
        val dependencies: DependencyHandler = project.dependencies

        val projectOptions = projectServices.projectOptions

        // The aars/jars may need to be processed (e.g., jetified to AndroidX) before they can be
        // used
        val autoNamespaceDependencies =
            namespacedAndroidResources && projectOptions[BooleanOption.CONVERT_NON_NAMESPACED_DEPENDENCIES]

        val jetifiedAarOutputType = if (autoNamespaceDependencies) {
            AndroidArtifacts.ArtifactType.MAYBE_NON_NAMESPACED_PROCESSED_AAR
        } else {
            AndroidArtifacts.ArtifactType.PROCESSED_AAR
        }
        // Arguments passed to an ArtifactTransform must not be null
        val jetifierIgnoreList = projectOptions[StringOption.JETIFIER_IGNORE_LIST] ?: ""
        if (projectOptions.get(BooleanOption.ENABLE_JETIFIER)) {
//            registerTransform(
//                JetifyTransform::class.java,
//                AndroidArtifacts.ArtifactType.AAR,
//                jetifiedAarOutputType
//            ) { params ->
//                params.ignoreListOption.setDisallowChanges(jetifierIgnoreList)
//            }
//            registerTransform(
//                JetifyTransform::class.java,
//                AndroidArtifacts.ArtifactType.JAR,
//                AndroidArtifacts.ArtifactType.PROCESSED_JAR
//            ) { params ->
//                params.ignoreListOption.setDisallowChanges(jetifierIgnoreList)
//            }
        } else {
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.AAR,
                jetifiedAarOutputType
            )
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.JAR,
                AndroidArtifacts.ArtifactType.PROCESSED_JAR
            )
        }
        registerTransform(
            ExtractAarTransform::class.java,
            AndroidArtifacts.ArtifactType.PROCESSED_AAR,
            AndroidArtifacts.ArtifactType.EXPLODED_AAR
        )
        registerTransform(
            ExtractAarTransform::class.java,
            AndroidArtifacts.ArtifactType.LOCAL_AAR_FOR_LINT,
            AndroidArtifacts.ArtifactType.LOCAL_EXPLODED_AAR_FOR_LINT
        )
//        dependencies.registerTransform(
//            MockableJarTransform::class.java
//        ) { spec: TransformSpec<MockableJarTransform.Parameters> ->
//            // Query for JAR instead of PROCESSED_JAR as android.jar doesn't need processing
//            spec.parameters.projectName.set(project.name)
//            spec.parameters.returnDefaultValues.set(true)
//            spec.from.attribute(
//                ArtifactAttributes.ARTIFACT_FORMAT,
//                AndroidArtifacts.ArtifactType.JAR.type
//            )
//            spec.from.attribute(
//                AndroidArtifacts.MOCKABLE_JAR_RETURN_DEFAULT_VALUES,
//                true
//            )
//            spec.to.attribute(
//                ArtifactAttributes.ARTIFACT_FORMAT,
//                AndroidArtifacts.TYPE_MOCKABLE_JAR
//            )
//            spec.to.attribute(
//                AndroidArtifacts.MOCKABLE_JAR_RETURN_DEFAULT_VALUES,
//                true
//            )
//        }
//        dependencies.registerTransform(
//            MockableJarTransform::class.java
//        ) { spec: TransformSpec<MockableJarTransform.Parameters> ->
//            // Query for JAR instead of PROCESSED_JAR as android.jar doesn't need processing
//            spec.parameters.projectName.set(project.name)
//            spec.parameters.returnDefaultValues.set(false)
//            spec.from.attribute(
//                ArtifactAttributes.ARTIFACT_FORMAT,
//                AndroidArtifacts.ArtifactType.JAR.type
//            )
//            spec.from.attribute(
//                AndroidArtifacts.MOCKABLE_JAR_RETURN_DEFAULT_VALUES,
//                false
//            )
//            spec.to.attribute(
//                ArtifactAttributes.ARTIFACT_FORMAT,
//                AndroidArtifacts.TYPE_MOCKABLE_JAR
//            )
//            spec.to.attribute(
//                AndroidArtifacts.MOCKABLE_JAR_RETURN_DEFAULT_VALUES,
//                false
//            )
//        }
        // transform to extract attr info from android.jar
//        registerTransform(
//            PlatformAttrTransform::class.java,
//            // Query for JAR instead of PROCESSED_JAR as android.jar doesn't need processing
//            AndroidArtifacts.ArtifactType.JAR.type,
//            AndroidArtifacts.TYPE_PLATFORM_ATTR
//        )

        val sharedLibSupport = projectOptions[BooleanOption.CONSUME_DEPENDENCIES_AS_SHARED_LIBRARIES]

        for (transformTarget in AarTransform.getTransformTargets()) {
            registerTransform(
                AarTransform::class.java,
                AndroidArtifacts.ArtifactType.EXPLODED_AAR,
                transformTarget
            ) { params ->
                params.targetType.setDisallowChanges(transformTarget)
                params.sharedLibSupport.setDisallowChanges(sharedLibSupport)
            }
        }
        if (projectOptions[BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES]) {
            registerTransform(
                AarResourcesCompilerTransform::class.java,
                AndroidArtifacts.ArtifactType.EXPLODED_AAR,
                AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES
            ) { params ->
                projectServices.initializeAapt2Input(params.aapt2)
            }
        }
        // API Jar: Produce a single API jar that can also contain the library R class from the AAR
        val apiUsage: Usage = project.objects.named(Usage::class.java, Usage.JAVA_API)

        dependencies.registerTransform(
            AarToClassTransform::class.java
        ) { reg: TransformSpec<AarToClassTransform.Params> ->
            reg.from.attribute(
                ArtifactAttributes.ARTIFACT_FORMAT,
                AndroidArtifacts.ArtifactType.PROCESSED_AAR.type
            )
            reg.from.attribute(
                Usage.USAGE_ATTRIBUTE,
                apiUsage
            )
            reg.to.attribute(
                ArtifactAttributes.ARTIFACT_FORMAT,
                AndroidArtifacts.ArtifactType.CLASSES_JAR.type
            )
            reg.to.attribute(
                Usage.USAGE_ATTRIBUTE,
                apiUsage
            )
            reg.parameters { params: AarToClassTransform.Params ->
                params.forCompileUse.set(true)
                params.generateRClassJar
                    .set(
                        projectOptions.get(
                            BooleanOption.COMPILE_CLASSPATH_LIBRARY_R_CLASSES
                        )
                    )
            }
        }
        // Produce a single runtime jar from the AAR.
        val runtimeUsage: Usage = project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME)

        dependencies.registerTransform(
            AarToClassTransform::class.java
        ) { reg: TransformSpec<AarToClassTransform.Params> ->
            reg.from.attribute(
                ArtifactAttributes.ARTIFACT_FORMAT,
                AndroidArtifacts.ArtifactType.PROCESSED_AAR.type
            )
            reg.from.attribute(
                Usage.USAGE_ATTRIBUTE,
                runtimeUsage
            )
            reg.to.attribute(
                ArtifactAttributes.ARTIFACT_FORMAT,
                AndroidArtifacts.ArtifactType.CLASSES_JAR.type
            )
            reg.to.attribute(
                Usage.USAGE_ATTRIBUTE,
                runtimeUsage
            )
            reg.parameters { params: AarToClassTransform.Params ->
                params.forCompileUse.set(false)

                params.generateRClassJar.set(false)
            }
        }

        if (projectOptions[BooleanOption.ENABLE_PROGUARD_RULES_EXTRACTION]) {
            registerTransform(
                ExtractProGuardRulesTransform::class.java,
                AndroidArtifacts.ArtifactType.PROCESSED_JAR,
                AndroidArtifacts.ArtifactType.UNFILTERED_PROGUARD_RULES
            )
        }
        registerTransform(
            LibrarySymbolTableTransform::class.java,
            AndroidArtifacts.ArtifactType.EXPLODED_AAR,
            AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME
        )
        if (autoNamespaceDependencies) {
            registerTransform(
                AutoNamespacePreProcessTransform::class.java,
                AndroidArtifacts.ArtifactType.MAYBE_NON_NAMESPACED_PROCESSED_AAR,
                AndroidArtifacts.ArtifactType.PREPROCESSED_AAR_FOR_AUTO_NAMESPACE
            ) { params ->
                projectServices.initializeAapt2Input(params.aapt2)
            }
            registerTransform(
                AutoNamespacePreProcessTransform::class.java,
                AndroidArtifacts.ArtifactType.JAR,
                AndroidArtifacts.ArtifactType.PREPROCESSED_AAR_FOR_AUTO_NAMESPACE
            ) { params ->
                projectServices.initializeAapt2Input(params.aapt2)
            }

            registerTransform(
                AutoNamespaceTransform::class.java,
                AndroidArtifacts.ArtifactType.PREPROCESSED_AAR_FOR_AUTO_NAMESPACE,
                AndroidArtifacts.ArtifactType.PROCESSED_AAR
            ) { params ->
                projectServices.initializeAapt2Input(params.aapt2)
            }
        }
        // Transform to go from external jars to CLASSES and JAVA_RES artifacts. This returns the
        // same exact file but with different types, since a jar file can contain both.
        for (classesOrResources in arrayOf(
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            AndroidArtifacts.ArtifactType.JAVA_RES
        )) {
            registerTransform(
                IdentityTransform::class.java,
                AndroidArtifacts.ArtifactType.PROCESSED_JAR,
                classesOrResources
            )
        }
        registerTransform(
            ExtractJniTransform::class.java,
            AndroidArtifacts.ArtifactType.PROCESSED_JAR,
            AndroidArtifacts.ArtifactType.JNI
        )
        // The Kotlin Kapt plugin should query for PROCESSED_JAR, but it is currently querying for
        // JAR, so we need to have the workaround below to make it get PROCESSED_JAR. See
        // http://issuetracker.google.com/111009645.
        project.configurations.all { configuration: Configuration ->
            if (configuration.name.startsWith("kapt")) {
                configuration
                    .attributes
                    .attribute(
                        ArtifactAttributes.ARTIFACT_FORMAT,
                        AndroidArtifacts.ArtifactType.PROCESSED_JAR.type
                    )
            }
        }

        // From an Android library subproject, there are 2 transform flows to CLASSES:
        //     1. CLASSES_DIR -> CLASSES
        //     2. CLASSES_JAR -> CLASSES
        // From a Java library subproject, there are also 2 transform flows to CLASSES:
        //     1. JVM_CLASS_DIRECTORY -> CLASSES
        //     2. JAR -> PROCESSED_JAR -> `CLASSES_JAR -> CLASSES
        registerTransform(
            ClassesDirToClassesTransform::class.java,
            AndroidArtifacts.ArtifactType.CLASSES_DIR,
            AndroidArtifacts.ArtifactType.CLASSES
        )
        registerTransform(
            IdentityTransform::class.java,
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            AndroidArtifacts.ArtifactType.CLASSES
        )
        registerTransform(
            IdentityTransform::class.java,
            ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
            AndroidArtifacts.ArtifactType.CLASSES.type
        ) { params ->
            params.acceptNonExistentInputFile.setDisallowChanges(true)
        }

        registerTransform(
            CollectResourceSymbolsTransform::class.java,
            AndroidArtifacts.ArtifactType.ANDROID_RES.type,
            AndroidArtifacts.ArtifactType.ANDROID_RES_SYMBOLS.type
        )
        registerTransform(
            CollectClassesTransform::class.java,
            AndroidArtifacts.ArtifactType.CLASSES_JAR,
            AndroidArtifacts.ArtifactType.JAR_CLASS_LIST
        )


        if (projectOptions.get(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT)) {
//            registerTransform(
//                AsarToApksTransform::class.java,
//                AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_ARCHIVE,
//                AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_APKS
//            ) { params ->
//                projectServices.initializeAapt2Input(params.aapt2)
//                params.signingConfigData.set(
//                    getBuildService(
//                        projectServices.buildServiceRegistry,
//                        AndroidLocationsBuildService::class.java
//                    ).map { it.getDefaultDebugKeystoreSigningConfig() }
//                )
//                params.signingConfigValidationResultDir.set(
//                    ArtifactsImpl(project, "global").get(InternalArtifactType.VALIDATE_SIGNING_CONFIG)
//                )
//            }
//            registerTransform(
//                AsarToManifestSnippetTransform::class.java,
//                AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_ARCHIVE,
//                AndroidArtifacts.ArtifactType.ANDROID_PRIVACY_SANDBOX_SDK_EXTRACTED_MANIFEST_SNIPPET
//            )
        }

        return this
    }

    fun configureCalculateStackFramesTransforms(
        bootClasspathConfig: BootClasspathConfig) : DependencyConfigurator {
//        registerGlobalRecalculateStackFramesTransform(
//            project.name,
//            project.dependencies,
//            bootClasspathConfig.fullBootClasspathProvider,
//            projectServices.buildServiceRegistry
//        )
        return this
    }

    fun configureJacocoTransforms() : DependencyConfigurator {
//        val jacocoTransformParametersConfig: (JacocoTransform.Params) -> Unit = {
//            val jacocoVersion = JacocoOptions.DEFAULT_VERSION
//            val jacocoConfiguration = JacocoConfigurations
//                .getJacocoAntTaskConfiguration(project, jacocoVersion)
//            it.jacocoInstrumentationService
//                .set(getBuildService(projectServices.buildServiceRegistry))
//            it.jacocoConfiguration.from(jacocoConfiguration)
//            it.jacocoVersion.setDisallowChanges(jacocoVersion)
//        }
//        registerTransform(
//            JacocoTransform::class.java,
//            AndroidArtifacts.ArtifactType.CLASSES,
//            AndroidArtifacts.ArtifactType.JACOCO_CLASSES,
//            jacocoTransformParametersConfig
//        )
//        registerTransform(
//            JacocoTransform::class.java,
//            AndroidArtifacts.ArtifactType.CLASSES_JAR,
//            AndroidArtifacts.ArtifactType.JACOCO_CLASSES_JAR,
//            jacocoTransformParametersConfig
//        )
//        registerTransform(
//            JacocoTransform::class.java,
//            AndroidArtifacts.ArtifactType.ASM_INSTRUMENTED_JARS,
//            AndroidArtifacts.ArtifactType.JACOCO_ASM_INSTRUMENTED_JARS,
//            jacocoTransformParametersConfig
//        )
        return this
    }

    private fun <T : GenericTransformParameters> registerTransform(
        transformClass: Class<out TransformAction<T>>,
        fromArtifactType: AndroidArtifacts.ArtifactType,
        toArtifactType: AndroidArtifacts.ArtifactType,
        parametersSetter: ((T) -> Unit)? = null
    ) {
        registerTransform(
            transformClass,
            fromArtifactType.type,
            toArtifactType.type,
            parametersSetter
        )
    }

    private fun <T : GenericTransformParameters> registerTransform(
        transformClass: Class<out TransformAction<T>>,
        fromArtifactType: String,
        toArtifactType: String,
        parametersSetter: ((T) -> Unit)? = null
    ) {
        project.dependencies.registerTransform(
            transformClass
        ) { spec: TransformSpec<T> ->
            spec.from.attribute(ArtifactAttributes.ARTIFACT_FORMAT, fromArtifactType)
            spec.to.attribute(ArtifactAttributes.ARTIFACT_FORMAT, toArtifactType)
            spec.parameters.projectName.setDisallowChanges(project.name)
            parametersSetter?.let { it(spec.parameters) }
        }
    }

    fun configureAttributeMatchingStrategies(
        variantInputModel: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
    ): DependencyConfigurator {
        val schema = project.dependencies.attributesSchema

        // custom strategy for build-type and product-flavor.
        setBuildTypeStrategy(schema, variantInputModel)
        setupFlavorStrategy(schema, variantInputModel)
        setupModelStrategy(schema)
        setUpAgpVersionStrategy(schema)

        return this
    }

    private fun setBuildTypeStrategy(
        schema: AttributesSchema,
        variantInputModel: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
    ) {
        // this is ugly but because the getter returns a very base class we have no choices.
        val dslBuildTypes = variantInputModel.buildTypes.values.map { it.buildType }

        if (dslBuildTypes.isEmpty()) {
            return
        }

        val alternateMap: MutableMap<String, List<String>> =
            Maps.newHashMap()
        for (buildType in dslBuildTypes) {
            if (!buildType.matchingFallbacks.isEmpty()) {
                alternateMap[buildType.name] = buildType.matchingFallbacks
            }
        }
        if (!alternateMap.isEmpty()) {
            val buildTypeStrategy =
                schema.attribute(
                    ATTRIBUTE
                )
            buildTypeStrategy
                .compatibilityRules
                .add(
                    AlternateCompatibilityRule.BuildTypeRule::class.java
                ) { config: ActionConfiguration ->
                    config.setParams(
                        alternateMap
                    )
                }
            buildTypeStrategy
                .disambiguationRules
                .add(
                    AlternateDisambiguationRule.BuildTypeRule::class.java
                ) { config: ActionConfiguration ->
                    config.setParams(
                        alternateMap
                    )
                }
        }
    }

    private fun setupFlavorStrategy(
        schema: AttributesSchema,
        variantInputModel: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
    ) {
        // this is ugly but because the getter returns a very base class we have no choices.
        val flavors = variantInputModel.productFlavors.values.map { it.productFlavor }

        // first loop through all the flavors and collect for each dimension, and each value, its
        // fallbacks
        // map of (dimension > (requested > fallbacks))
        val alternateMap: MutableMap<String, MutableMap<String, List<String>>> =
            Maps.newHashMap()
        for (flavor in flavors) {
            if (flavor.matchingFallbacks.isNotEmpty()) {
                val name = flavor.name
                val dimension = flavor.dimension!!
                val dimensionMap =
                    alternateMap.computeIfAbsent(
                        dimension
                    ) { s: String? -> Maps.newHashMap() }
                dimensionMap[name] = flavor.matchingFallbacks
            }
            handleMissingDimensions(alternateMap, flavor)
        }
        // also handle missing dimensions on the default config.
        handleMissingDimensions(alternateMap, variantInputModel.defaultConfigData.defaultConfig)
        // now that we know we have all the fallbacks for each dimensions, we can create the
        // rule instances.
        for ((key, value) in alternateMap) {
            addFlavorStrategy(schema, key, value)
        }
    }

    private fun setupModelStrategy(attributesSchema: AttributesSchema) {
        setUp(attributesSchema)
    }

    /** This is to enforce AGP version across a single or composite build. */
    private fun setUpAgpVersionStrategy(attributesSchema: AttributesSchema) {
        val strategy = attributesSchema.attribute(AgpVersionAttr.ATTRIBUTE)
//        strategy.compatibilityRules.add(AgpVersionCompatibilityRule::class.java)
    }

    private fun handleMissingDimensions(
        alternateMap: MutableMap<String, MutableMap<String, List<String>>>,
        flavor: BaseFlavor
    ) {
        val missingStrategies = flavor.missingDimensionStrategies
        if (missingStrategies.isNotEmpty()) {
            for ((dimension, value) in missingStrategies) {
                val dimensionMap = alternateMap.computeIfAbsent(dimension) { Maps.newHashMap() }
                dimensionMap[value.requested] = value.fallbacks
            }
        }
    }

    /** Configure artifact transforms that require variant-specific attribute information.  */
    fun <VariantBuilderT : VariantBuilder, VariantT : VariantCreationConfig>
            configureVariantTransforms(
        variants: List<ComponentInfo<VariantBuilderT, VariantT>>,
        nestedComponents: List<ComponentCreationConfig>,
        bootClasspathConfig: BootClasspathConfig
    ): DependencyConfigurator {
        val allComponents: List<ComponentCreationConfig> =
            variants.map { it.variant }.plus(nestedComponents)

        val dependencies = project.dependencies
        val projectOptions = projectServices.projectOptions

        for (component in allComponents) {
//            registerAsmTransformForComponent(
//                project.name,
//                dependencies,
//                component
//            )
//
//            registerRecalculateStackFramesTransformForComponent(
//                project.name,
//                dependencies,
//                component
//            )
        }
        if (allComponents.isNotEmpty()) {
            val bootClasspath = project.files(bootClasspathConfig.bootClasspath)
            if (projectOptions[BooleanOption.ENABLE_DEXING_ARTIFACT_TRANSFORM]) {
                if (allComponents.isNotEmpty()) {
                    val services = allComponents.first().services

                    for (artifactConfiguration in getDexingArtifactConfigurations(
                        allComponents
                    )) {
                        artifactConfiguration.registerTransform(
                            project.name,
                            dependencies,
                            bootClasspath,
                            getDesugarLibConfig(services),
                            SyncOptions.getErrorFormatMode(projectOptions),
                        )
                    }
                }
            }
        }
        if (projectOptions[BooleanOption.ENABLE_PROGUARD_RULES_EXTRACTION]
            && allComponents.any { it is ConsumableCreationConfig && it.minifiedEnabled }) {
            dependencies.registerTransform(
                FilterShrinkerRulesTransform::class.java
            ) { reg: TransformSpec<FilterShrinkerRulesTransform.Parameters> ->
                reg.from
                    .attribute(
                        ArtifactAttributes.ARTIFACT_FORMAT,
                        AndroidArtifacts.ArtifactType.UNFILTERED_PROGUARD_RULES.type
                    )
                reg.to
                    .attribute(
                        ArtifactAttributes.ARTIFACT_FORMAT,
                        AndroidArtifacts.ArtifactType.FILTERED_PROGUARD_RULES.type
                    )
                reg.parameters { params: FilterShrinkerRulesTransform.Parameters ->
                    params.shrinker.set(VersionedCodeShrinker.create())
                    params.projectName.set(project.name)
                }
            }
        }

        if (projectOptions[BooleanOption.ENABLE_DUPLICATE_CLASSES_CHECK]) {
            registerTransform(
                EnumerateClassesTransform::class.java,
                AndroidArtifacts.ArtifactType.CLASSES_JAR,
                AndroidArtifacts.ArtifactType.ENUMERATED_RUNTIME_CLASSES
            )
        }

        registerDexingOutputSplitTransform(dependencies)

        return this
    }

    companion object {
        @JvmStatic
        fun addFlavorStrategy(
            schema: AttributesSchema,
            dimension: String,
            alternateMap: Map<String, List<String>>
        ) {
            val attr = ProductFlavorAttr.of(dimension)
            val flavorStrategy = schema.attribute(attr)
            flavorStrategy
                .compatibilityRules
                .add(AlternateCompatibilityRule.ProductFlavorRule::class.java) {
                    it.setParams(alternateMap)
                }
            flavorStrategy
                .disambiguationRules
                .add(AlternateDisambiguationRule.ProductFlavorRule::class.java) {
                    it.setParams(alternateMap)
                }
        }
    }
}
