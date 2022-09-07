package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Shared properties between DSL objects [ProductFlavor] and [DefaultConfig]
 */
interface BaseFlavor : VariantDimension, HasInitWith<BaseFlavor> {
    // TODO(b/140406102)
    /** The name of the flavor. */
    @Incubating
    fun getName(): String

    /**
     * Test application ID.
     *
     * See [Set the Application ID](https://developer.android.com/studio/build/application-id.html)
     */
    var testApplicationId: String?

    /**
     * The minimum SDK version.
     * Setting this it will override previous calls of [minSdk] and [minSdkPreview] setters. Only
     * one of [minSdk] and [minSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    var minSdk: Int?

    @Deprecated("Replaced by minSdk property")
    @Incubating
    fun setMinSdkVersion(minSdkVersion: Int)
    @Deprecated("Replaced by minSdk property")
    @Incubating
    fun minSdkVersion(minSdkVersion: Int)

    /**
     * The minimum SDK version.
     * Setting this it will override previous calls of [minSdk] and [minSdkPreview] setters. Only
     * one of [minSdk] and [minSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    var minSdkPreview: String?

    @Deprecated("Replaced by minSdkPreview property")
    @Incubating
    fun setMinSdkVersion(minSdkVersion: String?)
    @Deprecated("Replaced by minSdkPreview property")
    @Incubating
    fun minSdkVersion(minSdkVersion: String?)

    /**
     * The renderscript target api, or null if not specified. This is only the value set on this
     * product flavor.
     */
    @get:Incubating
    @set:Incubating
    var renderscriptTargetApi: Int?

    /**
     * Whether the renderscript code should be compiled in support mode to make it compatible with
     * older versions of Android.
     *
     * True if support mode is enabled, false if not, and null if not specified.
     */
    @get:Incubating
    @set:Incubating
    var renderscriptSupportModeEnabled: Boolean?

    /**
     * Whether the renderscript BLAS support lib should be used to make it compatible with older
     * versions of Android.
     *
     * True if BLAS support lib is enabled, false if not, and null if not specified.
     */
    @get:Incubating
    @set:Incubating
    var renderscriptSupportModeBlasEnabled: Boolean?

    /**
     * Whether the renderscript code should be compiled to generate C/C++ bindings.
     * True for C/C++ generation, false for Java, null if not specified.
     */
    @get:Incubating
    @set:Incubating
    var renderscriptNdkModeEnabled: Boolean?

    /**
     * Test instrumentation runner class name.
     *
     * This is a fully qualified class name of the runner, e.g.
     * `android.test.InstrumentationTestRunner`
     *
     * See [instrumentation](http://developer.android.com/guide/topics/manifest/instrumentation-element.html).
     */
    var testInstrumentationRunner: String?

    /**
     * Test instrumentation runner custom arguments.
     *
     * e.g. `[key: "value"]` will give `adb shell am instrument -w -e key value com.example`...
     *
     * See [instrumentation](http://developer.android.com/guide/topics/manifest/instrumentation-element.html).
     *
     * Test runner arguments can also be specified from the command line:
     *
     * ```
     * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.size=medium
     * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.foo=bar
     * ```
     */
    val testInstrumentationRunnerArguments: MutableMap<String, String>

    @Incubating
    @Deprecated("Replaced by testInstrumentationRunnerArguments property")
    fun testInstrumentationRunnerArgument(key: String, value: String)

    @Incubating
    @Deprecated("Replaced by testInstrumentationRunnerArguments property")
    fun setTestInstrumentationRunnerArguments(
        testInstrumentationRunnerArguments: MutableMap<String, String>
    ): Any?

    @Incubating
    @Deprecated("Replaced by testInstrumentationRunnerArguments property")
    fun testInstrumentationRunnerArguments(args: Map<String, String>)

    /**
     * See [instrumentation](http://developer.android.com/guide/topics/manifest/instrumentation-element.html).
     */
    var testHandleProfiling: Boolean?

    @Incubating
    @Deprecated("Replaced by testFunctionalTest property")
    fun setTestHandleProfiling(testHandleProfiling: Boolean): Any?


    /**
     * See [instrumentation](http://developer.android.com/guide/topics/manifest/instrumentation-element.html).
     */
    var testFunctionalTest: Boolean?

    @Incubating
    @Deprecated("Replaced by testFunctionalTest property")
    fun setTestFunctionalTest(testFunctionalTest: Boolean): Any?

    /**
     * Specifies a list of
     * [alternative resources](https://d.android.com/guide/topics/resources/providing-resources.html#AlternativeResources)
     * to keep.
     *
     * For example, if you are using a library that includes language resources (such as
     * AppCompat or Google Play Services), then your APK includes all translated language strings
     * for the messages in those libraries whether the rest of your app is translated to the same
     * languages or not. If you'd like to keep only the languages that your app officially supports,
     * you can specify those languages using the `resourceConfigurations` property, as shown in the
     * sample below. Any resources for languages not specified are removed.
     *
     * ````
     * android {
     *     defaultConfig {
     *         ...
     *         // Keeps language resources for only the locales specified below.
     *         resourceConfigurations += ["en", "fr"]
     *     }
     * }
     * ````
     *
     * You can also use this property to filter resources for screen densities. For example,
     * specifying `hdpi` removes all other screen density resources (such as `mdpi`,
     * `xhdpi`, etc) from the final APK.
     *
     * **Note:** `auto` is no longer supported because it created a number of
     * issues with multi-module projects. Instead, you should specify a list of locales that your
     * app supports, as shown in the sample above. Android plugin 3.1.0 and higher ignore the `
     * auto` argument, and Gradle packages all string resources your app and its dependencies
     * provide.
     *
     * To learn more, see
     * [Remove unused alternative resources](https://d.android.com/studio/build/shrink-code.html#unused-alt-resources).
     */
    val resourceConfigurations: MutableSet<String>

    @Incubating
    @Deprecated("Replaced by resourceConfigurations field")
    fun resConfigs(config: Collection<String>)
    @Incubating
    @Deprecated("Replaced by resourceConfigurations field")
    fun resConfig(config: String)
    @Incubating
    @Deprecated("Replaced by resourceConfigurations field")
    fun resConfigs(vararg config: String)

    /** Options to configure the build-time support for `vector` drawables. */
    @get:Incubating
    val vectorDrawables: VectorDrawables

    /** Configures [VectorDrawables]. */
    @Incubating
    fun vectorDrawables(action: VectorDrawables.() -> Unit)

    /**
     * Whether to enable unbundling mode for embedded wear app.
     *
     * If true, this enables the app to transition from an embedded wear app to one
     * distributed by the play store directly.
     */
    var wearAppUnbundled: Boolean?


    /**
     * Specifies a flavor that the plugin should try to use from a given dimension in a dependency.
     *
     * Android plugin 3.0.0 and higher try to match each variant of your module with the same one
     * from its dependencies. For example, consider if both your app and its dependencies include a
     * "tier" [flavor dimension](/studio/build/build-variants.html#flavor-dimensions),
     * with flavors "free" and "paid". When you build a "freeDebug" version of your app, the plugin
     * tries to match it with "freeDebug" versions of the local library modules the app depends on.
     *
     * However, there may be situations in which **a library dependency includes a flavor
     * dimension that your app does not**. For example, consider if a library dependency includes
     * flavors for a "minApi" dimension, but your app includes flavors for only the "tier"
     * dimension. So, when you want to build the "freeDebug" version of your app, the plugin doesn't
     * know whether to use the "minApi23Debug" or "minApi18Debug" version of the dependency, and
     * you'll see an error message similar to the following:
     *
     * ```
     * Error:Failed to resolve: Could not resolve project :mylibrary.
     * Required by:
     * project :app
     * ```
     *
     * In this type of situation, use `missingDimensionStrategy` in the
     * [`defaultConfig`](com.android.build.gradle.internal.dsl.DefaultConfig.html)
     * block to specify the default flavor the plugin should select from each missing
     * dimension, as shown in the sample below. You can also override your selection in the
     * [`productFlavors`](com.android.build.gradle.internal.dsl.ProductFlavor.html)
     * block, so each flavor can specify a different matching strategy for a missing dimension.
     * (Tip: you can also use this property if you simply want to change the matching strategy for a
     * dimension that exists in both the app and its dependencies.)
     *
     * ```
     * // In the app's build.gradle file.
     * android {
     *     defaultConfig {
     *         // Specifies a flavor that the plugin should try to use from
     *         // a given dimension. The following tells the plugin that, when encountering
     *         // a dependency that includes a "minApi" dimension, it should select the
     *         // "minApi18" flavor.
     *         missingDimensionStrategy 'minApi', 'minApi18'
     *         // You should specify a missingDimensionStrategy property for each
     *         // dimension that exists in a local dependency but not in your app.
     *         missingDimensionStrategy 'abi', 'x86'
     *     }
     *     flavorDimensions 'tier'
     *     productFlavors {
     *         free {
     *             dimension 'tier'
     *             // You can override the default selection at the product flavor
     *             // level by configuring another missingDimensionStrategy property
     *             // for the "minApi" dimension.
     *             missingDimensionStrategy 'minApi', 'minApi23'
     *         }
     *         paid { }
     *     }
     * }
     * ```
     */
    @Incubating
    fun missingDimensionStrategy(dimension: String, requestedValue: String)

    /**
     * Specifies a sorted list of flavors that the plugin should try to use from a given dimension
     * in a dependency.
     *
     *
     * Android plugin 3.0.0 and higher try to match each variant of your module with the same one
     * from its dependencies. For example, consider if both your app and its dependencies include a
     * "tier" [flavor dimension](/studio/build/build-variants.html#flavor-dimensions),
     * with flavors "free" and "paid". When you build a "freeDebug" version of your app, the plugin
     * tries to match it with "freeDebug" versions of the local library modules the app depends on.
     *
     *
     * However, there may be situations in which **a library dependency includes a flavor
     * dimension that your app does not**. For example, consider if a library dependency includes
     * flavors for a "minApi" dimension, but your app includes flavors for only the "tier"
     * dimension. So, when you want to build the "freeDebug" version of your app, the plugin doesn't
     * know whether to use the "minApi23Debug" or "minApi18Debug" version of the dependency, and
     * you'll see an error message similar to the following:
     *
     * ```
     * Error:Failed to resolve: Could not resolve project :mylibrary.
     * Required by:
     * project :app
     * ```
     *
     *
     * In this type of situation, use `missingDimensionStrategy` in the
     * [`defaultConfig`](com.android.build.gradle.internal.dsl.DefaultConfig.html)
     * block to specify the default flavor the plugin should select from each missing
     * dimension, as shown in the sample below. You can also override your selection in the
     * [`productFlavors`](com.android.build.gradle.internal.dsl.ProductFlavor.html)
     * block, so each flavor can specify a different matching strategy for a missing dimension.
     * (Tip: you can also use this property if you simply want to change the matching strategy for a
     * dimension that exists in both the app and its dependencies.)
     *
     * ```
     * // In the app's build.gradle file.
     * android {
     *     defaultConfig {
     *         // Specifies a flavor that the plugin should try to use from
     *         // a given dimension. The following tells the plugin that, when encountering
     *         // a dependency that includes a "minApi" dimension, it should select the
     *         // "minApi18" flavor.
     *         missingDimensionStrategy 'minApi', 'minApi18'
     *         // You should specify a missingDimensionStrategy property for each
     *         // dimension that exists in a local dependency but not in your app.
     *         missingDimensionStrategy 'abi', 'x86'
     *     }
     *     flavorDimensions 'tier'
     *     productFlavors {
     *         free {
     *             dimension 'tier'
     *             // You can override the default selection at the product flavor
     *             // level by configuring another missingDimensionStrategy property
     *             // for the "minApi" dimension.
     *             missingDimensionStrategy 'minApi', 'minApi23'
     *         }
     *         paid { }
     *     }
     * }
     * ```
     */
    @Incubating
    fun missingDimensionStrategy(dimension: String, vararg requestedValues: String)

    /**
     * Specifies a sorted list of flavors that the plugin should try to use from a given dimension
     * in a dependency.
     *
     *
     * Android plugin 3.0.0 and higher try to match each variant of your module with the same one
     * from its dependencies. For example, consider if both your app and its dependencies include a
     * "tier" [flavor dimension](/studio/build/build-variants.html#flavor-dimensions),
     * with flavors "free" and "paid". When you build a "freeDebug" version of your app, the plugin
     * tries to match it with "freeDebug" versions of the local library modules the app depends on.
     *
     *
     * However, there may be situations in which **a library dependency includes a flavor
     * dimension that your app does not**. For example, consider if a library dependency includes
     * flavors for a "minApi" dimension, but your app includes flavors for only the "tier"
     * dimension. So, when you want to build the "freeDebug" version of your app, the plugin doesn't
     * know whether to use the "minApi23Debug" or "minApi18Debug" version of the dependency, and
     * you'll see an error message similar to the following:
     *
     * ```
     * Error:Failed to resolve: Could not resolve project :mylibrary.
     * Required by:
     * project :app
     * ```
     *
     * In this type of situation, use `missingDimensionStrategy` in the
     * [`defaultConfig`](com.android.build.gradle.internal.dsl.DefaultConfig.html)
     * block to specify the default flavor the plugin should select from each missing
     * dimension, as shown in the sample below. You can also override your selection in the
     * [`productFlavors`](com.android.build.gradle.internal.dsl.ProductFlavor.html)
     * block, so each flavor can specify a different matching strategy for a missing dimension.
     * (Tip: you can also use this property if you simply want to change the matching strategy for a
     * dimension that exists in both the app and its dependencies.)
     *
     * ```
     * // In the app's build.gradle file.
     * android {
     *     defaultConfig {
     *         // Specifies a flavor that the plugin should try to use from
     *         // a given dimension. The following tells the plugin that, when encountering
     *         // a dependency that includes a "minApi" dimension, it should select the
     *         // "minApi18" flavor.
     *         missingDimensionStrategy 'minApi', 'minApi18'
     *         // You should specify a missingDimensionStrategy property for each
     *         // dimension that exists in a local dependency but not in your app.
     *         missingDimensionStrategy 'abi', 'x86'
     *     }
     *     flavorDimensions 'tier'
     *     productFlavors {
     *         free {
     *             dimension 'tier'
     *             // You can override the default selection at the product flavor
     *             // level by configuring another missingDimensionStrategy property
     *             // for the "minApi" dimension.
     *             missingDimensionStrategy 'minApi', 'minApi23'
     *         }
     *         paid { }
     *     }
     * }
     * ```
     */
    @Incubating
    fun missingDimensionStrategy(dimension: String, requestedValues: List<String>)

    /**
     * Copies all properties from the given flavor.
     *
     * It can be used like this:
     *
     * ```
     * android.productFlavors {
     *     paid {
     *         initWith free
     *         // customize...
     *     }
     * }
     * ```
     */
    @Incubating
    override fun initWith(that: BaseFlavor)
}