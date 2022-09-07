package com.tyron.builder.api.attributes

import com.tyron.builder.api.attributes.BuildTypeAttr.Companion.ATTRIBUTE
import org.gradle.api.attributes.Attribute

/**
 * Type for the attribute holding BuildType information.
 *
 * There should only be one build type attribute associated to each
 * [org.gradle.api.artifacts.Configuration] object. The key should be [ATTRIBUTE].
 */
interface BuildTypeAttr : org.gradle.api.Named {
    companion object {
        @JvmField
        val ATTRIBUTE: Attribute<BuildTypeAttr> = Attribute.of(BuildTypeAttr::class.java)
    }
}