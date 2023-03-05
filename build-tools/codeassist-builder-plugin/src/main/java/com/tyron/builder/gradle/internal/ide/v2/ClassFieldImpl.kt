package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.ClassField
import java.io.Serializable

/**
 * Implementation of [ClassField] for serialization via the Tooling API.
 */
data class ClassFieldImpl(
    override val type: String,
    override val name: String,
    override val value: String,
    override val documentation: String,
    override val annotations: Set<String> = setOf()
) : ClassField, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
