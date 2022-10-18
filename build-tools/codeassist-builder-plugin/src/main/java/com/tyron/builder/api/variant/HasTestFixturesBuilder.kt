package com.tyron.builder.api.variant

import org.gradle.api.Incubating

/**
 * Interface that mark the potential existence of test fixtures associated with a variant.
 */
@Incubating
interface HasTestFixturesBuilder {
    /**
     * Set to `true` if the variant's has test fixtures, `false` otherwise.
     *
     * Default value will match [com.android.build.api.dsl.TestFixtures.enable] value
     * that is set through the extension via
     * [com.android.build.api.dsl.TestedExtension.testFixtures].
     */
    var enableTestFixtures: Boolean
}
