package com.tyron.builder.api.dsl

/**
 * Single variant publishing options for application projects.
 */
interface ApplicationSingleVariant : SingleVariant {
    /**
     * Configure to publish this variant as APK artifact. Android Gradle Plugin would publish this
     * variant as AAB artifact if this function is not invoked.
     */
    fun publishApk()
}