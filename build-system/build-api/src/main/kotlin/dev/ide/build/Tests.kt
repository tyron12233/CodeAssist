// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.build

import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.model.Project
import dev.ide.platform.ExtensionPoint

/**
 * A test, or a group of them, that a [TestProvider] found in a file: the gutter shows a run mark at [offset]
 * (the line holding it), labelled [name]. [target] is what [TestProvider.run] is handed to run just this, in
 * whatever form the provider's runner takes (`path/to/foo_test.dart:12`, `TestParse`, `suite > case`). A
 * [group] mark runs everything in it ("Run all tests in ..."). Since SPI 3.3.0.
 */
class TestDeclaration @JvmOverloads constructor(
    val name: String,
    val offset: Int,
    val target: String,
    val group: Boolean = false,
) {
    override fun toString(): String = (if (group) "group " else "") + "$name -> $target @$offset"
}

/**
 * Tests for a plugin's language: run marks in the gutter, a "Test <module>" row in the Run picker, and runs
 * whose results fill the Run screen's Tests tab (with re-run and re-run failed) exactly as JUnit's do.
 *
 * [id] is also the run's kind: the IDE starts a run through the task id `<id>:<module>[:<targets>]` (targets
 * newline-separated), so pick one no built-in uses (`test` and `androidTest` are the JVM's and Android's).
 * Results reach the Tests tab as records printed into the run's console: write [TestReport]'s lines to the
 * console's `ProgramIo.stdout`, starting with [TestReport.run] so a re-run comes back to this provider.
 *
 * Registered on [TEST_PROVIDER_EP]. Since SPI 3.3.0.
 */
interface TestProvider {
    val id: String

    /** The name the Run picker shows beside a module, e.g. `"Dart tests"`. */
    val displayName: String get() = id

    /** Files of these languages are scanned for [tests]. */
    val languages: Set<LanguageId>

    /** Whether [module] has tests this provider can run, which is what puts its row in the Run picker. */
    fun appliesTo(module: Module): Boolean = false

    /** The tests declared in [text], the live buffer of the file at [path] in [module]. Empty for a non-test file. */
    fun tests(module: Module, path: String, text: String): List<TestDeclaration>

    /**
     * The run for [targets] of [module]; empty runs every test. A target is a [TestDeclaration.target] (from a
     * gutter mark), or, from the Tests tab's re-run and re-run failed, a suite or `suite#test` as this run
     * reported them through [TestReport]. Null when this provider cannot run them now, which the user is told.
     * Set [RunAction.console] and print [TestReport] records to it.
     */
    fun run(module: Module, targets: List<String>, project: Project, ctx: BuildContext): RunAction?
}

/** Plugin-contributed test runners; see [TestProvider]. Since SPI 3.3.0. */
val TEST_PROVIDER_EP = ExtensionPoint<TestProvider>("platform.testProvider")

/**
 * The lines a test run prints for the Run screen's Tests tab to read. Each function returns one record
 * (newline included) to write to the console's standard output, between the run's own output, which stays
 * visible as it is. [suite] groups tests (a class, a file, a `group()`); [test] names one inside it. The Tests
 * tab shows a suite like a class name, the part after its last `.` as the name and the rest as the package, and
 * hands it back verbatim in a re-run's targets, so a dotted path (`test.parser_test`) reads well and resolves.
 * Since SPI 3.3.0.
 */
object TestReport {
    /** Marks a record inside console output. Two control characters no program prints by accident. */
    const val MARK: String = "\u0001ca-test\u0002"

    /** The first record of a run: [kind] is the [TestProvider.id] that re-runs it. */
    @JvmStatic fun run(kind: String): String = record("RUN", "", "", 0, kind)

    /** [test] will run (so the tab can list it before it starts). */
    @JvmStatic fun queued(suite: String, test: String): String = record("QUEUED", suite, test, 0, "")

    @JvmStatic fun started(suite: String, test: String): String = record("STARTED", suite, test, 0, "")

    @JvmStatic fun passed(suite: String, test: String, millis: Long): String = record("PASSED", suite, test, millis, "")

    /** [message] is shown under the test: the assertion, the stack trace. */
    @JvmStatic fun failed(suite: String, test: String, millis: Long, message: String): String =
        record("FAILED", suite, test, millis, message)

    @JvmStatic @JvmOverloads fun skipped(suite: String, test: String, reason: String = ""): String =
        record("SKIPPED", suite, test, 0, reason)

    /** Something outside any one test failed for [suite] (its setup, its file not compiling). */
    @JvmStatic fun suiteFailed(suite: String, message: String): String = record("CLASS_FAILED", suite, "", 0, message)

    /** The run is over. [noTests] says it found nothing to run, which the tab says rather than showing an empty list. */
    @JvmStatic @JvmOverloads fun finished(noTests: Boolean = false): String =
        record("FINISHED", "", "", 0, if (noTests) "no tests" else "")

    private fun record(event: String, suite: String, test: String, millis: Long, message: String): String =
        "$MARK$event ${suite.length}:$suite ${test.length}:$test $millis ${message.length}:$message\n"
}
