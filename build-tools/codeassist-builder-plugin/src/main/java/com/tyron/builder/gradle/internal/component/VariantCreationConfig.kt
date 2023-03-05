package com.tyron.builder.gradle.internal.component

import org.gradle.api.provider.MapProperty
import java.io.File

interface VariantCreationConfig: ConsumableCreationConfig {
    val consumerProguardFiles: List<File>

    val maxSdkVersion: Int?

    val experimentalProperties: MapProperty<String, Any>

    val externalNativeExperimentalProperties: Map<String, Any>

//    val ndkConfig: MergedNdkConfig

    val isJniDebuggable: Boolean

//    val testComponents: MutableMap<ComponentType, TestComponentCreationConfig>

    val nestedComponents: List<ComponentCreationConfig>

//    var unitTest: UnitTestImpl?
//
//    var testFixturesComponent: TestFixturesCreationConfig?
}