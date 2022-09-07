package com.tyron.builder.api.variant

import org.gradle.api.Incubating

@Incubating
interface CodeTransparency {

    /**
     * Sets the [com.tyron.builder.api.dsl.SigningConfig] with information on how to retrieve
     * the signing configuration.
     */
    @Incubating
    fun setSigningConfig(signingConfig: com.tyron.builder.api.dsl.SigningConfig)
}