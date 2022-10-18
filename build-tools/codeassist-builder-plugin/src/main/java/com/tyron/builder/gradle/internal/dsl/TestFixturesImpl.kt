package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.TestFixtures
import javax.inject.Inject

open class TestFixturesImpl @Inject constructor(override var enable: Boolean): TestFixtures {
    override var androidResources: Boolean = false
}