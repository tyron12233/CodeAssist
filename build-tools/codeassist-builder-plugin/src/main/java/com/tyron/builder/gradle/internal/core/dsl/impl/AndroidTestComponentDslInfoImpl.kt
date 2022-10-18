package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.impl.MutableAndroidVersion
import com.tyron.builder.core.ComponentType
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.DynamicFeatureVariantDslInfo
import com.tyron.builder.gradle.internal.core.dsl.TestedVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalTestedExtension
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.StringOption
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

internal class AndroidTestComponentDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    override val mainVariantDslInfo: TestedVariantDslInfo,
    /**
     *  Whether there are inconsistent applicationId in the test.
     *  This trigger a mode where the namespaceForR just returns the same as namespace.
     */
    private val inconsistentTestAppId: Boolean,
    private val signingConfigOverride: SigningConfig?,
    extension: InternalTestedExtension<*, *, *, *>
) : ConsumableComponentDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    services,
    buildDirectory,
    extension
), AndroidTestComponentDslInfo {
    override val namespace: Provider<String> by lazy {
        getTestComponentNamespace(extension, services, dataProvider)
    }

    override val applicationId: Property<String> =
        services.newPropertyBackingDeprecatedApi(
            String::class.java,
            initTestApplicationId(productFlavorList, defaultConfig, services)
        )

    override val minSdkVersion: MutableAndroidVersion
        get() = mainVariantDslInfo.minSdkVersion
    override val maxSdkVersion: Int?
        get() = mainVariantDslInfo.maxSdkVersion
    override val targetSdkVersion: MutableAndroidVersion?
        get() = mainVariantDslInfo.targetSdkVersion

    override val namespaceForR: Provider<String> by lazy {
        if (inconsistentTestAppId) {
            namespace
        } else {
            // For legacy reason, this code does the following:
            // - If testNamespace is set, use it.
            // - If android.namespace is set, use it with .test added
            // - else, use the variant applicationId.
            // TODO(b/176931684) Remove this and use [namespace] directly everywhere.
            extension.testNamespace?.let { services.provider { it } }
                ?: extension.namespace?.let { services.provider { it }.map { "$it.test" } }
                ?: applicationId
        }
    }

    override val isAndroidTestCoverageEnabled: Boolean
        get() =  false//instrumentedTestDelegate.isAndroidTestCoverageEnabled

    // TODO: Android Test doesn't have isDebuggable dsl in the build type, we should move to using
    //  the value from the tested type
    override val isDebuggable: Boolean
        get() = ProfilingMode.getProfilingModeType(
            services.projectOptions[StringOption.PROFILING_MODE]
        ).isDebuggable
            ?: (buildTypeObj as? ApplicationBuildType)?.isDebuggable
            ?: false

    override val signingConfig: SigningConfig? by lazy {
        if (mainVariantDslInfo is DynamicFeatureVariantDslInfo) {
            null
        } else {
            getSigningConfig(
                buildTypeObj,
                mergedFlavor,
                signingConfigOverride,
                extension,
                services
            )
        }
    }

    override val isSigningReady: Boolean
        get() = signingConfig?.isSigningReady == true

//    private val instrumentedTestDelegate by lazy {
//        InstrumentedTestDslInfoImpl(
//            buildTypeObj,
//            productFlavorList,
//            defaultConfig,
//            dataProvider,
//            services,
//            mainVariantDslInfo.testInstrumentationRunnerArguments
//        )
//    }

    override fun getInstrumentationRunner(dexingType: DexingType): Provider<String> {
//        return instrumentedTestDelegate.getInstrumentationRunner(dexingType)
        return services.provider{""}
    }

    override val instrumentationRunnerArguments: Map<String, String>
        get() = emptyMap()//instrumentedTestDelegate.instrumentationRunnerArguments
    override val handleProfiling: Provider<Boolean>
        get() = services.provider{false}//instrumentedTestDelegate.handleProfiling
    override val functionalTest: Provider<Boolean>
        get() = services.provider{false}//instrumentedTestDelegate.functionalTest
    override val testLabel: Provider<String?>
        get() = services.provider {null}//instrumentedTestDelegate.testLabel
}
