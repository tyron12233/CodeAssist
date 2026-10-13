package dev.ide.build.jvm.test

import dev.ide.build.KotlinCompilerPlugin
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import dev.ide.build.engine.kotlinOutputDir
import dev.ide.build.engine.levelOf
import dev.ide.build.engine.testCompileClasspath
import dev.ide.build.engine.testJavaSourceFiles
import dev.ide.build.engine.testKotlinOutputDir
import dev.ide.build.engine.testKotlinSourceFiles
import dev.ide.build.engine.testOutputDir
import dev.ide.build.resolveFor
import dev.ide.lang.jdt.build.javaFailureSummary
import dev.ide.lang.jdt.build.reportJavaProblems
import dev.ide.lang.jdt.compile.IncrementalJavaCompiler
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.lang.kotlin.build.kotlinFailureSummary
import dev.ide.lang.kotlin.build.reportKotlinProblems
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path

/**
 * `compileTestKotlin`: K2 over a module's test sources into `test-kotlin-classes`, against the main output and the
 * test classpath. The main Kotlin output is a FRIEND path, since tests are part of the module as far as Kotlin is
 * concerned and may use its `internal` declarations. The test Java sources are resolution inputs, as in the main
 * compile.
 *
 * The defaults are a plain JVM module's paths; an Android module passes its variant's ([testClasspath], [out],
 * [friends]).
 */
class TestKotlinCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val bootClasspath: List<Path>,
    private val compiler: IncrementalKotlinCompiler,
    private val plugins: List<KotlinCompilerPlugin>,
    private val testClasspath: () -> List<Path> = { testCompileClasspath(module) },
    private val out: Path = testKotlinOutputDir(module),
    private val friends: () -> List<Path> = { listOf(kotlinOutputDir(module)) },
    private val kotlinSources: () -> List<Path> = { testKotlinSourceFiles(module) },
    private val javaSources: () -> List<Path> = { testJavaSourceFiles(module) },
) : Task {
    private fun classpath(): List<Path> = testClasspath().filter { Files.exists(it) }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("kotlinSources", kotlinSources())
            filePaths("javaSources", javaSources())
            dirPaths("classpathDirs", classpath().filter { Files.isDirectory(it) })
            filePaths("classpathJars", classpath().filter { Files.isRegularFile(it) })
            property("level", levelOf(module.languageLevel))
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("classes", out) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(out)
        val kt = kotlinSources()
        if (kt.isEmpty()) return TaskResult.Success
        val classpath = classpath()
        val resolved = plugins.resolveFor(module, classpath + bootClasspath)
        val r = compiler.compile(
            kt, javaSources(), classpath, out, levelOf(module.languageLevel),
            bootClasspath = bootClasspath,
            compilerPlugins = resolved.classpaths, pluginOptions = resolved.options,
            runtimePluginClasspaths = resolved.runtimeClasspaths,
            friendPaths = friends().filter { Files.isDirectory(it) },
        )
        ctx.reportKotlinProblems(r)
        return if (r.success) TaskResult.Success else TaskResult.Failed(kotlinFailureSummary(r))
    }
}

/** `compileTestJava`: ecj over a module's test `.java` into `test-classes`, seeing the Kotlin test output. The
 *  defaults are a plain JVM module's paths, as for [TestKotlinCompileTask]. */
class TestJavaCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val bootClasspath: List<Path>,
    private val testClasspath: () -> List<Path> = { testCompileClasspath(module) + testKotlinOutputDir(module) },
    private val out: Path = testOutputDir(module),
    private val sources: () -> List<Path> = { testJavaSourceFiles(module) },
) : Task {
    private fun classpath(): List<Path> = testClasspath().filter { Files.exists(it) }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("sources", sources())
            dirPaths("classpathDirs", classpath().filter { Files.isDirectory(it) })
            filePaths("classpathJars", classpath().filter { Files.isRegularFile(it) })
            property("level", levelOf(module.languageLevel))
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("classes", out) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val sources = sources()
        Files.createDirectories(out)
        if (sources.isEmpty()) return TaskResult.Success
        val r = IncrementalJavaCompiler().compile(sources, classpath(), out, levelOf(module.languageLevel), bootClasspath = bootClasspath)
        ctx.debug("${name.value}: ${r.mode.name.lowercase()} compile of ${r.recompiledSources.size} source(s)")
        val result = JdtBatchCompiler.Result(r.success, r.messages, r.diagnostics)
        ctx.reportJavaProblems(result)
        return if (r.success) TaskResult.Success else TaskResult.Failed(javaFailureSummary(result))
    }
}
