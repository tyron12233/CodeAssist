package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.LintOptions
import java.io.File
import java.io.Serializable

/**
 * Implementation of [LintOptions] for serialization via the Tooling API.
 */
data class LintOptionsImpl(
    override val disable: Set<String>,
    override val enable: Set<String>,
    override val informational: Set<String>,
    override val warning: Set<String>,
    override val error: Set<String>,
    override val fatal: Set<String>,
    override val checkOnly: Set<String>,
    override val abortOnError: Boolean,
    override val absolutePaths: Boolean,
    override val noLines: Boolean,
    override val quiet: Boolean,
    override val checkAllWarnings: Boolean,
    override val ignoreWarnings: Boolean,
    override val warningsAsErrors: Boolean,
    override val checkTestSources: Boolean,
    override val ignoreTestSources: Boolean,
    override val ignoreTestFixturesSources: Boolean,
    override val checkGeneratedSources: Boolean,
    override val explainIssues: Boolean,
    override val showAll: Boolean,
    override val lintConfig: File?,
    override val textReport: Boolean,
    override val textOutput: File?,
    override val htmlReport: Boolean,
    override val htmlOutput: File?,
    override val xmlReport: Boolean,
    override val xmlOutput: File?,
    override val sarifReport: Boolean,
    override val sarifOutput: File?,
    override val checkReleaseBuilds: Boolean,
    override val checkDependencies: Boolean,
    override val baseline: File?,
) : LintOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
