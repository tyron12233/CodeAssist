package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.AaptOptions
import java.io.Serializable

/**
 * Implementation of [AaptOptions] for serialization via the Tooling API
 */
data class AaptOptionsImpl(
    override val namespacing: AaptOptions.Namespacing
) : AaptOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
