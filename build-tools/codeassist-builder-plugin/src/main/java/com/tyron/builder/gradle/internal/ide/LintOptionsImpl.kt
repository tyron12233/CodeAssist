package com.tyron.builder.gradle.internal.ide

import com.android.annotations.concurrency.Immutable
import com.tyron.builder.model.LintOptions
import java.io.File
import java.io.Serializable

/**
 * Implementation of [LintOptions] that is Serializable.
 *
 * Should only be used for the model.
 */
@Immutable
data class LintOptionsImpl(
    override val disable: Set<String> = emptySet(),
    override val enable: Set<String> = emptySet(),
    override val check: Set<String> = emptySet(),
    override val isAbortOnError: Boolean = false,
    override val isAbsolutePaths: Boolean = false,
    override val isNoLines: Boolean = false,
    override val isQuiet: Boolean = false,
    override val isCheckAllWarnings: Boolean = false,
    override val isIgnoreWarnings: Boolean = false,
    override val isWarningsAsErrors: Boolean = false,
    override val isCheckTestSources: Boolean = false,
    override val isIgnoreTestSources: Boolean = false,
    override val isCheckGeneratedSources: Boolean = false,
    override val isExplainIssues: Boolean = false,
    override val isShowAll: Boolean = false,
    override val lintConfig: File? = null,
    override val textReport: Boolean = false,
    override val textOutput: File? = null,
    override val htmlReport: Boolean = false,
    override val htmlOutput: File? = null,
    override val xmlReport: Boolean = false,
    override val xmlOutput: File? = null,
    override val sarifReport: Boolean  = false,
    override val sarifOutput: File? = null,
    override val isCheckReleaseBuilds: Boolean = false,
    override val isCheckDependencies: Boolean = false,
    override val baselineFile: File? = null,
    override val severityOverrides: Map<String, Int>? = null
) : LintOptions, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID = 1L
    }
}
