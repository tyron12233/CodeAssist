package dev.codeassist.csharp.build.tests

import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.build.tasks.CsCompileTask
import dev.codeassist.csharp.build.tasks.LinearGraph
import dev.codeassist.csharp.editor.CsModules
import dev.codeassist.csharp.project.CsModuleType
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojGraph
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.Utf8Chunks
import dev.codeassist.csharp.util.killableTool
import dev.ide.build.AlwaysRun
import dev.ide.build.BuildContext
import dev.ide.build.RunAction
import dev.ide.build.RunConsole
import dev.ide.build.TEST_PROVIDER_EP
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
import dev.ide.plugin.PluginRegistration
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tests for C# projects that reference xUnit, NUnit or MSTest: run marks beside test methods and classes, a
 * row in the Run picker, and results in the Run screen's Tests tab.
 *
 * A run compiles the project as a library, whatever its output type, and hands the DLL to [CsTestRunner].
 */
internal class CsTestProvider(private val sdk: DotnetSdk) : TestProvider {

    override val id = ID
    override val displayName = "C# tests"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    override fun appliesTo(module: Module): Boolean {
        if (module.type.id != CsModuleType.ID) return false
        return usesTestFramework(Csproj.load(Paths.get(module.dir.path)))
    }

    override fun tests(module: Module, path: String, text: String): List<TestDeclaration> {
        if (!path.endsWith(".cs", ignoreCase = true) || !CsTestScan.mayHoldTests(text)) return emptyList()
        val file = runCatching { Paths.get(path) }.getOrNull() ?: return emptyList()
        val dir = CsprojGraph.owningDir(file) ?: Paths.get(module.dir.path)
        if (!usesTestFramework(Csproj.load(dir))) return emptyList()

        CsModules.remember(module)
        val found = CsTestScan.scan(text)
        return found.suites.map { TestDeclaration(it.name, it.offset, it.fullName, group = true) } +
            found.tests.map { TestDeclaration(it.name, it.offset, "${it.suite}#${it.name}") }
    }

    override fun run(module: Module, targets: List<String>, project: Project, ctx: BuildContext): RunAction? {
        val dir = Paths.get(module.dir.path)
        val info = Csproj.load(dir) ?: return null
        val dll = Csproj.outputDir(dir, info).resolve(Csproj.assemblyName(info, module.name) + ".dll")

        val console = RunConsole("C# tests · ${module.name}")
        val tasks = listOf(
            CsCompileTask(TaskName(":${module.name}:compileCsTests"), dir, dll, sdk, console, asLibrary = true),
            CsRunTestsTask(TaskName(":${module.name}:testCs"), dir, dll, targets, sdk, console),
        )
        return RunAction("> C# tests ${module.name}", LinearGraph(tasks)).also { it.console = console }
    }

    companion object {
        const val ID = "csTest"

        /** Package ids that make a project a test project, lowercase. A prefix matches the framework's family. */
        private val FRAMEWORKS = listOf("xunit", "nunit", "mstest")

        fun usesTestFramework(info: CsprojInfo?): Boolean =
            info?.packageReferences?.any { reference ->
                val id = reference.id.lowercase()
                FRAMEWORKS.any { id == it || id.startsWith("$it.") }
            } == true
    }
}

/**
 * Registers [CsTestProvider]. A class of its own so that a host without the extension point (before
 * SPI 3.3.0) fails to link here, where the caller guards it, and not in the plugin's entry point.
 */
internal object CsTestRegistration {
    fun register(reg: PluginRegistration, sdk: DotnetSdk) {
        reg.register(TEST_PROVIDER_EP, CsTestProvider(sdk))
    }
}

/**
 * Runs the tests in [dll] with [CsTestRunner] and passes what it prints to the Run console, where the
 * records it writes become the Tests tab.
 */
internal class CsRunTestsTask(
    override val name: TaskName,
    private val dir: Path,
    private val dll: Path,
    private val targets: List<String>,
    private val sdk: DotnetSdk,
    private val console: RunConsole,
) : Task, AlwaysRun {

    override val inputs: TaskInputs
        get() = TaskInputsImpl()

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val launcher = sdk.launcher ?: return TaskResult.Failed(DotnetSdk.NO_DOTNET)
        val io = console.io
        val write: (String) -> Unit = { text -> io?.stdout(text) ?: ctx.logger()(text.trimEnd('\n')) }

        val code = killableTool { cancellation ->
            val runner = CsTestRunner.ensure(sdk, cancellation)
            if (runner == null) {
                // The Tests tab needs a record to say anything; the reason goes with it.
                write(TestReport.run(CsTestProvider.ID))
                write(TestReport.suiteFailed(dll.fileName.toString(), "The test runner could not be built."))
                write(TestReport.finished())
                return@killableTool RUNNER_MISSING
            }

            val command = listOf(launcher.toString(), runner.toString(), dll.toString(), CsTestProvider.ID) + targets
            val process = ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(sdk.environment()) }
                .start()

            cancellation.attach(process)
            io?.started()
            runCatching { process.outputStream.close() }

            val decoder = Utf8Chunks()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = process.inputStream.read(buffer)
                    if (read < 0) break
                    decoder.decode(buffer, read).takeIf { it.isNotEmpty() }?.let(write)
                }
            } catch (e: IOException) {
                // Stop closes the stream. Treat the resulting read failure as expected cancellation.
                if (!cancellation.isCancelled) throw e
            }
            decoder.finish().takeIf { it.isNotEmpty() }?.let(write)
            process.waitFor()
        }

        io?.exited(code)
        return when (code) {
            0 -> TaskResult.Success
            RUNNER_MISSING -> TaskResult.Failed("the C# test runner could not be built")
            else -> TaskResult.Failed("C# tests failed")
        }
    }

    private companion object {
        const val RUNNER_MISSING = 120
    }
}
