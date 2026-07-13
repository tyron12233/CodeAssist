package dev.ide.android.bench

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.ForkedD8Dexer
import dev.ide.android.support.AndroidBuildSystem
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidSupport
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.TaskName
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskStatus
import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.deps.impl.MavenDependencyResolver
import dev.ide.deps.impl.ResolverCache
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompilerPlugin
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import dev.ide.model.BuildSystemId
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.ProgressReporter
import dev.ide.platform.impl.PlatformCore
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * On-device build benchmark: opens a real Android project already present on this device, forces a **fresh**
 * `assembleDebug`, and times every build task (especially `dexBuilderDebug`) so we can measure the actual
 * on-ART build cost and A/B code changes without tapping Run by hand.
 *
 * It runs the SAME in-process ART wiring the app uses (`AndroidSdk.forDevice` over the bundled `android.jar` +
 * native `aapt2`/`zipalign`, `AndroidBuildSystem.inProcess`), except it drives an in-process D8 for both the
 * archive and the merge (no forked `dalvikvm`) so the number is the clean dex-on-ART cost, not fork/interpreter
 * overhead. It reuses the project's ALREADY-RESOLVED dependencies (`.platform/libraries.json`), so it needs no
 * network — create the project once in the app so its deps resolve, then run this repeatedly.
 *
 * Run it (Android Studio JBR builds the test APK; a device/emulator must be attached):
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.bench.OnDeviceBuildBenchmarkTest
 *     adb logcat -s BuildBench
 *
 * Instrumentation args (all optional):
 *   -e project   <name>        which project under codeassist/projects to build (default: first android-app found)
 *   -e coldLibs  true|false    true (default) = fresh dex cache → dexes every library from scratch (the fresh-build
 *                              cost); false = reuse the shared library-dex cache (measures library reuse)
 *   -e rounds    <n>           build n times (default 1); round 1 is the reported fresh build, later rounds show
 *                              warm-cache behavior
 *   -e maxParallel <n>         task-graph parallelism (default 2, matching the app)
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBuildBenchmarkTest {

    private val TAG = "BuildBench"

    @Test
    fun freshBuildOfAnOnDeviceProject() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val coldLibs = args.getString("coldLibs")?.toBoolean() ?: true
        val rounds = args.getString("rounds")?.toIntOrNull() ?: 1
        val maxParallel = args.getString("maxParallel")?.toIntOrNull() ?: 2
        // forkedD8=true reproduces the app's REAL on-device dexer (ForkedD8Dexer): archive in-process (shared
        // providers), but MERGE in a forked `dalvikvm` (interpreted). Default false = in-process D8 for both, so
        // an A/B of the two runs isolates the forked-`dalvikvm` merge penalty. Self-falls-back to in-process if
        // forking isn't usable here.
        val forkedD8 = args.getString("forkedD8")?.toBoolean() ?: false

        // The app's on-device home (external app storage): codeassist/projects/<name>, plus the shared caches.
        val home = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "codeassist")
        val projectsRoot = File(home, "projects").apply { mkdirs() }

        // The bundled SDK bits: android.jar (asset) + native aapt2/zipalign (extracted lib dir) + debug keystore.
        val work = File(ctx.filesDir, "build-bench").apply { mkdirs() }
        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar")).toPath()
        val stubs = copyAsset(ctx, "core-lambda-stubs.jar", File(work, "core-lambda-stubs.jar")).toPath()
        val debugKeystore = copyAsset(ctx, "debug.keystore", File(work, "debug.keystore")).toPath()
        val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir).toPath()
        val sdk = AndroidSdk.forDevice(androidJar, nativeLibDir)
        assumeTrue("Native aapt2/zipalign not extracted at $nativeLibDir — can't build here", sdk.hasNativeTools())
        val signing = SigningConfig(debugKeystore, DebugKeystore.STORE_PASS, DebugKeystore.KEY_ALIAS, DebugKeystore.KEY_PASS)

        // Target project: an explicitly-named existing one (`-e project <name>`), else a self-contained Material
        // app this test seeds + resolves under `bench-material` — so it runs on a fresh/wiped emulator with no
        // pre-existing project (the user's "create the project" ask).
        val wanted = args.getString("project")
        val workspaceRoot = if (wanted != null) {
            pickProject(projectsRoot, wanted) ?: throw AssertionError("No project named '$wanted' under $projectsRoot")
        } else {
            seedMaterialProject(File(projectsRoot, "bench-material"))
        }
        Log.i(TAG, "project = ${workspaceRoot.name}  coldLibs=$coldLibs forkedD8=$forkedD8 rounds=$rounds maxParallel=$maxParallel")

        val report = StringBuilder()
        report.appendLine("== build-bench ${workspaceRoot.name} coldLibs=$coldLibs forkedD8=$forkedD8 ==")
        // Per-round dexBuilder (archive) time. With coldLibs + rounds>=2 in ONE process, round 0 dexes with D8's
        // hot loops still interpreted (JIT cold) while later rounds re-dex the same libs with D8 already
        // JIT-compiled — so warm rounds approximate what a non-debuggable/AOT IDE gives from the start.
        val dexBuilderPerRound = ArrayList<Long>()

        repeat(rounds) { round ->
            val platform = PlatformCore()
            try {
                // Register the Android module types into the platform BEFORE opening, so the saved project's
                // `android-app`/`android-lib` modules resolve to real types when the model loads.
                AndroidSupport.register(ModuleTypeRegistry(platform.extensions), FacetCodecRegistry())
                val store = ProjectModel.open(workspaceRoot.toPath(), platform, FacetCodecRegistry().register(AndroidFacetCodec))

                val project = store.workspace.projects.firstOrNull { p ->
                    p.modules.any { it.type.id == "android-app" }
                } ?: throw AssertionError("workspace has no android-app module")
                val app = project.modules.first { it.type.id == "android-app" }

                // A fresh build: wipe every module's build/ output. Optionally use a throwaway library-dex cache so
                // the libraries are dexed from scratch (the fresh-build cost) instead of reused.
                clearBuildDirs(workspaceRoot)
                val dexCache = if (coldLibs) File(work, "dexcache-cold-$round").apply { deleteRecursively() }.toPath()
                else File(home, "caches/dex").toPath()

                // The app's real dexer forks the merge to a bigger-heap dalvikvm; the default is in-process D8 for
                // both archive and merge. One instance serves as both the archive dexer and the merge dexer, as the
                // app wires it. Rebuilt per round so a forked VM doesn't leak across rounds.
                val d8: Dexer? = if (forkedD8) ForkedD8Dexer(ctx.applicationContext) else null
                val build = AndroidBuildSystem.inProcess(
                    sdk, signing,
                    bootClasspath = listOf(androidJar, stubs),
                    dexCacheRoot = dexCache,
                    dexer = d8,
                    mergeDexer = d8,
                )
                val graph = build.createBuildGraph(
                    project, BuildRequest(listOf(app.id), VariantSelector("debug"), BuildGoal.PACKAGE),
                )

                val starts = ConcurrentHashMap<String, Long>()
                val took = ConcurrentHashMap<String, Long>()   // task -> ms
                val exec = TaskExecutorImpl(BuildCache(File(work, "buildcache-$round").toPath())) { name: TaskName, status: TaskStatus ->
                    when (status) {
                        TaskStatus.Running -> starts[name.value] = System.nanoTime()
                        TaskStatus.Succeeded, TaskStatus.Failed, TaskStatus.UpToDate ->
                            starts.remove(name.value)?.let { took[name.value] = (System.nanoTime() - it) / 1_000_000 }
                        else -> {}
                    }
                }

                val log = StringBuilder()
                val startNs = System.nanoTime()
                val outcome = runBlocking {
                    exec.execute(graph, SimpleTaskContext(log = { line ->
                        log.appendLine(line)
                        // Surface the dex-scope perf breakdown (parallelism / per-lib cost) to logcat.
                        if ("dexScope" in line) Log.i(TAG, "  $line")
                    }), maxParallel)
                }
                val totalMs = (System.nanoTime() - startNs) / 1_000_000

                val header = "-- round $round: ${if (outcome.succeeded) "OK" else "FAILED"} total=${totalMs}ms --"
                Log.i(TAG, header); report.appendLine(header)
                took.entries.sortedByDescending { it.value }.forEach { (task, ms) ->
                    val line = "  %6d ms  %s".format(ms, task)
                    Log.i(TAG, line); report.appendLine(line)
                }
                if (!outcome.succeeded) {
                    Log.e(TAG, "build log:\n$log")
                    report.appendLine(log.toString().lines().takeLast(40).joinToString("\n"))
                }
                took.entries.firstOrNull { it.key.contains("dexBuilder") }?.let { dexBuilderPerRound.add(it.value) }
                // Shared library-dex cache state: a STABLE digest means one namespace whose bucket count doesn't
                // grow across rounds. A new near-full namespace appearing per round = the digest is churning (the
                // bug that made every build re-dex all libraries). Only meaningful with coldLibs=false (uses the
                // shared cache dir); coldLibs=true uses a throwaway per-round dir.
                runCatching {
                    val nss = dexCache.toFile().listFiles()?.filter { it.isDirectory }.orEmpty()
                    val summary = nss.joinToString("; ") { ns -> "${ns.name}=${ns.listFiles()?.count { it.isDirectory } ?: 0} buckets" }
                    val line = "  dex-cache after round $round: ${nss.size} namespace(s) [$summary]"
                    Log.i(TAG, line); report.appendLine(line)
                }
                // Round 0 (the fresh build) is the one that must succeed; later rounds are warm-cache observations.
                if (round == 0) assertTrue("fresh build failed — see logcat -s $TAG", outcome.succeeded)
            } finally {
                platform.dispose()
            }
        }

        // Cold-JIT vs warm-JIT dexBuilder — the release/AOT-ceiling estimate for the archive (run with
        // `-e coldLibs true -e rounds 3`). Warm-JIT ≈ a non-debuggable IDE where ART AOT-compiles D8 up front.
        if (dexBuilderPerRound.size >= 2) {
            val cold = dexBuilderPerRound.first()
            val warm = dexBuilderPerRound.drop(1).min()
            val pct = if (cold > 0) 100 * (cold - warm) / cold else 0
            // coldLibs=true re-dexes the libraries every round, so the round0→warm delta isolates D8's JIT
            // warmup (≈ the release/AOT ceiling for the archive). coldLibs=false reuses the shared library-dex
            // cache from round 0, so the delta is library-reuse (cold cache → warm cache) — the fresh-vs-reused win.
            val what = if (coldLibs) "JIT-warmup (≈ release/AOT ceiling for the archive)" else "library-cache reuse (cold cache → warm cache)"
            val s = "$what: dexBuilder round0=${cold}ms → warm(min later)=${warm}ms, delta=${cold - warm}ms (~$pct%)"
            Log.i(TAG, s); report.appendLine(s)
        }

        // Leave a report the user can pull: adb pull .../files/codeassist/build-bench-report.txt
        runCatching { File(home, "build-bench-report.txt").writeText(report.toString()) }
        Log.i(TAG, "report written to ${File(home, "build-bench-report.txt")}")
    }

    /**
     * A/B for the "process more libraries in parallel by forking" question: dex ALL resolved library jars to
     * indexed dex in ONE pass, in-process (ART's ~576MB app-heap cap) vs a forked `dalvikvm` with a big `-Xmx`
     * (the only way past that cap — see largeHeap). On a high-RAM device the fork should be GC-free and can dex
     * the whole classpath in one shot; in-process on 576MB may GC-thrash or OOM. Logs both to `BuildBench`.
     */
    @Test
    fun dexAllLibsInProcessVsForked() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val home = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "codeassist")
        File(home, "projects").mkdirs()
        val work = File(ctx.filesDir, "build-bench").apply { mkdirs() }
        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar")).toPath()

        val ws = seedMaterialProject(File(File(home, "projects"), "bench-material"))
        val platform = PlatformCore()
        val libJars: List<Path> = try {
            AndroidSupport.register(ModuleTypeRegistry(platform.extensions), FacetCodecRegistry())
            val store = ProjectModel.open(ws.toPath(), platform, FacetCodecRegistry().register(AndroidFacetCodec))
            store.workspace.libraryTable.libraries
                .flatMap { lib -> lib.classesRoots.map { Paths.get(it.path) } }
                .filter { Files.exists(it) && Files.size(it) > 0L }
        } finally {
            platform.dispose()
        }
        assumeTrue("no resolved library jars to dex", libJars.isNotEmpty())
        Log.i(TAG, "dexer A/B: ${libJars.size} library jars → indexed dex, $cores threads")

        // A) in-process (ART app heap, ~576MB cap).
        val outA = File(work, "dexAB-inproc").apply { deleteRecursively(); mkdirs() }.toPath()
        val a0 = System.nanoTime()
        val ra = runCatching { D8InProcessDexer().dex(libJars, androidJar, 21, false, outA, cores, null) }
        val aMs = (System.nanoTime() - a0) / 1_000_000
        Log.i(TAG, "  in-process dex-all: ${ra.getOrNull()?.success ?: "THREW"} ${aMs}ms")
        ra.getOrNull()?.log?.takeLast(4)?.forEach { Log.i(TAG, "    | $it") }
        ra.exceptionOrNull()?.let { Log.i(TAG, "    | threw: $it") }

        // B) forked big-heap dalvikvm (off the app cap).
        val outB = File(work, "dexAB-forked").apply { deleteRecursively(); mkdirs() }.toPath()
        val b0 = System.nanoTime()
        val rb = runCatching { ForkedD8Dexer(ctx.applicationContext).dex(libJars, androidJar, 21, false, outB, cores, null) }
        val bMs = (System.nanoTime() - b0) / 1_000_000
        Log.i(TAG, "  forked big-heap dex-all: ${rb.getOrNull()?.success ?: "THREW"} ${bMs}ms")
        rb.getOrNull()?.log?.takeLast(6)?.forEach { Log.i(TAG, "    | $it") }
        rb.exceptionOrNull()?.let { Log.i(TAG, "    | threw: $it") }

        Log.i(TAG, "dexer A/B RESULT (${libJars.size} libs): in-process=${aMs}ms(ok=${ra.getOrNull()?.success}) forked-big-heap=${bMs}ms(ok=${rb.getOrNull()?.success})")
    }

    /**
     * The headline case: a **Jetpack Compose (Material3)** app — the classpath that makes `dexExtLibs`/
     * `mergeExtDex` the on-device bottleneck. It seeds a Compose app (Kotlin `MainActivity` + a `@Composable`,
     * `activity-compose` + `material3` + `compose-ui`), resolves the ~60-80-artifact Compose/AndroidX graph, then
     * times a fresh `assembleDebug` per task so the external-library dexing cost is directly visible. It wires the
     * REAL on-device toolchain the app uses for Compose: the in-process K2 compiler ([KotlinJvmCompiler]) with the
     * bundled Compose compiler plugin ([ComposeCompilerPlugin]), and the forked big-heap D8 ([ForkedD8Dexer]) for
     * both archive and merge — so the number includes Kotlin+Compose compilation and the actual dex path
     * (one-pass vs. bounded per-library, decided by [ForkedD8Dexer.runsOffHeap] from available RAM).
     *
     *     ./gradlew :ide-android:connectedDebugAndroidTest \
     *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.bench.OnDeviceBuildBenchmarkTest#freshComposeBuild
     *     adb logcat -s BuildBench
     *
     * Instrumentation args (all optional):
     *   -e minSdk    <n>          app minSdk (default 24). 21..25 forces on-device desugaring + the `dexExtLibs`
     *                             one-pass (the slow path); 26+ turns desugaring off + uses per-library
     *                             cross-project dex buckets — run both to measure the minSdk-26 speedup.
     *   -e coldLibs  true|false   true (default) = throwaway dex cache → dexes the whole Compose classpath fresh
     *                             (the cost being measured); false = reuse the shared cache (measures warm reuse).
     *   -e forkedD8  true|false   true (default) = the app's real forked big-heap D8; false = in-process D8.
     *   -e rounds    <n>          build n times (default 1); round 0 is the reported fresh build, later rounds are
     *                             warm-cache observations (round 1 with an unchanged dep set should be a cache hit).
     *   -e maxParallel <n>        task-graph parallelism (default 2, matching the app).
     */
    @Test
    fun freshComposeBuild() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val minSdk = args.getString("minSdk")?.toIntOrNull() ?: 24
        val coldLibs = args.getString("coldLibs")?.toBoolean() ?: true
        val forkedD8 = args.getString("forkedD8")?.toBoolean() ?: true
        val rounds = args.getString("rounds")?.toIntOrNull() ?: 1
        val maxParallel = args.getString("maxParallel")?.toIntOrNull() ?: 2

        val home = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "codeassist")
        val projectsRoot = File(home, "projects").apply { mkdirs() }
        val work = File(ctx.filesDir, "build-bench").apply { mkdirs() }

        // SDK bits: android.jar (asset) + native aapt2/zipalign (extracted lib dir) + desugar stubs + keystore.
        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar")).toPath()
        val stubs = copyAsset(ctx, "core-lambda-stubs.jar", File(work, "core-lambda-stubs.jar")).toPath()
        val debugKeystore = copyAsset(ctx, "debug.keystore", File(work, "debug.keystore")).toPath()
        val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir).toPath()
        val sdk = AndroidSdk.forDevice(androidJar, nativeLibDir)
        assumeTrue("Native aapt2/zipalign not extracted at $nativeLibDir — can't build here", sdk.hasNativeTools())
        val signing = SigningConfig(debugKeystore, DebugKeystore.STORE_PASS, DebugKeystore.KEY_ALIAS, DebugKeystore.KEY_PASS)

        // On-device kotlinc needs its resource dir on a real filesystem path (IntelliJ-core reads EP XML from
        // there) + the app-env keepalive the real build uses. Set before the first KotlinCoreEnvironment.
        System.setProperty("kotlin.environment.keepalive", "true")
        System.setProperty("kotlinc.art.home", provisionKotlincHome(ctx, File(work, "kotlinc-home")).absolutePath)

        // Per-minSdk project dir: the seed reuses an on-disk project (only refreshing sources), so a shared dir
        // would keep the first run's minSdk. A suffixed dir gives each minSdk its own correctly-configured project.
        val workspaceRoot = seedComposeProject(File(projectsRoot, "bench-compose-min$minSdk"), minSdk)
        Log.i(TAG, "compose project = ${workspaceRoot.name} minSdk=$minSdk coldLibs=$coldLibs forkedD8=$forkedD8 rounds=$rounds")

        val report = StringBuilder()
        report.appendLine("== compose-build-bench ${workspaceRoot.name} minSdk=$minSdk coldLibs=$coldLibs forkedD8=$forkedD8 ==")

        // The Kotlin+Compose toolchain: one warm K2 compiler (as the app keeps it app-scoped) + the bundled
        // Compose plugin (its registrar is dexed into the app, so the default plugin loader resolves it).
        val kotlin = IncrementalKotlinCompiler(KotlinJvmCompiler())
        val plugins: List<KotlinCompilerPlugin> = listOf(ComposeCompilerPlugin)

        repeat(rounds) { round ->
            val platform = PlatformCore()
            try {
                AndroidSupport.register(ModuleTypeRegistry(platform.extensions), FacetCodecRegistry())
                val store = ProjectModel.open(workspaceRoot.toPath(), platform, FacetCodecRegistry().register(AndroidFacetCodec))
                val project = store.workspace.projects.firstOrNull { p -> p.modules.any { it.type.id == "android-app" } }
                    ?: throw AssertionError("workspace has no android-app module")
                val app = project.modules.first { it.type.id == "android-app" }

                clearBuildDirs(workspaceRoot)
                val dexCache = if (coldLibs) File(work, "dexcache-compose-$round").apply { deleteRecursively() }.toPath()
                else File(home, "caches/dex").toPath()

                val d8: Dexer? = if (forkedD8) ForkedD8Dexer(ctx.applicationContext) else null
                val build = AndroidBuildSystem.inProcess(
                    sdk, signing,
                    bootClasspath = listOf(androidJar, stubs),
                    kotlin = kotlin,
                    plugins = plugins,
                    dexCacheRoot = dexCache,
                    dexer = d8,
                    mergeDexer = d8,
                )
                val graph = build.createBuildGraph(
                    project, BuildRequest(listOf(app.id), VariantSelector("debug"), BuildGoal.PACKAGE),
                )

                val starts = ConcurrentHashMap<String, Long>()
                val took = ConcurrentHashMap<String, Long>()
                val exec = TaskExecutorImpl(BuildCache(File(work, "buildcache-compose-$round").toPath())) { name: TaskName, status: TaskStatus ->
                    when (status) {
                        TaskStatus.Running -> starts[name.value] = System.nanoTime()
                        TaskStatus.Succeeded, TaskStatus.Failed, TaskStatus.UpToDate ->
                            starts.remove(name.value)?.let { took[name.value] = (System.nanoTime() - it) / 1_000_000 }
                        else -> {}
                    }
                }

                val log = StringBuilder()
                val startNs = System.nanoTime()
                val outcome = runBlocking {
                    exec.execute(graph, SimpleTaskContext(log = { line ->
                        log.appendLine(line)
                        if ("dexScope" in line || "dexExtLibs" in line || "one-pass" in line) Log.i(TAG, "  $line")
                    }), maxParallel)
                }
                val totalMs = (System.nanoTime() - startNs) / 1_000_000

                val header = "-- round $round: ${if (outcome.succeeded) "OK" else "FAILED"} total=${totalMs}ms --"
                Log.i(TAG, header); report.appendLine(header)
                took.entries.sortedByDescending { it.value }.forEach { (task, ms) ->
                    val line = "  %6d ms  %s".format(ms, task)
                    Log.i(TAG, line); report.appendLine(line)
                }
                if (!outcome.succeeded) {
                    Log.e(TAG, "build log:\n$log")
                    report.appendLine(log.toString().lines().takeLast(40).joinToString("\n"))
                }
                if (round == 0) assertTrue("fresh compose build failed — see logcat -s $TAG", outcome.succeeded)
            } finally {
                platform.dispose()
            }
        }

        runCatching { File(home, "compose-build-bench-report.txt").writeText(report.toString()) }
        Log.i(TAG, "report written to ${File(home, "compose-build-bench-report.txt")}")
    }

    /** The workspace with the requested [name], else the first one containing an android-app module. */
    private fun pickProject(projectsRoot: File, name: String?): File? {
        val candidates = if (name != null) listOfNotNull(File(projectsRoot, name).takeIf { it.isDirectory })
        else projectsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        return candidates.firstOrNull { ws ->
            // An android-app module has an [android] table with isApplication in one of its module.toml files.
            ws.walkTopDown().maxDepth(2).any { it.name == "module.toml" && it.readText().let { t -> "isApplication = true" in t } }
        }
    }

    /** Wipe every `<module>/build` under the workspace so the next build is fresh. */
    private fun clearBuildDirs(workspaceRoot: File) {
        workspaceRoot.listFiles()?.filter { it.isDirectory }?.forEach { module ->
            File(module, "build").takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun copyAsset(ctx: Context, assetName: String, dest: File): File {
        ctx.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    /**
     * Seed a self-contained "Material You" android-app (material:1.12.0, minSdk 21) under [ws] and resolve its
     * dependency graph from the network into [ws]/.platform, so the benchmark runs on a fresh/wiped emulator with
     * no pre-existing project. Reused across runs once resolved (the download cache + libraries.json persist).
     */
    private fun seedMaterialProject(ws: File): File {
        // Reuse a previously resolved project (the ~50-artifact download persists), but ALWAYS refresh the app
        // sources so template fixes apply without re-downloading.
        if (File(ws, "app/module.toml").exists() && File(ws, ".platform/libraries.json").exists()) {
            writeAppSources(ws)
            Log.i(TAG, "reusing seeded+resolved project at $ws (sources refreshed)"); return ws
        }
        Log.i(TAG, "seeding + resolving material project at $ws (first run downloads ~50 deps)")
        ws.deleteRecursively(); ws.mkdirs()

        val platform = PlatformCore()
        try {
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            val store = ProjectModel.open(ws.toPath(), platform, FacetCodecRegistry().register(AndroidFacetCodec))

            // Create the project + android-app module in the MODEL (writes workspace.json + module.toml). The
            // android-app type supplies the default source sets (src/main/java, src/main/res, …); the material
            // dependency is declared here and resolved below.
            store.workspace.beginModification().apply { addProject("bench", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.first { it.name == "bench" }.beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.bench", compileSdk = 34, minSdk = 21, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef("com.google.android.material:material:1.12.0"), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            // App sources/manifest/res as files (not model). A built-in Material theme avoids needing the material
            // AAR's own theme to resolve at link time; the app just pulls material onto the (dexed) classpath.
            writeAppSources(ws)

            val lfs = LocalFileSystem(ws.toPath())
            val resolver = MavenDependencyResolver(ResolverCache(ws.toPath()), { p -> lfs.fileFor(p) })
            val repos = listOf(
                Repository("Google", "https://dl.google.com/android/maven2"),
                Repository("Maven Central", "https://repo1.maven.org/maven2"),
            )
            val result = runBlocking {
                resolver.resolve(listOf(Coordinate("com.google.android.material", "material", "1.12.0")), repos, ConflictPolicy.NEWEST, NoopProgress)
            }
            Log.i(TAG, "resolved ${result.resolved.size} artifacts (unresolved=${result.unresolved})")
            assumeTrue("dependency resolution failed (network?): unresolved=${result.unresolved}", result.resolved.isNotEmpty() && result.unresolved.isEmpty())
            store.workspace.libraryTable.create("com.google.android.material:material:1.12.0").apply {
                kind = if (result.resolved.any { it.kind == ArtifactKind.AAR }) LibraryKind.AAR else LibraryKind.JAR
                result.resolved.forEach { a -> addClassesRoot(a.classesRoot); a.extraClassesRoots.forEach { addClassesRoot(it) } }
                commit()
            }
            store.save()
        } finally {
            platform.dispose()
        }
        return ws
    }

    private fun writeAppSources(ws: File) {
        write(File(ws, "app/src/main/java/com/example/bench/MainActivity.java"), MAIN_ACTIVITY)
        write(File(ws, "app/src/main/AndroidManifest.xml"), MANIFEST)
        write(File(ws, "app/src/main/res/values/strings.xml"), STRINGS)
    }

    /**
     * Seed a self-contained Jetpack Compose (Material3) android-app under [ws] at [minSdk] and resolve the
     * `activity-compose` + `material3` + `compose-ui` graph (~60-80 AndroidX/Compose artifacts) from the network
     * into [ws]/.platform, so the benchmark runs on a fresh/wiped emulator. Reused across runs once resolved (the
     * download cache + libraries.json persist). The whole graph goes into one aggregate library the app depends
     * on; the app uses a framework theme so aapt2 links without needing the Compose AARs' own themes (the code is
     * on the dex classpath either way — the point of the benchmark).
     */
    private fun seedComposeProject(ws: File, minSdk: Int): File {
        val stackName = "androidx.compose:bench-compose-stack:1.0"
        if (File(ws, "app/module.toml").exists() && File(ws, ".platform/libraries.json").exists()) {
            writeComposeAppSources(ws)
            Log.i(TAG, "reusing seeded+resolved compose project at $ws (sources refreshed)"); return ws
        }
        Log.i(TAG, "seeding + resolving compose project at $ws (first run downloads ~70 deps)")
        ws.deleteRecursively(); ws.mkdirs()

        val platform = PlatformCore()
        try {
            val types = ModuleTypeRegistry(platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            val store = ProjectModel.open(ws.toPath(), platform, FacetCodecRegistry().register(AndroidFacetCodec))

            store.workspace.beginModification().apply { addProject("bench", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.first { it.name == "bench" }.beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    languageLevel = LanguageLevel.JAVA_8
                    putFacet(AndroidFacet(namespace = "com.example.compose", compileSdk = 34, minSdk = minSdk, targetSdk = 34))
                    addDependency(LibraryDependency(LibraryRef(stackName), DependencyScope.IMPLEMENTATION))
                }
                commit()
            }
            writeComposeAppSources(ws)

            val lfs = LocalFileSystem(ws.toPath())
            val resolver = MavenDependencyResolver(ResolverCache(ws.toPath()), { p -> lfs.fileFor(p) })
            val repos = listOf(
                Repository("Google", "https://dl.google.com/android/maven2"),
                Repository("Maven Central", "https://repo1.maven.org/maven2"),
            )
            // A representative Compose app classpath: activity integration + Material3 + the compose-ui/foundation/
            // runtime/animation graph they pull transitively — the heavy, mostly-Kotlin classpath that dominates
            // dexExtLibs. Pinned versions so resolution is deterministic and offline after the first run.
            val coords = listOf(
                Coordinate("androidx.activity", "activity-compose", "1.9.3"),
                Coordinate("androidx.compose.material3", "material3", "1.3.1"),
                Coordinate("androidx.compose.ui", "ui-tooling-preview", "1.7.5"),
            )
            val result = runBlocking { resolver.resolve(coords, repos, ConflictPolicy.NEWEST, NoopProgress) }
            Log.i(TAG, "resolved ${result.resolved.size} artifacts (unresolved=${result.unresolved})")
            assumeTrue("dependency resolution failed (network?): unresolved=${result.unresolved}", result.resolved.isNotEmpty() && result.unresolved.isEmpty())
            store.workspace.libraryTable.create(stackName).apply {
                kind = if (result.resolved.any { it.kind == ArtifactKind.AAR }) LibraryKind.AAR else LibraryKind.JAR
                result.resolved.forEach { a -> addClassesRoot(a.classesRoot); a.extraClassesRoots.forEach { addClassesRoot(it) } }
                commit()
            }
            store.save()
        } finally {
            platform.dispose()
        }
        return ws
    }

    private fun writeComposeAppSources(ws: File) {
        write(File(ws, "app/src/main/kotlin/com/example/compose/MainActivity.kt"), MAIN_ACTIVITY_KT)
        write(File(ws, "app/src/main/AndroidManifest.xml"), COMPOSE_MANIFEST)
        write(File(ws, "app/src/main/res/values/strings.xml"), STRINGS)
    }

    /** Extract the kotlinc-resources.zip asset (the compiler's non-class resources) into [home] — the
     *  `kotlinc.art.home` the ASM-patched PathUtil reads so IntelliJ-core finds its EP descriptors on ART. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively(); home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {          // zip-slip guard
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    private fun write(f: File, content: String) { f.parentFile?.mkdirs(); f.writeText(content.trimIndent() + "\n") }

    private object NoopProgress : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }

    private companion object {
        val MAIN_ACTIVITY = """
            package com.example.bench;
            public class MainActivity extends android.app.Activity {
            }
        """

        val MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.bench">
                <application android:label="@string/app_name" android:theme="@android:style/Theme.Material.Light">
                    <activity android:name="com.example.bench.MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """

        val STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Bench Material</string>
            </resources>
        """

        // Kotlin activity + a @Composable: exercises the K2 compile AND the Compose plugin transform (setContent
        // lambda + the restartable Greeting), and references material3.Text / activity-compose / runtime.Composable
        // from the resolved classpath — so the build compiles Kotlin+Compose and then dexes the whole graph.
        val MAIN_ACTIVITY_KT = """
            package com.example.compose

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { Greeting("Compose") }
                }
            }

            @Composable
            fun Greeting(name: String) {
                Text("Hello, " + name)
            }
        """

        val COMPOSE_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.compose">
                <application android:label="@string/app_name" android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity android:name="com.example.compose.MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """
    }
}
