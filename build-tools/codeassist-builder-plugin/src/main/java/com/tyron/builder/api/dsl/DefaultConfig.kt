package com.tyron.builder.api.dsl

/**
 * Specifies defaults for variant properties that the Android plugin applies to all build variants.
 *
 * You can override any `defaultConfig` property when
 * [configuring product flavors](https://developer.android.com/studio/build/build-variants.html#product-flavors).
 * See [ProductFlavor].
 *
 * Each plugin has its own interface that extends this one, see [ApplicationDefaultConfig],
 * [LibraryDefaultConfig], [DynamicFeatureDefaultConfig] and [TestDefaultConfig].
 */
interface DefaultConfig :
    BaseFlavor {
}