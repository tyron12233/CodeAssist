package dev.codeassist.ndk

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildGoal
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticLocation
import dev.ide.build.Lifecycle
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.Module
import dev.ide.platform.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension

/**
 * Compiles a module's C and C++ into the APK, as part of the ordinary build.
 *
 * One task per module that carries an [NdkFacet], wired ahead of the Android packaging merge. There is no
 * second build system involved: the facet says what to build and this calls clang directly, which is what
 * makes a native build possible on a device that has room for a compiler but not for CMake and ninja too.
 */
class NdkBuildPlugin(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : BuildPlugin {

    override val id = "ndk"

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.request.goal != BuildGoal.CLEAN &&
            config.project.modules.any { it.facets.get(NdkFacet.KEY) != null }

    override fun apply(config: BuildConfiguration) {
        // Declare the C/C++ directories to the model, here, because this is the earliest place the plugin is
        // handed real `Module`s and therefore the only place it can reach the WORKSPACE-scoped service that
        // does it. A project made from the template arrives with the root already declared and skips this.
        runCatching { NdkSourceRoots.ensureFor(config.project.modules, log) }
            .onFailure { log.warn("could not declare the native source roots", it) }

        val variant = config.request.variant.name
        for (module in config.project.modules) {
            val facet = module.facets.get(NdkFacet.KEY) ?: continue
            val taskName = TaskName(":${module.name}:compileNative")
            val buildDir = config.env.buildDir(module)

            config.tasks.register(taskName) {
                NdkCompileTask(taskName, module, facet, buildDir, toolchain, log)
            }

            // Where to hang it. Configuring a task no build system registered is silently ignored, so naming
            // all of these is how one plugin serves every pipeline without probing which is running.
            //
            // The Android one is the load-bearing edge: `mergeNativeLibs` collects the module's jniLibs
            // directories, and the `.so` has to be on disk before it looks. The assemble aggregates are the
            // backstop for a pipeline that has no such merge.
            config.tasks.named(TaskName(":${module.name}:mergeNativeLibs${variant.cap()}"))
                .configure { dependsOn(taskName) }
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(taskName) }
            config.tasks.named(Lifecycle.assemble(module.name, variant)).configure { dependsOn(taskName) }
        }
    }

    private fun String.cap(): String = replaceFirstChar { it.uppercase() }
}

/**
 * Compiles every source the facet names and links them into one shared library.
 *
 * The output goes into the module's own `src/main/jniLibs/<abi>/`, which is not where generated files would
 * ideally live but is the only place the Android packaging merge looks: it collects the module's declared
 * `jniLibs` content roots, and those are fixed by the module type rather than something a plugin can add to.
 * A build directory would be tidier and would simply not be packaged.
 */
internal class NdkCompileTask(
    override val name: TaskName,
    private val module: Module,
    private val facet: NdkFacet,
    private val buildDir: Path,
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : Task {

    private val moduleDir: Path get() = Paths.get(module.dir.path)

    /** Every C and C++ file under the facet's source directories. */
    private fun sources(): List<Path> = facet.sourceDirs
        .map { moduleDir.resolve(it) }
        .filter { Files.isDirectory(it) }
        .flatMap { dir ->
            Files.walk(dir).use { walk ->
                walk.filter { Files.isRegularFile(it) && it.extension.lowercase() in SOURCE_EXTENSIONS }
                    .toList()
            }
        }
        .sorted()

    private fun outputFor(abi: String): Path =
        moduleDir.resolve("src/main/jniLibs").resolve(abi).resolve("lib${facet.libraryName}.so")

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            sources().forEach { property("src:$it", runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull()) }
            // The configuration is an input too: changing the C++ standard or the optimization level has to
            // rebuild even though not a byte of source moved.
            property("facet", NdkFacetCodec.encode(facet).toString())
        }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            facet.abis.forEach { abi -> filePath("so:$abi", outputFor(abi)) }
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val toolchain = toolchain() ?: return skip(ctx, "the NDK plugin has no toolchain on this device")
        val sources = sources()
        if (sources.isEmpty()) {
            // Not a failure: a module can carry the facet before anyone has written any C++ into it.
            ctx.logger()("ndk: no C or C++ sources under ${facet.sourceDirs.joinToString()} — nothing to build")
            return TaskResult.Success
        }

        when (val status = toolchain.prepare()) {
            is NdkToolchain.Status.Unavailable -> return skip(ctx, status.reason)
            is NdkToolchain.Status.Ready -> ctx.logger()("ndk: ${status.version}")
        }

        // The toolchain ships one backend, so only the ABI it can target is built. Saying so beats emitting
        // nothing for the others and leaving the user to wonder why the APK has one lib directory.
        val buildable = facet.abis.filter { it == SUPPORTED_ABI }
        (facet.abis - buildable.toSet()).forEach {
            ctx.diagnostics.report(
                BuildDiagnostic(
                    severity = BuildSeverity.WARNING,
                    message = "ndk: skipping ABI '$it' — the bundled toolchain only targets $SUPPORTED_ABI",
                    source = "ndk",
                    location = DiagnosticLocation(module.dir.path),
                )
            )
        }
        if (buildable.isEmpty()) return skip(ctx, "no ABI this toolchain can target")

        for (abi in buildable) {
            ctx.checkCanceled()
            val result = buildAbi(ctx, toolchain, sources, abi)
            if (result != null) return result
        }
        return TaskResult.Success
    }

    /** Compile and link one ABI; null on success, a failure result otherwise. */
    private fun buildAbi(
        ctx: TaskContext,
        toolchain: NdkToolchain,
        sources: List<Path>,
        abi: String,
    ): TaskResult? {
        val objDir = buildDir.resolve("ndk").resolve(abi)
        val objects = ArrayList<Path>()

        // The glue is compiled into the app rather than linked: the NDK ships it as source for exactly that.
        val glue = if (facet.nativeActivity) toolchain.nativeAppGlueDir?.resolve("android_native_app_glue.c") else null
        val all = sources + listOfNotNull(glue?.takeIf { Files.isRegularFile(it) })

        for ((index, source) in all.withIndex()) {
            ctx.checkCanceled()
            // A real fraction rather than indeterminate: a native build is the slow part of an Android build
            // on a phone, and a bar that moves is the difference between waiting and wondering.
            ctx.progress.report(index.toDouble() / all.size, "Compiling ${source.fileName}")
            val obj = objDir.resolve(source.fileName.toString() + ".o")
            val isCpp = source.extension.lowercase() != "c"
            val compiled = toolchain.compile(source, obj, cpp = isCpp, extraFlags = NdkFlags.build(facet, toolchain, isCpp))
            report(ctx, compiled.output, source)
            if (!compiled.ok) {
                ctx.logger()("ndk: failed to compile ${source.fileName}")
                return TaskResult.Failed("ndk: ${source.fileName} did not compile")
            }
            objects.add(obj)
        }

        ctx.progress.report(1.0, "Linking lib${facet.libraryName}.so")
        val out = outputFor(abi)
        val linked = toolchain.linkShared(objects, out, facet.linkLibraries)
        report(ctx, linked.output, null)
        if (!linked.ok) return TaskResult.Failed("ndk: lib${facet.libraryName}.so did not link")

        ctx.logger()("ndk: wrote ${out.fileName} (${runCatching { Files.size(out) }.getOrDefault(0L)} bytes) for $abi")
        return null
    }

    /**
     * Surface the compiler's own output as structured diagnostics.
     *
     * The same parser the editor uses, so a build error and the squiggle on the same line say the same thing
     * in the same words. Positions are relative to the file clang was given, which here is a real path
     * rather than the editor's `<stdin>`.
     */
    private fun report(ctx: TaskContext, output: String, source: Path?) {
        if (output.isBlank()) return
        val text = source?.let { runCatching { it.toFile().readText() }.getOrNull() }
        val parsed = text?.let {
            runCatching { ClangDiagnostics.parse(output, it, bufferName = source.toString()) }.getOrNull()
        }
        if (parsed.isNullOrEmpty()) {
            // Nothing parseable: the linker's output, or a failure about the toolchain rather than the code.
            output.lineSequence().filter { it.isNotBlank() }.forEach { ctx.logger()("ndk: $it") }
            return
        }
        for (d in parsed) {
            ctx.diagnostics.report(
                BuildDiagnostic(
                    severity = if (d.severity == dev.ide.lang.dom.Severity.ERROR) BuildSeverity.ERROR
                    else BuildSeverity.WARNING,
                    message = d.message,
                    source = "ndk",
                    location = DiagnosticLocation(source?.toString() ?: module.dir.path),
                )
            )
        }
    }

    /** A device that cannot compile is not a broken build; it is a build with no native part. */
    private fun skip(ctx: TaskContext, why: String): TaskResult {
        log.warn("ndk: skipping the native build — $why")
        ctx.diagnostics.report(
            BuildDiagnostic(
                severity = BuildSeverity.WARNING,
                message = "ndk: no native library was built — $why",
                source = "ndk",
                location = DiagnosticLocation(module.dir.path),
            )
        )
        return TaskResult.Success
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "c++")

        /** What the bundled toolchain was built to target; see tools/ndk-toolchain/README.md. */
        const val SUPPORTED_ABI = "arm64-v8a"
    }
}
