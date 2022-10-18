package com.tyron.builder.api.attributes

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

/**
 * Type of the attribute holding Android Gradle Plugin version.
 */
interface AgpVersionAttr : Named {
    companion object {
        @JvmField
        val ATTRIBUTE: Attribute<AgpVersionAttr> = Attribute.of(AgpVersionAttr::class.java)
    }
}