package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ANALYSIS, swept over real Kotlin — the other half of the parser sweep in `:kotlin-syntax`.
 *
 * Agreeing with the compiler about the TREE says nothing about what the editor then claims is wrong with it.
 * This runs the semantic checks over code that is known-good by construction and treats every ERROR as a
 * suspected FALSE POSITIVE. That is the failure this project cares most about: a missing diagnostic is a gap,
 * a wrong one is a bug report from a user whose correct code is underlined.
 *
 * It is not a parity test against the compiler's diagnostics — our checks are deliberately a subset, so
 * counting what we DON'T report would measure scope rather than correctness. It is a false-positive hunt,
 * plus the harder guarantee that nothing in the pipeline throws on real input.
 *
 * TWO CORPORA, because they are wrong in different ways:
 *
 *  * The Kotlin STANDARD LIBRARY, opt-in because it needs a checkout:
 *
 *        ./gradlew :lang-kotlin:jvmTest --tests '*RealWorldAnalysisSweep*' \
 *            -Dkt.externalCorpus=<kotlin checkout>
 *
 *    A deliberately hard corpus and an unrepresentative one: `expect`/`actual`, builtins with no bodies,
 *    `-opt-in=` and `-Xallow-kotlin-package` flags no ordinary project passes.
 *
 *  * THIS REPOSITORY's own Kotlin, which needs no checkout — it is the one the build is running in:
 *
 *        ./gradlew :lang-kotlin:jvmTest --tests '*RealWorldAnalysisSweep*' -Dkt.sweep=true
 *
 *    The other kind of input, and the kind users actually write: coroutines, Compose, sealed hierarchies,
 *    DSLs, delegation. A structural check that fires here is a bug report waiting to happen. Opt-in like the
 *    other one, because ~3,000 files of analysis does not belong in the fast correctness gate.
 *
 * WHAT THE CLASSPATH IS. The kotlin-stdlib jar and nothing else — no JDK, no android.jar, and for the
 * repository sweep no Compose, coroutines, ASM or JDT either. So every name out of any of those is genuinely
 * not there to find, and `kt.unresolved` measures the harness rather than the checker. It is reported,
 * because a real false positive would show up in it, but it is kept out of the headline count, which is
 * about the STRUCTURAL and flow checks — the population that can go wrong without an index at all.
 */
class KotlinRealWorldAnalysisSweepTest {

    /**
     * WHICH FILES COUNT in the stdlib sweep: the classpath is the RELEASED JVM stdlib jar, so only the source
     * sets that jar actually describes can be held to it. The JS, wasm and native sets declare types the JVM
     * jar has never heard of (`JsAny`, `ExperimentalWasmJsInterop`) and members with no bodies that only a
     * builtins compilation accepts. Every file is still SWEPT — the crash gate wants the breadth — but the
     * headline number and the per-code tally come from the checkable half, and the rest is reported
     * separately so the split stays visible rather than quietly inflating both.
     */
    @Test
    fun theCheckersReportNothingOnTheKotlinStandardLibrarysOwnSources() {
        val root = System.getProperty("kt.externalCorpus")?.let(::File) ?: return
        val stdlib = File(root, "libraries/stdlib")
        assertTrue(stdlib.isDirectory, "expected libraries/stdlib under $root")

        val files = kotlinFilesUnder(stdlib, skip = setOf("build", "test", "testData"))
        assertTrue(files.size > 500, "expected the stdlib sources, found ${files.size}")

        val jvmCheckable = setOf("common", "src", "jvm", "jdk7", "jdk8", "unsigned")
        val report = sweep(files, stdlib) { it.substringBefore('/') in jvmCheckable }

        println(
            "stdlib sweep: ${report.clean}/${report.counted} JVM-checkable files clean of structural " +
                "errors, ${report.crashed} crashed",
        )
        println(
            "  (not counted: ${report.skippedWithErrors}/${report.skipped} JS/wasm/native files report " +
                "something against a JVM classpath that does not describe them)",
        )
        report.printCodes()
        assertTrue(report.crashed == 0, "the analysis threw on ${report.crashed} of ${files.size} stdlib files")
    }

    /** The repository's own Kotlin; the build hands over its root only when `-Dkt.sweep` asked for it. */
    @Test
    fun theCheckersReportNothingOnThisRepositorysOwnKotlin() {
        val root = System.getProperty("kt.repoRoot")?.let(::File) ?: return
        // `testData` is the parser's corpus, deliberately full of malformed Kotlin.
        val files = kotlinFilesUnder(root, skip = setOf("build", ".gradle", "testData"))
        assertTrue(files.size > 2000, "expected this repository's sources, found ${files.size}")

        val report = sweep(files, root) { true }

        println(
            "repository sweep: ${report.clean}/${report.counted} files clean of structural errors, " +
                "${report.crashed} crashed",
        )
        report.printCodes()
        assertTrue(report.crashed == 0, "the analysis threw on ${report.crashed} of ${files.size} files")
    }

    /**
     * The third corpus: THIS module's own sources, against THIS module's REAL classpath.
     *
     * The other two sweeps carry the kotlin-stdlib jar and nothing else, which makes `kt.unresolved`
     * meaningless and everything downstream of resolution meaningless with it -- a type mismatch or an
     * argument count against a type we could not find says nothing. Handing the whole repository a real
     * classpath does not work either: its 2,600 files span a hundred modules with different dependencies,
     * and pointing one module's classpath at all of them was measured at over four minutes without
     * finishing.
     *
     * One module, though, has an exactly correct classpath already: the one these tests run in. The test
     * JVM's `java.class.path` IS `:lang-kotlin`'s test runtime classpath -- its dependencies as jars, its own
     * output as directories -- so for files under `lang/lang-kotlin/src` every name they use is genuinely
     * there to find. That makes this the only sweep whose RESOLUTION-dependent buckets mean anything, which
     * is why `kt.unresolved` is counted here and excluded in the other two.
     *
     * `iosMain`/`iosTest` are left out: they compile against Kotlin/Native, which a JVM classpath has none of.
     */
    @Test
    fun theCheckersReportNothingOnThisModulesOwnSourcesAgainstItsRealClasspath() {
        val root = System.getProperty("kt.repoRoot")?.let(::File) ?: return
        val module = File(root, "lang/lang-kotlin/src")
        assertTrue(module.isDirectory, "expected this module's sources at $module")

        val files = kotlinFilesUnder(module, skip = setOf("build", "iosMain", "iosTest"))
        assertTrue(files.size > 100, "expected this module's sources, found ${files.size}")

        val report = sweep(files, module, moduleAnalyzer, classpathBound = emptySet(), docRoot = moduleSrcDir) { true }

        println(
            "module sweep (real classpath): ${report.clean}/${report.counted} files clean, " +
                "${report.crashed} crashed"
        )
        report.printCodes()
        assertTrue(report.crashed == 0, "the analysis threw on ${report.crashed} of ${files.size} files")
    }

    // ---- the sweep itself ----------------------------------------------------------------------------

    private fun kotlinFilesUnder(root: File, skip: Set<String>): List<File> =
        root.walkTopDown()
            .onEnter { it.name !in skip && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /**
     * Analyze each of [files] and tally what the checkers said. [counts] decides which files the headline
     * number and the per-code tally are drawn from; the rest are still analyzed — the crash gate wants every
     * file — and reported only as a count.
     */
    private fun sweep(
        files: List<File>,
        base: File,
        with: KotlinSourceAnalyzer = analyzer,
        classpathBound: Set<String> = CLASSPATH_BOUND,
        docRoot: java.nio.file.Path = srcDir,
        counts: (String) -> Boolean,
    ): Report {
        val report = Report(classpathBound)
        for (file in files) {
            val text = runCatching { file.readText().replace("\r\n", "\n") }.getOrNull() ?: continue
            // The RELATIVE path, not the base name. A real tree has many same-named files (`Array.kt`,
            // `Collections.kt`, …); keying on the name alone collapses them onto one VirtualFile, the source
            // model merges their declarations, and the sweep then invents thousands of repeated-modifier and
            // conflicting-declaration reports that the checkers never actually produce.
            val relative = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val doc = SnippetDoc(text, DiskFile(docRoot.resolve(relative)))
            val diagnostics = runCatching {
                runBlocking { with.incrementalParser.parseFull(doc); with.analyze(doc.file).diagnostics }
            }.getOrElse { report.crash(relative, it); emptyList() }

            // Syntax errors are the parser's business and the `:kotlin-syntax` sweep already gates them; a
            // file that fails to PARSE would show up there as a divergence from the compiler.
            val errors = diagnostics.filter {
                it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
            }
            report.record(relative, errors, counted = counts(relative))
        }
        return report
    }

    private class Report(private val classpathBound: Set<String>) {
        /** The files the analysis THREW on, with what it threw. A count alone says the gate failed and
         *  nothing about where to look, and this gate is the one that matters most: the analysis runs on
         *  every keystroke, so an exception is a dead editor pane. */
        private val crashes = LinkedHashMap<String, String>()
        val crashed: Int get() = crashes.size
        var counted = 0
            private set
        var skipped = 0
            private set
        var skippedWithErrors = 0
            private set
        private var withErrors = 0
        private val byCode = HashMap<String, Int>()

        /** Up to three DISTINCT messages per code. One sample names the category; three show whether the hits
         *  are one repeated shape (usually a single missing rule) or a scatter of unrelated ones. */
        private val samples = HashMap<String, MutableSet<String>>()

        /** Per code, how the hits are spread over files. A count alone cannot tell a real false positive from
         *  a corpus artefact: 3,546 "property must be initialized" all landing in the JS builtins is one
         *  quirk, whereas the same count spread over 400 ordinary files is a bug every user would hit. */
        private val byCodeFiles = HashMap<String, MutableMap<String, Int>>()

        val clean: Int get() = counted - withErrors

        fun crash(relative: String, cause: Throwable) {
            crashes[relative] = "${cause::class.simpleName}: ${cause.message?.take(160)}"
        }

        fun record(relative: String, errors: List<Diagnostic>, counted: Boolean) {
            if (!counted) {
                skipped++
                if (errors.isNotEmpty()) skippedWithErrors++
                return
            }
            this.counted++
            if (errors.isEmpty()) return
            if (errors.any { it.code !in classpathBound }) withErrors++
            for (d in errors) {
                val code = d.code ?: "<none>"
                byCode.merge(code, 1, Int::plus)
                byCodeFiles.getOrPut(code) { HashMap() }.merge(relative, 1, Int::plus)
                samples.getOrPut(code) { LinkedHashSet() }.let { if (it.size < 3) it += "$relative: ${d.message}" }
            }
        }

        fun printCodes() {
            crashes.forEach { (file, cause) -> println("  THREW on $file -- $cause") }
            byCode.entries.sortedByDescending { it.value }.forEach { (code, n) ->
                val spread = byCodeFiles[code].orEmpty()
                println("  $n x $code  (across ${spread.size} files)")
                samples[code].orEmpty().forEach { println("      $it") }
                spread.entries.sortedByDescending { it.value }.take(3)
                    .forEach { (f, c) -> println("      $c in $f") }
            }
            byCode.keys.filter { it in classpathBound }.forEach {
                println("  ^ $it is classpath-bound: this harness holds the stdlib jar and nothing else, so a")
                println("    `java.lang` (or Compose, or coroutines) name has nothing to resolve to. Not counted.")
            }
        }
    }

    companion object {
        /** Codes whose count here is about the harness's classpath, not about the checker. See the class doc. */
        private val CLASSPATH_BOUND = setOf(KotlinDiagnosticCodes.UNRESOLVED)

        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))

        /** The jars this test JVM runs against — exactly `:lang-kotlin`'s own compile+test classpath. */
        private val moduleClasspath: List<java.nio.file.Path> =
            System.getProperty("java.class.path").orEmpty()
                .split(File.pathSeparatorChar)
                .filter { it.endsWith(".jar") }
                .map { java.nio.file.Paths.get(it) }
                .filter { java.nio.file.Files.isRegularFile(it) }

        /**
         * LAZY, and that is not a style choice: standing a symbol service up over these 68 jars costs
         * minutes, all of it before the first file is analyzed, and an eager `val` here made EVERY run of
         * this class pay it -- including the repository sweep, which does not use it at all.
         */
        /**
         * THIS module's real source root.
         *
         * The other two sweeps hand every file a path under the shared temp [srcDir], which is enough for
         * them because they judge one file at a time and discard `kt.unresolved` wholesale. This sweep counts
         * it, so the model has to be able to FIND the module: `SourceIndexBuilder.build` walks the source
         * roots and reads each file off disk, so a root pointing at a temp directory that holds one seed file
         * gives the analysis nothing to resolve a sibling declaration against. Rooted here, a reference to a
         * class in the file next door resolves the way it does in the editor.
         */
        private val moduleSrcDir: java.nio.file.Path =
            java.nio.file.Paths.get(System.getProperty("kt.repoRoot").orEmpty(), "lang/lang-kotlin/src")

        val moduleAnalyzer: KotlinSourceAnalyzer by lazy {
            KotlinSourceAnalyzer(fakeContext(moduleSrcDir, moduleClasspath)).apply {
                // The extension scan of 68 jars is what this sweep costs, and without a cache directory it
                // is paid IN FULL on every run -- measured at over nine minutes, before a single file is
                // analyzed. `ClasspathReader` persists each jar's scan here content-keyed, which is what the
                // product does through `analyzerFor`; the harness simply never passed one. Under the build
                // directory so it survives between runs and is cleaned with everything else.
                extensionCacheDir = java.nio.file.Paths.get(
                    System.getProperty("kt.repoRoot").orEmpty(),
                    "lang/lang-kotlin/build/tmp/sweep-extension-cache",
                ).also { java.nio.file.Files.createDirectories(it) }
            }
        }
    }
}
