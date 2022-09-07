package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Interface for component that can minify code
 */
@Incubating
interface CanMinifyCode {

    /**
     * Specifies whether code will be minified.
     * At this point the value is final. You can change it via
     * [AndroidComponentsExtension.beforeVariants] and
     * [CanMinifyCodeBuilder.isMinifyEnabled]
     */
    val isMinifyEnabled: Boolean
}