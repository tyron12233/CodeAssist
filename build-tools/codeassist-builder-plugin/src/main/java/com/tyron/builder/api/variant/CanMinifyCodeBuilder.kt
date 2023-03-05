package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Interface for component builder that can minify code
 */
@Incubating
interface CanMinifyCodeBuilder {

    /**
     * Specifies whether code will be minified
     */
    var isMinifyEnabled: Boolean
}