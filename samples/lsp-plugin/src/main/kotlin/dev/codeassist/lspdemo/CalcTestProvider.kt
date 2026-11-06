package dev.codeassist.lspdemo

import dev.ide.build.AlwaysRun
import dev.ide.build.BuildContext
import dev.ide.build.RunAction
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskGraph
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
import java.io.File

/**
 * Calc's tests: every `test NAME: EXPR is EXPECTED` line is one, and the file is their group. The gutter marks
 * each, the Run picker offers "Calc tests" for a module holding any, and a run evaluates them and reports
 * through [TestReport], so the results fill the Run screen's Tests tab like JUnit's.
 *
 * A suite is the file's path inside its module in dotted form, without the extension
 * (`src.main.java.com.example.area_test`): the Tests tab shows a suite as a class, the part after the last dot
 * as its name. A target is a suite (all its tests) or `suite#name` (one), which is also what a re-run sends back.
 */
object CalcTestProvider : TestProvider {
    override val id = "calcTest"
    override val displayName = "Calc tests"
    override val languages = setOf(LanguageId(CalcPlugin.LANGUAGE))

    override fun appliesTo(module: Module): Boolean = calcFiles(module).any { "test " in it.readText() }

    override fun tests(module: Module, path: String, text: String): List<TestDeclaration> {
        val a = CalcAnalysis(text)
        if (a.tests.isEmpty()) return emptyList()
        val offsets = lineOffsets(text)
        val suite = suiteOf(module, File(path))
        return listOf(TestDeclaration(File(path).name, 0, suite, group = true)) +
            a.tests.map { TestDeclaration(it.name, offsets[it.span.line] + it.span.start, "$suite#${it.name}") }
    }

    override fun run(module: Module, targets: List<String>, project: Project, ctx: BuildContext): RunAction {
        val console = RunConsole("Calc tests · ${module.name}")
        val task = RunCalcTests(TaskName(":${module.name}:calcTest"), module, targets, console)
        return RunAction("> Calc tests ${module.name}", OneTask(task)).also { it.console = console }
    }

    /** [file]'s suite name: its path under [module] in dotted form, without `.calc`. */
    internal fun suiteOf(module: Module, file: File): String {
        val base = File(module.dir.path).absoluteFile
        val relative = runCatching { file.absoluteFile.relativeTo(base).path }.getOrDefault(file.name)
        return relative.removeSuffix(CalcPlugin.SUFFIX).replace(File.separatorChar, '.').replace('/', '.')
    }

    internal fun calcFiles(module: Module): List<File> =
        File(module.dir.path).walkTopDown().filter { it.isFile && it.name.endsWith(CalcPlugin.SUFFIX) && "/build/" !in it.path }.toList()

    private fun lineOffsets(text: String): IntArray {
        val out = ArrayList<Int>().apply { add(0) }
        text.forEachIndexed { i, c -> if (c == '\n') out += i + 1 }
        return out.toIntArray()
    }
}

private class OneTask(task: Task) : TaskGraph {
    override val tasks: List<Task> = listOf(task)
    override fun dependencies(t: Task): List<Task> = emptyList()
    override fun topologicalLevels(): List<List<Task>> = listOf(tasks)
}

/** Evaluates the targeted tests and prints a [TestReport] record for each into the Run console. */
private class RunCalcTests(
    override val name: TaskName,
    private val module: Module,
    private val targets: List<String>,
    private val console: RunConsole,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val io = console.io
        val out: (String) -> Unit = io?.let { it::stdout } ?: { line -> ctx.logger()(line.trimEnd()) }
        io?.started()
        out(TestReport.run(CalcTestProvider.id))
        // Which tests of which file: a target is `path` or `path#name`; nothing means every Calc file.
        var failures = 0
        var ran = 0
        val files = CalcTestProvider.calcFiles(module)
        val wanted = LinkedHashMap<File, MutableSet<String>?>()
        if (targets.isEmpty()) files.forEach { wanted[it] = null }
        for (t in targets) {
            val suite = t.substringBefore('#')
            val file = files.firstOrNull { CalcTestProvider.suiteOf(module, it) == suite }
            if (file == null) {
                out(TestReport.suiteFailed(suite, "No Calc file in ${module.name} is $suite"))
                failures++
                continue
            }
            val name = t.substringAfter('#', "")
            if (name.isEmpty()) wanted[file] = null
            else if (!wanted.containsKey(file) || wanted[file] != null) wanted.getOrPut(file) { LinkedHashSet() }!!.add(name)
        }
        for ((file, names) in wanted) {
            val suite = CalcTestProvider.suiteOf(module, file)
            val text = runCatching { file.readText() }.getOrElse {
                out(TestReport.suiteFailed(suite, "Cannot read ${file.path}: ${it.message}"))
                failures++
                continue
            }
            val analysis = CalcAnalysis(text)
            val tests = analysis.tests.filter { names == null || it.name in names }
            tests.forEach { out(TestReport.queued(suite, it.name)) }
            for (test in tests) {
                out(TestReport.started(suite, test.name))
                val started = System.nanoTime()
                val ms = (System.nanoTime() - started) / 1_000_000
                ran++
                if (test.passed) {
                    out("${test.name}: ${formatValue(test.actual!!)} ✓\n")
                    out(TestReport.passed(suite, test.name, ms))
                } else {
                    failures++
                    val why = when {
                        test.actual == null -> "its expression has an error (line ${test.span.line + 1})"
                        test.expected == null -> "its expected value has an error (line ${test.span.line + 1})"
                        else -> "expected ${formatValue(test.expected)} but was ${formatValue(test.actual)}"
                    }
                    out(TestReport.failed(suite, test.name, ms, why))
                }
            }
        }
        out(TestReport.finished(noTests = ran == 0))
        io?.exited(if (failures == 0) 0 else 1)
        return if (failures == 0) TaskResult.Success else TaskResult.Failed("$failures Calc test(s) failed")
    }
}
