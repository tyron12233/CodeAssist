package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/**
 * DSL object for configuring test fixtures.
 */
@Incubating
interface TestFixtures {

    /**
     * Flag to enable test fixtures.
     *
     * Default value is derived from `android.experimental.enableTestFixtures` which is 'false' by
     * default.
     *
     * You can override the default for this for all projects in your build by adding the line
     *     `android.experimental.enableTestFixtures=true`
     * in the gradle.properties file at the root project of your build.
     */
    var enable: Boolean

    /**
     * Flag to enable Android resource processing in test fixtures.
     *
     * Default value is 'false'.
     */
    var androidResources: Boolean
}