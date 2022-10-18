package com.tyron.builder.gradle.internal.core.dsl

import com.tyron.builder.api.dsl.Lint
import com.tyron.builder.api.dsl.PackagingOptions
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty

/**
 * Contains the final dsl info computed from the DSL object model (extension, default config,
 * build type, flavors) that are needed by main variants.
 */
interface VariantDslInfo: ComponentDslInfo, ConsumableComponentDslInfo {

//    val nativeBuildSystem: NativeBuiltType?

//    val ndkConfig: MergedNdkConfig

//    val externalNativeBuildOptions: CoreExternalNativeBuildOptions

    /**
     * Returns the ABI filters associated with the artifact, or empty set if there are no filters.
     *
     * If the list contains values, then the artifact only contains these ABIs and excludes
     * others.
     */
    val supportedAbis: Set<String>

    fun getProguardFiles(into: ListProperty<RegularFile>)

    val isJniDebuggable: Boolean

    val lintOptions: Lint

    val packaging: PackagingOptions

    val experimentalProperties: Map<String, Any>

    val externalNativeExperimentalProperties: Map<String, Any>
}