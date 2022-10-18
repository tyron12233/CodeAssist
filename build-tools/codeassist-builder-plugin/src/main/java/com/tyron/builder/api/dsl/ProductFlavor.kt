package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.Named
import org.gradle.api.plugins.ExtensionAware

/**
 * Encapsulates all product flavors properties for this project.
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
interface
ProductFlavor : Named, BaseFlavor, ExtensionAware, HasInitWith<BaseFlavor> {

    /**
     * Specifies the flavor dimension that this product flavor belongs to.
     *
     *
     * When configuring product flavors with Android plugin 3.0.0 and higher, you must specify at
     * least one flavor dimension, using the `flavorDimensions` property, and then assign each
     * flavor to a dimension.
     * Otherwise, you will get the following build error:
     *
     * ```
     * Error:All flavors must now belong to a named flavor dimension.
     * The flavor 'flavor_name' is not assigned to a flavor dimension.
     * ```
     *
     *
     * By default, when you specify only one dimension, all flavors you configure automatically
     * belong to that dimension. If you specify more than one dimension, you need to manually assign
     * each flavor to a dimension, as shown in the sample below:
     *
     * ```
     * android {
     *     ...
     *     // Specifies the flavor dimensions you want to use. The order in which you
     *     // list each dimension determines its priority, from highest to lowest,
     *     // when Gradle merges variant sources and configurations. You must assign
     *     // each product flavor you configure to one of the flavor dimensions.
     *     flavorDimensions 'api', 'version'
     *
     *     productFlavors {
     *         demo {
     *             // Assigns this product flavor to the 'version' flavor dimension.
     *             dimension 'version'
     *             ...
     *         }
     *
     *         full {
     *             dimension 'version'
     *             ...
     *         }
     *
     *         minApi24 {
     *             // Assigns this flavor to the 'api' dimension.
     *             dimension 'api'
     *             minSdkVersion '24'
     *             versionNameSuffix "-minApi24"
     *             ...
     *         }
     *
     *         minApi21 {
     *             dimension "api"
     *             minSdkVersion '21'
     *             versionNameSuffix "-minApi21"
     *             ...
     *         }
     *     }
     * }
     * ```
     *
     *
     * To learn more about configuring flavor dimensions, read
     * [Combine multiple flavors](https://developer.android.com/studio/build/build-variants.html#flavor-dimensions).
     */
    var dimension: String?

    @Incubating
    @Deprecated("Replaced with the dimension property")
    fun setDimension(dimension: String?): Void?

    /**
     * Specifies a sorted list of product flavors that the plugin should try to use when a direct
     * variant match with a local module dependency is not possible.
     *
     * Android plugin 3.0.0 and higher try to match each variant of your module with the same one
     * from its dependencies. For example, when you build a "freeDebug" version of your app, the
     * plugin tries to match it with "freeDebug" versions of the local library modules the app
     * depends on.
     *
     * However, there may be situations in which, for a given flavor dimension that exists in
     * both the app and its library dependencies, **your app includes flavors that a dependency
     * does not**. For example, consider if both your app and its library dependencies include a
     * "tier" flavor dimension. However, the "tier" dimension in the app includes "free" and "paid"
     * flavors, but one of its dependencies includes only "demo" and "paid" flavors for the same
     * dimension. When the plugin tries to build the "free" version of your app, it won't know which
     * version of the dependency to use, and you'll see an error message similar to the following:
     *
     * ```
     * Error:Failed to resolve: Could not resolve project :mylibrary.
     * Required by:
     * project :app
     * ```
     *
     * In this situation, you should use `matchingFallbacks` to specify alternative
     * matches for the app's "free" product flavor, as shown below:
     *
     * ```
     * // In the app's build.gradle file.
     * android {
     *     flavorDimensions 'tier'
     *     productFlavors {
     *         paid {
     *             dimension 'tier'
     *             // Because the dependency already includes a "paid" flavor in its
     *             // "tier" dimension, you don't need to provide a list of fallbacks
     *             // for the "paid" flavor.
     *         }
     *         free {
     *             dimension 'tier'
     *             // Specifies a sorted list of fallback flavors that the plugin
     *             // should try to use when a dependency's matching dimension does
     *             // not include a "free" flavor. You may specify as many
     *             // fallbacks as you like, and the plugin selects the first flavor
     *             // that's available in the dependency's "tier" dimension.
     *             matchingFallbacks = ['demo', 'trial']
     *         }
     *     }
     * }
     * ```
     *
     * Note that, for a given flavor dimension that exists in both the app and its library
     * dependencies, there is no issue when a library includes a product flavor that your app does
     * not. That's because the plugin simply never requests that flavor from the dependency.
     *
     * If instead you are trying to resolve an issue in which **a library dependency includes a
     * flavor dimension that your app does not**, use [missingDimensionStrategy].
     */
    @get:Incubating
    val matchingFallbacks: MutableList<String>

    @Incubating
    @Deprecated("Replaced with property matchingFallbacks")
    fun setMatchingFallbacks(vararg fallbacks: String)

    @Incubating
    @Deprecated("Replaced with property matchingFallbacks")
    fun setMatchingFallbacks(fallbacks: List<String>)
}