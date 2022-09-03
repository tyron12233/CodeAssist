package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.gradle.internal.services.DslServices
import org.gradle.api.Action
import javax.inject.Inject

abstract class Splits @Inject constructor(dslServices: DslServices) :
    com.tyron.builder.api.dsl.Splits {
    abstract override val density: DensitySplitOptions
    abstract override val abi: AbiSplitOptions

    /**
     * Encapsulates settings for
     * [building per-language (or locale) APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split).
     *
     * **Note:** Building per-language APKs is supported only when
     * [building configuration APKs](https://developer.android.com/topic/instant-apps/guides/config-splits.html)
     * for [Android Instant Apps](https://developer.android.com/topic/instant-apps/index.html).
     */
    val language: LanguageSplitOptions =
        dslServices.newInstance(LanguageSplitOptions::class.java)

    /**
     * Encapsulates settings for
     * [building per-language (or locale) APKs](https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split).
     *
     * **Note:** Building per-language APKs is supported only when
     * [building configuration APKs](https://developer.android.com/topic/instant-apps/guides/config-splits.html)
     * for [Android Instant Apps](https://developer.android.com/topic/instant-apps/index.html).
     *
     * For more information about the properties you can configure in this block, see
     * [LanguageSplitOptions].
     */
    fun language(action: Action<LanguageSplitOptions>) {
        action.execute(language)
    }

    override val densityFilters: Set<String>
        get() = density.applicableFilters

    override val abiFilters: Set<String>
        get() = abi.applicableFilters

    /**
     * Returns the list of languages (or locales) that the plugin will generate separate APKs for.
     *
     * If this property returns `null`, it means the plugin will not generate separate per-language
     * APKs. That is, each APK will include resources for all languages your project supports.
     *
     * @return a set of languages (or locales).
     */
    val languageFilters: Set<String>
        get() = language.applicationFilters
}