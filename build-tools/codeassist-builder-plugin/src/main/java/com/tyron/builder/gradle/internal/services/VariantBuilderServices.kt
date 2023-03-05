package com.tyron.builder.gradle.internal.services

/**
 * Services for the [com.android.build.api.variant.VariantBuilder] API objects.
 *
 * This contains whatever is needed by all the variant objects.
 *
 * This is meant to be used only by the variant api objects. Other stages of the plugin
 * will use different services objects.
 */
interface VariantBuilderServices:
    BaseServices {

    /**
     * Instantiate a [Value] object that wraps a basic type. This offers read/write locking as
     * needed by the lifecycle of Variant API objects:
     * - during API actions, [Value.get] is disabled
     * - afterward, [Value.set] is disabled and [Value.get] is turned on
     *   (so that AGP can read the value).
     */
    fun <T> valueOf(value: T): Value<T>

    /**
     * Locks the [Value] object.
     *
     * This disables [Value.set] while enabling [Value.get]
     */
    fun lockValues()

    val isPostVariantApi: Boolean

    /**
     * A value wrapper
     */
    interface Value<T> {
        fun set(value: T)
        fun get(): T
    }
}