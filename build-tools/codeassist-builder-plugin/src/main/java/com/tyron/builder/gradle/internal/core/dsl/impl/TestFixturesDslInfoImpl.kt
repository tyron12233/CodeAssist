package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.impl.MutableAndroidVersion
import com.tyron.builder.gradle.internal.core.dsl.PublishableComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.TestFixturesComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.TestedVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.internal.testFixtures.testFixturesFeatureName
import com.tyron.builder.core.ComponentType
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

internal class TestFixturesDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    override val mainVariantDslInfo: TestedVariantDslInfo,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    extension: CommonExtension<*, *, *, *>
) : ComponentDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    services,
    buildDirectory,
    extension
), TestFixturesComponentDslInfo {
    override val testFixturesAndroidResourcesEnabled: Boolean
        get() = mainVariantDslInfo.testFixtures.androidResources

    // for test fixtures, these values are always derived from the main variant

    override val namespace: Provider<String> =
        mainVariantDslInfo.namespace.map { "$it.$testFixturesFeatureName" }
    override val applicationId: Property<String> = services.newPropertyBackingDeprecatedApi(
        String::class.java,
        namespace.map { "$it${computeApplicationIdSuffix()}" }
    )
    override val minSdkVersion: MutableAndroidVersion
        get() = mainVariantDslInfo.minSdkVersion
    override val maxSdkVersion: Int?
        get() = mainVariantDslInfo.maxSdkVersion
    override val targetSdkVersion: MutableAndroidVersion?
        get() = mainVariantDslInfo.targetSdkVersion

    override val publishInfo: VariantPublishingInfo
        get() = (mainVariantDslInfo as PublishableComponentDslInfo).publishInfo
}
