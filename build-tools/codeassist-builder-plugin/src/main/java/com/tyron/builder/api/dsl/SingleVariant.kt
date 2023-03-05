package com.tyron.builder.api.dsl

/**
 * Single variant publishing options.
 */
interface SingleVariant : PublishingOptions {
    val variantName: String
}