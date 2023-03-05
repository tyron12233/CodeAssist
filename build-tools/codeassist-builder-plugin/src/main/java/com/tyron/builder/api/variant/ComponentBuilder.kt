package com.tyron.builder.api.variant

/**
 * Component object that contains properties that must be set during configuration time as it
 * changes the build flow for the variant.
 */
interface ComponentBuilder: ComponentIdentity {

    /**
     * Set to `true` if the variant is active and should be configured, false otherwise.
     */
    var enable: Boolean

    @Deprecated("Will be removed in 8.0")
    var enabled: Boolean
}