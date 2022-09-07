package com.tyron.builder.api.variant

import org.gradle.api.provider.Property

/**
 * Defines a variant output.
 */
interface VariantOutput: VariantOutputConfiguration {

    /**
     * Returns a modifiable [Property] representing the variant output version code.
     *
     * This will be initialized with the variant's merged flavor value or read from the manifest
     * file if unset.
     */
    val versionCode: Property<Int?>

    /**
     * Returns a modifiable [Property] representing the variant output version name.
     *
     * This will be initialized with the variant's merged flavor value, or it will be read from the
     * manifest source file if it's not set via the DSL, or it will be null if it's also not set in
     * the manifest.
     */
    val versionName: Property<String?>

    /**
     * Returns a modifiable [Property] to enable or disable the production of this [VariantOutput]
     *
     * @return a [Property] to enable or disable this output.
     */
    val enabled: Property<Boolean>

    /**
     * Returns a modifiable [Property] to enable or disable the production of this [VariantOutput]
     *
     * @return a [Property] to enable or disable this output.
     */
    @Deprecated("Replaced by enable", ReplaceWith("enable"))
    val enable: Property<Boolean>
}
