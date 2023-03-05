package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.core.dsl.ApkProducingComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.TestComponentDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalTestedExtension
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.errors.IssueReporter
import org.gradle.api.provider.Provider

internal fun TestComponentDslInfo.getTestComponentNamespace(
    extension: InternalTestedExtension<*, *, *, *>,
    services: VariantServices,
    dataProvider: ManifestDataProvider
): Provider<String> {
    return extension.testNamespace?.let {
            services.provider {
                if (extension.testNamespace == extension.namespace) {
                    services.issueReporter
                        .reportError(
                            IssueReporter.Type.GENERIC,
                            "namespace and testNamespace have the same value (\"$it\"), which is not allowed."
                        )
                }
                it
            }
        } ?: extension.namespace?.let { services.provider {"$it.test" } }
        ?: mainVariantDslInfo.namespace.flatMap { testedVariantNamespace ->
            dataProvider.manifestData.map { manifestData ->
                manifestData.packageName ?: "$testedVariantNamespace.test"
            }
        }
}

// Special case for test components and separate test sub-projects
internal fun ComponentDslInfo.initTestApplicationId(
    productFlavorList: List<ProductFlavor>,
    defaultConfig: DefaultConfig,
    services: VariantServices,
): Provider<String> {
    // get first non null testAppId from flavors/default config
    val testAppIdFromFlavors =
        productFlavorList.asSequence().map { it.testApplicationId }
            .firstOrNull { it != null }
            ?: defaultConfig.testApplicationId

    return if (testAppIdFromFlavors != null) {
        services.provider { testAppIdFromFlavors }
    } else if (this is TestComponentDslInfo) {
        this.mainVariantDslInfo.applicationId.map {
            "$it.test"
        }
    } else {
        namespace
    }
}

internal fun ApkProducingComponentDslInfo.getSigningConfig(
    buildTypeObj: BuildType,
    mergedFlavor: MergedFlavor,
    signingConfigOverride: SigningConfig?,
    extension: CommonExtension<*, *, *, *>,
    services: VariantServices
): SigningConfig? {
    val dslSigningConfig =
        (buildTypeObj as? ApplicationBuildType)?.signingConfig
            ?: mergedFlavor.signingConfig

    signingConfigOverride?.let {
        // use enableV1 and enableV2 from the DSL if the override values are null
        if (it.enableV1Signing == null) {
            it.enableV1Signing = dslSigningConfig?.enableV1Signing
        }
        if (it.enableV2Signing == null) {
            it.enableV2Signing = dslSigningConfig?.enableV2Signing
        }
        // use enableV3 and enableV4 from the DSL because they're not injectable
        it.enableV3Signing = dslSigningConfig?.enableV3Signing
        it.enableV4Signing = dslSigningConfig?.enableV4Signing
        return it
    }
    if (services.projectOptions[BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG] &&
        dslSigningConfig == null &&
        ((this as? ApplicationVariantDslInfo)?.isProfileable == true || isDebuggable)) {
        return extension.signingConfigs.findByName(BuilderConstants.DEBUG) as SigningConfig?
    }
    return dslSigningConfig as SigningConfig?
}
