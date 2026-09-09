package dev.ide.lang.kotlin

import dev.ide.bench.Bench
import dev.ide.bench.RegressionSuite
import dev.ide.lang.kotlin.resolve.KotlinResolverStats
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Preview-lowering benchmark at LARGE-Compose scale (opt-in `regressionTest`). Unlike
 * [KotlinPreviewLoweringBenchmark] (a stdlib-only classpath + a small file), this lowers a big
 * `@Composable` file against an overload-bearing Compose-shaped classpath (the `dev.ide.fakecompose`
 * fixtures: defaulted-param overload pairs, named-arg-disambiguated overloads, multi-owner member
 * extensions, generic inference, content lambdas). That makes `chooseCallee`/`inferType` — the dominant
 * first-render + per-keystroke cost — actually do the work a real Material3 screen forces.
 *
 * Two metrics, mapping to the two symptoms we care about (slow first render, per-keystroke lag):
 *  - **full re-lower** — change a top-level token each op → `fileSigHash` moves → re-lower EVERY function +
 *    classes. This is the cost of the FIRST render of a large file (and of any signature/import/class edit).
 *  - **body-edit** — change one composable's body literal each op → re-lower only that one function, reuse
 *    every sibling + the classes (the hot per-keystroke case: typing inside a `@Composable`).
 *
 * The win is body-edit ≪ full re-lower on a many-function file. Baseline:
 * `baselines/kotlin-compose-lowering.json`. Latency is machine-dependent (loose drift + an interactive
 * ceiling); allocation is deterministic and gates tighter.
 */
@Tag("regression")
class KotlinComposeLoweringBenchmark {

    private val screenCount = 24
    private val helperCount = 12

    /** A large `@Composable` file: data-class state models, [helperCount] plain helpers, [screenCount]
     *  composable screens each with ~10 resolvable call sites that exercise the overload-resolution paths,
     *  and a `@Preview` entry. [bodyVar] varies a literal in ONE screen (body-edit); [sigVar] varies a
     *  top-level comment token (full re-lower). */
    private fun source(bodyVar: Int, sigVar: Int): String = buildString {
        append("package app\n")
        append("import androidx.compose.runtime.Composable\n")
        append("import dev.ide.fakecompose.*\n")
        append("// v$sigVar\n")
        append("data class Model(val a: Int, val b: String, val c: Float)\n")
        append("data class UiState(val items: List<Int>, val label: String, val open: Boolean)\n\n")
        repeat(helperCount) { i ->
            append(
                "fun helper$i(p: Int): Int { " +
                    "val a = p + $i; val b = listOf(a, $i, p).map { it * 2 }.filter { it > $i }; " +
                    "val c = b.sum() + a; val d = (\"x\" + c).length; return c + d + b.size }\n",
            )
        }
        append("\n")
        repeat(screenCount) { i ->
            // One screen's body literal is the body-edit knob; the rest are stable so they're reused.
            val lit = if (i == 0) "lit$bodyVar" else "s$i"
            append(
                """
                @Composable
                fun Screen$i(label: String) {
                    val text = fakeRemember { fakeMutableStateOf("$lit") }
                    val count = fakeRemember { fakeMutableStateOf(0) }
                    FakeColumn {
                        FakeText(label + "-$i")
                        FakeRow {
                            FakeText("row$i")
                            FakeModifier.scopedWeight(1)
                            val pad = FakeModifier.fakePadding(8)
                            FakeText(pad.fakeBackground().toString())
                        }
                        fakeChip(onClick = {}, label = {})
                        fakeTextField(value = text.value, onValueChange = { })
                        fakeLazyColumn {
                            fakeItemsIndexed(listOf($i, ${i + 1}, ${i + 2})) { idx, t ->
                                FakeText(idx.toString() + ":" + t.toString())
                            }
                        }
                        FakeText(FakeDefaults.theme.scheme.toString())
                        val h = helper${i % helperCount}(label.length + count.value)
                        FakeText("h=" + h)
                    }
                }

                """.trimIndent(),
            )
            append("\n")
        }
        append("@Composable\n")
        append("fun Entry() {\n")
        repeat(6) { i -> append("    Screen${i * 4 % screenCount}(\"e$i\")\n") }
        append("}\n")
    }

    @Test
    fun composeLoweringHoldsAgainstBaseline() {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fakeComposeJar)))
        val vf = DiskFile(srcDir.resolve("Screens.kt"))
        var version = 1L
        fun lower(bodyVar: Int, sigVar: Int): Long {
            analyzer.incrementalParser.parseFull(SnippetDoc(source(bodyVar, sigVar), vf, ++version))
            return analyzer.lowerFile(vf).size.toLong() + analyzer.lowerFileClasses(vf).size
        }

        // Warm the classpath extension scan + sanity: the whole program lowers (screens + helpers + Entry).
        val total = lower(0, 0)
        assertTrue(total >= screenCount + helperCount, "expected the full program to lower (got $total)")

        // How much of the program lowers to COMPLETE functions (no Unsupported) — a realism check: if most
        // screens are Unsupported the benchmark isn't measuring the success path we care about.
        val program = analyzer.lowerFile(vf)
        val complete = program.values.count { it.isComplete }
        println("\n=== Compose lowering corpus: $screenCount screens + $helperCount helpers + Entry; ${program.size} fns, $complete complete ===")

        val suite = RegressionSuite("kotlin-compose-lowering")

        // parse-only: the PSI reparse paid on every edit before lowering even starts. `.flatten()` forces the
        // lazy PSI tree to realize (createFileFromText is lazy), so this is the real reparse cost. Subtract
        // from the full pipeline to attribute parse vs lower.
        var p = 0
        val parseNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 4) {
            analyzer.incrementalParser.parseFull(SnippetDoc(source(0, ++p), vf, ++version)).flatten().size.toLong()
        }

        // Inference vs candidate-enumeration attribution for ONE full lower against a COLD resolver (the
        // first-render shape): how many inferType / callTargets computations the lowering forces, and how
        // effective the per-snapshot cache is (calls vs computes).
        KotlinResolverStats.enabled = true
        KotlinResolverStats.reset()
        lower(0, 100_000) // a never-seen sig → fresh resolver, full re-lower
        val st = "inferType ${KotlinResolverStats.inferComputes} computes / ${KotlinResolverStats.inferCalls} calls" +
            " (hit ${hitPct(KotlinResolverStats.inferCalls, KotlinResolverStats.inferComputes)}%)" +
            "; callTargets ${KotlinResolverStats.callTargetsComputes} computes / ${KotlinResolverStats.callTargetsCalls} calls" +
            " (hit ${hitPct(KotlinResolverStats.callTargetsCalls, KotlinResolverStats.callTargetsComputes)}%)"
        KotlinResolverStats.enabled = false
        println("=== one full lower (cold resolver): $st ===\n")

        // Double-inference probe: the editor runs analyze() (diagnostics) AND preview-lowering on the SAME
        // keystroke, each through its OWN KotlinResolver (separate inferType/callTargets caches), so inference
        // over the file is done TWICE. Count the computes for analyze alone and for lowering alone on one fresh
        // snapshot — they are additive (no sharing), which is the premise for sharing one resolver per snapshot.
        analyzer.incrementalParser.parseFull(SnippetDoc(source(0, 200_001), vf, ++version))
        KotlinResolverStats.enabled = true
        KotlinResolverStats.reset()
        runBlocking { analyzer.analyze(vf) }
        val aInfer = KotlinResolverStats.inferComputes; val aCt = KotlinResolverStats.callTargetsComputes
        KotlinResolverStats.reset()
        analyzer.lowerFile(vf); analyzer.lowerFileClasses(vf)
        val lInfer = KotlinResolverStats.inferComputes; val lCt = KotlinResolverStats.callTargetsComputes
        KotlinResolverStats.enabled = false
        println(
            "=== double inference on one keystroke (separate resolvers) ===\n" +
                "analyze : inferType $aInfer computes, callTargets $aCt computes\n" +
                "lower   : inferType $lInfer computes, callTargets $lCt computes\n" +
                "combined: inferType ${aInfer + lInfer}, callTargets ${aCt + lCt}  (a shared resolver could elide the overlap)\n",
        )

        // full re-lower (first render / signature edit): every function re-lowers each op.
        var s = 0
        val fullNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 4) { lower(0, ++s) }
        val fullBytes = Bench.allocPerOp(warmup = 2, ops = 4) { lower(0, ++s) }
        // body-edit (per keystroke): only Screen0's body changes → re-lower 1, reuse the rest.
        var b = 0
        val bodyNs = Bench.nsPerOp(warmup = 3, runs = 5, ops = 4) { lower(++b, 0) }
        val bodyBytes = Bench.allocPerOp(warmup = 2, ops = 4) { lower(++b, 0) }

        val ratio = if (bodyNs > 0) fullNs / bodyNs else 1.0
        // parse-only forces FULL PSI realization (`.flatten()`); a full re-lower realizes everything too, so
        // its lower-proper ≈ full - parse. A body edit realizes only the edited function's subtree lazily, so
        // it is NOT parse + lower-all and that subtraction wouldn't be meaningful — shown as a total only.
        println(
            "\n=== Compose preview lowering (per-function cache) ===\n" +
                "parse+realize (full file)   : ${Bench.ns(parseNs)}\n" +
                "full re-lower (first render): ${Bench.ns(fullNs)}, alloc ${Bench.bytes(fullBytes.toDouble())}  (lower ≈ ${Bench.ns(fullNs - parseNs)})\n" +
                "body-edit    (per keystroke): ${Bench.ns(bodyNs)}, alloc ${Bench.bytes(bodyBytes.toDouble())}\n" +
                "full/incremental: ${"%.2f".format(ratio)}x\n",
        )

        suite.latencyNs("lowering.full.ns", fullNs, tolerance = 1.5, ceilingNs = 3_000_000_000.0)
        suite.latencyNs("lowering.body-edit.ns", bodyNs, tolerance = 1.5, ceilingNs = 1_000_000_000.0)
        suite.allocBytes("alloc.full.bytes", fullBytes, tolerance = 0.40)
        suite.allocBytes("alloc.body-edit.bytes", bodyBytes, tolerance = 0.40)
        suite.finishAndAssert()
        assertTrue(Bench.sink >= 0L || Bench.sink < 0L)
    }

    /** Cache hit-rate percent: (calls - computes) / calls. */
    private fun hitPct(calls: Long, computes: Long): Int =
        if (calls <= 0) 0 else (((calls - computes) * 100) / calls).toInt()

    companion object {
        val srcDir: Path = tempProject(mapOf("placeholder.kt" to "package app\n"))

        /** Stage the compiled `dev.ide.fakecompose.*` classes + the fake `androidx.compose.runtime.Composable`
         *  annotation into a jar the symbol service can scan (a `kotlin_module` entry makes it look like a
         *  Kotlin library so the extension scan doesn't skip it). */
        val fakeComposeJar: Path by lazy { buildFakeComposeJar() }

        private fun buildFakeComposeJar(): Path {
            // Locate the test-classes output root from a compiled fixture class, then zip the two packages.
            val root = Path.of(
                Class.forName("dev.ide.fakecompose.FakeState").protectionDomain.codeSource.location.toURI(),
            )
            val jar = Files.createTempFile("fake-compose-bench", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/fakecompose.kotlin_module")); zos.closeEntry()
                for (pkg in listOf("dev/ide/fakecompose", "androidx/compose/runtime")) {
                    val dir = root.resolve(pkg)
                    if (!dir.exists()) continue
                    Files.walk(dir).use { paths ->
                        paths.filter { it.toString().endsWith(".class") }.forEach { p ->
                            val name = root.relativize(p).toString().replace('\\', '/')
                            zos.putNextEntry(ZipEntry(name)); zos.write(Files.readAllBytes(p)); zos.closeEntry()
                        }
                    }
                }
            }
            return jar
        }
    }
}
