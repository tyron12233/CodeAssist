package com.tyron.builder.api.variant

import com.tyron.builder.api.dsl.ApplicationExtension

/**
 * Extension for the Android Application Gradle Plugin components.
 *
 * This is the `androidComponents` block when the `com.android.application` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.tyron.builder.api.variant.
 */
interface ApplicationAndroidComponentsExtension:
    AndroidComponentsExtension<ApplicationExtension, ApplicationVariantBuilder, ApplicationVariant>