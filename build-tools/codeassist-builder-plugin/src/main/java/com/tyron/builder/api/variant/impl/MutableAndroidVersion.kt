package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.AndroidVersion
import org.gradle.api.tasks.Internal

/**
 * Mutable version of [AndroidVersion].
 *
 * This also allows [apiLevel] to be nullable since our API allows this.
 */
data class MutableAndroidVersion @JvmOverloads constructor(
    @get:Internal internal var api: Int?,
    override var codename: String? = null
): AndroidVersion {

    override val apiLevel: Int
        // this should be an internal issue only has we sanitize before exposing to the variant
        // api or the internal tasks first.
        get() = api ?: throw RuntimeException("Calling apiLevel on null API. Probably need to call sanitize() first")

    /**
     * Sanitize the version in case both api and codename are null (which should only happen
     * if they are both set to null via the variant builder api).
     */
    internal fun sanitize(): AndroidVersion = if (api == null && codename == null) {
        AndroidVersionImpl(1)
    } else this
}