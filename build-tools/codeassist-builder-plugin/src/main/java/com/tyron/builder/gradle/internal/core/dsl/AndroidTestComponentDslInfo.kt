package com.tyron.builder.gradle.internal.core.dsl

import org.gradle.api.provider.Provider

/**
 * Represents the dsl info for an android test component, initialized from the DSL object model
 * (extension, default config, build type, flavors)
 *
 * This class allows querying for the values set via the DSL model.
 *
 * Use [DslInfoBuilder] to instantiate.
 *
 * @see [com.tyron.builder.gradle.internal.component.AndroidTestCreationConfig]
 */
interface AndroidTestComponentDslInfo
    : TestComponentDslInfo, ApkProducingComponentDslInfo, InstrumentedTestComponentDslInfo {
    /**
     * The namespace for the R class for AndroidTest.
     *
     * This is a special case due to legacy reasons.
     *
     *  TODO(b/176931684) Remove and use [namespace] after we stop supporting using applicationId
     *   to namespace the test component R class.
     */
    val namespaceForR: Provider<String>
}
