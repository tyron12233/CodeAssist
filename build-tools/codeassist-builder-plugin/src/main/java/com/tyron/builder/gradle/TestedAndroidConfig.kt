package com.tyron.builder.gradle

import com.tyron.builder.gradle.api.TestVariant
import com.tyron.builder.gradle.api.UnitTestVariant
import org.gradle.api.DomainObjectSet

/**
 * User configuration settings for android plugin with test component.
 */
interface TestedAndroidConfig : AndroidConfig {

    /** The name of the BuildType for testing. */
    override val testBuildType: String

    /**
     * The list of (Android) test variants. Since the collections is built after evaluation,
     * it should be used with Gradle's `all` iterator to process future items.
     */
    val testVariants: DomainObjectSet<TestVariant>

    /**
     * The list of (Android) test variants. Since the collections is built after evaluation,
     * it should be used with Gradle's `all` iterator to process future items.
     */
    val unitTestVariants: DomainObjectSet<UnitTestVariant>
}
