package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shrinks + dexes by shelling out to R8 (`java -cp d8.jar com.android.tools.r8.R8 ...`), and dexes the
 * core-library desugaring runtime via L8 (same jar) - the desktop counterpart to [R8InProcessShrinker].
 * Keep-rule files map to `--pg-conf`, inline rules to a temp `--pg-conf`, the mapping to `--pg-map-output`,
 * compat mode to `--pg-compat`, resource shrinking to `--android-resources <in> <out>`, and core-library
 * desugaring to `--desugared-lib`/`--desugared-lib-pg-conf-output`. With no rules it falls back to a
 * pass-through config so the dex stays correct.
 */
class R8Subprocess(
    private val d8Jar: Path,
    private val javaLauncher: Path,
) : Shrinker {

    override fun shrink(request: ShrinkRequest): ToolResult {
        Files.createDirectories(request.outDir)
        val existing = request.programs.filter { Files.exists(it) }
        if (existing.isEmpty()) return ToolResult.fail("no inputs to shrink")

        val temp = ArrayList<Path>()
        return try {
            val keepFiles = request.keepRuleFiles.filter { Files.exists(it) }.toMutableList()
            val inline = ArrayList(request.inlineRules)
            if (keepFiles.isEmpty() && inline.isEmpty()) inline.addAll(R8_PASS_THROUGH)
            if (inline.isNotEmpty()) {
                val f = Files.createTempFile("r8-inline", ".pro").also { Files.write(it, inline) }
                temp.add(f); keepFiles.add(f)
            }
            val cmd = buildList {
                add(javaLauncher.toString()); add("-cp"); add(d8Jar.toString()); add("com.android.tools.r8.R8")
                if (request.release) add("--release") else add("--debug")
                add("--min-api"); add(request.minApi.toString())
                if (!request.fullMode) add("--pg-compat")
                if (request.threads > 0) { add("--thread-count"); add(request.threads.toString()) }
                if (Files.exists(request.library)) { add("--lib"); add(request.library.toString()) }
                request.classpath.filter { Files.exists(it) }.forEach { add("--classpath"); add(it.toString()) }
                keepFiles.forEach { add("--pg-conf"); add(it.toString()) }
                request.mappingOutput?.let { it.parent?.let(Files::createDirectories); add("--pg-map-output"); add(it.toString()) }
                request.desugaredLibrary?.let {
                    add("--desugared-lib"); add(it.configJson.toString())
                    it.keepRulesOutput.parent?.let(Files::createDirectories)
                    add("--desugared-lib-pg-conf-output"); add(it.keepRulesOutput.toString())
                }
                request.resources?.let {
                    add("--android-resources"); add(it.inputAp.toString()); add(it.outputAp.toString())
                }
                add("--output"); add(request.outDir.toString())
                addAll(existing.map { it.toString() })
            }
            Subprocess.run(cmd)
        } finally {
            temp.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    override fun l8(request: L8Request): ToolResult {
        if (!Files.exists(request.desugarJdkLibs)) return ToolResult.fail("desugar runtime jar missing")
        Files.createDirectories(request.outDir)
        // Shrink the runtime only with keep rules in a release build; otherwise keep all of it (see the
        // in-process L8 for why release-shrinking against R8's keep rules is avoided).
        val shrink = request.release && Files.exists(request.keepRules)
        val cmd = buildList {
            add(javaLauncher.toString()); add("-cp"); add(d8Jar.toString()); add("com.android.tools.r8.L8")
            if (shrink) add("--release") else add("--debug")
            add("--min-api"); add(request.minApi.toString())
            if (Files.exists(request.library)) { add("--lib"); add(request.library.toString()) }
            add("--desugared-lib"); add(request.configJson.toString())
            if (shrink) { add("--pg-conf"); add(request.keepRules.toString()) }
            add("--output"); add(request.outDir.toString())
            add(request.desugarJdkLibs.toString())
        }
        return Subprocess.run(cmd)
    }
}
