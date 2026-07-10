package dev.ide.android.bench

import android.os.Debug
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.spike.SpikeComposeActivity
import dev.ide.interp.InterpProfile
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.lang.CompilationContext
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.platform.log.PerfTrace
import dev.ide.vfs.VirtualFile
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

/**
 * On-device (ART) profile of the Compose preview interpreter RUNTIME — the tree-walk + reflective dispatch +
 * Compose ABI that run when a `@Preview` first renders, recomposes on a state change, and re-renders after a
 * live edit. It is the counterpart to the desktop `KotlinComposeLoweringBenchmark` (which times the *lowering*
 * stage): here we exercise the stage the desktop harness cannot — real reflection against the real bundled
 * Compose runtime on ART — because that cost was never measured (see `docs/compose-interpreter.md` / the perf
 * memories: "ART never measured, these are warm desktop numbers").
 *
 * Scope: the runtime, not lowering. The program is hand-built (like the spike tests) so it is deterministic
 * and needs no on-device analyzer/classpath; on-device lowering time is covered by the live `pass=lower` /
 * `kt.lowerPreview` logs the profiler emits in the running IDE. The fixture is a flat tree — one `Root` that
 * reads a `MutableState` and calls N distinct child composables, each emitting a few `Spacer`s — chosen so all
 * three phases and the `$changed` skip path are exercised:
 *   - first render  → Root + N children + 3N Spacers.
 *   - recompose     → mutate the state Root read; Root re-runs, the N (unchanged-arg) children SKIP.
 *   - live edit     → swap in a fresh instance of ONE child; Root re-runs, that child is FORCED, the rest SKIP.
 *
 * Reads structured per-pass numbers off [InterpProfile.onTrace] (no log scraping) and reports median wall-clock
 * + the profiler counters (dispatch calls, cache misses, composables run/skipped, `Env`/closure allocations)
 * per phase, plus a best-effort ART global allocation-count delta. Opt-in (needs an emulator/device; excluded
 * from CI_CORE_ONLY like the desktop regression suite):
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.bench.ComposeInterpreterBenchmarkTest
 *     adb logcat -s interp-bench interp-perf
 */
@RunWith(AndroidJUnit4::class)
class ComposeInterpreterBenchmarkTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val span = SourceSpan(0, 0)

    // Collected per-pass summaries (composition runs on the UI thread; the test reads on the instr. thread).
    private val samples: MutableList<InterpProfile.Summary> = Collections.synchronizedList(ArrayList())

    @After
    fun tearDown() {
        InterpProfile.onTrace = null
        PerfTrace.enabled = false
    }

    @Test
    fun profilePreviewRuntimeOnDevice() {
        PerfTrace.enabled = true // turns InterpProfile (and the KotlinPerf lowering spans) on
        InterpProfile.onTrace = { samples.add(it) }

        val root = buildRoot()
        val baseChildren = (0 until N).associate { "Child$it/1" to buildChild(it) }
        // [salt] makes a rebuilt child NOT `.equals()` the previous one (a data-class ResolvedFunction is equal
        // when structurally identical). A real edit changes the body so `program` differs and Render recomposes;
        // without a salt the "edited" child is equal → Compose skips Render → no live-edit pass ever runs.
        fun program(freshChild: Int?, salt: Int = 0): Map<String, ResolvedFunction> =
            baseChildren.mapValues { (k, v) -> if (k == "Child$freshChild/1") buildChild(freshChild!!, salt) else v } +
                ("Root/2" to root)

        val renderer = ComposePreviewRenderer(loader = null, tolerateGaps = true)

        // --- Phase 1: first render (fresh composition per launch, so relaunch for a median). ---
        repeat(FIRST_RENDER_LAUNCHES) {
            ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val tick = mutableStateOf(0)
                    activity.setContent {
                        renderer.Render(entry = root, program = program(null), args = listOf(Modifier, tick))
                    }
                }
                instrumentation.waitForIdleSync()
            }
        }
        report("first", byPhase("first"))

        // --- Phases 2 & 3 share one long-lived composition. ---
        samples.clear()
        val tick = mutableStateOf(0)
        val programState = mutableStateOf(program(null))
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    renderer.Render(entry = root, program = programState.value, args = listOf(Modifier, tick))
                }
            }
            instrumentation.waitForIdleSync()

            // Phase 2: state-driven recomposition (Root re-runs, children skip). Await EACH recomposition — a
            // fire-and-forget loop races ahead of the async Recomposer, so writes coalesce and few traces fire.
            samples.clear()
            val allocRecompose = countingAllocations {
                for (i in 1..ITERATIONS) {
                    val before = samples.size
                    scenario.onActivity { tick.value = i }
                    awaitUntil { samples.size > before }
                }
            }
            report("recompose", byPhase("recompose"), allocRecompose)

            // Phase 3: live edit (swap one child's instance → it is forced, the rest skip).
            samples.clear()
            val allocLiveEdit = countingAllocations {
                for (i in 1..ITERATIONS) {
                    val before = samples.size
                    scenario.onActivity { programState.value = program(freshChild = DIRTY_CHILD, salt = i) }
                    awaitUntil { samples.size > before }
                }
            }
            report("liveEdit", byPhase("liveEdit"), allocLiveEdit)
        }
    }

    /** Poll until [predicate] holds (a new trace was recorded) or the timeout — pumping the main queue so the
     *  Recomposer runs. Returns whether it held; the loop proceeds regardless so one stall can't hang the run. */
    private fun awaitUntil(timeoutMs: Long = 4000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            Thread.sleep(8)
        }
        return predicate()
    }

    private fun byPhase(phase: String): List<InterpProfile.Summary> =
        synchronized(samples) { samples.filter { it.phase == phase } }

    private fun report(phase: String, samples: List<InterpProfile.Summary>, allocObjects: Long = -1) {
        if (samples.isEmpty()) {
            Log.w(TAG, "phase=$phase NO SAMPLES (nothing rendered/recomposed for this phase)")
            return
        }
        val totals = samples.map { it.totalNanos }.sorted()
        val median = totals[totals.size / 2]
        Log.i(
            TAG,
            "phase=$phase n=${samples.size} total median=${ms(median)} min=${ms(totals.first())} max=${ms(totals.last())}" +
                if (allocObjects >= 0) " allocObjects≈$allocObjects (over the batch)" else "",
        )
        // Counters are per-pass and stable across identical passes, so a representative sample characterizes them.
        Log.i(TAG, "  representative: ${samples.last().line()}")
    }

    /** Best-effort count of objects the process allocated while [body] ran — the ART GC lever. Approximate
     *  (background threads allocate too), guarded so an unsupported build just reports -1. */
    private fun countingAllocations(body: () -> Unit): Long = runCatching {
        Debug.startAllocCounting()
        val before = Debug.getGlobalAllocCount()
        body()
        instrumentation.waitForIdleSync()
        val delta = (Debug.getGlobalAllocCount() - before).toLong()
        Debug.stopAllocCounting()
        delta
    }.getOrElse { body(); -1L }

    /**
     * On-device profile of the LOWERING stage (PSI parse → RNode), the other half of the edit→render loop.
     * Drives the REAL `KotlinSourceAnalyzer` pipeline on ART over a substantial `@Composable` file.
     *
     * SCOPE / caveat: an EMPTY classpath. Library overload resolution (the desktop-dominant `chooseCallee`/
     * `inferType` cost against Material3) needs the project's real dependency JARs, which don't exist as
     * scannable files in a dexed test APK — so the fixture is deliberately SOURCE-ONLY (composables call source
     * helpers/composables + arithmetic/control-flow/string-templates, all lowering COMPLETE with no classpath).
     * This faithfully measures the classpath-free part on ART: PSI parse + source resolution + RNode building.
     * The library-resolution part is characterized by the desktop `KotlinComposeLoweringBenchmark` and, for the
     * real end-to-end figure, the live `kt.lowerPreview` / `pass=lower` logs in the running IDE (already wired).
     */
    @Test
    fun profileLoweringOnDevice() {
        val srcDir = Files.createTempDirectory(
            Paths.get(instrumentation.targetContext.cacheDir.absolutePath), "lower-bench",
        )
        val analyzer = KotlinSourceAnalyzer(emptyClasspathContext(srcDir))
        val vf: VirtualFile = MemFile(srcDir.resolve("Screens.kt"))
        var version = 0L
        fun parse(src: String) = analyzer.incrementalParser.parseFull(Doc(src, vf, ++version))
        fun lower(src: String): Int { parse(src); return analyzer.lowerFile(vf).size + analyzer.lowerFileClasses(vf).size }

        // Cold first parse (includes the KotlinCoreEnvironment / PSI-host init — the cold-start cost).
        val cold = System.nanoTime()
        parse(loweringSource(0, 0))
        val coldMs = (System.nanoTime() - cold) / 1_000_000

        val total = lower(loweringSource(0, 1))
        Log.i(TAG, "lowering fixture: ${LOWER_SCREENS * 2} composables + $HELPERS helpers → $total lowered fns/classes; cold first-parse=${coldMs}ms")

        var p = 1
        val parseNs = medianNanos(warmup = 3, runs = 8) { parse(loweringSource(0, ++p)) }
        var s = 1
        val fullNs = medianNanos(warmup = 3, runs = 8) { lower(loweringSource(0, ++s)) } // new sig → full re-lower
        var b = 0
        val bodyNs = medianNanos(warmup = 3, runs = 8) { lower(loweringSource(++b, 0)) } // body-only → incremental

        Log.i(TAG, "phase=lowering (empty classpath — parse + source-lowering only)")
        Log.i(TAG, "  parse (warm)          median=${ms(parseNs)}")
        Log.i(TAG, "  full re-lower         median=${ms(fullNs)}  (lower≈${ms(fullNs - parseNs)})")
        Log.i(TAG, "  body-edit (per-key)   median=${ms(bodyNs)}  incremental=${"%.1f".format(if (bodyNs > 0) fullNs.toDouble() / bodyNs else 1.0)}x")
    }

    private fun medianNanos(warmup: Int, runs: Int, body: () -> Unit): Long {
        repeat(warmup) { body() }
        val samples = LongArray(runs)
        for (i in 0 until runs) {
            val t = System.nanoTime()
            body()
            samples[i] = System.nanoTime() - t
        }
        samples.sort()
        return samples[runs / 2]
    }

    /** A source-only `@Composable` corpus that lowers COMPLETE with no classpath: data classes, [HELPERS] Int
     *  helpers, and [LOWER_SCREENS] Screen/Leaf composable pairs using arithmetic, `if`/`when`, string
     *  templates, and source calls. [sigVar] varies a top-level token (→ full re-lower); [bodyVar] varies one
     *  body literal (→ incremental re-lower of just Screen0). */
    private fun loweringSource(bodyVar: Int, sigVar: Int): String = buildString {
        append("package app\n")
        append("import androidx.compose.runtime.Composable\n")
        append("// v$sigVar\n")
        append("data class Model(val a: Int, val b: Int, val c: Int)\n")
        append("data class UiState(val n: Int, val open: Boolean)\n\n")
        repeat(HELPERS) { i -> append("fun helper$i(p: Int): Int { val a = p + $i; val b = a * 2 - $i; return b + $i }\n") }
        append("\n")
        repeat(LOWER_SCREENS) { i ->
            val lit = if (i == 0) bodyVar else i
            append(
                """
                @Composable
                fun Screen$i(seed: Int) {
                    val z = $lit
                    val n = helper${i % HELPERS}(seed + $i + z)
                    val tag = "s$i-" + n
                    if (n > $i) { Leaf$i(tag) } else { Leaf$i(tag + "!") }
                    when (n % 3) {
                        0 -> Leaf$i("a" + n)
                        1 -> Leaf$i("b" + n)
                        else -> Leaf$i("c" + n)
                    }
                }
                @Composable
                fun Leaf$i(t: String) { val u = t + ":$i" }

                """.trimIndent(),
            )
            append("\n")
        }
        append("@Composable\nfun Entry() {\n")
        repeat(6) { i -> append("    Screen${i * 3 % LOWER_SCREENS}($i)\n") }
        append("}\n")
    }

    /** A `CompilationContext` with an EMPTY classpath (see [profileLoweringOnDevice]'s caveat). */
    private fun emptyClasspathContext(srcDir: Path): CompilationContext = object : CompilationContext {
        private val emptyCp = object : ClasspathSnapshot {
            override val entries: List<ClasspathEntry> = emptyList()
            override fun fingerprint(): ContentHash = ContentHash("")
        }
        override val sourceRoots: List<VirtualFile> = listOf(MemFile(srcDir))
        override val classpath: ClasspathSnapshot = emptyCp
        override val bootClasspath: ClasspathSnapshot = emptyCp
        override val languageLevel: LanguageLevel = LanguageLevel.JAVA_17
        override val outputDir: VirtualFile = MemFile(srcDir)
        override val processors = emptyList<dev.ide.lang.AnnotationProcessor>()
    }

    /** Minimal disk-backed [VirtualFile] (mirrors the lang-kotlin test `DiskFile`) — the lowering pipeline
     *  reads text from the [Doc] snapshot, so only [path]/[name] are load-bearing here. */
    private class MemFile(private val p: Path) : VirtualFile {
        override val path: String get() = p.toString()
        override val name: String get() = p.fileName.toString()
        override val isDirectory: Boolean get() = Files.isDirectory(p)
        override val exists: Boolean get() = Files.exists(p)
        override val length: Long get() = if (exists && !isDirectory) Files.size(p) else 0
        override fun parent(): VirtualFile? = p.parent?.let { MemFile(it) }
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash("")
        override fun readBytes(): ByteArray = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
        override fun readText(): CharSequence = String(readBytes())
    }

    private class Doc(
        override val text: CharSequence,
        override val file: VirtualFile,
        override val version: Long,
    ) : DocumentSnapshot {
        override fun length(): Int = text.length
    }

    // --- fixture builders -------------------------------------------------------------------------------

    /** `@Composable fun Child<i>(m: Modifier) { <salt>; Spacer(m); Spacer(m); Spacer(m) }` — a skippable (Unit)
     *  leaf. Call-site keys are per-child-index (stable across rebuilds, so the Compose groups are reused, as a
     *  real function-relative key would be); [salt] is a discarded leading `Const` that varies the body so an
     *  "edited" instance is not `.equals()` the old one (mimicking a real body edit). */
    private fun buildChild(i: Int, salt: Int = 0): ResolvedFunction {
        val mRef = { RArg(RNode.Name(Binding.Param(SlotId(0), "m"), span)) }
        val stmts = listOf(RNode.Const(salt, null, span)) +
            (0 until SPACERS_PER_CHILD).map { j -> spacerCall(1000 + i * 100 + j, mRef()) }
        return ResolvedFunction(
            name = "Child$i",
            params = listOf(RParam(SlotId(0), "m", null)),
            body = RNode.Block(stmts, isExpression = false, source = span),
            diagnostics = emptyList(),
            returnsUnit = true,
        )
    }

    /** `@Composable fun Root(m: Modifier, tick: State) { tick.value; Child0(m); … ChildN-1(m) }` — reads the
     *  state (subscribes its scope) then fans out to the children. */
    private fun buildRoot(): ResolvedFunction {
        val readTick = RNode.PropertyGet(
            receiver = RNode.Name(Binding.Param(SlotId(1), "tick"), span),
            binding = Binding.Property("value", ownerFqn = null, backingField = false),
            source = span,
        )
        val childCalls = (0 until N).map { i ->
            RNode.Call(
                callee = ResolvedCallable.Source(
                    displayName = "Child$i", declId = "Child$i/1", paramNames = listOf("m"), isComposable = true,
                ),
                dispatch = DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(RArg(RNode.Name(Binding.Param(SlotId(0), "m"), span))),
                callSiteKey = CallSiteKey(500 + i), source = span,
            )
        }
        return ResolvedFunction(
            name = "Root",
            params = listOf(RParam(SlotId(0), "m", null), RParam(SlotId(1), "tick", null)),
            body = RNode.Block(listOf(readTick) + childCalls, isExpression = false, source = span),
            diagnostics = emptyList(),
            returnsUnit = true,
        )
    }

    private fun spacerCall(key: Int, arg: RArg) = RNode.Call(
        callee = ResolvedCallable.Library(
            displayName = "Spacer", ownerFqn = "androidx.compose.foundation.layout.SpacerKt",
            methodName = "Spacer", paramTypes = emptyList(), isStatic = true, isConstructor = false,
            isInline = false, isComposable = true, descriptorPrecise = true,
        ),
        dispatch = DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(arg),
        callSiteKey = CallSiteKey(key), source = span,
    )

    private fun ms(nanos: Long): String = "${nanos / 1_000_000}.${(nanos % 1_000_000) / 100_000}ms"

    private companion object {
        const val TAG = "interp-bench"
        const val N = 12                // child composables under Root
        const val SPACERS_PER_CHILD = 3
        const val DIRTY_CHILD = 5       // the child a live edit dirties
        const val FIRST_RENDER_LAUNCHES = 5
        const val ITERATIONS = 30       // recompose / live-edit passes to median over
        const val LOWER_SCREENS = 20    // Screen/Leaf composable pairs in the lowering corpus
        const val HELPERS = 8           // Int helper functions in the lowering corpus
    }
}
