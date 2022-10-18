package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.gradle.internal.BuildTypeData
import com.tyron.builder.gradle.internal.ProductFlavorData
import com.tyron.builder.gradle.internal.api.DefaultAndroidSourceSet
import com.tyron.builder.gradle.internal.dependency.SourceSetManager
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.core.ComponentType
import com.tyron.builder.core.ComponentTypeImpl
import com.android.utils.appendCapitalized

/**
 * Abstract Class responsible for handling the DSL containers of flavors/build types and processing
 * them so that they can be consumed by [com.tyron.builder.gradle.internal.VariantManager]
 *
 * This is has type parameters to handle different implementations of flavors and build types,
 * whether this is legacy or new per-plugin-type versions.
 *
 * This instantiate the containers and makes them available to the extension classes via
 * implementing [DslContainerProvider].
 *
 * This setups container call back to process new values and validates them.
 *
 * Then, this exposes the results by implementing [VariantInputModel].
 *
 * This class does everything, except actually instantiating the containers as this needs
 * to be done per flavor/build-type type. This is handled by children classes for each use case.
 */
abstract class AbstractVariantInputManager<
        DefaultConfigT : com.tyron.builder.api.dsl.DefaultConfig,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor,
        SigningConfigT : com.tyron.builder.api.dsl.ApkSigningConfig>(
    private val dslServices: DslServices,
    private val componentType: ComponentType,
    override val sourceSetManager: SourceSetManager
        ) : VariantInputModel<DefaultConfigT, BuildTypeT, ProductFlavorT, SigningConfigT>,
    DslContainerProvider<DefaultConfigT, BuildTypeT, ProductFlavorT, SigningConfigT> {

    override val buildTypes = mutableMapOf<String, BuildTypeData<BuildTypeT>>()
    override val productFlavors = mutableMapOf<String, ProductFlavorData<ProductFlavorT>>()
    override val signingConfigs = mutableMapOf<String, SigningConfigT>()

    protected fun addSigningConfig(signingConfig: SigningConfigT) {
        signingConfigs[signingConfig.name] = signingConfig
    }

    /**
     * Adds new BuildType, creating a BuildTypeData, and the associated source set, and adding it to
     * the map.
     *
     * @param buildType the build type.
     */
    protected fun addBuildType(buildType: BuildTypeT) {
        val name = buildType.name
        checkName(name, "BuildType")
        if (productFlavors.containsKey(name)) {
            throw RuntimeException("BuildType names cannot collide with ProductFlavor names")
        }

        // FIXME update when we have the newer interfaces for BuildTypes.
        if (buildType is BuildType) {
            if (componentType.isDynamicFeature) {
                // initialize it without the signingConfig for dynamic-features.
                buildType.init()
            } else {
                buildType.init(signingConfigContainer.findByName(BuilderConstants.DEBUG) as SigningConfig)
            }
        } else {
            throw RuntimeException("Unexpected instance of BuildTypeT")
        }

        // always create the android Test source set even if this is not the buildType that is
        // tested. this is because we cannot delay creation without breaking compatibility.
        // So for now we create more than we need and we'll migrate to a better way later.
        // FIXME b/149489432
        val androidTestSourceSet = if (componentType.hasTestComponents) {
            sourceSetManager.setUpTestSourceSet(
                computeSourceSetName(
                    buildType.name, ComponentTypeImpl.ANDROID_TEST
                )
            ) as DefaultAndroidSourceSet
        } else null

        val unitTestSourceSet = if (componentType.hasTestComponents) {
            sourceSetManager.setUpTestSourceSet(
                computeSourceSetName(
                    buildType.name, ComponentTypeImpl.UNIT_TEST
                )
            ) as DefaultAndroidSourceSet
        } else null

        val testFixturesSourceSet =
            if (componentType.hasTestComponents) {
                sourceSetManager.setUpSourceSet(
                    computeSourceSetName(
                        buildType.name, ComponentTypeImpl.TEST_FIXTURES
                    )
                ) as DefaultAndroidSourceSet
            } else null

        buildTypes[name] = BuildTypeData(
            buildType = buildType,
            sourceSet = sourceSetManager.setUpSourceSet(buildType.name) as DefaultAndroidSourceSet,
            testFixturesSourceSet = testFixturesSourceSet,
            androidTestSourceSet = androidTestSourceSet,
            unitTestSourceSet = unitTestSourceSet
        )
    }

    /**
     * Adds a new ProductFlavor, creating a ProductFlavorData and associated source sets, and adding
     * it to the map.
     *
     * @param productFlavor the product flavor
     */
    protected fun addProductFlavor(productFlavor: ProductFlavorT) {
        val name = productFlavor.name
        checkName(name, "ProductFlavor")
        if (buildTypes.containsKey(name)) {
            throw RuntimeException("ProductFlavor names cannot collide with BuildType names")
        }
        val mainSourceSet =
            sourceSetManager.setUpSourceSet(productFlavor.name) as DefaultAndroidSourceSet
        var testFixturesSourceSet: DefaultAndroidSourceSet? = null
        var androidTestSourceSet: DefaultAndroidSourceSet? = null
        var unitTestSourceSet: DefaultAndroidSourceSet? = null
        if (componentType.hasTestComponents) {
            androidTestSourceSet = sourceSetManager.setUpTestSourceSet(
                computeSourceSetName(
                    productFlavor.name, ComponentTypeImpl.ANDROID_TEST
                )
            ) as DefaultAndroidSourceSet
            unitTestSourceSet = sourceSetManager.setUpTestSourceSet(
                computeSourceSetName(
                    productFlavor.name, ComponentTypeImpl.UNIT_TEST
                )
            ) as DefaultAndroidSourceSet
            testFixturesSourceSet = sourceSetManager.setUpSourceSet(
                computeSourceSetName(
                    productFlavor.name, ComponentTypeImpl.TEST_FIXTURES
                )
            ) as DefaultAndroidSourceSet
        }
        val productFlavorData =
            ProductFlavorData(
                productFlavor = productFlavor,
                sourceSet = mainSourceSet,
                testFixturesSourceSet = testFixturesSourceSet,
                androidTestSourceSet = androidTestSourceSet,
                unitTestSourceSet = unitTestSourceSet
            )
        productFlavors[productFlavor.name] = productFlavorData
    }

    companion object {
        /**
         * Turns a string into a valid source set name for the given [ComponentType], e.g.
         * "fooBarUnitTest" becomes "testFooBar".
         */
        private fun computeSourceSetName(
            baseName: String,
            componentType: ComponentType
        ): String {
            var name = baseName
            if (name.endsWith(componentType.suffix)) {
                name = name.substring(0, name.length - componentType.suffix.length)
            }
            if (!componentType.prefix.isEmpty()) {
                name = componentType.prefix.appendCapitalized(name)
            }
            return name
        }

        private fun checkName(name: String, displayName: String) {
            checkPrefix(
                name,
                displayName,
                ComponentType.ANDROID_TEST_PREFIX
            )
            checkPrefix(
                name,
                displayName,
                ComponentType.UNIT_TEST_PREFIX
            )
            if (BuilderConstants.LINT == name) {
                throw RuntimeException(
                    String.format(
                        "%1\$s names cannot be %2\$s",
                        displayName,
                        BuilderConstants.LINT
                    )
                )
            }
        }

        private fun checkPrefix(
            name: String,
            displayName: String,
            prefix: String
        ) {
            if (name.startsWith(prefix)) {
                throw RuntimeException(
                    String.format(
                        "%1\$s names cannot start with '%2\$s'",
                        displayName,
                        prefix
                    )
                )
            }
        }
    }
}
