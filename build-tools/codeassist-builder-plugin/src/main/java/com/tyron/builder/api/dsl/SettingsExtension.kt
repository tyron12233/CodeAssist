package com.tyron.builder.api.dsl

import org.gradle.api.Action
import org.gradle.api.Incubating

/**
 * Interface used for the `android` DSL in the `settings.gradle[.kts]` file, after the
 * `com.android.settings` plugin is applied.
 *
 * This allows settings default values that are then applied to all android projects which this
 * build.
 */
@Incubating
interface SettingsExtension {

    /**
     * Specifies the API level to compile your project against. The Android plugin requires you to
     * configure this property.
     *
     * This means your code can use only the Android APIs included in that API level and lower.
     * You can configure the compile sdk version by adding the following to the `android`
     * block: `compileSdk = 26`.
     *
     * You should generally
     * [use the most up-to-date API level](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#ApiLevels)
     * available.
     * If you are planning to also support older API levels, it's good practice to
     * [use the Lint tool](https://developer.android.com/studio/write/lint.html)
     * to check if you are using APIs that are not available in earlier API levels.
     *
     * The value you assign to this property is parsed and stored in a normalized form, so
     * reading it back may return a slightly different value.
     */
    var compileSdk: Int?

    /**
     * Specifies the SDK Extension level to compile your project against. This value is optional.
     *
     * When not provided the base extension for the given `compileSdk` API level will be selected.
     */
    var compileSdkExtension: Int?

    /**
     * Specify a preview API to compile your project against.
     *
     * For example, to try out the Android S preview,
     * rather than `compileSdk = 30` you can use `compileSdkPreview = "S"`
     *
     * Once the preview APIs are finalized, they will be allocated a stable integer value.
     */
    var compileSdkPreview: String?

    fun compileSdkAddon(vendor: String, name: String, version: Int)

    /** Value set via `compileSdkAddon` */
    val addOnVendor: String?
    /** Value set via `compileSdkAddon` */
    val addOnName: String?
    /** Value set via `compileSdkAddon` */
    val addOnVersion: Int?

    /**
     * The minimum SDK version.
     * Setting this it will override previous calls of [minSdk] and [minSdkPreview] setters. Only
     * one of [minSdk] and [minSdkPreview] should be set.
     *
     * See [uses-sdk element documentation](http://developer.android.com/guide/topics/manifest/uses-sdk-element.html).
     */
    var minSdk: Int?

    var minSdkPreview: String?


    /** Set execution profiles and options for tools. */
    val execution: Execution

    /** Set execution profiles and options for tools. */
    fun execution(action: Action<Execution>)

    /** Set execution profiles and options for tools. */
    fun execution(action: Execution.() -> Unit)

    /**
     * Requires the specified NDK version to be used.
     *
     * See [com.android.build.api.dsl.CommonExtension.ndkVersion] for more information
     */
    var ndkVersion: String?

    /**
     * Requires the specified path to NDK be used.
     *
     * See [com.android.build.api.dsl.CommonExtension.ndkPath] for more information
     */
    var ndkPath: String?

    /**
     * Specifies the version of the
     * [SDK Build Tools](https://developer.android.com/studio/releases/build-tools.html)
     * to use when building your project.
     *
     * See [com.android.build.api.dsl.CommonExtension.buildToolsVersion] for more information
     */
    var buildToolsVersion: String?
}
