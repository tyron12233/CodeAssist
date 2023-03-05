package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.VectorDrawablesOptions
import java.io.Serializable

data class VectorDrawableOptionsImpl(
    override val generatedDensities: Set<String>?,
    override val useSupportLibrary: Boolean?
) : VectorDrawablesOptions, Serializable {

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
