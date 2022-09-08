package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.ViewBindingOptions
import java.io.Serializable

/**
 * Implementation of [ViewBindingOptions] for serialization via the Tooling API.
 */
data class ViewBindingOptionsImpl(
    override val isEnabled: Boolean
) : ViewBindingOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}