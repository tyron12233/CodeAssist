package com.tyron.builder.api.variant

/**
 * Interface that mark the potential existence of android tests associated with a variant.
 */
interface HasAndroidTestBuilder {

    /**
     * Set to `true` if the variant's has any android tests, false otherwise.
     * Value is [Boolean#True] by default.
     */
    @Deprecated("replaced with enableAndroidTest", ReplaceWith("enableAndroidTest"))
    var androidTestEnabled: Boolean

    /**
     * Set to `true` if the variant's has any android tests, false otherwise.
     * Value is [Boolean#True] by default.
     */
    var enableAndroidTest: Boolean
}
