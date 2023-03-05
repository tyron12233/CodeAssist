package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

interface BundleLanguage {
    @get:Incubating
    @set:Incubating
    var enableSplit: Boolean?
}