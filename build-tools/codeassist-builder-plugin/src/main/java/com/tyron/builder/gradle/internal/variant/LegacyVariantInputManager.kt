package com.tyron.builder.gradle.internal.variant

import com.android.SdkConstants
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.internal.DefaultConfigData
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.dsl.*
import com.tyron.builder.gradle.internal.packaging.getDefaultDebugKeystoreLocation
import com.tyron.builder.gradle.internal.services.AndroidLocationsBuildService
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.services.getBuildService
import org.gradle.api.NamedDomainObjectContainer

/**
 * Implementation of [AbstractVariantInputManager] with the legacy types for build types, flavors,
 * etc...
 */
class LegacyVariantInputManager(
    dslServices: DslServices,
    componentType: ComponentType,
    sourceSetManager: SourceSetManager
) : AbstractVariantInputManager<DefaultConfig, BuildType, ProductFlavor, SigningConfig>(
    dslServices,
    componentType,
    sourceSetManager
) {

    override val buildTypeContainer: NamedDomainObjectContainer<BuildType> =
        dslServices.domainObjectContainer(
            BuildType::class.java, BuildTypeFactory(dslServices, componentType)
        )
    override val productFlavorContainer: NamedDomainObjectContainer<ProductFlavor> =
        dslServices.domainObjectContainer(
            ProductFlavor::class.java,
            ProductFlavorFactory(dslServices)
        )
    override val signingConfigContainer: NamedDomainObjectContainer<SigningConfig> =
        dslServices.domainObjectContainer(
            SigningConfig::class.java,
            SigningConfigFactory(
                dslServices,
                getBuildService(
                    dslServices.buildServiceRegistry,
                    AndroidLocationsBuildService::class.java
                ).get().getDefaultDebugKeystoreLocation()
            )
        )

    override val defaultConfig: DefaultConfig = dslServices.newDecoratedInstance(
        DefaultConfig::class.java,
        BuilderConstants.MAIN,
        dslServices
    )
    override val defaultConfigData: DefaultConfigData<DefaultConfig>

    init {
        var testFixturesSourceSet: DefaultAndroidSourceSet? = null
        var androidTestSourceSet: DefaultAndroidSourceSet? = null
        var unitTestSourceSet: DefaultAndroidSourceSet? = null
        if (componentType.hasTestComponents) {
            androidTestSourceSet =
                sourceSetManager.setUpTestSourceSet(ComponentType.ANDROID_TEST_PREFIX) as DefaultAndroidSourceSet
            unitTestSourceSet =
                sourceSetManager.setUpTestSourceSet(ComponentType.UNIT_TEST_PREFIX) as DefaultAndroidSourceSet
            testFixturesSourceSet =
                sourceSetManager.setUpSourceSet(ComponentType.TEST_FIXTURES_PREFIX)
                        as DefaultAndroidSourceSet
        }

        defaultConfigData = DefaultConfigData(
            defaultConfig = defaultConfig,
            sourceSet = sourceSetManager.setUpSourceSet(SdkConstants.FD_MAIN) as DefaultAndroidSourceSet,
            testFixturesSourceSet = testFixturesSourceSet,
            androidTestSourceSet = androidTestSourceSet,
            unitTestSourceSet = unitTestSourceSet
        )

        // map the whenObjectAdded/whenObjectRemoved callbacks on the containers.

        signingConfigContainer.whenObjectAdded(this::addSigningConfig)
        signingConfigContainer.whenObjectRemoved {
            throw UnsupportedOperationException("Removing signingConfigs is not supported.")
        }

        buildTypeContainer.whenObjectAdded(this::addBuildType)
        buildTypeContainer.whenObjectRemoved {
            throw UnsupportedOperationException("Removing build types is not supported.")
        }

        productFlavorContainer.whenObjectAdded(this::addProductFlavor);
        productFlavorContainer.whenObjectRemoved {
            throw UnsupportedOperationException("Removing product flavors is not supported.")
        }

    }
}