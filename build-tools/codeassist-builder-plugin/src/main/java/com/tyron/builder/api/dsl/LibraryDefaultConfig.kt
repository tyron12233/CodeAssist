package com.tyron.builder.api.dsl

/**
 * Specifies defaults for properties that the Android library plugin applies to all build variants.
 *
 * You can override any `defaultConfig` property when
 * [configuring product flavors](https://developer.android.com/studio/build/build-variants.html#product-flavors).
 * See [LibraryProductFlavor].
 */
interface LibraryDefaultConfig :
    LibraryBaseFlavor,
    DefaultConfig {
}