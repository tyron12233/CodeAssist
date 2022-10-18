package com.tyron.builder.api.extension.impl

import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.VariantSelector
import java.util.regex.Pattern

open class VariantSelectorImpl : VariantSelector {

    override fun all(): VariantSelectorImpl = this

    // By default the selector applies to all variants.
    internal open fun appliesTo(variant: ComponentIdentity): Boolean {
        return true;
    }

    override fun withBuildType(buildType: String): VariantSelectorImpl {
        return object: VariantSelectorImpl() {
            override fun appliesTo(variant: ComponentIdentity): Boolean {
                return buildType == variant.buildType && this@VariantSelectorImpl.appliesTo(variant)
            }
        }
    }

    override fun withFlavor(flavorToDimension: Pair<String, String>): VariantSelectorImpl {
        return object: VariantSelectorImpl() {
            override fun appliesTo(variant: ComponentIdentity): Boolean {
                return variant.productFlavors.contains(flavorToDimension) && this@VariantSelectorImpl.appliesTo(variant)
            }
        }
    }

    override fun withName(pattern: Pattern): VariantSelectorImpl {
        return object : VariantSelectorImpl() {
            override fun appliesTo(variant: ComponentIdentity): Boolean {
                return pattern.matcher(variant.name).matches() && this@VariantSelectorImpl.appliesTo(variant)
            }
        }
    }

    override fun withName(name: String): VariantSelectorImpl {
        return object : VariantSelectorImpl() {
            override fun appliesTo(variant: ComponentIdentity): Boolean {
                return variant.name == name && this@VariantSelectorImpl.appliesTo(variant)
            }
        }
    }
}