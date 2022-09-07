package com.tyron.builder.api.variant

/** [Variant] for Library projects */
interface LibraryVariant : Variant, GeneratesAar, HasAndroidTest, HasTestFixtures, CanMinifyCode {

    /**
     * Variant specific settings for the renderscript compiler. This will return null when
     * [com.android.build.api.dsl.BuildFeatures.renderScript] is false.
     */
    val renderscript: Renderscript?
}