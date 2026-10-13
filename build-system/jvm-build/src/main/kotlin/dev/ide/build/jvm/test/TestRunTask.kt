package dev.ide.build.jvm.test

import dev.ide.build.AlwaysRun
import dev.ide.build.InterpretRunRequest
import dev.ide.build.ProgramInterpreter
import dev.ide.build.ProgramIo
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.testKotlinOutputDir
import dev.ide.build.engine.testOutputDir
import dev.ide.build.engine.testRunnerDir
import dev.ide.build.engine.testRuntimeClasspath
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path

/**
 * `test`: run a module's compiled tests. Discovers them from the test output ([TestDiscovery]), keeps the ones
 * [selector] names, generates the runner ([TestRunnerGenerator]) and interprets it on the bytecode VM, the same way
 * a console Run executes a program, so it behaves alike on the desktop and on device. Results reach [programIo] as
 * [TestProtocol] records interleaved with the tests' own output.
 *
 * The run interprets `kotlin/test/` from the project's classpath: kotlin-test lives under the bridged `kotlin/`
 * namespace but is not part of the IDE, so its assertions could not load otherwise.
 *
 * The defaults are a plain JVM module's paths; an Android module passes its variant's ([testDirs],
 * [runtimeClasspath], [runnerDir]).
 */
class TestRunTask(
    override val name: TaskName,
    private val module: Module,
    private val selector: TestSelector,
    private val interpreter: ProgramInterpreter,
    private val programIo: ProgramIo? = null,
    private val testDirs: () -> List<Path> = { listOf(testKotlinOutputDir(module), testOutputDir(module)) },
    private val runtimeClasspath: () -> List<Path> = { testRuntimeClasspath(module) },
    private val runnerDir: Path = testRunnerDir(module),
    /** Namespaces the run interprets from [runtimeClasspath] although the VM would bridge them to the host. */
    private val interpretPrefixes: List<String> = listOf("kotlin/test/"),
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val io = programIo ?: LineLog(ctx.logger())
        val classpath = runtimeClasspath()
        val dirs = testDirs().filter { Files.isDirectory(it) }
        val classes = TestDiscovery.discover(dirs, classpath)
            .filter(selector::matches)
            .map { c -> c.copy(tests = c.tests.filter { selector.matches(c, it) }.map { it.copy(only = selector.invocationsOf(c, it)) }) }
            .filter { it.tests.isNotEmpty() }
        if (classes.isEmpty()) {
            ctx.logger()("No tests found")
            io.started()
            io.stdout(TestProtocol.record(TestProtocol.FINISHED, message = NO_TESTS))
            io.exited(0)
            return TaskResult.Success
        }
        val count = classes.sumOf { it.tests.size }
        ctx.logger()("> Running $count test(s) in ${classes.size} class(es)")

        val mainFile = runnerDir.resolve(TestRunnerGenerator.MAIN_CLASS + ".class")
        Files.createDirectories(mainFile.parent)
        Files.write(mainFile, TestRunnerGenerator.generate(classes))

        val request = InterpretRunRequest(
            classpath = listOf(runnerDir) + classpath,
            mainClass = TestRunnerGenerator.MAIN_CLASS.replace('/', '.'),
            interpretPrefixes = interpretPrefixes,
        )
        val code = interpreter.run(request, io)
        return if (code == 0) TaskResult.Success else TaskResult.Failed("Some tests failed")
    }

    companion object {
        /** The FINISHED record's message when nothing matched, so a results view can say so. */
        const val NO_TESTS = "no tests"
    }

    /** Output into the build log, a line at a time, when no console is attached (a headless test run). */
    private class LineLog(private val log: (String) -> Unit) : ProgramIo {
        private val pending = StringBuilder()
        override val stdin: java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

        @Synchronized
        override fun stdout(text: String) {
            pending.append(text)
            var nl = pending.indexOf("\n")
            while (nl >= 0) {
                log(pending.substring(0, nl).trimEnd('\r'))
                pending.delete(0, nl + 1)
                nl = pending.indexOf("\n")
            }
        }

        @Synchronized
        override fun exited(code: Int) {
            if (pending.isNotEmpty()) log(pending.toString())
            pending.setLength(0)
        }
    }
}
