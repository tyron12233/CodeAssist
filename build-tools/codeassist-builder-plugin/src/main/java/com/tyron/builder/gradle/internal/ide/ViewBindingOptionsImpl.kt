package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.ViewBindingOptions
import java.io.Serializable

/**
 * Implementation of ViewBindingOptions that is Serializable.
 *
 * <p>Should only be used for the model.
 */
data class ViewBindingOptionsImpl(private val enabled: Boolean) : ViewBindingOptions, Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    override fun isEnabled(): Boolean = enabled
}
