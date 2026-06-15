package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shrinks + dexes by shelling out to R8 (`java -cp d8.jar com.android.tools.r8.R8 …`) — the desktop
 * counterpart to [D8Dexer] (R8 lives in the same `d8.jar`). [keepRules] are written to a temp ProGuard
 * config and passed via `--pg-conf`; with none, a pass-through config keeps the dex correct (no shrinking).
 */
class R8Subprocess(
    private val d8Jar: Path,
    private val javaLauncher: Path,
) : Shrinker {

    override fun shrink(programs: List<Path>, library: Path, keepRules: List<String>, minApi: Int, release: Boolean, outDir: Path): ToolResult {
        Files.createDirectories(outDir)
        val existing = programs.filter { Files.exists(it) }
        if (existing.isEmpty()) return ToolResult.fail("no inputs to shrink")
        val rules = keepRules.ifEmpty { listOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-ignorewarnings") }
        val pgConf = Files.createTempFile("r8-rules", ".pro").also { Files.write(it, rules) }
        return try {
            val cmd = buildList {
                add(javaLauncher.toString()); add("-cp"); add(d8Jar.toString()); add("com.android.tools.r8.R8")
                if (release) add("--release") else add("--debug")
                add("--min-api"); add(minApi.toString())
                if (Files.exists(library)) { add("--lib"); add(library.toString()) }
                add("--pg-conf"); add(pgConf.toString())
                add("--output"); add(outDir.toString())
                addAll(existing.map { it.toString() })
            }
            Subprocess.run(cmd)
        } finally {
            runCatching { Files.deleteIfExists(pgConf) }
        }
    }
}
