package com.tyron.builder.api.dsl

/**
 * Maven publishing DSL object for configuring options related to publishing Android variants to a
 * Maven repository.
 *
 * Android Gradle Plugin 7.1.0 and higher allows you to configure which build variants to publish
 * to an Apache Maven repository. Android Gradle Plugin creates components for those variants in
 * your app or library module that you can use to customize a publication to a Maven repository.
 * You are able to publish build variants in two different publishing mechanisms:
 * single variant publishing and multiple variants publishing.

 * In single variant publishing, Android Gradle Plugin creates a component for the variant you want
 * to publish in your app or library module. The component has the same name as the variant and only
 * contains that single variant. For modules that use the application plugin, Android Gradle Plugin
 * publishes the variant as an Android App Bundle(AAB) by default. You can publish the variant as
 * a ZIP of APK(s) instead by using [ApplicationSingleVariant.publishApk] inside the singleVariant
 * config block. However, publishing the variant as AAB and APK together is not supported.
 *
 * Multiple variants publishing allows you to publish multiple variants in your library module and
 * choose the name of the component those variants are added to. The component name is set to
 * “default” unless you specify it in the multipleVariants function call. Android Gradle Plugin adds
 * necessary variant specific attributes including buildType and flavorDimensions to disambiguate
 * those variants. Note that multiple variants publishing is not supported in app modules.
 *
 * [LibraryPublishing] extends this with options for publishing library projects.
 * [ApplicationPublishing] extends this with options for publishing application projects.
 */
interface Publishing<SingleVariantT: SingleVariant> {
    /**
     * Publish a variant with single variant publishing mechanism.
     */
    fun singleVariant(variantName: String)

    /**
     * Publish a variant with single variant publishing options.
     */
    fun singleVariant(variantName: String, action: SingleVariantT.() -> Unit)
}