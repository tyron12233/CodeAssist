package com.tyron.builder.api.dsl

/**
 * Options to configure Multiple APKs.
 *
 * [`android`][ApplicationExtension.splits]`.`[`splits`][Splits]
 *
 * If you publish your app to Google Play, you should build and upload an
 * [Android App Bundle](https://developer.android.com/guide/app-bundle).
 * When you do so, Google Play automatically generates and serves optimized APKs for each user’s
 * device configuration, so they download only the code and resources they need to run your app.
 * This is much simpler than managing multiple APKs manually.
 *
 * If you publish your app to a store that doesn't support the Android App Bundle format, you can
 * publish
 * [multiple APKs](https://developer.android.com/studio/build/configure-apk-splits.html)
 * manually.
 *
 * The Android Gradle plugin supports generating multiple APKs based on screen density and
 * [Application Binary Interface (ABI)](https://developer.android.com/ndk/guides/abis.html),
 * where each APK contains the code and resources required for a given device configuration.
 *
 * You will also need to
 * [assign version codes to each APK](https://developer.android.com/studio/build/configure-apk-splits.html#configure-APK-versions)
 * so that you are able to manage updates later.
 *
 * Previously the Android Gradle plugin also supported building 'Configuration APKs' for Instant
 * Apps using this `splits` block, but that has been superseded by the Android App Bundle format.
 */
interface Splits {

    /**
     * Encapsulates settings for
     * [building per-ABI APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split).
     */
    val abi: AbiSplit

    /**
     * Encapsulates settings for <a
     * [building per-ABI APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split).
     *
     * For more information about the properties you can configure in this block, see [AbiSplit].
     */
    fun abi(action: AbiSplit.() -> Unit)

    /**
     * Encapsulates settings for
     * [building per-density APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-density-split).
     */
    val density: DensitySplit

    /**
     * Encapsulates settings for
     * [building per-density APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-density-split).
     *
     * For more information about the properties you can configure in this block, see
     * [DensitySplit].
     */
    fun density(action: DensitySplit.() -> Unit)

    /**
     * Returns the list of ABIs that the plugin will generate separate APKs for.
     *
     * If this property returns `null`, it means the plugin will not generate separate per-ABI APKs.
     * That is, each APK will include binaries for all ABIs your project supports.
     *
     * @return a set of ABIs.
     */
    val abiFilters: Collection<String>

    /**
     * Returns the list of screen density configurations that the plugin will generate separate APKs
     * for.
     *
     * If this property returns `null`, it means the plugin will not generate separate per-density
     * APKs. That is, each APK will include resources for all screen density configurations your
     * project supports.
     *
     * @return a set of screen density configurations.
     */
    val densityFilters: Collection<String>
}
