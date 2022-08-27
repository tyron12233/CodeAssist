package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface BundleAbi {
    @get:Incubating
    @set:Incubating
    var enableSplit: Boolean?
}