package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import java.io.File

/**
 * DSL object for configuring lint options.
 *
 * This block is replaced by the new [Lint] block. Unlike most other renamed blocks the new type is
 * unrelated, to allow for some extra changes, such as removing the 'is' prefix from boolean
 * properties.
 *
 * Example:
 *
 * ```
 * android {
 *    lintOptions {
 *          // set to true to turn off analysis progress reporting by lint
 *          quiet true
 *          // if true, stop the gradle build if errors are found
 *          abortOnError false
 *          // set to true to have all release builds run lint on issues with severity=fatal
 *          // and abort the build (controlled by abortOnError above) if fatal issues are found
 *          checkReleaseBuilds true
 *          // if true, only report errors
 *          ignoreWarnings true
 *          // if true, emit full/absolute paths to files with errors (true by default)
 *          absolutePaths true
 *          // if true, check all issues, including those that are off by default
 *          checkAllWarnings true
 *          // if true, treat all warnings as errors
 *          warningsAsErrors true
 *          // turn off checking the given issue id's
 *          disable 'TypographyFractions','TypographyQuotes'
 *          // turn on the given issue id's
 *          enable 'RtlHardcoded','RtlCompat', 'RtlEnabled'
 *          // check *only* the given issue id's
 *          check 'NewApi', 'InlinedApi'
 *          // if true, don't include source code lines in the error output
 *          noLines true
 *          // if true, show all locations for an error, do not truncate lists, etc.
 *          showAll true
 *          // whether lint should include full issue explanations in the text error output
 *          explainIssues false
 *          // Fallback lint configuration (default severities, etc.)
 *          lintConfig file("default-lint.xml")
 *          // if true, generate a text report of issues (false by default)
 *          textReport true
 *          // location to write the output; can be a file or 'stdout' or 'stderr'
 *          //textOutput 'stdout'
 *          textOutput file("$reportsDir/lint-results.txt")
 *          // if true, generate an XML report for use by for example Jenkins
 *          xmlReport true
 *          // file to write report to (if not specified, defaults to lint-results.xml)
 *          xmlOutput file("$reportsDir/lint-report.xml")
 *          // if true, generate an HTML report (with issue explanations, sourcecode, etc)
 *          htmlReport true
 *          // optional path to HTML report (default will be lint-results.html in the builddir)
 *          htmlOutput file("$reportsDir/lint-report.html")
 *          // if true, generate a SARIF report (OASIS Static Analysis Results Interchange Format)
 *          sarifReport true
 *          // optional path to SARIF report (default will be lint-results.sarif in the builddir)
 *          sarifOutput file("$reportsDir/lint-report.html")
 *          // Set the severity of the given issues to fatal (which means they will be
 *          // checked during release builds (even if the lint target is not included)
 *          fatal 'NewApi', 'InlineApi'
 *          // Set the severity of the given issues to error
 *          error 'Wakelock', 'TextViewEdits'
 *          // Set the severity of the given issues to warning
 *          warning 'ResourceAsColor'
 *          // Set the severity of the given issues to ignore (same as disabling the check)
 *          ignore 'TypographyQuotes'
 *          // Set the severity of the given issues to informational
 *          informational 'StopShip'
 *          // Use (or create) a baseline file for issues that should not be reported
 *          baseline file("lint-baseline.xml")
 *          // Normally most lint checks are not run on test sources (except the checks
 *          // dedicated to looking for mistakes in unit or instrumentation tests, unless
 *          // ignoreTestSources is true). You can turn on normal lint checking in all
 *          // sources with the following flag, false by default:
 *          checkTestSources true
 *          // Like checkTestSources, but always skips analyzing tests -- meaning that it
 *          // also ignores checks that have explicitly asked to look at test sources, such
 *          // as the unused resource check.
 *          ignoreTestSources true
 *          // Normally lint will skip generated sources, but you can turn it on with this flag
 *          checkGeneratedSources true
 *          // Whether lint should check all dependencies too as part of its analysis.
 *          // Default is false.
 *          checkDependencies true
 *     }
 * }
 * ```
 */
@Incubating
@Deprecated("Renamed to Lint", replaceWith = ReplaceWith("Lint"))
interface LintOptions {
    /**
     * The set of issue IDs to suppress.
     *
     * Issues passed to [disable]  to this list, call
     * Callers are allowed to modify this collection.
     *
     */
    @get:Incubating
    @Deprecated("Moved to lint.disable")
    val disable: MutableSet<String>

    /**
     * The set of issue IDs to enable. Callers are allowed to modify this collection.
     */
    @get:Incubating
    @Deprecated("Moved to lint.enable")
    val enable: MutableSet<String>

    /**
     * The exact set of issues to check set by [checkOnly].
     *s
     * If empty, lint will detect the issues that are enabled by default plus
     * any issues enabled via [enable] and without issues disabled via [disable].
     */
    @get:Incubating
    @Deprecated("Moved to lint.checkOnly")
    val checkOnly: MutableSet<String>

    /** Whether lint should set the exit code of the process if errors are found */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.abortOnError")
    var isAbortOnError: Boolean

    /**
     * Whether lint should display full paths in the error output. By default the paths are relative
     * to the path lint was invoked from.
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.absolutePaths")
    var isAbsolutePaths: Boolean

    /**
     * Whether lint should include the source lines in the output where errors occurred (true by
     * default)
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.noLines")
    var isNoLines: Boolean

    /**
     * Whether lint should be quiet (for example, not write informational messages such as paths to
     * report files written)
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.quiet")
    var isQuiet: Boolean

    /** Whether lint should check all warnings, including those off by default */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.checkAllWarnings")
    var isCheckAllWarnings: Boolean

    /** Returns whether lint will only check for errors (ignoring warnings) */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.ignoreWarnings")
    var isIgnoreWarnings: Boolean

    /** Whether lint should treat all warnings as errors */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.warningsAsErrors")
    var isWarningsAsErrors: Boolean

    /**
     * Whether lint should run all checks on test sources, instead of just the lint checks
     * that have been specifically written to include tests (e.g. checks looking for specific test
     * errors, or checks that need to consider testing code such as the unused resource detector)
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.checkTestSources")
    var isCheckTestSources: Boolean

    /**
     * Whether lint should ignore all test sources. This is like [isCheckTestSources], but always
     * skips analyzing tests -- meaning that it also ignores checks that have explicitly asked to
     * look at test sources, such as the unused resource check.
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.ignoreTestSources")
    var isIgnoreTestSources: Boolean

    /** Returns whether lint should run checks on generated sources. */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.checkGeneratedSources")

    var isCheckGeneratedSources: Boolean

    /** Whether lint should check all dependencies too as part of its analysis. Default is false. */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.checkDependencies")
    var isCheckDependencies: Boolean

    /**
     * Whether lint should include explanations for issue errors. (Note that HTML and XML reports
     * intentionally do this unconditionally, ignoring this setting.)
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.explainIssues")
    var isExplainIssues: Boolean

    /**
     * Whether lint should include all output (e.g. include all alternate locations, not truncating
     * long messages, etc.)
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.showAll")
    var isShowAll: Boolean

    /**
     * Whether lint should check for fatal errors during release builds. Default is true. If issues
     * with severity "fatal" are found, the release build is aborted.
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.checkReleaseBuilds")
    var isCheckReleaseBuilds: Boolean

    /**
     * The default config file to use as a fallback. This corresponds to a `lint.xml` file with
     * severities etc to use when a project does not have more specific information.
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.lintConfig")
    var lintConfig: File?

    /**
     * Whether we should write a text report. Default is false. The location can be controlled by
     * [textOutput].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.textReport")
    var textReport: Boolean

    /**
     * Whether we should write an HTML report. Default is true. The location can be controlled by
     * [htmlOutput].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.htmlReport")
    var htmlReport: Boolean

    /**
     * Whether we should write a SARIF (OASIS Static Analysis Results Interchange Format) report.
     * Default is false. The location can be controlled by [sarifOutput].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.sarifReport")
    var sarifReport: Boolean

    /**
     * Whether we should write an XML report. Default is true. The location can be controlled by
     * [xmlOutput].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.xmlReport")
    var xmlReport: Boolean

    /**
     * The optional path to where a text report should be written. The special value "stdout" can be
     * used to point to standard output. Setting this property will also turn on [textReport].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.textOutput")
    var textOutput: File?

    /**
     * The optional path to where an HTML report should be written.
     * Setting this property will also turn on [htmlReport].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.htmlOutput")
    var htmlOutput: File?

    /**
     * The optional path to where an XML report should be written.
     * Setting this property will also turn on [xmlReport].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.xmlOutput")
    var xmlOutput: File?

    /**
     * The optional path to where a SARIF report (OASIS Static
     * Analysis Results Interchange Format) should be written.
     * Setting this property will also turn on [sarifReport].
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.sarifOutput")
    var sarifOutput: File?

    /**
     * The baseline file to use, if any. The baseline file is an XML report previously created by
     * lint, and any warnings and errors listed in that report will be ignored from analysis.
     *
     * If you have a project with a large number of existing warnings, this lets you set a baseline
     * and only see newly introduced warnings until you get a chance to go back and address the
     * "technical debt" of the earlier warnings.
     */
    @get:Incubating
    @set:Incubating
    @Deprecated("Moved to lint.baseline")
    var baselineFile: File?

    /**
     * Sets the baseline file to use, if any. The baseline file is an XML report previously created
     * by lint, and any warnings and errors listed in that report will be ignored from analysis.
     *
     * If you have a project with a large number of existing warnings, this lets you set a baseline
     * and only see newly introduced warnings until you get a chance to go back and address the
     * "technical debt" of the earlier warnings.
     */
    @Incubating
    @Deprecated("Replaced by lint.baseline")
    fun baseline(baseline: String)

    /**
     * Sets the baseline file to use, if any. The baseline file is an XML report previously created
     * by lint, and any warnings and errors listed in that report will be ignored from analysis.
     *
     * If you have a project with a large number of existing warnings, this lets you set a baseline
     * and only see newly introduced warnings until you get a chance to go back and address the
     * "technical debt" of the earlier warnings.
     */
    @Incubating
    @Deprecated("Replaced by lint.baseline")
    fun baseline(baselineFile: File)

    /** Sets the optional path to where a text report should be written */
    @Incubating
    @Deprecated("Replaced by lint.textOutput")
    fun textOutput(textOutput: String)

    /** Sets the optional path to where a text report should be written */
    @Incubating
    @Deprecated("Replaced by lint.textOutput")
    fun textOutput(textOutput: File)

    /** Adds the id to the set of unique issues to check. */
    @Deprecated(
        message = "Use checkOnly instead; check will turn off all other checks so the method " +
                "has been renamed to be more explicit about this",
        replaceWith = ReplaceWith("checkOnly(id)")
    )
    @Incubating
    fun check(id: String)

    /** Adds the ids to the set of unique issues to check. */
    @Deprecated(
        message = "Use checkOnly instead; check will turn off all other checks so the method " +
                "has been renamed to be more explicit about this",
        replaceWith = ReplaceWith("checkOnly(ids)")
    )
    @Incubating
    fun check(vararg ids: String)

    /**
     * Adds the id to the set of issues to check. Note that when using this, all other checks
     * are turned off.
     */
    @Incubating
    @Deprecated("Replaced by lint.checkOnly += id")
    fun checkOnly(id: String)

    /**
     * Adds the id to the set of issues to check. Note that when using this, all other checks
     * are turned off.
     */
    @Incubating
    @Deprecated("Replaced by lint.checkOnly += ids")
    fun checkOnly(vararg ids: String)

    /** Adds the id to the set of issues to enable. */
    @Incubating
    @Deprecated("Replaced by lint.enable += id")
    fun enable(id: String)

    /** Adds the ids to the set of issues to enable. */
    @Incubating
    @Deprecated("Replaced by lint.enable += ids")
    fun enable(vararg ids: String)

    /** Adds the id to the set of issues to suppress. */
    @Incubating
    @Deprecated("Replaced by lint.disable += ids")
    fun disable(id: String)

    /** Adds the ids to the set of issues to suppress. */
    @Incubating
    @Deprecated("Replaced by lint.disable += ids")
    fun disable(vararg ids: String)

    /** Adds a severity override for the given issue. */
    @Incubating
    @Deprecated("Replaced by lint.informational += id")
    fun informational(id: String)

    /** Adds a severity override for the given issues. */
    @Incubating
    @Deprecated("Replaced by lint.informational += ids")
    fun informational(vararg ids: String)

    /** Adds a severity override for the given issue. */
    @Incubating
    @Deprecated("Replaced by lint.ignore += id")
    fun ignore(id: String)

    /** Adds a severity override for the given issues. */
    @Incubating
    @Deprecated("Replaced by lint.ignore += ids")
    fun ignore(vararg ids: String)

    /** Adds a severity override for the given issue. */
    @Incubating
    @Deprecated("Replaced by lint.warning += id")
    fun warning(id: String)

    /** Adds a severity override for the given issues. */
    @Incubating
    @Deprecated("Replaced by lint.warning += ids")
    fun warning(vararg ids: String)

    /** Adds a severity override for the given issue. */
    @Incubating
    @Deprecated("Replaced by lint.error += id")
    fun error(id: String)

    /** Adds a severity override for the given issues. */
    @Incubating
    @Deprecated("Replaced by lint.error += ids")
    fun error(vararg ids: String)

    /** Adds a severity override for the given issue. */
    @Incubating
    @Deprecated("Replaced by lint.fatal += id")
    fun fatal(id: String)

    /** Adds a severity override for the given issues. */
    @Incubating
    @Deprecated("Replaced by lint.fatal += ids")
    fun fatal(vararg ids: String)
}