package com.tyron.builder.gradle.internal.component

import org.gradle.api.provider.Provider

/**
 * Internal interface for Android Test component
 */
interface AndroidTestCreationConfig:
    TestComponentCreationConfig,
    InstrumentedTestCreationConfig {
    /**
     * TODO(b/176931684) Remove this and use [namespace] instead after we stop supporting using
     *  applicationId to namespace the test component R class.
     */
    val namespaceForR: Provider<String>
}