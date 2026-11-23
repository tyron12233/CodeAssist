package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test

/**
 * The editor's checks held against the COMPILER'S OWN EXPECTATIONS: Kotlin's diagnostic test data
 * (`compiler/testData/diagnostics/tests` in a JetBrains/kotlin checkout), where every diagnostic K2 reports is
 * written into the source as `<!NAME!>code<!>`.
 *
 *     ./gradlew :lang-kotlin:jvmTest --tests '*CompilerTestDataSweep*' -Dkt.testData=<kotlin checkout>
 *
 * Two populations, measured differently:
 *
 *  * `// RUN_PIPELINE_TILL: CODEGEN` (or later) files COMPILE. Any ERROR we report in one is a false positive,
 *    by construction: no marker reading is involved.
 *  * `// RUN_PIPELINE_TILL: FRONTEND` files carry the errors K2 reports. An error of ours that overlaps none
 *    of them (nor sits on a line holding one) is a suspected false positive; an expected ERROR-severity
 *    marker no error of ours overlaps is a MISS, tallied by K2 name, which is the recall table.
 *
 * Severity of a K2 name comes from `FirErrors.kt`/`FirJvmErrors.kt` in the same checkout (the generated
 * factories name it). Files that depend on a non-default compiler setup (language features, opt-in flags,
 * other modules, Java sources, JS/native targets, full JDK, reflection, coroutines helpers) are skipped: the
 * editor models the default JVM configuration over the stdlib and `java.base`, and so does this sweep.
 *
 * Each test case gets its own source root and analyzer: the corpus reuses `class A`/`fun foo` in every file.
 * Every finding is written to `$TMPDIR/kt-testdata-sweep.txt`; the console carries the tallies.
 */
class KotlinCompilerTestDataSweepTest {

    private class Marker(val start: Int, val end: Int, val names: List<String>)
    private class Case(
        val path: String, val stage: String, val files: List<Pair<String, String>>, val checkType: Boolean,
        /** The diagnostics a `// DIAGNOSTICS: -NAME` directive turns off, which the file then does not mark. */
        val disabled: Set<String>,
        val inferenceHelpers: Boolean = false,
        /** The explicit API mode the compiler's test runner gives the case: `// EXPLICIT_API_MODE: X`, or strict
         *  for everything under `testsWithExplicitApi/`. */
        val explicitApi: KotlinExplicitApiMode = KotlinExplicitApiMode.DISABLED,
    )

    /** Whether every K2 diagnostic our [code] can stand for is turned off for the case, as the compiler's test
     *  runner does for a `// DIAGNOSTICS: -NAME` directive. */
    private fun disabledByDirective(code: String?, disabled: Set<String>): Boolean {
        val names = KotlinSuppression.K2_NAMES[code ?: return false] ?: return false
        return names.isNotEmpty() && names.all { it in disabled }
    }

    @Test
    fun theCheckersAgreeWithTheCompilersDiagnosticTestData() {
        val root = System.getProperty("kt.testData")?.let(::File) ?: return
        val tests = File(root, "compiler/testData/diagnostics/tests")
        require(tests.isDirectory) { "expected compiler/testData/diagnostics/tests under $root" }
        val severity = readSeverities(root)
        val only = System.getProperty("kt.testDataOnly")
        val limit = System.getProperty("kt.testDataLimit")?.toIntOrNull() ?: Int.MAX_VALUE
        val jars = listOfNotNull(
            stdlibJarPath(),
            TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar")),
        )

        val skipped = HashMap<String, Int>()
        val cases = tests.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .sortedBy { it.path }
            .filter { f -> only == null || only.split('|').any { f.path.contains(it) } }
            .mapNotNull { f -> toCase(tests, f)?.let { (c, why) -> if (why != null) { skipped.merge(why, 1, Int::plus); null } else c } }
            .take(limit).toList()

        val out = StringBuilder()
        val fpByCode = HashMap<String, Int>()          // errors in compiling files
        val strayByCode = HashMap<String, Int>()       // errors in FRONTEND files far from any expected error
        val missByName = HashMap<String, Int>()
        val hitByName = HashMap<String, Int>()
        // A hit whose paired error of ours stands for that very K2 name (the position-first pairing also credits
        // an error of another kind on the same line).
        var exactHits = 0
        var cleanCompiling = 0; var compiling = 0; var crashed = 0; var timedOut = 0
        fun newExec() = Executors.newSingleThreadExecutor { r -> Thread(null, r, "sweep", 64L shl 20).apply { isDaemon = true } }
        // Cases are independent (each gets its own temp dir and analyzer), so they are analyzed on several
        // workers at once and then scored in their sorted order: the report is the same as a one-thread run.
        // `-Dkt.sweepThreads=1` gives the old serial run, for comparing.
        val threads = System.getProperty("kt.sweepThreads")?.toIntOrNull() ?: maxOf(1, Runtime.getRuntime().availableProcessors() - 2)
        class Outcome(
            val stripped: List<Pair<String, Pair<String, List<Marker>>>>,
            val results: List<Pair<String, List<dev.ide.lang.dom.Diagnostic>>>? = null,
            val timedOut: Boolean = false,
            val crash: Throwable? = null,
        )
        val outcomes = arrayOfNulls<Outcome>(cases.size)
        val nextCase = java.util.concurrent.atomic.AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicInteger()
        val workers = (0 until threads).map { w ->
            Thread {
                var exec = newExec()
                while (true) {
                    val idx = nextCase.getAndIncrement()
                    if (idx >= cases.size) break
                    val case = cases[idx]
                    val stripped = case.files.map { (name, text) -> Pair(name, strip(text)) }
                    val src = Files.createTempDirectory("kt-td")
                    stripped.forEach { (name, s) -> src.resolve(name).also { Files.createDirectories(it.parent) }.toFile().writeText(s.first) }
                    // The compiler's test runner adds these declarations to a `// CHECK_TYPE` file's module; they are on
                    // disk for resolution and never analyzed themselves.
                    if (case.checkType) src.resolve("_CheckTypeHelpers.kt").toFile().writeText(CHECK_TYPE_HELPERS)
                    if (case.inferenceHelpers) src.resolve("_InferenceHelpers.kt").toFile().writeText(INFERENCE_HELPERS)
                    val future = exec.submit<List<Pair<String, List<dev.ide.lang.dom.Diagnostic>>>> {
                        val a = KotlinSourceAnalyzer(fakeContext(src, jars)).also { it.explicitApiMode = { case.explicitApi } }
                        try {
                            stripped.map { (name, s) ->
                                val doc = SnippetDoc(s.first, DiskFile(src.resolve(name)))
                                runBlocking {
                                    a.incrementalParser.parseFull(doc)
                                    name to a.analyze(doc.file).diagnostics.filter {
                                        it.severity == Severity.ERROR && !disabledByDirective(it.code, case.disabled)
                                    }
                                }
                            }
                        } finally { runCatching { a.dispose() } }
                    }
                    outcomes[idx] = try {
                        Outcome(stripped, results = future.get(30L * threads.coerceAtMost(4), TimeUnit.SECONDS))
                    } catch (e: TimeoutException) {
                        future.cancel(true); exec.shutdownNow(); exec = newExec()
                        Outcome(stripped, timedOut = true)
                    } catch (e: Exception) {
                        Outcome(stripped, crash = e.cause ?: e)
                    } finally { src.toFile().deleteRecursively() }
                    val n = finished.incrementAndGet()
                    if (n % 250 == 0) println("… $n/${cases.size}")
                }
                exec.shutdownNow()
            }.apply { name = "sweep-worker-$w"; isDaemon = true; start() }
        }
        workers.forEach { it.join() }

        for ((idx, case) in cases.withIndex()) {
            val outcome = outcomes[idx]!!
            val stripped = outcome.stripped
            if (outcome.timedOut) { timedOut++; out.append("TIMEOUT ${case.path}\n"); continue }
            outcome.crash?.let { c ->
                crashed++
                out.append("CRASH ${case.path}: $c\n    ${c.stackTrace.take(6).joinToString("\n    ")}\n")
                continue
            }
            val results = outcome.results!!

            val compilingCase = case.stage != "FRONTEND"
            if (compilingCase) compiling++
            var dirty = false
            for ((name, errs) in results) {
                val (text, markers) = stripped.first { it.first == name }.second
                val errMarkers = markers.filter { m -> m.names.any { severity[it] == "ERROR" } }
                fun lineOf(o: Int) = text.take(o.coerceIn(0, text.length)).count { it == '\n' }
                fun src(o: Int) = text.lineSequence().elementAtOrNull(lineOf(o))?.trim().orEmpty()
                if (compilingCase) {
                    for (d in errs) {
                        val code = d.code ?: "?"
                        dirty = true
                        fpByCode.merge(code, 1, Int::plus)
                        out.append("FP $code ${case.path}:${lineOf(d.range.start) + 1} [$name] ${d.message}\n    | ${src(d.range.start)}\n")
                    }
                    continue
                }
                // Pair our errors with the expected ones in three passes, loosest last: overlapping ranges (or the
                // same line), then the same KIND anywhere in the file (K2 puts NO_RETURN on the closing brace, we
                // put it on the declaration), then any kind within two lines.
                val ours = errs.toMutableList()
                val pending = errMarkers.toMutableList()
                val hitMarkers = HashSet<Marker>()
                val pairedCodes = HashMap<Marker, MutableSet<String>>()
                fun pair(matches: (dev.ide.lang.dom.Diagnostic, Marker) -> Boolean) {
                    val itD = ours.iterator()
                    while (itD.hasNext()) {
                        val d = itD.next()
                        val m = pending.firstOrNull { matches(d, it) } ?: errMarkers.firstOrNull { it in hitMarkers && matches(d, it) }
                        if (m != null) {
                            itD.remove(); pending.remove(m); hitMarkers += m
                            d.code?.let { c -> pairedCodes.getOrPut(m) { HashSet() } += c }
                        }
                    }
                }
                // Where several markers share a line, an error of ours first takes one of ITS kind there, so the
                // pairing is not decided by marker order (`@A1(12)` carries WRONG_ANNOTATION_TARGET and
                // TOO_MANY_ARGUMENTS, and each of our two errors should meet its own).
                fun near(d: dev.ide.lang.dom.Diagnostic, m: Marker) =
                    d.range.start - 1 <= m.end && m.start <= maxOf(d.range.end, d.range.start + 1) + 1 || lineOf(d.range.start) == lineOf(m.start)
                pair { d, m -> near(d, m) && m.names.any { n -> KotlinSuppression.K2_NAMES[d.code]?.contains(n) == true } }
                pair { d, m -> near(d, m) }
                pair { d, m -> m.names.any { it in (SAME_KIND[d.code] ?: emptySet()) } }
                pair { d, m -> kotlin.math.abs(lineOf(d.range.start) - lineOf(m.start)) <= 2 }
                for (d in ours) {
                    val code = d.code ?: "?"
                    strayByCode.merge(code, 1, Int::plus)
                    out.append("STRAY $code ${case.path}:${lineOf(d.range.start) + 1} [$name] ${d.message}\n    | ${src(d.range.start)}\n")
                }
                for (m in errMarkers) {
                    val hit = m in hitMarkers
                    val names = m.names.filter { severity[it] == "ERROR" }
                    names.forEach { (if (hit) hitByName else missByName).merge(it, 1, Int::plus) }
                    if (hit) names.forEach { n ->
                        if (pairedCodes[m].orEmpty().any { c -> KotlinSuppression.K2_NAMES[c]?.contains(n) == true }) exactHits++
                        else out.append("INEXACT $n ${pairedCodes[m].orEmpty().joinToString(",")} ${case.path}:${lineOf(m.start) + 1}\n")
                    }
                    if (!hit) out.append("MISS ${names.joinToString(",")} ${case.path}:${lineOf(m.start) + 1}\n    | ${src(m.start)}\n")
                }
            }
            if (compilingCase && !dirty) cleanCompiling++
        }

        fun table(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.joinToString("\n") { "  %6d  %s".format(it.value, it.key) }
        val recall = (hitByName.keys + missByName.keys).associateWith { (hitByName[it] ?: 0) to (missByName[it] ?: 0) }
            .entries.sortedByDescending { it.value.second }
        val summary = buildString {
            append("compiler test data: ${cases.size} cases swept, skipped $skipped\n")
            append("COMPILING files clean: $cleanCompiling / $compiling   crashed=$crashed timedOut=$timedOut\n")
            append("FALSE POSITIVES in compiling files, by our code:\n${table(fpByCode)}\n")
            append("STRAY errors in FRONTEND files (no expected error on the line), by our code:\n${table(strayByCode)}\n")
            val h = hitByName.values.sum(); val mi = missByName.values.sum()
            append("RECALL on expected ERROR markers: $h / ${h + mi}\n")
            append("EXACT-CODE RECALL (our paired error stands for that K2 name): $exactHits / ${h + mi}\n")
            append(recall.take(60).joinToString("\n") { (n, p) -> "  %5d missed %5d hit  %s".format(p.second, p.first, n) })
            append("\n")
        }
        println(summary)
        // `-Dkt.testDataOut=<file>` keeps concurrent sweeps (several worktrees) from overwriting one details file.
        val details = System.getProperty("kt.testDataOut")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "kt-testdata-sweep.txt")
        // The whole recall table, every name with a hit or a miss, as `RECALLNAME <missed> <hit> <name>` lines: the
        // console keeps to the top 60, and a name's hits are not recoverable from the MISS lines alone.
        val fullTable = recall.joinToString("") { (n, p) -> "RECALLNAME ${p.second} ${p.first} $n\n" }
        details.writeText(summary + "\n" + fullTable + out)
        println("details: $details")
    }

    /** The case a test-data file describes, or the reason it is skipped. */
    private fun toCase(tests: File, f: File): Pair<Case?, String?>? {
        if (Regex("""\.(fir|ll|reversed|latestLV|lf|ni|kmp|partialBody|disabled)\.kt$""").containsMatchIn(f.name)) return null to "variant"
        val rel = f.relativeTo(tests).path
        TEST_ONLY_FEATURE_DIRS.firstOrNull { rel.contains(it, ignoreCase = true) }?.let { return null to "testOnlyFeature" }
        val text = f.readText().replace("\r\n", "\n")
        SKIP_DIRECTIVE.find(text)?.let { return null to it.groupValues[1] }
        val stage = Regex("""^// RUN_PIPELINE_TILL: *(\w+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?: return null to "noStage"
        val heads = Regex("""^// ?FILE: *(\S+).*$""", RegexOption.MULTILINE).findAll(text).toList()
        val files = if (heads.isEmpty()) listOf("Test.kt" to text) else {
            // Code ahead of the first `// FILE:` belongs to the FIRST file: the compiler's test runner
            // (ModuleStructureExtractorImpl) keeps the lines read so far when it meets the module's first FILE
            // directive (`resetFileCaches` clears them only once a file is done). Dropping it lost declarations the
            // other files use.
            val lead = text.substring(0, heads.first().range.first)
            heads.mapIndexed { i, m ->
                val end = heads.getOrNull(i + 1)?.range?.first ?: text.length
                m.groupValues[1] to ((if (i == 0) lead else "") + text.substring(m.range.last + 1, end))
            }
        }
        if (files.any { !it.first.endsWith(".kt") }) return null to "nonKotlinFile"
        if (files.map { it.first }.toSet().size != files.size) return null to "dupFile"
        val checkType = Regex("""^// ?CHECK_TYPE""", RegexOption.MULTILINE).containsMatchIn(text)
        val disabled = Regex("""^// ?DIAGNOSTICS:(.*)$""", RegexOption.MULTILINE).findAll(text)
            .flatMap { it.groupValues[1].split(' ', ',') }.map { it.trim() }.filter { it.startsWith("-") }
            .map { it.removePrefix("-") }.toHashSet()
        // The runner also turns UNDERSCORE_USAGE_WITHOUT_BACKTICKS off for a `// CHECK_TYPE` file, whose `_<T>()`
        // helper calls would otherwise all report it.
        if (checkType) disabled += "UNDERSCORE_USAGE_WITHOUT_BACKTICKS"
        val inferenceHelpers = Regex("""^// ?INFERENCE_HELPERS""", RegexOption.MULTILINE).containsMatchIn(text)
        val explicitApi = when (Regex("""^// ?EXPLICIT_API_MODE: *(\w+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.uppercase()) {
            "STRICT" -> KotlinExplicitApiMode.STRICT
            "WARNING" -> KotlinExplicitApiMode.WARNING
            "DISABLED" -> KotlinExplicitApiMode.DISABLED
            else -> if (rel.startsWith("testsWithExplicitApi/")) KotlinExplicitApiMode.STRICT else KotlinExplicitApiMode.DISABLED
        }
        return Case(rel, stage, files.map { (n, t) -> sanitize(n) to t }, checkType, disabled, inferenceHelpers, explicitApi) to null
    }

    private fun sanitize(name: String) = name.trimStart('/').replace("..", "_")

    /** [text] without its `<!NAME(args), NAME2!>…<!>` markup, and the markers as ranges over the stripped text. */
    private fun strip(text: String): Pair<String, List<Marker>> {
        val sb = StringBuilder(text.length)
        val open = ArrayDeque<Pair<Int, List<String>>>()
        val markers = ArrayList<Marker>()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("<!>", i) && open.isNotEmpty()) {
                val (s, names) = open.removeLast()
                markers += Marker(s, sb.length, names)
                i += 3; continue
            }
            if (text.startsWith("<!", i) && i + 2 < text.length && (text[i + 2].isUpperCase() || text[i + 2] == '_')) {
                // Scan to the closing `!>`, skipping over quoted arguments and balanced parentheses.
                var j = i + 2; var depth = 0; var quote = false
                while (j < text.length) {
                    val c = text[j]
                    if (quote) { if (c == '\\') j++ else if (c == '"') quote = false }
                    else if (c == '"') quote = true
                    else if (c == '(') depth++
                    else if (c == ')') depth--
                    else if (depth == 0 && c == '!' && text.startsWith("!>", j)) break
                    j++
                }
                if (j < text.length) {
                    val body = text.substring(i + 2, j)
                    val names = splitTopLevel(body).mapNotNull { Regex("""^\s*([A-Z][A-Z0-9_]*)""").find(it)?.groupValues?.get(1) }
                    open.addLast(sb.length to names)
                    i = j + 2; continue
                }
            }
            sb.append(text[i]); i++
        }
        return sb.toString() to markers
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = ArrayList<String>(); var depth = 0; var quote = false; var from = 0
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (quote) { if (c == '\\') k++ else if (c == '"') quote = false }
            else when (c) { '"' -> quote = true; '(' -> depth++; ')' -> depth--; ',' -> if (depth == 0) { parts += s.substring(from, k); from = k + 1 } }
            k++
        }
        parts += s.substring(from)
        return parts
    }

    private fun readSeverities(root: File): Map<String, String> {
        val out = HashMap<String, String>()
        val re = Regex("""val ([A-Z0-9_]+): Kt\w*Factory[^=]*= *Kt\w*\("([A-Z0-9_]+)", *([A-Z_]+)""")
        root.resolve("compiler/fir/checkers").walkTopDown()
            .filter { it.isFile && (it.name == "FirErrors.kt" || it.name == "FirJvmErrors.kt") }
            .forEach { f -> re.findAll(f.readText()).forEach { out[it.groupValues[2]] = it.groupValues[3] } }
        // Deprecation-driven factories are errors in the current language version.
        Regex("""val ([A-Z0-9_]+): KtDiagnosticFactoryForDeprecation""").let { r ->
            root.resolve("compiler/fir/checkers").walkTopDown().filter { it.isFile && it.name == "FirErrors.kt" }
                .forEach { f -> r.findAll(f.readText()).forEach { out.putIfAbsent(it.groupValues[1], "ERROR") } }
        }
        out["SYNTAX"] = "ERROR"
        return out
    }

    private companion object {
        /** `compiler/testData/diagnostics/helpers/inference/inferenceUtils.kt`, injected for `// INFERENCE_HELPERS`. */
        val INFERENCE_HELPERS = """
            fun <K> id(x: K): K = x
            fun <K> materialize(): K = null!!
            fun <K> select(vararg values: K): K = values[0]
        """.trimIndent()

        /** `compiler/testData/diagnostics/helpers/types/checkType.kt` + `checkTypeWithExact.kt`. */
        val CHECK_TYPE_HELPERS = """
            fun <T> checkSubtype(t: T) = t
            class CheckTypeInv<T>
            fun <E> CheckTypeInv<E>._() {}
            infix fun <T> T.checkType(f: CheckTypeInv<T>.() -> Unit) {}
            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
            fun <T> checkExactType(expr: @kotlin.internal.Exact T) {}
            @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
            fun <T> checkTypeEquality(reference: @kotlin.internal.Exact T, expr: @kotlin.internal.Exact T) {}
        """.trimIndent()

        /** Test directories of language features that no released compiler enables (`sinceVersion = null`,
         *  on only in the compiler's latest-LV tests), plus 2.5-only ones: users cannot write that code yet. */
        val TEST_ONLY_FEATURE_DIRS = listOf(
            "contextSensitiveResolution", "collectionLiteral", "strictEquals", "companionBlocks", "companionExtensions",
            "richErrors", "nameBasedDestructuring", "callableReferencesToContextual", "escapingCapturedVariable",
            "fullValueClasses", "eagerLambdaAnalysis", "unnamedLocalVariables", "dataClassCopyRespectsConstructorVisibility",
        )
        /** Our code -> the K2 diagnostics that describe the same defect (paired anywhere in a file). */
        val SAME_KIND: Map<String, Set<String>> = mapOf(
            "kt.unresolved" to setOf("UNRESOLVED_REFERENCE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", "UNRESOLVED_IMPORT", "INVISIBLE_REFERENCE", "UNRESOLVED_LABEL"),
            "kt.typeMismatch" to setOf("ARGUMENT_TYPE_MISMATCH", "TYPE_MISMATCH", "RETURN_TYPE_MISMATCH", "INITIALIZER_TYPE_MISMATCH", "ASSIGNMENT_TYPE_MISMATCH", "CONDITION_TYPE_MISMATCH", "NULL_FOR_NONNULL_TYPE", "CONSTANT_EXPECTED_TYPE_MISMATCH", "RESULT_TYPE_MISMATCH", "TYPE_MISMATCH_WHEN_FLEXIBILITY_CHANGES"),
            "kt.argumentCount" to setOf("TOO_MANY_ARGUMENTS", "NO_VALUE_FOR_PARAMETER", "NONE_APPLICABLE", "INAPPLICABLE_CANDIDATE", "ARGUMENT_TYPE_MISMATCH", "NAMED_PARAMETER_NOT_FOUND"),
            "kt.constructorArgs" to setOf("TOO_MANY_ARGUMENTS", "NO_VALUE_FOR_PARAMETER", "NONE_APPLICABLE", "INAPPLICABLE_CANDIDATE", "ARGUMENT_TYPE_MISMATCH"),
            "kt.missingReturn" to setOf("NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY"),
            "kt.unsafeNullable" to setOf("UNSAFE_CALL", "UNSAFE_INFIX_CALL", "UNSAFE_OPERATOR_CALL", "UNSAFE_IMPLICIT_INVOKE_CALL"),
            "kt.smartcastImpossible" to setOf("SMARTCAST_IMPOSSIBLE", "SMARTCAST_IMPOSSIBLE_ON_IMPLICIT_INVOKE_RECEIVER"),
            "kt.memberProjectedOut" to setOf("MEMBER_PROJECTED_OUT"),
            "kt.whenExhaustive" to setOf("NO_ELSE_IN_WHEN"),
            "kt.abstractNotImplemented" to setOf("ABSTRACT_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED", "MANY_IMPL_MEMBER_NOT_IMPLEMENTED", "MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED", "INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER"),
            "kt.abstractClassMemberNotImplemented" to setOf("ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_MEMBER_NOT_IMPLEMENTED", "INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER"),
            "kt.manyImplMemberNotImplemented" to setOf("MANY_IMPL_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_MEMBER_NOT_IMPLEMENTED"),
            "kt.manyInterfacesMemberNotImplemented" to setOf("MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_MEMBER_NOT_IMPLEMENTED"),
            "kt.valReassign" to setOf("VAL_REASSIGNMENT", "VAL_REASSIGNMENT_VIA_BACKING_FIELD"),
            "kt.uninitializedVariable" to setOf("UNINITIALIZED_VARIABLE", "UNINITIALIZED_PARAMETER", "UNINITIALIZED_ENUM_ENTRY"),
            "kt.mustBeInitialized" to setOf("MUST_BE_INITIALIZED", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "MUST_BE_INITIALIZED_OR_BE_FINAL", "MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT"),
            "kt.conflictingDeclaration" to setOf("CONFLICTING_OVERLOADS", "REDECLARATION", "PACKAGE_OR_CLASSIFIER_REDECLARATION", "CLASSIFIER_REDECLARATION"),
            "kt.nothingToOverride" to setOf("NOTHING_TO_OVERRIDE"),
            "kt.overrideRequired" to setOf("VIRTUAL_MEMBER_HIDDEN"),
            "kt.overridingFinal" to setOf("OVERRIDING_FINAL_MEMBER"),
            "kt.unresolvedLabel" to setOf("UNRESOLVED_LABEL"),
            "kt.dslScopeViolation" to setOf("DSL_SCOPE_VIOLATION"),
            "kt.overloadAmbiguity" to setOf("OVERLOAD_RESOLUTION_AMBIGUITY", "AMBIGUOUS_CALL"),
            "kt.cannotInferType" to setOf("CANNOT_INFER_PARAMETER_TYPE", "CANNOT_INFER_VALUE_PARAMETER_TYPE", "NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER"),
            "kt.invisibleReference" to setOf("INVISIBLE_REFERENCE", "INVISIBLE_SETTER"),
            "kt.forbiddenIdentityEquals" to setOf("FORBIDDEN_IDENTITY_EQUALS"),
            "kt.impossibleIsCheck" to setOf("IMPOSSIBLE_IS_CHECK_ERROR", "IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR"),
            "kt.upperBoundViolated" to setOf("UPPER_BOUND_VIOLATED", "UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION"),
            "kt.wrongModifierTarget" to setOf("WRONG_MODIFIER_TARGET"),
            "kt.wrongModifierContainer" to setOf("WRONG_MODIFIER_CONTAINING_DECLARATION"),
            "kt.inapplicableOperator" to setOf("INAPPLICABLE_OPERATOR_MODIFIER"),
            "kt.inapplicableInfix" to setOf("INAPPLICABLE_INFIX_MODIFIER"),
            "kt.returnNotAllowed" to setOf("RETURN_NOT_ALLOWED"),
            "kt.declarationShape" to setOf("MULTIPLE_VARARG_PARAMETERS", "TYPE_PARAMETERS_IN_OBJECT", "LOCAL_OBJECT_NOT_ALLOWED", "LOCAL_INTERFACE_NOT_ALLOWED", "NESTED_CLASS_NOT_ALLOWED", "ANONYMOUS_FUNCTION_WITH_NAME", "VARIANCE_ON_TYPE_PARAMETER_NOT_ALLOWED", "NULLABLE_TYPE_OF_ANNOTATION_MEMBER", "MULTIPLE_LABELS_ARE_FORBIDDEN", "REPEATED_ANNOTATION", "PRIVATE_SETTER_FOR_OPEN_PROPERTY", "PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY"),
            "kt.modifiers" to setOf("INCOMPATIBLE_MODIFIERS", "REPEATED_MODIFIER", "REPEATED_ANNOTATION"),
            "kt.lateinit" to setOf("INAPPLICABLE_LATEINIT_MODIFIER"),
            "kt.wrongAnnotationTarget" to setOf("WRONG_ANNOTATION_TARGET", "WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET"),
            "kt.wrongAnnotationTargetWithUseSiteTarget" to setOf("WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET", "WRONG_ANNOTATION_TARGET"),
            "kt.notConstant" to setOf("ANNOTATION_ARGUMENT_MUST_BE_CONST", "NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION", "CONST_VAL_WITH_NON_CONST_INITIALIZER"),
            "kt.cannotWeakenAccess" to setOf("CANNOT_WEAKEN_ACCESS_PRIVILEGE", "CANNOT_CHANGE_ACCESS_PRIVILEGE"),
            "kt.uninitializedEnumCompanion" to setOf("UNINITIALIZED_ENUM_COMPANION"),
            "kt.instanceAccessBeforeSuperCall" to setOf("INSTANCE_ACCESS_BEFORE_SUPER_CALL"),
            "kt.usageIsNotInlinable" to setOf("USAGE_IS_NOT_INLINABLE"),
            "kt.nonPublicCallFromPublicInline" to setOf("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE", "NON_PUBLIC_INLINE_CALL_FROM_PUBLIC_INLINE", "PRIVATE_CLASS_MEMBER_FROM_INLINE"),
            "kt.incomparableEquality" to setOf("EQUALITY_NOT_APPLICABLE", "INCOMPATIBLE_TYPES", "INCOMPATIBLE_ENUM_COMPARISON_ERROR"),
            "kt.castErased" to setOf("CANNOT_CHECK_FOR_ERASED", "NO_TYPE_ARGUMENTS_ON_RHS"),
            "kt.deprecation" to setOf("DEPRECATION_ERROR"),
            "kt.illegalLiteral" to setOf("ILLEGAL_ESCAPE", "WRONG_LONG_SUFFIX", "ILLEGAL_UNDERSCORE", "INT_LITERAL_WITH_LEADING_ZEROS", "INT_LITERAL_OUT_OF_RANGE", "EMPTY_CHARACTER_LITERAL", "TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL", "INCORRECT_CHARACTER_LITERAL"),
            "kt.illegalSelector" to setOf("ILLEGAL_SELECTOR", "NO_RECEIVER_ALLOWED"),
            "kt.superMisuse" to setOf("SUPER_IS_NOT_AN_EXPRESSION", "SUPER_NOT_AVAILABLE", "AMBIGUOUS_SUPER", "ABSTRACT_SUPER_CALL"),
            "kt.importFromSingleton" to setOf("CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON"),
            "kt.operatorModifierRequired" to setOf("OPERATOR_MODIFIER_REQUIRED"),
            "kt.nonLocalReturnNotAllowed" to setOf("NON_LOCAL_RETURN_NOT_ALLOWED"),
            "kt.classLiteral" to setOf("NULLABLE_TYPE_IN_CLASS_LITERAL_LHS", "EXPRESSION_OF_NULLABLE_TYPE_IN_CLASS_LITERAL_LHS", "CLASS_LITERAL_LHS_NOT_A_CLASS", "TYPE_PARAMETER_AS_REIFIED"),
            "kt.underscoreUsage" to setOf("UNDERSCORE_USAGE_WITHOUT_BACKTICKS"),
            "kt.expressionExpected" to setOf("EXPRESSION_EXPECTED"),
            "kt.argumentMapping" to setOf("MIXING_NAMED_AND_POSITIONAL_ARGUMENTS", "ARGUMENT_PASSED_TWICE", "NON_VARARG_SPREAD", "VARARG_OUTSIDE_PARENTHESES", "MANY_LAMBDA_EXPRESSION_ARGUMENTS", "NAMED_ARGUMENTS_NOT_ALLOWED"),
            "kt.constMisuse" to setOf("CONST_VAL_WITH_NON_CONST_INITIALIZER", "NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION", "CONST_VAL_NOT_TOP_LEVEL_OR_OBJECT", "CONST_VAL_WITH_GETTER", "CONST_VAL_WITH_DELEGATE", "CONST_VAL_WITHOUT_INITIALIZER", "TYPE_CANT_BE_USED_FOR_CONST_VAL", "WRONG_MODIFIER_TARGET"),
        ) + PORTED_DECLARATION_K2_NAMES + ROUND6_K2_NAMES + INHERITANCE_CLASH_K2_NAMES + ROUND7_K2_NAMES + ROUND8_K2_NAMES
        val SKIP_DIRECTIVE = Regex(
            """^// ?(MODULE|LANGUAGE|API_VERSION|ALLOW_KOTLIN_PACKAGE|WITH_REFLECT|TARGET_PLATFORM|TARGET_BACKEND|JDK_KIND|""" +
                """LANGUAGE_VERSION|OPT_IN|WITH_COROUTINES|FULL_JDK|LANGUAGE_FEATURE_TOGGLED|JVM_TARGET|""" +
                """WITH_KOTLIN_JVM_ANNOTATIONS|LATEST_LV_DIFFERENCE|COMPILER_ARGUMENTS|IGNORE_FIR_DIAGNOSTICS|STDLIB_JDK8|""" +
                """JSPECIFY_STATE|NULLABILITY_ANNOTATIONS|JVM_DEFAULT_MODE|NO_STDLIB|DISABLE_JAVA_FACADE|IGNORE_REVERSED_RESOLVE|""" +
                """ENABLE_JVM_PREVIEW|WITH_PLATFORM_LIBS|JVM_EXPOSE_BOXED|SKIP_KT_DUMP|PLATFORM_DEPENDANT_METADATA)\b""",
            RegexOption.MULTILINE,
        )
    }
}
