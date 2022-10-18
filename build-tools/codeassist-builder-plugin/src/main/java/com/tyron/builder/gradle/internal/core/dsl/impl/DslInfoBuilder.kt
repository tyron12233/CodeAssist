package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.component.impl.ComponentIdentityImpl
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.core.ComponentType
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.core.VariantSources
import com.tyron.builder.gradle.internal.core.dsl.*
import com.tyron.builder.gradle.internal.core.dsl.impl.DslInfoBuilder.Companion.getBuilder
import com.tyron.builder.gradle.internal.dsl.*
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.utils.createPublishingInfoForApp
import com.tyron.builder.gradle.internal.utils.createPublishingInfoForLibrary
import com.tyron.builder.gradle.internal.utils.toImmutableList
import com.tyron.builder.gradle.internal.variant.DimensionCombination
import com.tyron.builder.model.SourceProvider
import org.gradle.api.file.DirectoryProperty

/** Builder for dsl info classes.
 *
 * This allows setting all temporary items on the builder before actually
 * instantiating the configuration, in order to keep it immutable.
 *
 * Use [getBuilder] as an entry point.
 */
class DslInfoBuilder<CommonExtensionT: CommonExtension<*, *, *, *>, DslInfoT: ComponentDslInfo> private constructor(
    private val dimensionCombination: DimensionCombination,
    val componentType: ComponentType,
    private val defaultConfig: DefaultConfig,
    private val defaultSourceProvider: SourceProvider,
    private val buildType: BuildType,
    private val buildTypeSourceProvider: SourceProvider?,
    private val signingConfigOverride: SigningConfig?,
    private val manifestDataProvider: ManifestDataProvider,
    private val variantServices: VariantServices,
    private val extension: CommonExtensionT,
    private val buildDirectory: DirectoryProperty,
) {

    companion object {
        /**
         * Returns a new builder
         */
        @JvmStatic
        fun <CommonExtensionT: CommonExtension<*, *, *, *>, DslInfoT: ComponentDslInfo> getBuilder(
            dimensionCombination: DimensionCombination,
            componentType: ComponentType,
            defaultConfig: DefaultConfig,
            defaultSourceSet: SourceProvider,
            buildType: BuildType,
            buildTypeSourceSet: SourceProvider?,
            signingConfigOverride: SigningConfig?,
            manifestDataProvider: ManifestDataProvider,
            variantServices: VariantServices,
            extension: CommonExtensionT,
            buildDirectory: DirectoryProperty,
            dslServices: DslServices
        ): DslInfoBuilder<CommonExtensionT, DslInfoT> {
            return DslInfoBuilder(
                dimensionCombination,
                componentType,
                defaultConfig,
                defaultSourceSet,
                buildType,
                buildTypeSourceSet,
                signingConfigOverride?.let { signingOverride ->
                    dslServices.newDecoratedInstance(
                        SigningConfig::class.java,
                        signingOverride.name,
                        dslServices
                    ).also {
                        it.initWith(signingOverride)
                    }
                },
                manifestDataProvider,
                variantServices,
                extension,
                buildDirectory,
            )
        }
    }

    private lateinit var variantName: String
    private lateinit var multiFlavorName: String

    val name: String
        get() {
            if (!::variantName.isInitialized) {
                computeNames()
            }

            return variantName
        }

    val flavorName: String
        get() {
            if (!::multiFlavorName.isInitialized) {
                computeNames()
            }
            return multiFlavorName

        }

    private val flavors = mutableListOf<Pair<ProductFlavor, SourceProvider>>()

    var variantSourceProvider: DefaultAndroidSourceSet? = null
    var multiFlavorSourceProvider: DefaultAndroidSourceSet? = null
    var productionVariant: TestedVariantDslInfo? = null
    var inconsistentTestAppId: Boolean = false

    fun addProductFlavor(
        productFlavor: ProductFlavor,
        sourceProvider: SourceProvider
    ) {
        if (::variantName.isInitialized) {
            throw RuntimeException("call to getName() before calling all addProductFlavor")
        }
        flavors.add(Pair(productFlavor, sourceProvider))
    }

    private fun createComponentIdentity(): ComponentIdentity = ComponentIdentityImpl(
        name,
        flavorName,
        dimensionCombination.buildType,
        dimensionCombination.productFlavors
    )

    private fun createApplicationVariantDslInfo(): ApplicationVariantDslInfo {
        return ApplicationVariantDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            publishInfo = createPublishingInfoForApp(
                (extension as InternalApplicationExtension).publishing as ApplicationPublishingImpl,
                name,
                extension.dynamicFeatures.isNotEmpty(),
                variantServices.issueReporter
            ),
            signingConfigOverride = signingConfigOverride,
            extension = extension
        )
    }

    private fun createLibraryVariantDslInfo(): LibraryVariantDslInfo {
        return LibraryVariantDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            publishInfo = createPublishingInfoForLibrary(
                (extension as InternalLibraryExtension).publishing as LibraryPublishingImpl,
                name,
                buildType,
                flavors.map { it.first },
                extension.buildTypes,
                extension.productFlavors,
                variantServices.issueReporter
            ),
            extension = extension
        )
    }

    private fun createDynamicFeatureVariantDslInfo(): DynamicFeatureVariantDslInfo {
        return DynamicFeatureVariantDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            extension = extension as InternalDynamicFeatureExtension
        )
    }

    private fun createTestProjectVariantDslInfo(): TestProjectVariantDslInfo {
        return TestProjectVariantDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            signingConfigOverride = signingConfigOverride,
            extension = extension as InternalTestExtension
        )
    }

    private fun createTestFixturesComponentDslInfo(): TestFixturesComponentDslInfo {
        return TestFixturesDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            mainVariantDslInfo = productionVariant!!,
            services = variantServices,
            buildDirectory = buildDirectory,
            extension = extension
        )
    }

    private fun createUnitTestComponentDslInfo(): UnitTestComponentDslInfo {
        return UnitTestComponentDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            mainVariantDslInfo = productionVariant!!,
            extension = extension as InternalTestedExtension<*, *, *, *>
        )
    }

    private fun createAndroidTestComponentDslInfo(): AndroidTestComponentDslInfo {
        return AndroidTestComponentDslInfoImpl(
            componentIdentity = createComponentIdentity(),
            componentType = componentType,
            defaultConfig = defaultConfig,
            buildTypeObj = buildType,
            productFlavorList = flavors.map { it.first },
            dataProvider = manifestDataProvider,
            services = variantServices,
            buildDirectory = buildDirectory,
            mainVariantDslInfo = productionVariant!!,
            inconsistentTestAppId = inconsistentTestAppId,
            signingConfigOverride = signingConfigOverride,
            extension = extension as InternalTestedExtension<*, *, *, *>
        )
    }

    fun createDslInfo(): DslInfoT {
        return when (componentType) {
            ComponentTypeImpl.BASE_APK -> createApplicationVariantDslInfo()
            ComponentTypeImpl.LIBRARY -> createLibraryVariantDslInfo()
            ComponentTypeImpl.OPTIONAL_APK -> createDynamicFeatureVariantDslInfo()
            ComponentTypeImpl.TEST_APK -> createTestProjectVariantDslInfo()
            ComponentTypeImpl.TEST_FIXTURES -> createTestFixturesComponentDslInfo()
            ComponentTypeImpl.UNIT_TEST -> createUnitTestComponentDslInfo()
            ComponentTypeImpl.ANDROID_TEST -> createAndroidTestComponentDslInfo()
            else -> {
                throw RuntimeException("Unknown component type ${componentType.name}")
            }
        } as DslInfoT
    }

    fun createVariantSources(): VariantSources {
        return VariantSources(
            name,
            componentType,
            defaultSourceProvider,
            buildTypeSourceProvider,
            flavors.map { it.second }.toImmutableList(),
            multiFlavorSourceProvider,
            variantSourceProvider
        )
    }

    /**
     * computes the name for the variant and the multi-flavor combination
     */
    private fun computeNames() {
        variantName = computeName(dimensionCombination, componentType) {
            multiFlavorName = it
        }
    }
}
