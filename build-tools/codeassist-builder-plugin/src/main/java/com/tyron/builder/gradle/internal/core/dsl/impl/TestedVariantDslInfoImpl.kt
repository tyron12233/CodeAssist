package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.dsl.TestFixtures
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.core.dsl.TestedVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalTestedExtension
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.core.ComponentType
import org.gradle.api.file.DirectoryProperty

internal abstract class TestedVariantDslInfoImpl internal constructor(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    extension: InternalTestedExtension<*, *, *, *>
) : VariantDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    dataProvider,
    services,
    buildDirectory,
    extension
), TestedVariantDslInfo {

    override val testFixtures: TestFixtures = extension.testFixtures

    override val testInstrumentationRunnerArguments: Map<String, String>
        get() = mergedFlavor.testInstrumentationRunnerArguments
}
