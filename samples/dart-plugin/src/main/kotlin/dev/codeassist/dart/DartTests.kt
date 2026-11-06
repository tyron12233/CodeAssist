package dev.codeassist.dart

import dev.ide.build.AlwaysRun
import dev.ide.build.BuildContext
import dev.ide.build.RunAction
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.TestDeclaration
import dev.ide.build.TestProvider
import dev.ide.build.TestReport
import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.model.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Dart tests (package:test) in the gutter, the Run picker and the Run screen's Tests tab.
 *
 * `dart test` cannot run from an installed app (it looks for its compilers beside the runtime's real path),
 * so a run is the plugin's own: one generated entry point imports every test file it runs and hands each
 * file's `main` to package:test's engine through `directRunTests`, the entry point test_core keeps for other
 * runners, with the JSON reporter `dart test --reporter json` uses. That is compiled to kernel and run on the
 * VM like a program, and its events become [TestReport] records.
 *
 * A suite is a test file's path in the package, dotted and without `.dart` (`test.parser_test`); a target is a
 * suite, or `suite#name` for one test or group by its full name (group names and test name, space separated),
 * which is what package:test calls a test and what a re-run sends back.
 */
class DartTestProvider(private val sdk: DartSdk, private val pub: Pub) : TestProvider {
    override val id = ID
    override val displayName = "Dart tests"
    override val languages = setOf(LanguageId(DartPlugin.LANGUAGE))

    // A Flutter package's tests are widget tests (flutter_test), which need Flutter's host test engine: not here.
    override fun appliesTo(module: Module): Boolean =
        module.type.id == DartModuleType.ID && testFiles(Paths.get(module.dir.path)).isNotEmpty() &&
            !FlutterSdk.isFlutterPackage(Paths.get(module.dir.path))

    override fun tests(module: Module, path: String, text: String): List<TestDeclaration> {
        if (!path.endsWith(TEST_SUFFIX)) return emptyList()
        val pkg = Paths.get(module.dir.path)
        val file = Paths.get(path)
        if (!file.startsWith(pkg.resolve("test")) || FlutterSdk.isFlutterPackage(pkg)) return emptyList()
        val calls = DartTestScanner.scan(text)
        if (calls.isEmpty()) return emptyList()
        val suite = suiteOf(pkg, file)
        return listOf(TestDeclaration(file.fileName.toString(), mainOffset(text), suite, group = true)) +
            calls.map { TestDeclaration(it.fullName, it.offset, "$suite#${it.fullName}", group = it.group) }
    }

    override fun run(module: Module, targets: List<String>, project: Project, ctx: BuildContext): RunAction {
        val console = RunConsole("Dart tests · ${module.name}")
        val task = RunDartTests(TaskName(":${module.name}:dartTest"), Paths.get(module.dir.path), targets, sdk, pub, console)
        return RunAction("> Dart tests ${module.name}", LinearGraph(listOf(task))).also { it.console = console }
    }

    companion object {
        const val ID = "dartTest"
        const val TEST_SUFFIX = "_test.dart"

        /** The test files `dart test` would run: every `*_test.dart` under `test/`, sorted. */
        fun testFiles(pkg: Path): List<Path> {
            val dir = pkg.resolve("test")
            if (!Files.isDirectory(dir)) return emptyList()
            return Files.walk(dir).use { w ->
                w.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(TEST_SUFFIX) }.collect(Collectors.toList())
            }.sorted()
        }

        /** Where the file's own mark goes: its `main`, else the top. Never the blank line above `main`. */
        fun mainOffset(text: String): Int =
            Regex("""(?m)^[ \t]*(?:void\s+|Future<void>\s+)?main\s*\(""").find(text)?.range?.first ?: 0

        /** [file]'s suite name: its path in the package, dotted, without `.dart`. */
        fun suiteOf(pkg: Path, file: Path): String =
            pkg.relativize(file).joinToString(".") { it.toString() }.removeSuffix(".dart")
    }
}

/**
 * Finds the `test(...)` and `group(...)` calls in a test file and their full names, for the gutter.
 *
 * Not a parser: a scan that skips comments and strings, matches each call's parentheses to know which tests a
 * group encloses, and takes a name only when it is a plain string literal. A name built at runtime
 * (`test('adds $x', ...)`), or anything inside a group so named, gets no mark rather than a wrong one.
 */
object DartTestScanner {
    class Call(val fullName: String, val offset: Int, val group: Boolean)

    private class Raw(val name: String?, val offset: Int, val end: Int, val group: Boolean)

    fun scan(text: String): List<Call> {
        val raws = ArrayList<Raw>()
        var i = 0
        while (i < text.length) {
            val skipped = DartLex.skipNonCode(text, i)
            if (skipped != i) { i = skipped; continue }
            val c = text[i]
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                val prev = text.substring(0, i).trimEnd().lastOrNull()
                if ((word == "test" || word == "group") && prev != '.') {
                    var k = j
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (k < text.length && text[k] == '(') {
                        val end = DartLex.matchParen(text, k)
                        raws += Raw(literalAt(text, k + 1), i, end, word == "group")
                    }
                }
                i = j
                continue
            }
            i++
        }
        val groups = raws.filter { it.group }
        return raws.mapNotNull { call ->
            val enclosing = groups.filter { it !== call && it.offset < call.offset && call.offset < it.end }.sortedBy { it.offset }
            if (call.name == null || enclosing.any { it.name == null }) return@mapNotNull null
            Call((enclosing.map { it.name!! } + call.name).joinToString(" "), call.offset, call.group)
        }
    }

    /** The value of the plain string literal at the start of [from] (after whitespace), or null. */
    private fun literalAt(text: String, from: Int): String? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        val raw = i < text.length && text[i] == 'r'
        val q = if (raw) i + 1 else i
        if (q >= text.length || (text[q] != '\'' && text[q] != '"')) return null
        val quote = text[q]
        if (text.startsWith("$quote$quote$quote", q)) return null
        val sb = StringBuilder()
        var j = q + 1
        while (j < text.length && text[j] != quote) {
            val c = text[j]
            if (c == '\n') return null
            if (!raw && c == '$') return null // interpolated: only known at runtime
            if (!raw && c == '\\' && j + 1 < text.length) {
                val e = text[j + 1]
                sb.append(when (e) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                j += 2
                continue
            }
            sb.append(c)
            j++
        }
        return sb.toString()
    }
}

/** The generated entry point that runs test files through package:test without `dart test`. */
object DartTestRunnerSource {
    /**
     * Dart source importing each of [suites] (suite name to the file's URI relative to the runner) and running
     * them in order. Its arguments are targets (`suite` or `suite#name`); none runs everything.
     */
    fun generate(suites: Map<String, String>): String = buildString {
        appendLine("// Generated by CodeAssist to run tests without `dart test`, which cannot run on Android.")
        appendLine("import 'dart:convert';")
        appendLine("import 'dart:io';")
        appendLine()
        appendLine("import 'package:test_core/src/direct_run.dart';")
        appendLine("import 'package:test_core/src/runner/reporter/json.dart';")
        appendLine()
        suites.values.forEachIndexed { i, uri -> appendLine("import ${dartString(uri)} as s$i;") }
        appendLine()
        appendLine("final suites = <String, void Function()>{")
        suites.keys.forEachIndexed { i, suite -> appendLine("  ${dartString(suite)}: () => s$i.main(),") }
        appendLine("};")
        append(
            """

            Future<void> main(List<String> args) async {
              var ok = true;
              for (final entry in suites.entries) {
                final wanted = [for (final a in args) if (a == entry.key || a.startsWith('${'$'}{entry.key}#')) a];
                if (args.isNotEmpty && wanted.isEmpty) continue;
                stdout.writeln(jsonEncode({'type': 'codeassist.suite', 'suite': entry.key}));
                reporter(e) => JsonReporter.watch(e, stdout, isDebugRun: false);
                try {
                  if (wanted.isEmpty || wanted.contains(entry.key)) {
                    ok = await directRunTests(entry.value, reporterFactory: reporter) && ok;
                  } else {
                    final names = await enumerateTestCases(entry.value);
                    final only = [for (final w in wanted) w.substring(entry.key.length + 1)];
                    for (final name in names.where((n) => only.any((o) => n == o || n.startsWith('${'$'}o ')))) {
                      ok = await directRunSingleTest(entry.value, name, reporterFactory: reporter) && ok;
                    }
                  }
                } catch (e, st) {
                  ok = false;
                  stdout.writeln(jsonEncode({'type': 'codeassist.error', 'error': '${'$'}e', 'stackTrace': '${'$'}st'}));
                }
              }
              exitCode = ok ? 0 : 1;
            }
            """.trimIndent(),
        )
        appendLine()
    }

    private fun dartString(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$") + "'"
}

/**
 * Turns the runner's output into [TestReport] records for the Tests tab, and everything else (a test's
 * prints, anything that is not an event) into console text. One instance per run.
 */
class DartTestEvents(private val out: (String) -> Unit) {
    var ran = 0
        private set
    var failed = 0
        private set

    private var suite = ""
    private val names = HashMap<Int, String>()
    private val startedAt = HashMap<Int, Long>()
    private val skipReasons = HashMap<Int, String>()
    private val errors = HashMap<Int, StringBuilder>()

    fun line(text: String) {
        val event = if (text.startsWith("{")) runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() else null
        val type = event?.string("type")
        if (event == null || type == null) {
            out(text + "\n")
            return
        }
        when (type) {
            "codeassist.suite" -> suite = event.string("suite").orEmpty()
            "codeassist.error" -> {
                failed++
                out(TestReport.suiteFailed(suite, listOfNotNull(event.string("error"), event.string("stackTrace")).joinToString("\n")))
            }
            // Each engine numbers its tests from scratch, and a filtered run starts one engine per test.
            "start" -> { names.clear(); startedAt.clear(); skipReasons.clear(); errors.clear() }
            "testStart" -> {
                val test = event["test"] as? JsonObject ?: return
                val id = test.int("id") ?: return
                val name = test.string("name") ?: return
                names[id] = name
                startedAt[id] = event.long("time") ?: 0
                (test["metadata"] as? JsonObject)?.string("skipReason")?.let { skipReasons[id] = it }
                out(TestReport.started(suite, name))
            }
            "print" -> event.string("message")?.let { out(it + "\n") }
            "error" -> {
                val id = event.int("testID")
                val message = listOfNotNull(event.string("error"), event.string("stackTrace")).joinToString("\n")
                if (id == null || id !in names) {
                    failed++
                    out(TestReport.suiteFailed(suite, message))
                } else {
                    errors.getOrPut(id) { StringBuilder() }.apply { if (isNotEmpty()) append("\n") }.append(message)
                }
            }
            "testDone" -> {
                val id = event.int("testID") ?: return
                val name = names[id] ?: return
                if (event.bool("hidden") == true) return
                val millis = (event.long("time") ?: 0) - (startedAt[id] ?: 0)
                when {
                    event.bool("skipped") == true -> out(TestReport.skipped(suite, name, skipReasons[id].orEmpty()))
                    event.string("result") == "success" -> { ran++; out(TestReport.passed(suite, name, millis)) }
                    else -> {
                        ran++
                        failed++
                        out(TestReport.failed(suite, name, millis, errors[id]?.toString() ?: "Failed (${event.string("result")})"))
                    }
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/** One test run: pub get if needed, generate and compile the runner, run it, report. */
private class RunDartTests(
    override val name: TaskName,
    private val pkg: Path,
    private val targets: List<String>,
    private val sdk: DartSdk,
    private val pub: Pub,
    private val console: RunConsole,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val io = console.io
        val out: (String) -> Unit = io?.let { it::stdout } ?: { text -> ctx.logger()(text.trimEnd()) }
        val outLine: (String) -> Unit = { out(it + "\n") }
        io?.started()
        out(TestReport.run(DartTestProvider.ID))
        val code = runTests(out, outLine)
        io?.exited(code)
        return if (code == 0) TaskResult.Success else TaskResult.Failed("Dart tests failed")
    }

    private suspend fun runTests(out: (String) -> Unit, outLine: (String) -> Unit): Int {
        fun failAll(suites: Collection<String>, message: String): Int {
            outLine(message)
            suites.forEach { out(TestReport.suiteFailed(it, message)) }
            out(TestReport.finished())
            return 1
        }

        val status = sdk.prepare()
        if (status is DartSdk.Status.Unavailable) return failAll(emptyList(), status.reason)

        val files = DartTestProvider.testFiles(pkg).associateBy { DartTestProvider.suiteOf(pkg, it) }
        val wantedSuites = if (targets.isEmpty()) files.keys.toList() else targets.map { it.substringBefore('#') }.distinct()
        val missing = wantedSuites.filter { it !in files }
        missing.forEach { out(TestReport.suiteFailed(it, "No test file in this package is $it")) }
        val suites = wantedSuites.filter { it in files }
        if (suites.isEmpty()) {
            out(TestReport.finished(noTests = missing.isEmpty()))
            return if (missing.isEmpty()) 0 else 1
        }

        if (DartPackages.needsPubGet(pkg)) {
            outLine("> dart pub get")
            val got = killableTool { c -> pub.run(pkg, "get", cancellation = c, out = outLine) }
            if (got != 0) return failAll(suites, "pub get failed (exit code $got)")
        }
        val config = pkg.resolve(DartPackages.PACKAGE_CONFIG)
        if (!String(Files.readAllBytes(config)).contains("\"test_core\"")) {
            return failAll(suites, "This package does not depend on package:test. Add it under dev_dependencies in pubspec.yaml (test: ^1.25.0), then run pub get.")
        }

        val dir = pkg.resolve(".dart_tool/codeassist/test")
        Files.createDirectories(dir)
        val runner = dir.resolve("runner.dart")
        val imports = suites.associateWith { suite -> dir.relativize(files.getValue(suite)).joinToString("/") { it.toString() } }
        Files.write(runner, DartTestRunnerSource.generate(imports).toByteArray())
        val dill = dir.resolve("runner.dill")
        val compileOutput = StringBuilder()
        val compiled = killableTool { c -> compileKernel(sdk, pkg, runner, dill, c) { compileOutput.appendLine(it) } }
        if (compiled != 0 || !Files.isRegularFile(dill)) {
            out(compileOutput.toString())
            return failAll(suites, "The tests did not compile:\n$compileOutput".trimEnd())
        }

        val vm = sdk.vm ?: return failAll(suites, DartSdk.NO_SDK)
        val events = DartTestEvents(out)
        val exit = killableTool { cancellation ->
            val process = ProcessBuilder(listOf(vm.toString(), dill.toString()) + targets)
                .directory(pkg.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(sdk.environment()) }
                .start()
            cancellation.attach(process)
            runCatching { process.outputStream.close() }
            try {
                process.inputStream.bufferedReader().forEachLine(events::line)
            } catch (e: IOException) {
                if (!cancellation.isCancelled) throw e
            }
            process.waitFor()
        }
        out(TestReport.finished(noTests = events.ran == 0 && events.failed == 0))
        return if (exit == 0 && events.failed == 0 && missing.isEmpty()) 0 else 1
    }
}
