package dev.ide.android.support.tools

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.L8
import com.android.tools.r8.L8Command
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.StringConsumer
import com.android.tools.r8.origin.Origin
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Shrinks + dexes in-process via the R8 API (`com.android.tools.r8.R8`) and dexes the core-library
 * desugaring runtime via L8 (`com.android.tools.r8.L8`) - the on-device counterpart to [D8InProcessDexer]
 * (R8/L8 ship in the same pure-Java tool jar). All keep rules, the mapping output, full/compat mode,
 * integrated resource shrinking, and core-library desugaring are driven through the public R8 API; with no
 * keep rules at all it falls back to [R8_PASS_THROUGH] so the result is a correct dex rather than over-shrunk.
 */
class R8InProcessShrinker : Shrinker {

    override fun shrink(request: ShrinkRequest): ToolResult {
        Files.createDirectories(request.outDir)
        val programs = request.programs.filter { Files.exists(it) }
        if (programs.isEmpty()) {
            return ToolResult.fail("no inputs to shrink")
        }
        // Without a handler R8 routes its diagnostics to a default sink (stderr) and they're lost — a failed
        // shrink would surface only a generic CompilationFailedException message. Capture them as "level:
        // message" lines (mirrors D8InProcessDexer and l8 below) so the r8 task's reportToolDiagnostics turns
        // them into structured Problems entries (a missing-class warning, a proguard-rule parse error, …).
        val diagnostics = ArrayList<String>()
        val handler = object : DiagnosticsHandler {
            override fun info(d: Diagnostic) { diagnostics.add("info: ${d.diagnosticMessage}") }
            override fun warning(d: Diagnostic) { diagnostics.add("warning: ${d.diagnosticMessage}") }
            override fun error(d: Diagnostic) { diagnostics.add("error: ${d.diagnosticMessage}") }
        }
        return try {
            val builder = R8Command.builder(handler)
                .addProgramFiles(programs)
                .setMinApiLevel(request.minApi)
                .setMode(if (request.release) CompilationMode.RELEASE else CompilationMode.DEBUG)
                .setOutput(request.outDir, OutputMode.DexIndexed)
                .setProguardCompatibility(!request.fullMode) // Full mode is R8's default (compatibility = false); compat mode mimics legacy ProGuard.
            if (Files.exists(request.library)) {
                builder.addLibraryFiles(request.library)
            }
            request.classpath.filter { Files.exists(it) }.takeIf { it.isNotEmpty() }
                ?.let { builder.addClasspathFiles(it) }

            val keepFiles = request.keepRuleFiles.filter { Files.exists(it) }
            if (keepFiles.isNotEmpty()) {
                builder.addProguardConfigurationFiles(keepFiles)
            }
            val inline = ArrayList(request.inlineRules)
            if (keepFiles.isEmpty() && inline.isEmpty()) {
                inline.addAll(R8_PASS_THROUGH)
            }
            // Don't let a class that's referenced but absent from the classpath fail the build (see
            // R8_IGNORE_WARNINGS) — R8_PASS_THROUGH already carries it, so only the minify path needs it added.
            if (R8_IGNORE_WARNINGS !in inline) inline.add(R8_IGNORE_WARNINGS)
            if (inline.isNotEmpty()) {
                builder.addProguardConfiguration(inline, Origin.unknown())
            }

            request.mappingOutput?.let { map ->
                map.parent?.let(Files::createDirectories)
                builder.setProguardMapOutputPath(map)
            }
            request.desugaredLibrary?.let { dl ->
                builder.addDesugaredLibraryConfiguration(Files.readAllBytes(dl.configJson).decodeToString())
                dl.keepRulesOutput.parent?.let(Files::createDirectories)
                builder.setDesugaredLibraryKeepRuleConsumer(StringConsumer.FileConsumer(dl.keepRulesOutput))
            }
            request.resources?.let { rs ->
                builder.setAndroidResourceProvider(ZipResourceProvider(rs.inputAp))
                builder.setAndroidResourceConsumer(ZipResourceConsumer(rs.outputAp))
            }

            val command = builder.build()
            // Bound R8's worker pool to keep peak memory down (no setThreadCount on this version; cap via the
            // executor overload, as the dexer does).
            if (request.threads > 0) {
                val pool = Executors.newFixedThreadPool(request.threads)
                try { R8.run(command, pool) } finally { pool.shutdown() }
            } else {
                R8.run(command)
            }
            ToolResult.ok(DexDiagnostics.humanize(buildList {
                add("R8 (in-process) processed ${programs.size} input(s) -> ${request.outDir.fileName}")
                addAll(diagnostics) // R8 can emit warnings (unused rules, missing classes) on a successful run
            }))
        } catch (t: Throwable) {
            // The captured handler diagnostics carry the real cause; humanize them into an actionable Problem.
            ToolResult(false, DexDiagnostics.humanize(diagnostics + "R8 shrinking failed: ${t.message}"))
        }
    }

    override fun l8(request: L8Request): ToolResult {
        if (!Files.exists(request.desugarJdkLibs)) {
            return ToolResult.fail("desugar runtime jar missing")
        }
        Files.createDirectories(request.outDir)
        val diagnostics = ArrayList<String>()
        val handler = object : DiagnosticsHandler {
            override fun warning(d: Diagnostic) { diagnostics.add("warning: ${d.diagnosticMessage}") }
            override fun error(d: Diagnostic) { diagnostics.add("error: ${d.diagnosticMessage}") }
        }
        // Release mode shrinks the runtime to what the app uses, but only with R8's emitted keep rules; without
        // them (or in a debug build) keep the whole runtime, which is correct and only larger.
        val shrink = request.release && Files.exists(request.keepRules)
        return try {
            val builder = L8Command.builder(handler)
                .addProgramFiles(request.desugarJdkLibs)
                .setMinApiLevel(request.minApi)
                .setMode(if (shrink) CompilationMode.RELEASE else CompilationMode.DEBUG)
                .setOutput(request.outDir, OutputMode.DexIndexed)
                .addDesugaredLibraryConfiguration(Files.readAllBytes(request.configJson).decodeToString())
            if (Files.exists(request.library)) {
                builder.addLibraryFiles(request.library)
            }
            if (shrink) {
                builder.addProguardConfigurationFiles(listOf(request.keepRules))
            }
            L8.run(builder.build())
            ToolResult.ok(listOf("L8 (in-process) dexed the core-library desugaring runtime -> ${request.outDir.fileName}"))
        } catch (t: Throwable) {
            ToolResult(false, DexDiagnostics.humanize(diagnostics + "L8 (core-library desugaring) failed: ${t.message}"))
        }
    }
}
