package com.tyron.builder.internal.aapt

import java.io.Serializable

/**
 * Represents the AAPT options used for linking.
 *
 * Serializable for use in worker actions.
 *
 * Not suitable for use as a task input, see `LinkingTaskInputAaptOptions`
 */
data class AaptOptions @JvmOverloads constructor(
        val noCompress: Collection<String>? = null,
        val additionalParameters: List<String>? = null
) : Serializable