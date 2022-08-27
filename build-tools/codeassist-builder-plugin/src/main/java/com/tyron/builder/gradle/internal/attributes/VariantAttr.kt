package com.tyron.builder.gradle.internal.attributes

import org.gradle.api.attributes.Attribute

/**
 * Type for the attribute holding the variant name information.
 *
 * The key should be [ATTRIBUTE].
 */
interface VariantAttr : org.gradle.api.Named {
    companion object {
        @JvmField
        val ATTRIBUTE: Attribute<VariantAttr> = Attribute.of(VariantAttr::class.java)
    }
}