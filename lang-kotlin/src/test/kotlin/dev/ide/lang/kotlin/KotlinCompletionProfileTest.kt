package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogSink
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * NOT a regression gate — a manual profiling harness. Drives completion + analyze on a large (~450-line)
 * Kotlin file with [KotlinPerf] active and writes the per-stage timing lines to a file, so the ~1s editor
 * lag can be attributed to parse vs member-lookup vs scope vs ranking. Run with:
 *   CI_CORE_ONLY=true ./gradlew :lang-kotlin:test --tests '*KotlinCompletionProfileTest' -Dide.kotlin.perf=true
 * then read /tmp/kotlin-perf-profile.txt. Parse cost is classpath-independent, so even a stdlib-only run
 * answers "is the full reparse the bottleneck?".
 */
class KotlinCompletionProfileTest {

    @Test
    fun profile() {
        // Manual tool, not a gate: only runs when explicitly profiling (`-Dide.kotlin.perf=true`), so the normal
        // suite doesn't pay its cost, write /tmp, or flip the global flag.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("ide.kotlin.perf")?.toBoolean() == true, "set -Dide.kotlin.perf=true to profile",
        )
        val out = Path.of("/tmp/kotlin-perf-profile.txt")
        val lines = ArrayList<String>()
        val sink = LogSink { rec -> if (rec.tag == "kotlin-perf") synchronized(lines) { lines += rec.message } }
        Log.addSink(sink)
        KotlinPerf.enabled = true

        val srcDir = tempProject(mapOf("Big.kt" to bigFile))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        try {
            // Member access on a List receiver: supertype walk + extensions (map/filter/...) + stdlib scan.
            val memberCode = bigFile.replace("/*CARET_MEMBER*/", "items.fil")
            // Bare-name scope completion: scopeSymbolsAt (walks file decls) + top-level callables + type names.
            val scopeCode = bigFile.replace("/*CARET_SCOPE*/", "Metr")

            fun completeAt(code: String, token: String) {
                val caret = code.indexOf(token) + token.length
                val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Big.kt")))
                runBlocking { analyzer.completion!!.complete(CompletionRequest(doc, caret, CompletionTrigger.TypedChar('.'))) }
            }
            fun analyzeOnce(code: String, a: KotlinSourceAnalyzer = analyzer) {
                val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Big.kt")))
                runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file) }
            }

            // Warm up (cold class-loading, environment, classpath scan, index warm) — discard.
            repeat(3) { completeAt(memberCode, "items.fil"); completeAt(scopeCode, "Metr"); analyzeOnce(bigFile) }
            synchronized(lines) { lines.clear() }
            synchronized(lines) { lines += "=== STEADY STATE (5 runs each) ===" }

            synchronized(lines) { lines += "-- member access (items.fil|) --" }
            repeat(5) { completeAt(memberCode, "items.fil") }
            synchronized(lines) { lines += "-- scope name (Metr|) --" }
            repeat(5) { completeAt(scopeCode, "Metr") }
            // A fresh analyzer → the FIRST analyze is a full walk (the cold/structural baseline).
            synchronized(lines) { lines += "-- analyze (full, cold) --" }
            analyzeOnce(bigFile, KotlinSourceAnalyzer(fakeContext(srcDir)))
            // Body-only edits to ONE @Composable function → the scoped recompute path (the typing case).
            synchronized(lines) { lines += "-- analyze (body edit, scoped) --" }
            analyzeOnce(bigFile) // seed this analyzer's cache
            repeat(5) { n ->
                val edited = bigFile.replaceFirst("Text(transform0(m))", "Text(transform0(m))\n        val probe$n = $n")
                analyzeOnce(edited)
            }
        } finally {
            Log.removeSink(sink)
            synchronized(lines) { Files.writeString(out, lines.joinToString("\n")) }
            println("kotlin-perf profile written to $out (${lines.size} lines)")
        }
    }

    companion object {
        /** A ~450-line Compose-shaped file: many @Composable functions whose bodies are full of calls (so the
         *  per-call composableInvocation check has realistic work), plus data/services for scope size, plus two
         *  completion carets. The @Composable annotation check is syntactic, so this exercises the context-walk
         *  path without needing the real Compose classpath on the test path. */
        private val bigFile: String = buildString {
            appendLine("package demo.big")
            appendLine()
            appendLine("annotation class Composable")
            appendLine("@Composable fun Text(s: String) {}")
            appendLine("@Composable fun Column(content: @Composable () -> Unit) { content() }")
            appendLine("@Composable fun Row(content: @Composable () -> Unit) { content() }")
            appendLine()
            // 30 data classes + helpers → a realistically large declaration set (scopeSymbolsAt work).
            repeat(30) { i ->
                appendLine("data class Model$i(val id: String, val name: String, val value: Int, val ratio: Double)")
                appendLine("fun build$i(seed: Int): Model$i = Model$i(\"$i\", \"n$i\", seed * $i, seed.toDouble())")
                appendLine("fun transform$i(m: Model$i): String = \"\${m.id}:\${m.name}:\${m.value}\"")
                appendLine()
            }
            // 30 @Composable UI functions, each body packed with calls → the user's file shape (calls in a
            // composable context). composableInvocation runs per call here.
            repeat(30) { i ->
                appendLine("@Composable")
                appendLine("fun Screen$i(seed: Int) {")
                appendLine("    Column {")
                appendLine("        val m = build$i(seed)")
                appendLine("        Text(transform$i(m))")
                appendLine("        Row {")
                appendLine("            Text(m.name)")
                appendLine("            Text(m.id)")
                appendLine("            Text(build$i(seed + 1).name)")
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
                appendLine()
            }
            appendLine("@Composable")
            appendLine("fun scenario(items: List<String>) {")
            appendLine("    val Metric = 42")
            appendLine("    val doubled = items.map { it.length }")
            appendLine("    Column {")
            appendLine("        Text(items.first())")
            appendLine("        val result = /*CARET_MEMBER*/")
            appendLine("        val tag = /*CARET_SCOPE*/")
            appendLine("    }")
            appendLine("    println(doubled)")
            appendLine("}")
        }
    }
}
