package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface BundleDensity {
    @get:Incubating
    @set:Incubating
    var enableSplit: Boolean?
}