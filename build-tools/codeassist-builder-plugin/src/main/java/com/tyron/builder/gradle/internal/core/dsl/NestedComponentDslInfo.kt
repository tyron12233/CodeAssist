package com.tyron.builder.gradle.internal.core.dsl

interface NestedComponentDslInfo: ComponentDslInfo {

    val mainVariantDslInfo: TestedVariantDslInfo
}