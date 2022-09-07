package com.tyron.builder.api.dsl

/**
 * Encapsulates all product flavors properties for library projects.
 *
 * Product flavors represent different versions of your project that you expect to co-exist on a
 * single device, the Google Play store, or repository. For example, you can configure 'demo' and
 * 'full' product flavors for your app, and each of those flavors can specify different features,
 * device requirements, resources, and application ID's--while sharing common source code and
 * resources. So, product flavors allow you to output different versions of your project by simply
 * changing only the components and settings that are different between them.
 *
 * Configuring product flavors is similar to
 * [configuring build types](https://developer.android.com/studio/build/build-variants.html#build-types):
 * add them to the `productFlavors` block of your project's `build.gradle` file
 * and configure the settings you want.
 *
 * Product flavors support the same properties as the [DefaultConfig] block—this is because
 * `defaultConfig` defines a [ProductFlavor] object that the plugin uses as the base configuration
 * for all other flavors.
 * Each flavor you configure can then override any of the default values in `defaultConfig`, such as
 * the [`applicationId`](https://d.android.com/studio/build/application-id.html).
 *
 * When using Android plugin 3.0.0 and higher,
 * *[each flavor must belong to a `dimension`][ProductFlavor.dimension]*.
 *
 * When you configure product flavors, the Android plugin automatically combines them with your
 * [BuildType] configurations to
 * [create build variants](https://developer.android.com/studio/build/build-variants.html).
 * If the plugin creates certain build variants that you don't want, you can
 * [filter variants using `android.variantFilter`](https://developer.android.com/studio/build/build-variants.html#filter-variants).
 */
interface LibraryProductFlavor :
    LibraryBaseFlavor,
    ProductFlavor {
    /** Whether this product flavor should be selected in Studio by default  */
    var isDefault: Boolean
}
