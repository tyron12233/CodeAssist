package com.tyron.builder.api.dsl

/** Settings related to the gathering of code-coverage data from tests */
interface TestCoverage {
    /**
     * The version of JaCoCo to use.
     */
    var jacocoVersion: String
}