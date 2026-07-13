package dev.ide.core.services

import dev.ide.core.MEM_HEARTBEAT_EVERY_SAMPLES
import dev.ide.core.MEM_SAMPLE_INTERVAL_MS
import dev.ide.core.PeakHeap
import dev.ide.platform.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidVariants
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.RunDexer
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildGoal
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSeverity
import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.SOURCE_GENERATOR_EP
import dev.ide.build.SourceGenerator
import dev.ide.build.TaskGraph
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.RunDexBackend
import dev.ide.build.engine.GuardCategory
import dev.ide.build.engine.Guards
import dev.ide.build.engine.PermissionBroker
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskStatus
import dev.ide.build.engine.jarPath
import dev.ide.build.jvm.JavaBuildSystem
import dev.ide.core.EngineContext
import dev.ide.core.MemSample
import dev.ide.core.PermissionPolicy
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KOTLIN_COMPILER_PLUGIN_EP
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.module
import dev.ide.platform.Disposable
import dev.ide.platform.PluginId
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import dev.ide.ui.backend.UiSeverity
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WORKSPACE-scoped engine service: build + run orchestration — the native Java/Kotlin + Android build
 * systems, the live build/run state, the run-task list, interactive console I/O, and the run-sandbox
 * permission broker. Carved out of [dev.ide.core.IdeServices]; [dev.ide.core.InProcessBuildRunner] (the
 * in-process arm of [dev.ide.core.BuildRunner]) delegates here. Disposable: it cancels the current run's I/O
 * and clears the process-global sandbox broker on dispose. Build results land in the model + the build-state
 * flow, which the editor reads, so the rest of the engine stays decoupled. Reaches shared infrastructure
 * (model, compilers, classpath, ports) through [EngineContext].
 */
internal class BuildService(private val ctx: EngineContext) : Disposable {

    /** Phase-0 build heap-peak instrumentation; read by the analytics bridge to attach to build_result. */
    @Volatile
    internal var lastBuildPeak: MemSample? = null

    private val memLog = Log.logger("ide.mem")

    /** Background scope the build/run coroutine launches on; cancelled with the service (workspace close). */
    private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- build & run ----

    // Incremental state is per-output-dir, so the incremental wrapper stays workspace-scoped. The build's
    // `compileKotlin` task (lang-kotlin's KotlinCompileTask) drives this directly — Compose-plugin detection
    // and the boot classpath now live in that task, not in a port lambda here.
    private val incrementalKotlin = IncrementalKotlinCompiler(ctx.kotlinJvmCompiler)

    // Kotlin compiler plugins are contributed through the `platform.kotlinCompilerPlugin` EP and applied
    // per module by the build's compileKotlin tasks. Compose is the built-in (registered here); a plugin
    // adds more by contributing to the EP. Captured once for the build systems (Compose is registered eagerly).
    private val kotlinCompilerPlugins: List<KotlinCompilerPlugin> = run {
        ctx.platform.extensions.register(
            KOTLIN_COMPILER_PLUGIN_EP, ComposeCompilerPlugin, PluginId("kotlin-support")
        )
        ctx.platform.extensions.extensions(KOTLIN_COMPILER_PLUGIN_EP)
    }

    // Build-time source generators contributed through `platform.sourceGenerator` (a KSP runner, ViewBinding
    // emitter, …); the build runs them into a module's GENERATED root ahead of compilation. Empty until a
    // plugin contributes one, so the seam is wired but dormant today.
    private val sourceGenerators: List<SourceGenerator> =
        ctx.platform.extensions.extensions(SOURCE_GENERATOR_EP)

    // The native Java/Kotlin build system (`:jvm-build`): JavaPlugin wires each module's own compile task
    // (lang-jdt's JdtCompileTask, lang-kotlin's KotlinCompileTask), which drive ecj / K2 directly. The boot
    // classpath is resolved PER MODULE ([ctx.bootClasspathFor]: the core-Java platform for a console module,
    // the Android SDK for an android module, empty on desktop → host JRE) so a Java/Kotlin console app never
    // compiles against android.jar. Plus the incremental Kotlin compiler, compiler plugins, source generators.
    private val buildSystem = JavaBuildSystem(
        { ctx.bootClasspathFor(it) }, incrementalKotlin, kotlinCompilerPlugins, sourceGenerators
    )

    /**
     * The native Android build. On-device ([ctx.androidTools] non-null) it is the in-process wiring (D8/R8/
     * apksigner in-process, bundled native `aapt2`/`zipalign` from `nativeLibraryDir`, debug keystore from
     * assets). On the desktop it is the subprocess wiring over a detected SDK, or null when none is
     * installed (the UI then reports "install an SDK" for assemble tasks).
     */


    /** Resolves a build type's assigned signing keystore (its `signingConfig` id) to a [SigningConfig], or
     *  null to fall back to the debug keystore. Captured into the Android build system. */
    private val signingResolver: (Module, String) -> SigningConfig? = { module, buildType ->
        module.facets.get(AndroidFacet.KEY)?.buildType?.invoke(buildType)?.signingConfig?.let {
            ctx.keystoreRegistry.signingConfigFor(
                it
            )
        }
    }

    private val androidBuild: AndroidBuildSystem? by lazy {
        // A content-addressed library-dex cache shared across every project (alongside the resolved-deps
        // cache), so a library jar is dexed once per machine, not once per project — the big win when many
        // projects share the same AndroidX/Compose jars. Falls back to the per-project workspace if the host
        // gave no shared dir (then it just survives cleans within the one project).
        val dexCache = (ctx.sharedCachesRoot ?: ctx.store.rootPath).resolve("caches").resolve("dex")
        ctx.androidTools?.let { t ->
            if (!Files.exists(t.androidJar) || !Files.exists(t.nativeLibDir)) return@lazy null
            val sdk =
                AndroidSdk.forDevice(t.androidJar, t.nativeLibDir).takeIf { it.hasNativeTools() }
                    ?: return@lazy null
            val signing = SigningConfig(
                t.debugKeystore,
                DebugKeystore.STORE_PASS,
                DebugKeystore.KEY_ALIAS,
                DebugKeystore.KEY_PASS
            )
            return@lazy AndroidBuildSystem.inProcess(
                sdk,
                signing,
                bootClasspath = ctx.compileBootClasspath,
                kotlin = incrementalKotlin,
                plugins = kotlinCompilerPlugins,
                dexCacheRoot = dexCache,
                signingResolver = signingResolver,
                // On ART, run R8 in a forked VM with a bigger heap (self-falls-back to in-process if forking
                // isn't available). Null on devices that didn't wire it → unchanged in-process behavior.
                shrinker = t.r8Shrinker,
                // ...the dex merge (debug-path memory peak) in a forked VM too...
                mergeDexer = t.r8MergeDexer,
                // ...and the same forked D8 as the dexBuilder ARCHIVE dexer (it's an OffHeapArchiveDexer): a big
                // project jar / cold library archives off the app heap above the "Off-heap dexing threshold", and
                // cold libraries archive several at once. Small incremental archives still stay in-process.
                dexer = t.r8MergeDexer,
                // The "Dex merge batch size" setting (app-scoped); read per build via the host's provider.
                mergeChunk = t.mergeChunkProvider,
            )
        }
        val sdk =
            AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }?.takeIf { it.isComplete() }
                ?: return@lazy null
        val signing =
            DebugKeystore.getOrCreate(ctx.store.rootPath.resolve(".platform/debug.ks"), sdk.keytool)
        AndroidBuildSystem.subprocess(
            sdk,
            signing,
            bootClasspath = ctx.compileBootClasspath,
            kotlin = incrementalKotlin,
            plugins = kotlinCompilerPlugins,
            dexCacheRoot = dexCache,
            signingResolver = signingResolver,
        )
    }

    /**
     * On-device only: the dex backend for the Java `run` path — D8 in-process, desugaring against the bundled
     * `android.jar`. Paired with [ctx.dexRunner], it lets a console app run on ART. Null on the desktop (which
     * forks `java` via [JavaBuildSystem.createRunGraph] instead). [RunDexer] content-hash caches the immutable
     * library jars (stdlib + deps) into `caches/dex-run`, shared across projects, so a source edit re-dexes
     * only the changed user classes instead of the whole runtime classpath.
     */
    private val javaRunDexBackend: RunDexBackend? = ctx.androidTools?.let { t ->
        val runDexCache = (ctx.sharedCachesRoot ?: ctx.store.rootPath).resolve("caches").resolve("dex-run")
        RunDexer(D8InProcessDexer(), t.androidJar, runDexCache)
    }

    private val buildCache = BuildCache(ctx.store.rootPath.resolve(".platform/caches/build"))
    private val _buildState = MutableStateFlow(BuildState())
    val buildState: StateFlow<BuildState> get() = _buildState

    @Volatile
    private var buildCtx: SimpleTaskContext? = null

    @Volatile
    private var buildJob: Job? = null

    // ---- interactive console run (the full-screen Run terminal: program stdio + lifecycle) ----

    private val _runConsole = MutableStateFlow<RunConsoleUi?>(null)
    val runConsole: StateFlow<RunConsoleUi?> get() = _runConsole
    private val runConsoleSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var currentRunIo: RunProgramIo? = null

    /** Feed a line of input to the running program (newline appended) and echo it into the transcript. */
    fun sendRunInput(text: String) {
        val io = currentRunIo ?: return
        if (_runConsole.value?.acceptsInput != true) return
        io.input.feed(text + "\n")
        appendConsoleChunk(ConsoleChunkKind.INPUT, text + "\n")
    }

    /** Signal end-of-input (EOF) to the running program's stdin. */
    fun closeRunInput() {
        currentRunIo?.input?.close()
        _runConsole.update { it?.copy(acceptsInput = false) }
    }

    /** Append to the run transcript, coalescing into the trailing same-[kind] chunk (bounded per chunk) and
     *  capping total size so a chatty program can't grow it without limit. */
    private fun appendConsoleChunk(kind: ConsoleChunkKind, text: String) {
        if (text.isEmpty()) return
        _runConsole.update { rc ->
            rc ?: return@update null
            val chunks = rc.transcript
            val last = chunks.lastOrNull()
            val merged =
                if (last != null && last.kind == kind && last.text.length < CONSOLE_CHUNK_MAX) {
                    chunks.dropLast(1) + ConsoleChunk(last.text + text, kind)
                } else {
                    chunks + ConsoleChunk(text, kind)
                }
            rc.copy(transcript = capTranscript(merged))
        }
    }

    private fun capTranscript(chunks: List<ConsoleChunk>): List<ConsoleChunk> {
        var total = chunks.sumOf { it.text.length }
        if (total <= CONSOLE_TRANSCRIPT_MAX) return chunks
        val out = ArrayDeque(chunks)
        while (total > CONSOLE_TRANSCRIPT_MAX && out.size > 1) total -= out.removeFirst().text.length
        if (total > CONSOLE_TRANSCRIPT_MAX && out.isNotEmpty()) {
            val head = out.removeFirst()
            out.addFirst(head.copy(text = head.text.takeLast(CONSOLE_TRANSCRIPT_MAX)))
        }
        out.addFirst(ConsoleChunk("…(earlier output truncated)…\n", ConsoleChunkKind.SYSTEM))
        return out.toList()
    }

    /** Drive the run console to Finished if the program lifecycle didn't already (e.g. a compile failure
     *  where the program never started, or a cancelled run). Also EOFs stdin and drops the per-run IO. */
    private fun finalizeRunConsole(succeeded: Boolean) {
        _runConsole.update { rc ->
            if (rc == null || rc.phase == RunPhase.Finished) rc
            else rc.copy(
                phase = RunPhase.Finished,
                acceptsInput = false,
                exitCode = if (succeeded) 0 else null
            )
        }
        currentRunIo?.input?.close()
        currentRunIo = null
    }

    /** The host's [ProgramIo] for a console run: routes the program's output into [runConsole], provides a
     *  blocking stdin the UI feeds, and flips the lifecycle phase on start/exit. */
    private inner class RunProgramIo(private val sessionId: Int) : ProgramIo {
        val input = RunInputStream()
        override val stdin: InputStream get() = input
        override fun stdout(text: String) {
            if (_runConsole.value?.id == sessionId) appendConsoleChunk(
                ConsoleChunkKind.OUTPUT, text
            )
        }

        override fun started() {
            _runConsole.update {
                if (it?.id == sessionId) it.copy(
                    phase = RunPhase.Running, acceptsInput = true
                ) else it
            }
        }

        override fun exited(code: Int) {
            _runConsole.update {
                if (it?.id == sessionId) it.copy(
                    phase = RunPhase.Finished, acceptsInput = false, exitCode = code
                ) else it
            }
            if (_runConsole.value?.id == sessionId) appendConsoleChunk(
                ConsoleChunkKind.SYSTEM, "\nProcess finished with exit code $code\n"
            )
        }
    }

    /**
     * Blocking standard input for a running program. The UI feeds lines via [feed]; reads block until input
     * arrives or the stream is [close]d. Closing latches end-of-input — every later read returns -1 — so the
     * program makes forward progress even if it swallows the cancellation interrupt.
     */
    private class RunInputStream : InputStream() {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var closed = false
        private var head: ByteArray? = null
        private var pos = 0

        fun feed(text: String) {
            if (closed) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty()) queue.put(bytes)
        }

        override fun close() {
            closed = true
            queue.offer(ByteArray(0)) // wake a blocked take() so the read returns EOF
        }

        override fun read(): Int {
            val b = ensure() ?: return -1
            return b[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val cur = ensure() ?: return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = head?.let { it.size - pos } ?: 0

        /** Return the chunk currently being read (blocking for one if needed), or null at end-of-input. */
        private fun ensure(): ByteArray? {
            while (head == null || pos >= head!!.size) {
                if (closed && queue.isEmpty()) return null
                val next = try {
                    queue.take()
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); return null
                }
                if (next.isEmpty()) {
                    if (closed) return null else continue
                }
                head = next; pos = 0
            }
            return head
        }
    }

    // ---- runtime permission guard (mediates instrumented code's network/file/reflection/exec; see SandboxGuard) ----

    private val _permissionRequest = MutableStateFlow<UiPermissionRequest?>(null)
    val permissionRequest: StateFlow<UiPermissionRequest?> get() = _permissionRequest

    /** Remembers run/always decisions (persisted per project); the pure, testable part of the guard. */
    private val permissionPolicy =
        PermissionPolicy(ctx.store.rootPath.resolve(".platform/permissions.properties"))
    private val promptLock = Any()

    @Volatile
    private var pendingAnswer: java.util.concurrent.ArrayBlockingQueue<UiPermissionDecision>? = null
    private val permissionIdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The broker [Guards] consults from a running program's (instrumented) code. Fast-paths already-decided
     * categories via [permissionPolicy]; otherwise blocks that program's thread on a UI prompt and applies
     * the returned decision. One prompt at a time ([promptLock] serializes concurrent program threads).
     */
    private val permissionBroker = object : PermissionBroker {
        override fun check(category: GuardCategory, detail: String): Boolean {
            permissionPolicy.decided(category)?.let { return it }
            synchronized(promptLock) {
                permissionPolicy.decided(category)?.let { return it }
                val answer = java.util.concurrent.ArrayBlockingQueue<UiPermissionDecision>(1)
                pendingAnswer = answer
                _permissionRequest.value = UiPermissionRequest(
                    permissionIdSeq.incrementAndGet(), category.name.lowercase(), detail
                )
                val decision = try {
                    answer.take()
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); UiPermissionDecision.DENY
                }
                _permissionRequest.value = null
                pendingAnswer = null
                return permissionPolicy.apply(category, decision)
            }
        }
    }

    /** Answer the running program's pending permission prompt (from the UI). */
    fun answerPermission(id: Int, decision: UiPermissionDecision) {
        if (_permissionRequest.value?.id == id) pendingAnswer?.offer(decision)
    }

    /** Tasks the UI's Run picker offers: a `run` for each runnable console (Java/Kotlin) module + Android
     *  `assemble<Variant>`. A module is runnable when its Run configuration names a main class, or one is
     *  auto-detected in its sources (see [runnableMainFor]). */
    fun runTasks(): List<RunTaskOption> = buildList {
        // Console (Java/Kotlin) modules: a `run` when a main is found, plus a `build` (assemble the jar) that
        // every such module offers — so a library module (no main) is still buildable from the Run picker.
        for (m in ctx.modules()) {
            if (!isConsoleRunModule(m)) continue
            if (runnableMainFor(m) != null) add(RunTaskOption("run:${m.name}", "Run ${m.name}", "run"))
            add(RunTaskOption("build:${m.name}", "Build ${m.name}", "build"))
        }
        for (m in ctx.modules().filter { it.type.id == "android-app" }) {
            for (v in AndroidVariants.compute(m)) {
                val cap = v.name.replaceFirstChar { it.uppercase() }
                // On device, the android Run builds + installs + launches; on desktop it stops at assemble.
                if (ctx.apkInstaller != null) add(
                    RunTaskOption(
                        "androidRun:${m.name}:${v.name}", "Run $cap · ${m.name}", "android"
                    )
                )
                else add(
                    RunTaskOption(
                        "assemble:${m.name}:${v.name}", "assemble$cap · ${m.name}", "android"
                    )
                )
                // The signed Android App Bundle (.aab) for Play upload — available on desktop and device.
                add(
                    RunTaskOption(
                        "bundle:${m.name}:${v.name}", "bundle$cap (.aab) · ${m.name}", "android"
                    )
                )
            }
        }
        // Android libraries package an .aar (per variant) — visible from Run like any other module's task.
        for (m in ctx.modules().filter { it.type.id == "android-lib" }) {
            for (v in AndroidVariants.compute(m)) {
                val cap = v.name.replaceFirstChar { it.uppercase() }
                add(RunTaskOption("assembleAar:${m.name}:${v.name}", "assembleAar$cap (.aar) · ${m.name}", "android"))
            }
        }
    }

    /** Run/assemble the task with [id] (from [runTasks]); streams progress into [buildState]. */
    fun runTask(id: String) {
        if (_buildState.value.status == RunStatus.Running) return
        ctx.flushOpenDocuments() // save unsaved editor buffers so the compiler sees the latest source
        runCatching { ctx.ensureKotlinStdlib() } // a newly-added .kt module needs the stdlib dep before it builds/runs
        // Graph construction + topological ordering run synchronously here; a misconfiguration (cyclic deps,
        // a duplicate task) must surface as a Failed build in the console, never crash the IDE.
        try {
            when {
                id.startsWith("run:") -> {
                    val moduleName = id.removePrefix("run:")
                    val module = ctx.modules().firstOrNull { it.name == moduleName }
                        ?: return fail("No module '$moduleName'.")
                    val target = runnableMainFor(module)
                        ?: return fail("No runnable main() found for ${module.name}. Set one in Module Settings ▸ Run.")
                    val mainClass = target.mainClass
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val project = ctx.projectOf(module) ?: return
                    // Start an interactive console session: program stdio + stdin flow through this ProgramIo
                    // into the full-screen Run terminal.
                    val sessionId = runConsoleSeq.incrementAndGet()
                    val io = RunProgramIo(sessionId)
                    currentRunIo = io
                    _runConsole.value = RunConsoleUi(sessionId, module.name, mainClass)
                    val runner = ctx.dexRunner
                    val backend = javaRunDexBackend
                    if (runner != null && backend != null) {
                        // On-device (ART): there is no `java` to fork, so dex the runtime classpath and run the dex,
                        // targeting this device's API level (default 21 if unknown). The dex runner reflects the
                        // entry point itself, so it handles an instance main without a hint.
                        val minApi = ctx.androidTools?.apiLevel ?: 21
                        launch(
                            module.name, buildSystem.createDexRunGraph(
                                project, module, mainClass, minApi, backend, runner, programIo = io
                            ), "> Run (dex) $mainClass", onComplete = ::finalizeRunConsole
                        )
                    } else {
                        launch(
                            module.name,
                            buildSystem.createRunGraph(project, module, mainClass, programIo = io, instanceMain = target.instance),
                            "> Run $mainClass",
                            onComplete = ::finalizeRunConsole
                        )
                    }
                }

                id.startsWith("assemble:") -> {
                    val parts = id.removePrefix("assemble:").split(":")
                    val module = ctx.modules().firstOrNull { it.name == parts[0] }
                        ?: return fail("No module '${parts[0]}'.")
                    val variant = parts.getOrNull(1) ?: ctx.activeVariant(module)
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val android = androidBuild
                        ?: return fail("Android SDK (platform + build-tools) not found — install one to assemble Android modules.")
                    val project = ctx.projectOf(module) ?: return
                    val graph = android.createBuildGraph(
                        project, BuildRequest(
                            listOf(module.id), VariantSelector(variant), BuildGoal.ASSEMBLE
                        )
                    )
                    launch(
                        module.name,
                        graph,
                        "> assemble $variant · ${module.name}",
                        firstBuildDexBanner(module)
                    )
                }

                id.startsWith("bundle:") -> {
                    val parts = id.removePrefix("bundle:").split(":")
                    val module = ctx.modules().firstOrNull { it.name == parts[0] }
                        ?: return fail("No module '${parts[0]}'.")
                    val variant = parts.getOrNull(1) ?: ctx.activeVariant(module)
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val android = androidBuild
                        ?: return fail("Android SDK (platform + build-tools) not found — install one to bundle Android modules.")
                    val project = ctx.projectOf(module) ?: return
                    val graph = android.createBuildGraph(
                        project, BuildRequest(
                            listOf(module.id), VariantSelector(variant), BuildGoal.BUNDLE
                        )
                    )
                    val aab = AndroidBuildSystem.signedAabPath(module, variant)
                    launch(
                        module.name,
                        graph,
                        "> bundle $variant (.aab) · ${module.name}",
                        firstBuildDexBanner(module)
                    ) { log -> log("Signed bundle: $aab") }
                }

                id.startsWith("assembleAar:") -> {
                    val parts = id.removePrefix("assembleAar:").split(":")
                    val module = ctx.modules().firstOrNull { it.name == parts[0] }
                        ?: return fail("No module '${parts[0]}'.")
                    val variant = parts.getOrNull(1) ?: ctx.activeVariant(module)
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val android = androidBuild
                        ?: return fail("Android SDK (platform + build-tools) not found — install one to assemble Android modules.")
                    val project = ctx.projectOf(module) ?: return
                    val graph = android.createBuildGraph(
                        project, BuildRequest(listOf(module.id), VariantSelector(variant), BuildGoal.ASSEMBLE)
                    )
                    val aar = AndroidBuildSystem.aarPath(module, variant)
                    launch(
                        module.name, graph, "> assembleAar $variant (.aar) · ${module.name}", firstBuildDexBanner(module)
                    ) { log -> log("Packaged AAR: $aar") }
                }

                // Prepare the layout preview: compile + dex (populate the shared library-dex cache) but stop
                // before packaging. The real-view preview no longer dexes libraries itself; this is the one-time
                // (per library set) build it prompts for. minSdk<21 has no per-lib shared buckets, but the debug
                // variant is what the preview loads, so DEX always targets debug.
                id.startsWith("prepareDex:") -> {
                    val parts = id.removePrefix("prepareDex:").split(":")
                    val module = ctx.modules().firstOrNull { it.name == parts[0] }
                        ?: return fail("No module '${parts[0]}'.")
                    val variant = parts.getOrNull(1) ?: ctx.activeVariant(module)
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val android = androidBuild
                        ?: return fail("Android SDK (platform + build-tools) not found — install one to prepare the preview.")
                    val project = ctx.projectOf(module) ?: return
                    val graph = android.createBuildGraph(
                        project, BuildRequest(listOf(module.id), VariantSelector(variant), BuildGoal.DEX)
                    )
                    launch(module.name, graph, "> prepare libraries (dex) $variant · ${module.name}", firstBuildDexBanner(module)) { log ->
                        log("Libraries prepared — the layout preview can now render.")
                    }
                }

                // Build (assemble the jar of) a plain Java/Kotlin module — a library has no `main`, so this is
                // the only way to build it from the Run picker.
                id.startsWith("build:") -> {
                    val moduleName = id.removePrefix("build:")
                    val module = ctx.modules().firstOrNull { it.name == moduleName }
                        ?: return fail("No module '$moduleName'.")
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val project = ctx.projectOf(module) ?: return
                    val graph = buildSystem.createBuildGraph(
                        project, BuildRequest(listOf(module.id), VariantSelector(ctx.activeVariant(module)), BuildGoal.ASSEMBLE)
                    )
                    launch(module.name, graph, "> build ${module.name}") { log -> log("Built: ${jarPath(module)}") }
                }

                id.startsWith("androidRun:") -> {
                    val parts = id.removePrefix("androidRun:").split(":")
                    val module = ctx.modules().firstOrNull { it.name == parts[0] }
                        ?: return fail("No module '${parts[0]}'.")
                    val variant = parts.getOrNull(1) ?: ctx.activeVariant(module)
                    unresolvedBlocker(module)?.let { return fail(it) }
                    val installer =
                        ctx.apkInstaller ?: return fail("APK install is only available on device.")
                    val android = androidBuild ?: return fail("Android SDK not found.")
                    val pkg = module.facets.get(AndroidFacet.KEY)?.namespace
                        ?: return fail("No Android package for '${parts[0]}'.")
                    val project = ctx.projectOf(module) ?: return
                    val graph = android.createBuildGraph(
                        project, BuildRequest(
                            listOf(module.id), VariantSelector(variant), BuildGoal.ASSEMBLE
                        )
                    )
                    val apk = AndroidBuildSystem.signedApkPath(module, variant)
                    // On a successful build, install + launch (the OS shows its own install-confirmation).
                    launch(
                        module.name,
                        graph,
                        "> Run $variant · ${module.name}",
                        firstBuildDexBanner(module)
                    ) { log -> installer.installAndLaunch(apk, pkg, log) }
                }

                else -> fail("Unknown task: $id")
            }
        } catch (e: CyclicTaskDependencyException) {
            fail("Build configuration error — cyclic task dependency: ${e.cycle.joinToString(" → ") { it.value }}")
        } catch (e: Throwable) {
            fail("Couldn't start the build: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Run the default task (first of [runTasks]) — the plain Run button + existing callers. */
    fun runBuild() {
        val first =
            runTasks().firstOrNull() ?: return fail("Nothing to run or assemble in this project.")
        runTask(first.id)
    }

    /**
     * Make kotlin-stdlib a resolvable dependency of every Kotlin module, sourced from the bundled jar (never
     * the host runtime; see [BundledKotlinStdlib]). Extracts the bundled stdlib to a stable workspace path,
     * registers it once as the `kotlin-stdlib` workspace library, and adds an `implementation` dependency to
     * each module that has `.kt` sources but doesn't already declare it, so the stdlib flows onto both the
     * compile and the run/dex classpaths through the normal classpath machinery (link and run). Idempotent;
     * a no-op for Java-only projects.
     */

    private fun fail(message: String) {
        _buildState.value = BuildState(
            RunStatus.Failed,
            "",
            emptyList(),
            listOf(logLine(message, UiLogLevel.Error)),
            elapsedMs = 0
        )
        finalizeRunConsole(succeeded = false) // unstick a run console if a run failed before its program started
    }

    /**
     * The first-build dex notice, or null. The shared library-dex cache (the dir [androidBuild] dexes into)
     * is empty on a machine's first Android build, so every library is dexed from scratch — the slow part of
     * a cold build. We reassure the user the next build reuses the cache, but only when there are enough
     * libraries that dexing is actually felt; a tiny app dexes fast and the notice would just be noise.
     */
    private fun firstBuildDexBanner(module: Module): String? {
        val depCount = runCatching {
            module.classpath(DependencyScope.RUNTIME_ONLY).entries.count { it.kind == ClasspathEntryKind.LIBRARY }
        }.getOrDefault(0)
        val notes = ArrayList<String>()
        val dexCache = (ctx.sharedCachesRoot ?: ctx.store.rootPath).resolve("caches").resolve("dex")
        if (depCount >= FIRST_BUILD_DEX_BANNER_THRESHOLD && !dexCacheHasEntries(dexCache)) {
            notes += "First build — dexing $depCount libraries from scratch (there's no dex cache yet), so this " +
                "build is slower than usual. The next build reuses the cached dex and will be much faster."
        }
        // Desugaring hint: below API 26, D8 must desugar every library on-device and the library dex cache is
        // keyed by the whole classpath, so a big (e.g. Compose) project re-dexes all its libraries whenever a
        // dependency changes. At minSdk 26+ desugaring is off and each library dexes once into a reusable
        // cross-project bucket. Surfaced once per build so the user can weigh raising minSdk.
        val minSdk = module.facets.get(AndroidFacet.KEY)?.minSdk
        if (minSdk != null && minSdk in 21..25 && depCount >= FIRST_BUILD_DEX_BANNER_THRESHOLD) {
            notes += "This module's minSdk is $minSdk. Below API 26, on-device dexing must desugar the whole " +
                "library classpath, which is significantly slower and re-dexes every library when dependencies " +
                "change. If your app can require API 26+, raising minSdk makes library dexing far faster and cacheable."
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Whether the shared dex cache already holds any dexed output (so a build isn't the cold first one). */
    private fun dexCacheHasEntries(dir: Path): Boolean = Files.isDirectory(dir) && runCatching {
        Files.walk(dir).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }
    }.getOrDefault(false)

    /** Stream [graph] execution into [buildState] (shared by run + assemble). [onSuccess] (e.g. install +
     *  launch an APK) runs after a successful build, receiving the console log appender. [onComplete] runs
     *  when the build finishes normally (success or failure, not cancellation), with the outcome. */
    private fun launch(
        moduleName: String,
        graph: TaskGraph,
        header: String,
        banner: String? = null,
        onComplete: ((succeeded: Boolean) -> Unit)? = null,
        onSuccess: (suspend (log: (String) -> Unit) -> Unit)? = null,
    ) {
        val order = graph.topologicalLevels().flatten()
            .map { BuildStepUi(it.name.value, StepStatus.Pending) }
        _buildState.value = BuildState(
            RunStatus.Running,
            moduleName,
            order,
            listOf(logLine(header)),
            elapsedMs = 0,
            banner = banner
        )
        val start = System.currentTimeMillis()
        // Phase-0 build-process-isolation instrumentation (docs/build-process-isolation.md): track this
        // build/run's heap peak so we can see how close a build comes to the OOM ceiling and compare it
        // against the project-open peak — the comparison that justifies (or not) a separate build process.
        val peak = PeakHeap().also { it.record() }
        memLog.info("build '$header' start: ${MemSample.now().fmt()}")
        val ctx = SimpleTaskContext(
            onLog = { e -> _buildState.update { it.copy(log = it.log + e.toUi()) } },
            onDiagnostic = { d -> _buildState.update { it.copy(diagnostics = it.diagnostics + d.toUi()) } },
        )
        buildCtx = ctx
        // Arm the run-time guard for this run: fresh per-run decisions + this engine's broker. Only the
        // in-process dex-run executes instrumented code that consults it; other graphs never touch it.
        permissionPolicy.resetRun(); _permissionRequest.value = null
        Guards.broker = permissionBroker
        // Tasks currently in-flight (maxParallel=2). The heap heartbeat names these so a process-killing OOM
        // leaves a logcat trail pinning which task was at the ceiling — the finally summary never runs then.
        val runningTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val exec = TaskExecutorImpl(buildCache, onEvent = { name, status ->
            peak.record() // sample at each task-status change, in addition to the periodic sampler below
            // Log heap as each task STARTS so a clean build's timeline names the task that pegs the ceiling
            // (cold kotlinc vs D8 vs R8) — tells us whether process isolation alone suffices or the task's
            // own peak must also be cut. Phase-0 instrumentation; see docs/build-process-isolation.md.
            if (status == TaskStatus.Running) {
                runningTasks.add(name.value)
                memLog.info(
                    "task ${name.value}: ${
                        MemSample.now().fmt()
                    } (peak-so-far ${peak.peak().usedMb}MB)"
                )
            } else runningTasks.remove(name.value) // any terminal status clears it
            _buildState.update { st ->
                st.copy(steps = st.steps.map {
                    if (it.name == name.value) it.copy(
                        status = mapStatus(
                            status
                        )
                    ) else it
                })
            }
        })
        buildJob = buildScope.launch {
            val memSampler = launch {
                var ticks = 0
                while (isActive) {
                    peak.record()
                    // Periodic heartbeat (≈ every MEM_SAMPLE_INTERVAL_MS × MEM_HEARTBEAT_EVERY_SAMPLES ms):
                    // names the in-flight task(s) so a hard OOM that kills the process mid-task still leaves a
                    // logcat trail whose last `heap [...]` line pins the task at the ceiling and its heap.
                    if (ticks % MEM_HEARTBEAT_EVERY_SAMPLES == 0 && runningTasks.isNotEmpty()) {
                        memLog.info("heap [${runningTasks.joinToString(",")}]: ${MemSample.now().fmt()} (peak ${peak.peak().usedMb}MB)")
                    }
                    ticks++
                    delay(MEM_SAMPLE_INTERVAL_MS)
                }
            }
            val outcome = try {
                exec.execute(graph, ctx, maxParallel = 2)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Throwable) {
                // The executor reports per-task failures itself; this only catches anything that still escaped,
                // so the build never ends as a silent failure with an empty log.
                ctx.buildLog.log(
                    BuildLogEntry(
                        "Build failed: ${e.message ?: e.toString()}", BuildLogLevel.ERROR
                    )
                )
                null
            } finally {
                // Record the peak on every exit path (success / failure / cancel) so the build_result analytics
                // and the log reflect the build that just ran, never a stale prior peak.
                memSampler.cancel()
                lastBuildPeak = peak.peak()
                memLog.info("build '$header' peak: ${peak.peak().fmt()}")
            }
            Guards.broker = null
            _permissionRequest.value = null
            if (outcome?.succeeded == true && onSuccess != null) {
                runCatching {
                    onSuccess { line ->
                        _buildState.update { st ->
                            st.copy(
                                log = st.log + logLine(
                                    line
                                )
                            )
                        }
                    }
                }.onFailure {
                    ctx.buildLog.log(
                        BuildLogEntry(
                            "post-build step failed: ${it.message}", BuildLogLevel.ERROR
                        )
                    )
                }
            }
            _buildState.update {
                it.copy(
                    status = if (outcome?.succeeded == true) RunStatus.Succeeded else RunStatus.Failed,
                    elapsedMs = System.currentTimeMillis() - start,
                )
            }
            onComplete?.invoke(outcome?.succeeded == true)
        }
    }

    /** Cancel an in-progress build/run. */
    fun stopBuild() {
        buildCtx?.canceled = true
        currentRunIo?.input?.close() // EOF a program blocked reading stdin, before we interrupt its thread
        buildJob?.cancel()
        pendingAnswer?.offer(UiPermissionDecision.DENY) // unblock a program waiting on a permission prompt
        Guards.broker = null
        _permissionRequest.value = null
        finalizeRunConsole(succeeded = false) // the cancelled coroutine skips launch's onComplete
        _buildState.update {
            if (it.status == RunStatus.Running) it.copy(
                status = RunStatus.Failed, log = it.log + logLine("Stopped.", UiLogLevel.Warn)
            ) else it
        }
    }

    /** A build-engine [BuildDiagnostic] → the UI DTO (paths/ids as plain strings for the surface-agnostic UI). */
    private fun BuildDiagnostic.toUi(): BuildDiagnosticUi = BuildDiagnosticUi(
        severity = when (severity) {
            BuildSeverity.ERROR -> UiSeverity.Error
            BuildSeverity.WARNING -> UiSeverity.Warning
            BuildSeverity.INFO -> UiSeverity.Info
        },
        message = message,
        kind = kind.id,
        source = source,
        file = location?.path,
        line = location?.line ?: -1,
        column = location?.column ?: -1,
        detail = detail,
        task = task?.value,
    )

    /** A host-side build-log line: stamps the current wall-clock time + a formatted label for the console. */
    private fun logLine(
        message: String, level: UiLogLevel = UiLogLevel.Info, task: String? = null
    ): BuildLogLine {
        val ms = System.currentTimeMillis()
        return BuildLogLine(message, level, task, buildLogTimeLabel(ms), ms)
    }

    /** A build-engine [BuildLogEntry] → the UI log row (level mapped, task as a string, time formatted). */
    private fun BuildLogEntry.toUi(): BuildLogLine {
        val ms = if (timestampMs != 0L) timestampMs else System.currentTimeMillis()
        val mapped = when (level) {
            BuildLogLevel.DEBUG -> UiLogLevel.Debug
            BuildLogLevel.INFO -> UiLogLevel.Info
            BuildLogLevel.WARN -> UiLogLevel.Warn
            BuildLogLevel.ERROR -> UiLogLevel.Error
        }
        return BuildLogLine(
            message, inferLevel(message, mapped), task?.value, buildLogTimeLabel(ms), ms
        )
    }

    /**
     * Best-effort level for an untyped tool line (most arrive as INFO) so the Log tab's level filter is
     * useful — recognizes the common compiler/tool prefixes (kotlinc `e:`/`w:`, GNU/aapt2 `error:`/
     * `warning:`). Only ever *upgrades* an INFO line; an explicit level from the engine is left untouched.
     */
    private fun inferLevel(message: String, declared: UiLogLevel): UiLogLevel {
        if (declared != UiLogLevel.Info) return declared
        val l = message.lowercase()
        return when {
            l.startsWith("e:") || l.startsWith("error:") || "error:" in l || "exception" in l || l.startsWith(
                "failed"
            ) -> UiLogLevel.Error

            l.startsWith("w:") || l.startsWith("warning") || "warning:" in l -> UiLogLevel.Warn
            else -> UiLogLevel.Info
        }
    }

    /** Local time-of-day label (HH:mm:ss.SSS) for a build-log line's epoch-millis timestamp. */
    private fun buildLogTimeLabel(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    }.getOrDefault("")

    private fun mapStatus(status: TaskStatus): StepStatus = when (status) {
        TaskStatus.Running -> StepStatus.Running
        TaskStatus.Succeeded -> StepStatus.Done
        TaskStatus.UpToDate -> StepStatus.UpToDate
        TaskStatus.NoSource -> StepStatus.NoSource
        TaskStatus.Failed -> StepStatus.Failed
        TaskStatus.Blocked -> StepStatus.Skipped
    }

    /** A build-blocking message when [module] (or a module it depends on) has unresolved declared dependencies,
     *  else null. A build can't succeed against a missing classpath, so we refuse with a clear, actionable why. */
    private fun unresolvedBlocker(module: Module): String? {
        val bad =
            ctx.moduleBuildClosure(module).flatMap { m -> ctx.dependencies.declaredUnresolved(m).map { m.name to it } }
        if (bad.isEmpty()) return null
        val lines = bad.joinToString("\n") { (mod, coord) -> "  • $coord  ($mod)" }
        val n = bad.size
        return "Can't build: $n declared ${if (n == 1) "dependency is" else "dependencies are"} unresolved.\n" + "$lines\nResolve them first: tap Retry on the dependency banner (check your internet connection), or open the Dependencies screen."
    }

    /** The entry point to launch for console [module]: the user-configured Run override if set (carrying the
     *  instance/static flag from a matching detected entry when known), else the first auto-detected entry
     *  point in its sources (Java mains before Kotlin). Null when neither exists. With [live], the sources are
     *  scanned on disk rather than via the index — for the programmatic run-and-capture path, whose sources are
     *  written straight to disk, so a stale index entry can't misname the class to launch. */
    private fun runnableMainFor(module: Module, live: Boolean = false): RunTarget? {
        val detected = if (live) MainClassDetection.detectLive(ctx, module) else MainClassDetection.detect(ctx, module)
        val override = ctx.mainClassOverride(module)
        if (override != null) return detected.firstOrNull { it.mainClass == override } ?: RunTarget(override, instance = false)
        return detected.firstOrNull()
    }

    /**
     * Compile [moduleName] and run its detected `main`, capturing stdout + exit code + compile-error
     * diagnostics — a self-contained, synchronous variant of [runTask] for programmatic callers (the Learn
     * exercise checker). It reuses the same run graph ([JavaBuildSystem.createRunGraph] on desktop /
     * [JavaBuildSystem.createDexRunGraph] on ART) but with a buffering [ProgramIo] and does NOT touch the
     * interactive [buildState]/[runConsole] flows. [stdin] is fed to the program then EOF'd; the run is
     * bounded by [timeoutMs]. The run sandbox is auto-allowed for the duration so a lesson snippet never
     * blocks on a permission prompt.
     */
    suspend fun runAndCapture(moduleName: String, stdin: String = "", timeoutMs: Long = 60_000): RunCapture {
        ctx.flushOpenDocuments()
        runCatching { ctx.ensureKotlinStdlib() }
        val module = ctx.modules().firstOrNull { it.name == moduleName }
            ?: return RunCapture(false, false, "", null, listOf("No module '$moduleName'."))
        // Detect the main from the just-flushed disk sources, not the index: this path writes the module's
        // sources directly (the Learn checker overwrites its `Main` per exercise), so the persisted index can
        // still name a since-deleted class ("Could not find or load main class com.example.app.Main").
        val target = runnableMainFor(module, live = true)
            ?: return RunCapture(false, false, "", null, listOf("No runnable main() found in ${module.name}."))
        unresolvedBlocker(module)?.let { return RunCapture(false, false, "", null, listOf(it)) }
        val project = ctx.projectOf(module)
            ?: return RunCapture(false, false, "", null, listOf("No project for ${module.name}."))

        val io = CaptureProgramIo(stdin)
        val runner = ctx.dexRunner
        val backend = javaRunDexBackend
        val graph = if (runner != null && backend != null) {
            val minApi = ctx.androidTools?.apiLevel ?: 21
            buildSystem.createDexRunGraph(project, module, target.mainClass, minApi, backend, runner, programIo = io)
        } else {
            buildSystem.createRunGraph(project, module, target.mainClass, programIo = io, instanceMain = target.instance)
        }

        val diags = java.util.Collections.synchronizedList(mutableListOf<String>())
        val taskCtx = SimpleTaskContext(
            onDiagnostic = { d -> if (d.severity == BuildSeverity.ERROR) diags.add(diagLine(d)) },
        )
        val exec = TaskExecutorImpl(buildCache)
        val prevBroker = Guards.broker
        Guards.broker = AllowAllBroker
        val timedOut: Boolean
        try {
            timedOut = withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) { exec.execute(graph, taskCtx, maxParallel = 2) } == null
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Throwable) {
            Guards.broker = prevBroker
            io.close()
            return RunCapture(io.started, io.exited, io.output(), io.exitCode, diags + (e.message ?: e.toString()))
        } finally {
            if (Guards.broker === AllowAllBroker) Guards.broker = prevBroker
            io.close()
        }
        val extra = if (timedOut && !io.exited) listOf("The program didn't finish within ${timeoutMs / 1000}s.") else emptyList()
        return RunCapture(io.started, io.exited, io.output(), io.exitCode, diags + extra)
    }

    /** A build-engine diagnostic → a compact one-line message (with location when known). */
    private fun diagLine(d: BuildDiagnostic): String {
        val loc = d.location
        val where = loc?.let { "${Paths.get(it.path).fileName}${if (it.line > 0) ":${it.line}" else ""}: " } ?: ""
        return where + d.message
    }

    /** A [ProgramIo] that buffers stdout, feeds a canned [stdinText] then EOFs, and records lifecycle. */
    private class CaptureProgramIo(stdinText: String) : ProgramIo {
        private val buf = StringBuilder()
        private val stdinStream = java.io.ByteArrayInputStream(stdinText.toByteArray(Charsets.UTF_8))
        @Volatile var started = false; private set
        @Volatile var exited = false; private set
        @Volatile var exitCode: Int? = null; private set
        override val stdin: InputStream get() = stdinStream
        override fun stdout(text: String) { synchronized(buf) { if (buf.length < CAPTURE_MAX) buf.append(text) } }
        override fun started() { started = true }
        override fun exited(code: Int) { exited = true; exitCode = code }
        fun output(): String = synchronized(buf) { buf.toString() }
        fun close() { runCatching { stdinStream.close() } }
    }

    /** Allow every guarded call — a learning snippet the user typed runs in a trusted sandbox, never prompts. */
    private object AllowAllBroker : PermissionBroker {
        override fun check(category: GuardCategory, detail: String): Boolean = true
    }

    override fun dispose() {
        // Unblock any in-flight run (a program reading stdin / waiting on a permission prompt) and don't
        // leave this disposed engine's broker installed process-wide.
        currentRunIo?.input?.close()
        pendingAnswer?.offer(UiPermissionDecision.DENY)
        if (Guards.broker === permissionBroker) Guards.broker = null
        buildScope.cancel()
    }

    private companion object {
        /** Below this many library deps, dexing is quick enough that the first-build notice is just noise. */
        private const val FIRST_BUILD_DEX_BANNER_THRESHOLD = 8

        /** Coalesce console output into chunks up to this size; cap the retained transcript so an interactive
         *  program's output is otherwise unbounded. */
        private const val CONSOLE_CHUNK_MAX = 8192
        private const val CONSOLE_TRANSCRIPT_MAX = 1_000_000

        /** Cap the captured stdout of a [runAndCapture] run (a lesson exercise) — plenty for teaching output. */
        private const val CAPTURE_MAX = 200_000
    }
}

/**
 * Outcome of [BuildService.runAndCapture]: whether the program [compiled] (its `main` started), [ran] to
 * completion, its captured [stdout], its [exitCode] (null if it never finished / timed out), and any
 * compile-error [diagnostics].
 */
data class RunCapture(
    val compiled: Boolean,
    val ran: Boolean,
    val stdout: String,
    val exitCode: Int?,
    val diagnostics: List<String>,
)
