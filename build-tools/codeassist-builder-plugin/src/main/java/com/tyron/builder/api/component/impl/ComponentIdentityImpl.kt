package com.tyron.builder.api.component.impl

import com.tyron.builder.api.variant.ComponentIdentity
import com.google.common.collect.ImmutableList

/**
 * Implementation of [ComponentIdentity] as a data class to provide [equals]/[hashCode]
 * and [toString].
 */
data class ComponentIdentityImpl(
    val variantName: String,
    override val flavorName: String? = null,
    override val buildType: String? = null,
    override val productFlavors: List<Pair<String, String>> = ImmutableList.of()
) : ComponentIdentity {
    override val name: String = variantName
}