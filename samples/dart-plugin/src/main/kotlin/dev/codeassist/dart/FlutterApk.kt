package dev.codeassist.dart

import dev.ide.build.AlwaysRun
import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildGoal
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildSeverity
import dev.ide.build.Lifecycle
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetKey
import dev.ide.platform.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * Marks an Android app module as the Android side of a Flutter app, as the `flutter { source = "../.." }`
 * block of a Flutter project's `android/app/build.gradle` does: [source] is the Flutter package, relative to
 * the module. The module's build then compiles that package and packages it with the engine
 * ([FlutterBuildPlugin]).
 */
data class FlutterFacet(val source: String = DEFAULT_SOURCE) : Facet {
    override val key get() = KEY

    companion object {
        val KEY = FacetKey<FlutterFacet>("flutter")

        /** `android/app` inside the package, where `flutter create` puts it. */
        const val DEFAULT_SOURCE = "../.."
    }
}

/** The `[flutter]` table of an Android module's `module.toml`. */
object FlutterFacetCodec : FacetCodec<FlutterFacet> {
    override val key = FlutterFacet.KEY
    override val tomlTable = "flutter"

    override fun encode(facet: FlutterFacet): Map<String, Any?> = mapOf("source" to facet.source)

    override fun decode(values: Map<String, Any?>): FlutterFacet =
        FlutterFacet((values["source"] as? String)?.takeIf { it.isNotBlank() } ?: FlutterFacet.DEFAULT_SOURCE)
}

/**
 * Builds a Flutter app into its Android app's APK, as the Flutter Gradle plugin's `compileFlutterBuild<Variant>`
 * does: for each module with a [FlutterFacet], one task that compiles the Flutter package and lays out what
 * the APK needs from it. The Android build packages the result like any module's own assets and native
 * libraries, so signing, installing and Run are the ordinary Android ones.
 *
 * A debug variant is what `flutter build apk --debug` makes: the program as kernel, run by the debug engine's
 * JIT. A release variant is `flutter build apk --release`: the program compiled ahead of time to machine code
 * (`libapp.so`) by gen_snapshot, run by the release engine, with no kernel in the APK.
 */
class FlutterBuildPlugin(
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pub: Pub,
    private val log: Logger,
) : BuildPlugin {

    override val id = "flutter"

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.request.goal != BuildGoal.CLEAN && config.project.modules.any { it.facets.get(FlutterFacet.KEY) != null }

    override fun apply(config: BuildConfiguration) {
        val variant = config.request.variant.name
        for (module in config.project.modules) {
            val facet = module.facets.get(FlutterFacet.KEY) ?: continue
            val pkg = Paths.get(module.dir.path).resolve(facet.source).normalize()
            val out = config.env.buildDir(module).resolve("intermediates").resolve("flutter").resolve(variant)
            val taskName = TaskName(":${module.name}:compileFlutterBuild${variant.cap()}")
            val assets = out.resolve("assets")
            val libs = out.resolve("lib")

            // Both declarations need SPI 3.8.0 (assets) and 3.1.0 (native libraries). On an older host the
            // task is still registered, to fail with the reason instead of packaging an app with no Flutter in it.
            val declared = try {
                config.addAssets(module, assets, taskName)
                config.addNativeLibraries(module, libs, taskName)
                true
            } catch (e: LinkageError) {
                log.info("this IDE cannot package a build directory's assets; Flutter APKs need a newer CodeAssist")
                false
            }

            val moduleNames = config.project.modules.mapTo(HashSet()) { it.name }
            // The modules whose C/C++ something builds: their `[ndk]` table decodes only when the NDK plugin,
            // which registers its codec, is installed and on.
            val nativeModules = config.project.modules.filter { m -> m.facets.all.any { it.key.id == NDK_FACET } }.mapTo(HashSet()) { it.name }
            config.tasks.register(taskName) {
                FlutterApkTask(taskName, pkg, out, release = isRelease(variant), hostCanPackage = declared, sdk, flutter, pub, moduleNames, nativeModules)
            }
            config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(taskName) }
            config.tasks.named(Lifecycle.assemble(module.name, variant)).configure { dependsOn(taskName) }
        }
    }

    private fun String.cap(): String = replaceFirstChar { it.uppercase() }

    companion object {
        /**
         * Whether [variant] is a release build. The Android facet that says which build types are debuggable
         * belongs to another plugin, so this goes by the name AGP gives a variant: `release`, `freeRelease`.
         */
        fun isRelease(variant: String): Boolean = variant.endsWith("release", ignoreCase = true)

        /** The NDK plugin's facet id (its `[ndk]` table). */
        const val NDK_FACET = "ndk"
    }
}

/**
 * Compiles the Flutter package at [pkg] and writes, under [out]:
 *  - `assets/flutter_assets/`: the asset bundle (fonts, shaders, images, the manifests the framework reads them
 *    by), and for a debug build the program (`kernel_blob.bin`) and the VM snapshots the debug engine starts from;
 *  - `lib/<abi>/libflutter.so`: the engine (debug or release), for the ABI the plugin carries it for;
 *  - `lib/<abi>/libapp.so`, for a release build: the program as machine code.
 *
 * A debug build compiles with the incremental compiler Run uses ([FrontendServer]), started from the kernel it
 * wrote last time, so a build after an edit recompiles only what changed. A release build compiles the whole
 * program to kernel against the product platform with tree shaking (`--aot --tfa`), then gen_snapshot compiles
 * that to an ELF library, as `flutter build apk --release` does.
 */
internal class FlutterApkTask(
    override val name: TaskName,
    private val pkg: Path,
    private val out: Path,
    private val release: Boolean,
    private val hostCanPackage: Boolean,
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pub: Pub,
    /** The project's modules as this build sees them, to tell when the app's plugins have none yet. */
    private val moduleNames: Set<String> = emptySet(),
    /** The modules whose native code a plugin builds (see [FlutterBuildPlugin.NDK_FACET]). */
    private val nativeModules: Set<String> = emptySet(),
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    /**
     * The build, with its reason for failing (an app that does not compile, a missing NDK plugin) also filed under
     * Problems: the log line alone is easy to miss among a build's hundreds.
     */
    override suspend fun execute(ctx: TaskContext): TaskResult {
        val result = build(ctx)
        if (result is TaskResult.Failed) {
            runCatching { ctx.diagnostics.report(BuildDiagnostic(BuildSeverity.ERROR, result.message, source = "flutter")) }
        }
        return result
    }

    private suspend fun build(ctx: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        val log = ctx.logger()
        if (!hostCanPackage) return@withContext TaskResult.Failed(TOO_OLD)
        (sdk.prepare() as? DartSdk.Status.Unavailable)?.let { return@withContext TaskResult.Failed(it.reason) }
        val prepared = if (release) flutter.prepareRelease() else flutter.prepare()
        (prepared as? FlutterSdk.Status.Unavailable)?.let { return@withContext TaskResult.Failed(it.reason) }
        val pubspec = pkg.resolve(DartPackages.PUBSPEC)
        if (!Files.isRegularFile(pubspec)) return@withContext TaskResult.Failed("No Flutter package at $pkg (no pubspec.yaml).")
        val name = DartPackages.nameOf(String(Files.readAllBytes(pubspec)))
            ?: return@withContext TaskResult.Failed("$pubspec has no name.")
        if (!Files.isRegularFile(pkg.resolve("lib/main.dart"))) return@withContext TaskResult.Failed("A Flutter app needs lib/main.dart.")
        val engine = (if (release) flutter.releaseEngine else flutter.engine)
            ?: return@withContext TaskResult.Failed(if (release) FlutterSdk.NO_RELEASE else FlutterSdk.NO_FLUTTER)
        val abi = abiOf(engine.parent)
            ?: return@withContext TaskResult.Failed("Could not tell which ABI the Flutter engine at $engine is for.")

        if (DartPackages.needsPubGet(pkg)) {
            log("> dart pub get")
            val code = killableTool { cancellation -> pub.run(pkg, "get", cancellation = cancellation, out = log) }
            // `dart pub get` can exit 0 after "version solving failed"; what matters is the package config it writes.
            if (code != 0 || DartPackages.needsPubGet(pkg)) return@withContext TaskResult.Failed("pub get failed: see its output above.")
        }

        // A plugin pub just brought in (here, or since the last sync) has no module in this build: its Android
        // code would be missing from the APK. The project syncs before the next build, which then has it.
        val plugins = FlutterPlugins.discover(pkg)
        val missing = plugins.filter { it.hasAndroidCode && it.name !in moduleNames }.map { it.name }
        if (missing.isNotEmpty() && moduleNames.isNotEmpty()) {
            return@withContext TaskResult.Failed(
                "The app's Flutter plugins changed (${missing.joinToString()}), and this build started before the " +
                    "project took them in. Build again: the project syncs them first.",
            )
        }
        // An FFI plugin's C code is built by the NDK plugin. Without it the APK would install and crash the first
        // time the app reaches that code, so this fails the build and says what to install instead.
        val unbuilt = plugins.filter { it.ffi && it.hasAndroidCode && it.name in moduleNames && it.name !in nativeModules }.map { it.name }
        if (unbuilt.isNotEmpty()) return@withContext TaskResult.Failed(noNativeBuild(unbuilt))

        val entry = "package:$name/main.dart"
        val libs = out.resolve("lib").resolve(abi)
        val registrant = try {
            writeDartRegistrant()
        } catch (e: IOException) {
            return@withContext TaskResult.Failed("Could not write the Dart plugin registrant: ${e.message}")
        }
        if (registrant.isNotEmpty()) log("Registering the Dart side of the app's Flutter plugins.")
        if (release) return@withContext buildRelease(entry, engine, abi, libs, registrant, log)

        log("Compiling $entry ...")
        val started = System.nanoTime()
        val compiled = compile(entry, registrant)
        compiled.messages.forEach(log)
        val dill = compiled.dill
        if (!compiled.ok || dill == null) {
            return@withContext TaskResult.Failed("The Flutter app did not compile (${compiled.errors.coerceAtLeast(1)} error(s)).")
        }
        log("Compiled in ${(System.nanoTime() - started) / 1_000_000} ms.")

        try {
            writeBundle(kernel = dill)
            copyIfChanged(engine, libs.resolve(ENGINE))
        } catch (e: IOException) {
            return@withContext TaskResult.Failed("Could not lay out the Flutter app for packaging: ${e.message}")
        }
        log("Flutter $abi debug engine and ${name}'s bundle ready for packaging.")
        TaskResult.Success
    }

    /** The release build: kernel ahead of time, gen_snapshot to `libapp.so`, the release engine, no kernel. */
    private suspend fun buildRelease(
        entry: String, engine: Path, abi: String, libs: Path, registrant: List<String>, log: (String) -> Unit,
    ): TaskResult {
        val runtime = sdk.runtime ?: return TaskResult.Failed(DartSdk.NO_SDK)
        val compiler = sdk.frontendServer ?: return TaskResult.Failed(DartSdk.NO_SDK)
        val genSnapshot = flutter.genSnapshot ?: return TaskResult.Failed(FlutterSdk.NO_RELEASE)
        val work = out.resolve("compile")
        val dill = work.resolve("app.dill")
        Files.createDirectories(work)
        Files.deleteIfExists(dill)

        log("Compiling $entry ahead of time ...")
        var started = System.nanoTime()
        val kernel = killableTool { c ->
            runTool(
                aotKernelCommand(runtime, compiler, flutter.productPlatformDir, pkg.resolve(DartPackages.PACKAGE_CONFIG), dill, entry, registrant),
                c, log,
            )
        }
        if (kernel.exitCode != 0 || kernel.errors > 0 || !Files.isRegularFile(dill)) {
            return TaskResult.Failed("The Flutter app did not compile (${kernel.errors.coerceAtLeast(1)} error(s)).")
        }
        log("Kernel in ${ms(started)} ms. Compiling to $abi machine code ...")

        started = System.nanoTime()
        Files.createDirectories(libs)
        // Written beside the real name and moved into place, so a failed or cancelled run never leaves a
        // half-written library for the packaging to pick up.
        val partial = libs.resolve("$APP_LIBRARY.partial")
        val snapshot = killableTool { c -> runTool(genSnapshotCommand(genSnapshot, abi, dill, partial), c, log) }
        if (snapshot.exitCode != 0 || !Files.isRegularFile(partial)) {
            Files.deleteIfExists(partial)
            return TaskResult.Failed("gen_snapshot could not compile the app to machine code (exit code ${snapshot.exitCode}).")
        }
        Files.move(partial, libs.resolve(APP_LIBRARY), StandardCopyOption.REPLACE_EXISTING)
        log("Machine code in ${ms(started)} ms (${Files.size(libs.resolve(APP_LIBRARY)) / 1024} KB).")

        try {
            writeBundle(kernel = null)
            copyIfChanged(engine, libs.resolve(ENGINE))
        } catch (e: IOException) {
            return TaskResult.Failed("Could not lay out the Flutter app for packaging: ${e.message}")
        }
        log("Flutter $abi release engine and the app's machine code ready for packaging.")
        return TaskResult.Success
    }

    /** What a tool run came to: its exit code, and the error count the compiler reported (0 for other tools). */
    class ToolRun(val exitCode: Int, val errors: Int)

    /**
     * Run [command] in the package, its output to [log] (less the compiler's list of the files it read), and
     * answer how it went. Blocking; [cancellation] kills it.
     */
    private fun runTool(command: List<String>, cancellation: ToolCancellation, log: (String) -> Unit): ToolRun {
        val process = ProcessBuilder(command).directory(pkg.toFile()).redirectErrorStream(true)
            .apply { environment().putAll(sdk.environment()) }
            .start()
        cancellation.attach(process)
        var errors = 0
        // The compiler frames its messages with `result <key>` and `<key>` lines, which say nothing to a reader.
        var key: String? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            val result = compileErrors(line)
            when {
                result != null -> errors = result
                line.startsWith("result ") -> key = line.removePrefix("result ").trim()
                line == key -> Unit
                line.startsWith("+") || line.startsWith("-") -> Unit
                else -> log(line)
            }
        }
        return ToolRun(process.waitFor(), errors)
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /**
     * Write the Dart plugin registrant for the app's plugins and answer the compiler arguments that include it,
     * or none when no plugin registers a Dart class (the file is then removed, so a dropped plugin's stays out).
     */
    private fun writeDartRegistrant(): List<String> {
        val file = FlutterPlugins.dartRegistrantFile(pkg)
        val text = FlutterPlugins.dartRegistrant(FlutterPlugins.discover(pkg), FlutterPlugins.directDependencies(pkg))
        if (text == null) {
            Files.deleteIfExists(file)
            return emptyList()
        }
        if (!Files.isRegularFile(file) || String(Files.readAllBytes(file)) != text) {
            Files.createDirectories(file.parent)
            Files.write(file, text.toByteArray())
        }
        return FlutterPlugins.dartRegistrantArgs(file)
    }

    private suspend fun compile(entry: String, registrant: List<String>): FrontendServer.Result {
        val seeds = listOf(
            FlutterSession.runWork(pkg).resolve("app.dill"),
            FlutterSession.paneWork(pkg).resolve("app.dill"),
            FlutterSession.seed(sdk),
        )
        val compiler = FrontendServer(sdk, flutter, pkg, out.resolve("compile").resolve("app.dill"), seeds, registrant)
        // Cancelling interrupts the wait for the compiler's answer; closing it then ends its process.
        return runInterruptible {
            try {
                compiler.start()
                compiler.compile(entry)
            } finally {
                compiler.close()
            }
        }
    }

    /**
     * `flutter_assets`, rewritten whole except for the files that did not change: a stale asset from a previous
     * build (one the pubspec no longer lists) must not reach the APK, and an unchanged 40 MB kernel need not be
     * copied again.
     */
    private fun writeBundle(kernel: Path?) {
        val root = out.resolve("assets").resolve(BUNDLE)
        val bundle = FlutterAssets.build(pkg, flutter.baseAssets)
        val wanted = HashSet<Path>()

        fun keep(relative: String): Path = root.resolve(relative).normalize().also {
            require(it.startsWith(root)) { "asset path escapes the bundle: $relative" }
            wanted.add(it)
        }

        // A debug build's program and the snapshots its JIT starts from. A release build's program is libapp.so.
        if (kernel != null) {
            copyIfChanged(kernel, keep(KERNEL))
            for (snapshot in SNAPSHOTS) {
                val target = keep(snapshot)
                val stream = flutter.vmSnapshot(snapshot) ?: throw IOException("this build of the Dart plugin has no $snapshot")
                stream.use { writeIfChanged(it, target) }
            }
        }
        for ((relative, file) in bundle.files) copyIfChanged(file, keep(relative))
        for ((relative, bytes) in bundle.generated) writeIfChanged(bytes.inputStream(), keep(relative))

        if (Files.isDirectory(root)) {
            val all = Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).collect(Collectors.toList()) }
            for (p in all) {
                if (Files.isRegularFile(p) && p !in wanted) Files.delete(p)
                else if (Files.isDirectory(p) && p != root && Files.list(p).use { !it.findFirst().isPresent }) Files.delete(p)
            }
        }
    }

    internal companion object {
        const val BUNDLE = "flutter_assets"
        const val KERNEL = "kernel_blob.bin"
        val SNAPSHOTS = listOf("vm_snapshot_data", "isolate_snapshot_data")

        const val ENGINE = "libflutter.so"
        const val APP_LIBRARY = "libapp.so"

        /**
         * The error count on the compiler's last line for a compile, `<key> <output dill> <error count>`, or null
         * for any other line.
         */
        fun compileErrors(line: String): Int? {
            val parts = line.split(' ')
            if (parts.size != 3 || !parts[1].endsWith(".dill")) return null
            return parts[2].toIntOrNull()
        }

        /**
         * The release kernel: the whole program and the platform, tree-shaken (`--tfa`) for ahead-of-time
         * compilation, against the product platform, as flutter_tools compiles one for `flutter build --release`.
         */
        fun aotKernelCommand(
            runtime: Path, compiler: Path, platformDir: Path, packages: Path, dill: Path, entry: String,
            extraArgs: List<String> = emptyList(),
        ): List<String> =
            listOf(runtime.toString()) + DartSdk.VM_TUNING + listOf(
                compiler.toString(),
                "--sdk-root", "$platformDir/",
                "--target=flutter",
                "--aot",
                "--tfa",
                "-Ddart.vm.product=true",
                "-Ddart.vm.profile=false",
                "--no-print-incremental-dependencies",
                "--packages=$packages",
                "--output-dill=$dill",
            ) + extraArgs + entry

        /**
         * gen_snapshot from the release kernel to an ELF library the release engine loads, as flutter_tools
         * runs it for Android. 32-bit ARM leaves integer division out: not every ARMv7 CPU has the instruction.
         */
        fun genSnapshotCommand(genSnapshot: Path, abi: String, dill: Path, elf: Path): List<String> = buildList {
            add(genSnapshot.toString())
            add("--deterministic")
            add("--snapshot_kind=app-aot-elf")
            add("--elf=$elf")
            add("--strip")
            if (abi == "armeabi-v7a") add("--no-use-integer-division")
            add(dill.toString())
        }

        /** What to do when [plugins] have C/C++ that nothing builds. */
        fun noNativeBuild(plugins: List<String>): String =
            "The Flutter plugin${if (plugins.size == 1) "" else "s"} ${plugins.joinToString()} " +
                "${if (plugins.size == 1) "has" else "have"} C code, which the NDK (C/C++) plugin compiles, and it is " +
                "not installed or not turned on. Install it from Settings > Plugins > Get plugins (and allow it), " +
                "then build again."

        const val TOO_OLD = "This version of CodeAssist cannot package a Flutter app. Update CodeAssist to build its APK."

        /**
         * The Android ABI a native library directory holds: an app's extracted `lib/arm64` names its instruction
         * set rather than the ABI, an APK's `lib/arm64-v8a` the ABI itself.
         */
        fun abiOf(libraryDir: Path?): String? = when (libraryDir?.fileName?.toString()) {
            "arm64", "arm64-v8a" -> "arm64-v8a"
            "arm", "armeabi-v7a" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }

        /** Copy [from] over [to] unless [to] already has its size and time, which is how a copy made here is left. */
        fun copyIfChanged(from: Path, to: Path) {
            val time = Files.getLastModifiedTime(from)
            if (Files.isRegularFile(to) && Files.size(to) == Files.size(from) && Files.getLastModifiedTime(to) == time) return
            Files.createDirectories(to.parent)
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(to, time)
        }

        /** Write [content] to [to] unless it already holds exactly that, so its time (and the APK's inputs) stay put. */
        fun writeIfChanged(content: InputStream, to: Path) {
            val bytes = content.readBytes()
            if (Files.isRegularFile(to) && Files.size(to) == bytes.size.toLong() && Files.readAllBytes(to).contentEquals(bytes)) return
            Files.createDirectories(to.parent)
            Files.write(to, bytes)
        }
    }
}
