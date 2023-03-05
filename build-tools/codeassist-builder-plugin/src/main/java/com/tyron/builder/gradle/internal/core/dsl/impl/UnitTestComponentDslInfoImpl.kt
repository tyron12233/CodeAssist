package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.impl.MutableAndroidVersion
import com.tyron.builder.gradle.internal.core.dsl.TestedVariantDslInfo
import com.tyron.builder.gradle.internal.core.dsl.UnitTestComponentDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalTestedExtension
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.core.ComponentType
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

internal class UnitTestComponentDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    override val mainVariantDslInfo: TestedVariantDslInfo,
    extension: InternalTestedExtension<*, *, *, *>
) : ComponentDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    services,
    buildDirectory,
    extension
), UnitTestComponentDslInfo {

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

    override val isUnitTestCoverageEnabled: Boolean
        get() = buildTypeObj.enableUnitTestCoverage || buildTypeObj.isTestCoverageEnabled
}
