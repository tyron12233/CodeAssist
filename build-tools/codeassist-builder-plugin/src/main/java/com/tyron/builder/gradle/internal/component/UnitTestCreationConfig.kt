package com.tyron.builder.gradle.internal.component

interface UnitTestCreationConfig: TestComponentCreationConfig {

    val isUnitTestCoverageEnabled: Boolean
}