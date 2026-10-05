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
import java.util.stream.Collectors
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
            dev.codeassist.ndk.jni.JniModules.remember(module)
            val facet = module.facets.get(NdkFacet.KEY) ?: continue
            val taskName = TaskName(":${module.name}:compileNative")
            val buildDir = config.env.buildDir(module)

            // The library is build output, so it goes under the build directory and is declared to whatever
            // packages it. A host older than SPI 3.1.0 has no such declaration and packages only the module's
            // own jniLibs roots, so there, and only there, it is written into `src/main/jniLibs`.
            val generated = buildDir.resolve("intermediates").resolve("ndk").resolve("lib")
            val libDir = if (declareNativeLibraries(config, module, generated, taskName)) generated
            else Paths.get(module.dir.path).resolve("src/main/jniLibs")

            config.tasks.register(taskName) {
                NdkCompileTask(taskName, module, facet, buildDir, libDir, toolchain, log)
            }

            // Where to hang it. Configuring a task no build system registered is silently ignored, so naming
            // all of these is how one plugin serves every pipeline without probing which is running.
            //
            // The Android merge is ordered after this task by the declaration above; naming it here as well
            // keeps that true on a host that predates the declaration. The assemble aggregates are the
            // backstop for a pipeline that packages no native code at all.
            config.tasks.named(TaskName(":${module.name}:mergeNativeLibs${variant.cap()}"))
                .configure { dependsOn(taskName) }
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(taskName) }
            config.tasks.named(Lifecycle.assemble(module.name, variant)).configure { dependsOn(taskName) }
        }
    }

    /**
     * Declare [dir] as [module]'s native libraries. False on a host older than SPI 3.1.0, where the call does
     * not link: the plugin is built against the newer SPI and still has to work on the IDE the user has.
     */
    private fun declareNativeLibraries(config: BuildConfiguration, module: Module, dir: Path, task: TaskName): Boolean =
        try {
            config.addNativeLibraries(module, dir, task)
            true
        } catch (e: LinkageError) {
            log.info("this IDE cannot package a build directory's native libraries; writing to src/main/jniLibs")
            false
        }

    private fun String.cap(): String = replaceFirstChar { it.uppercase() }
}

/**
 * Compiles every source the facet names and links them into one shared library at
 * `<libDir>/<abi>/lib<name>.so`.
 *
 * Incremental per source: each object keeps the dependency file clang wrote beside it, and a source is only
 * compiled again when it, or a header it included, is newer than its object, or when the flags changed.
 * Objects are named after the source's path inside the module, so two `util.cpp` in different directories
 * are two objects rather than one overwriting the other.
 */
internal class NdkCompileTask(
    override val name: TaskName,
    private val module: Module,
    private val facet: NdkFacet,
    private val buildDir: Path,
    private val libDir: Path,
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
                    .collect(Collectors.toList())
            }
        }
        .sorted()

    /** Every header under the facet's source directories. */
    private fun headers(): List<Path> = facet.sourceDirs
        .map { moduleDir.resolve(it) }
        .filter { Files.isDirectory(it) }
        .flatMap { dir ->
            Files.walk(dir).use { walk ->
                walk.filter { Files.isRegularFile(it) && it.extension.lowercase() in HEADER_EXTENSIONS }.collect(Collectors.toList())
            }
        }
        .sorted()

    private fun outputFor(abi: String): Path = libDir.resolve(abi).resolve("lib${facet.libraryName}.so")

    private val objRoot: Path get() = buildDir.resolve("intermediates").resolve("ndk").resolve("obj")

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            sources().forEach { property("src:$it", NativeIncremental.modified(it)) }
            // A header is an input as much as a source is: editing only `values.h` has to rebuild every file
            // that includes it, and without this the task is up to date and the APK keeps the old library.
            headers().forEach { property("h:$it", NativeIncremental.modified(it)) }
            // Headers outside the source directories (an `-I` in the flags, the glue, the toolchain's own)
            // are known from the last build's dependency files.
            property(
                "deps",
                NativeIncremental.recordedDependencies(objRoot)
                    .joinToString("\n") { "$it@${NativeIncremental.modified(Paths.get(it))}" }
                    .hashCode(),
            )
            // The configuration is an input too: changing the C++ standard or the optimization level has to
            // rebuild even though not a byte of source moved.
            property("facet", NdkFacetCodec.encode(facet).toString())
            property("libDir", libDir.toString())
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

        val version = when (val status = toolchain.prepare()) {
            is NdkToolchain.Status.Unavailable -> return skip(ctx, status.reason)
            is NdkToolchain.Status.Ready -> status.version.also { ctx.logger()("ndk: $it") }
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
            val result = buildAbi(ctx, toolchain, version, sources, abi)
            if (result != null) return result
            removeLegacyOutput(ctx, abi)
        }
        return TaskResult.Success
    }

    /**
     * Delete the library an older version of this plugin wrote into `src/main/jniLibs/<abi>/`.
     *
     * Leaving it would not be harmless: the Android merge reads the module's jniLibs before declared build
     * output and keeps the first library of a name, so the stale copy would be packaged and every later
     * change to the C++ would build and never reach the APK.
     */
    private fun removeLegacyOutput(ctx: TaskContext, abi: String) {
        val legacyDir = moduleDir.resolve("src/main/jniLibs")
        if (libDir.normalize() == legacyDir.normalize()) return
        val abiDir = legacyDir.resolve(abi)
        val stale = abiDir.resolve("lib${facet.libraryName}.so")
        if (!Files.isRegularFile(stale)) return
        runCatching {
            Files.delete(stale)
            ctx.logger()("ndk: removed src/main/jniLibs/$abi/${stale.fileName}, which an earlier version of the NDK plugin wrote; native output now goes to ${moduleDir.relativize(libDir)}")
            Files.newDirectoryStream(abiDir).use { if (!it.iterator().hasNext()) Files.delete(abiDir) }
            Files.newDirectoryStream(legacyDir).use { if (!it.iterator().hasNext()) Files.delete(legacyDir) }
        }.onFailure { log.warn("could not remove the stale $stale", it) }
    }

    /** Compile what changed and link one ABI; null on success, a failure result otherwise. */
    private fun buildAbi(
        ctx: TaskContext,
        toolchain: NdkToolchain,
        version: String,
        sources: List<Path>,
        abi: String,
    ): TaskResult? {
        val objDir = objRoot.resolve(abi)

        // The glue is compiled into the app rather than linked: the NDK ships it as source for exactly that.
        val glue = if (facet.nativeActivity) toolchain.nativeAppGlueDir?.resolve("android_native_app_glue.c") else null
        val units = sources.map { it to objectKey(it) } +
            listOfNotNull(glue?.takeIf { Files.isRegularFile(it) }?.let { it to "_glue/${it.fileName}" })

        // Flags change what every object contains, so a different set of them invalidates all of them. The
        // compiler's version is part of it: an updated plugin brings an updated clang.
        val compileStamp = buildString {
            appendLine(version)
            appendLine(NdkFlags.build(facet, toolchain, cpp = true).joinToString(" "))
            appendLine(NdkFlags.build(facet, toolchain, cpp = false).joinToString(" "))
        }
        val compileStampFile = objDir.resolve("compile.stamp")
        if (readStamp(compileStampFile) != compileStamp) deleteTree(objDir)
        Files.createDirectories(objDir)

        val objects = ArrayList<Path>()
        val stale = units.filter { (source, key) ->
            val obj = objDir.resolve("$key.o")
            NativeIncremental.isStale(source, obj, depFileFor(obj))
        }
        if (stale.isNotEmpty()) {
            ctx.logger()("ndk: compiling ${stale.size} of ${units.size} source(s) for $abi")
        }
        for ((index, unit) in stale.withIndex()) {
            val (source, key) = unit
            ctx.checkCanceled()
            // A real fraction rather than indeterminate: a native build is the slow part of an Android build
            // on a phone, and a bar that moves is the difference between waiting and wondering.
            ctx.progress.report(index.toDouble() / (stale.size + 1), "Compiling ${source.fileName}")
            val obj = objDir.resolve("$key.o")
            val isCpp = source.extension.lowercase() != "c"
            val compiled = toolchain.compile(
                source, obj, cpp = isCpp,
                extraFlags = NdkFlags.build(facet, toolchain, isCpp),
                depFile = depFileFor(obj),
            )
            report(ctx, compiled.output, source)
            if (!compiled.ok) {
                // A failed compile can leave a partial object behind; without its dependency file the next
                // build treats it as stale either way, but there is no reason to keep it.
                runCatching { Files.deleteIfExists(obj); Files.deleteIfExists(depFileFor(obj)) }
                ctx.logger()("ndk: failed to compile ${source.fileName}")
                return TaskResult.Failed("ndk: ${source.fileName} did not compile")
            }
        }
        writeStamp(compileStampFile, compileStamp)
        units.forEach { (_, key) -> objects.add(objDir.resolve("$key.o")) }
        removeOrphanObjects(objDir, objects.toSet())

        val out = outputFor(abi)
        // Linking through the C++ driver is what brings the runtime in, so it follows the sources, not a flag.
        val cpp = units.any { (source, _) -> source.extension.lowercase() != "c" }
        val linkStamp = buildString {
            appendLine(version)
            objects.forEach { appendLine(it.toString()) }
            appendLine(facet.linkLibraries.joinToString(" "))
            appendLine("cpp=$cpp stl=${facet.stl}")
        }
        val linkStampFile = objDir.resolve("link.stamp")
        if (!NativeIncremental.needsLink(stale.isNotEmpty(), out, objects, linkStamp, readStamp(linkStampFile))) {
            ctx.logger()("ndk: ${out.fileName} for $abi is up to date")
            return null
        }

        ctx.progress.report(stale.size.toDouble() / (stale.size + 1), "Linking lib${facet.libraryName}.so")
        val linked = toolchain.linkShared(objects, out, facet.linkLibraries, cpp = cpp, stl = facet.stl)
        report(ctx, linked.output, null)
        if (!linked.ok) {
            runCatching { Files.deleteIfExists(linkStampFile) }
            return TaskResult.Failed("ndk: lib${facet.libraryName}.so did not link")
        }
        packageCxxRuntime(ctx, toolchain, cpp, out)?.let { return it }
        writeStamp(linkStampFile, linkStamp)

        ctx.logger()("ndk: wrote ${out.fileName} (${runCatching { Files.size(out) }.getOrDefault(0L)} bytes) for $abi")
        return null
    }

    /**
     * Put `libc++_shared.so` beside the library when it links against it, since the APK must carry it and
     * nothing else packages it. With any other runtime, a copy this task left there earlier is removed, but
     * only from the build directory: in `src/main/jniLibs` the file may be the user's own.
     */
    private fun packageCxxRuntime(ctx: TaskContext, toolchain: NdkToolchain, cpp: Boolean, out: Path): TaskResult? {
        val target = out.resolveSibling(CXX_SHARED)
        if (cpp && facet.stl == "c++_shared") {
            val runtime = toolchain.sharedCxxRuntime
                ?: return TaskResult.Failed("ndk: stl = c++_shared, but the toolchain has no $CXX_SHARED")
            runCatching { Files.copy(runtime, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { return TaskResult.Failed("ndk: could not package $CXX_SHARED: ${it.message}", it) }
            ctx.logger()("ndk: packaged $CXX_SHARED")
        } else if (libDir.normalize() != moduleDir.resolve("src/main/jniLibs").normalize()) {
            runCatching { Files.deleteIfExists(target) }
        }
        return null
    }

    private fun depFileFor(obj: Path): Path = obj.resolveSibling(obj.fileName.toString() + ".d")

    /**
     * Where under the object directory [source]'s object goes: its path inside the module. A source directory
     * outside the module (`../shared`) would climb out of the build directory, so `..` becomes `__`.
     */
    private fun objectKey(source: Path): String =
        moduleDir.relativize(source).toString().replace('\\', '/').split('/')
            .joinToString("/") { if (it == "..") "__" else it }

    /** Drop the objects of sources that were deleted or renamed, so they stop being linked in. */
    private fun removeOrphanObjects(objDir: Path, keep: Set<Path>) {
        Files.walk(objDir).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".o") && it !in keep }
                .collect(Collectors.toList())
        }.forEach { obj ->
            runCatching { Files.deleteIfExists(obj); Files.deleteIfExists(depFileFor(obj)) }
        }
    }

    private fun readStamp(file: Path): String? = runCatching { String(Files.readAllBytes(file)) }.getOrNull()

    private fun writeStamp(file: Path, text: String) {
        runCatching { Files.write(file, text.toByteArray()) }.onFailure { log.warn("could not write $file", it) }
    }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList()) }
            .forEach { runCatching { Files.deleteIfExists(it) } }
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
        val HEADER_EXTENSIONS = setOf("h", "hh", "hpp", "hxx", "inl")
        const val CXX_SHARED = "libc++_shared.so"

        /** What the bundled toolchain was built to target; see tools/ndk-toolchain/README.md. */
        const val SUPPORTED_ABI = "arm64-v8a"
    }
}
