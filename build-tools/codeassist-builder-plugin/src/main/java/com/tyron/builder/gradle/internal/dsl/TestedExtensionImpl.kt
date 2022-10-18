package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.BuildFeatures
import com.tyron.builder.api.dsl.DefaultConfig
import com.tyron.builder.api.dsl.TestFixtures
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.options.BooleanOption
import org.gradle.api.Action

/** Internal implementation of the 'new' DSL interface */
abstract class TestedExtensionImpl<
        BuildFeaturesT : BuildFeatures,
        BuildTypeT : com.tyron.builder.api.dsl.BuildType,
        DefaultConfigT : DefaultConfig,
        ProductFlavorT : com.tyron.builder.api.dsl.ProductFlavor>(
    dslServices: DslServices,
    dslContainers: DslContainerProvider<DefaultConfigT, BuildTypeT, ProductFlavorT, SigningConfig>
        ) : CommonExtensionImpl<
        BuildFeaturesT,
        BuildTypeT,
        DefaultConfigT,
        ProductFlavorT>(
    dslServices,
    dslContainers
), com.tyron.builder.api.dsl.TestedExtension {
    override var testBuildType = "debug"
    override var testNamespace: String? = null

    override val testFixtures: TestFixtures =
        dslServices.newInstance(
            TestFixturesImpl::class.java,
            dslServices.projectOptions[BooleanOption.ENABLE_TEST_FIXTURES]
        )

    override fun testFixtures(action: TestFixtures.() -> Unit) {
        action.invoke(testFixtures)
    }

    fun testFixtures(action: Action<TestFixtures>) {
        action.execute(testFixtures)
    }
}