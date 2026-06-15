package dev.ide.android.support.tools

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shrinks + dexes in-process via the R8 API (`com.android.tools.r8.R8`) — the on-device counterpart to
 * [D8InProcessDexer] (R8 ships in the same pure-Java tool jar). [library] (`android.jar`) is the
 * bootclasspath, kept out of the output; [keepRules] are ProGuard-syntax rules (manifest-derived, etc.).
 * With no keep rules it adds `-dontshrink -dontoptimize -dontobfuscate` so the result is a correct dex
 * (R8 acting as D8) rather than an over-shrunk one.
 */
class R8InProcessShrinker : Shrinker {

    override fun shrink(programs: List<Path>, library: Path, keepRules: List<String>, minApi: Int, release: Boolean, outDir: Path): ToolResult {
        Files.createDirectories(outDir)
        val progs = programs.filter { Files.exists(it) }
        if (progs.isEmpty()) return ToolResult.fail("no inputs to shrink")
        return try {
            val builder = R8Command.builder()
                .addProgramFiles(progs)
                .setMinApiLevel(minApi)
                .setMode(if (release) CompilationMode.RELEASE else CompilationMode.DEBUG)
                .setOutput(outDir, OutputMode.DexIndexed)
            if (Files.exists(library)) builder.addLibraryFiles(library)
            val rules = keepRules.ifEmpty { listOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-ignorewarnings") }
            builder.addProguardConfiguration(rules, Origin.unknown())
            R8.run(builder.build())
            ToolResult.ok(listOf("R8 (in-process) minified ${progs.size} input(s) -> ${outDir.fileName}"))
        } catch (t: Throwable) {
            ToolResult.fail("R8 in-process failed: ${t.message}")
        }
    }
}
