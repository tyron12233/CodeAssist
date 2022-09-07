package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * Extension for the Android Test Gradle Plugin.
 *
 * This is the `android` block when the `com.android.test` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
*/
interface TestExtension :
    CommonExtension<
            TestBuildFeatures,
            TestBuildType,
            TestDefaultConfig,
            TestProductFlavor> {
    // TODO(b/140406102)
    /**
     * The Gradle path of the project that this test project tests.
     */
    @get:Incubating
    @set:Incubating
    var targetProjectPath: String?
}
