package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

/** DSL object for configuring JaCoCo settings. */
@Incubating
@Deprecated("Renamed to TestCoverage", replaceWith = ReplaceWith("TestCoverage"))
interface JacocoOptions : TestCoverage {
    /** The version of JaCoCo to use. */
    @Deprecated("Renamed to testCoverage.jacocoVersion", replaceWith = ReplaceWith("jacocoVersion"))
    var version: String
}