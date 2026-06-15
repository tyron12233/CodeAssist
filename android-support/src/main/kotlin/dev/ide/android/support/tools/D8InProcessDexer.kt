package dev.ide.android.support.tools

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dexes in-process by calling the D8 API directly (`com.android.tools.r8.D8`), the on-device path.
 * On ART there is no `java` launcher to fork, so D8 (pure Java) must be statically
 * linked into the app and run in-process; this same impl runs on the desktop JVM, so the desktop test
 * exercises exactly the on-device code. Inputs are jars/class files (the [dev.ide.android.support.tasks]
 * DexTask jars the compiled classes first); `android.jar` is the library (bootclasspath) for desugaring.
 *
 * Contrast [D8Dexer], which shells out to `java -cp d8.jar …` and suits a desktop with an installed SDK.
 */
class D8InProcessDexer : Dexer {

    override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path): ToolResult =
        run(inputs, emptyList(), androidJar, minApi, release, outDir, OutputMode.DexIndexed)

    override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path): ToolResult =
        run(inputs, classpath, androidJar, minApi, release, outDir, OutputMode.DexFilePerClassFile)

    private fun run(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, mode: OutputMode): ToolResult {
        Files.createDirectories(outDir)
        val programs = inputs.filter { Files.exists(it) }
        if (programs.isEmpty()) return ToolResult.fail("no class inputs to dex")
        // Collect D8's diagnostics so benign desugaring warnings (guava's MethodHandle helpers at a
        // low min-api, etc.) don't spam the console; real warnings/errors are surfaced.
        val diagnostics = ArrayList<String>()
        var suppressed = 0
        val handler = object : DiagnosticsHandler {
            override fun info(d: Diagnostic) {}
            override fun warning(d: Diagnostic) {
                if (isBenignDexWarning(d.diagnosticMessage)) suppressed++ else diagnostics.add("warning: ${d.diagnosticMessage}")
            }
            override fun error(d: Diagnostic) { diagnostics.add("error: ${d.diagnosticMessage}") }
        }
        return try {
            val builder = D8Command.builder(handler)
                .addProgramFiles(programs)
                .setMinApiLevel(minApi)
                .setMode(if (release) CompilationMode.RELEASE else CompilationMode.DEBUG)
                .setOutput(outDir, mode)
            // Per-class-file output is an *intermediate* result: cross-class desugaring is deferred to the
            // merge (the merger gets the same library and finalizes it), exactly as AGP's archive→merge flow.
            if (mode == OutputMode.DexFilePerClassFile) builder.setIntermediate(true)
            classpath.filter { Files.exists(it) }.takeIf { it.isNotEmpty() }?.let { builder.addClasspathFiles(it) }
            if (Files.exists(androidJar)) builder.addLibraryFiles(androidJar)
            D8.run(builder.build())
            val role = if (mode == OutputMode.DexFilePerClassFile) "archived" else "dexed"
            val summary = buildList {
                add("D8 (in-process) $role ${programs.size} input(s) -> ${outDir.fileName}")
                addAll(diagnostics)
                if (suppressed > 0) add("($suppressed D8 desugaring warning(s) suppressed — library APIs needing a higher min-api; benign)")
            }
            ToolResult.ok(summary)
        } catch (t: Throwable) {
            ToolResult.fail("D8 in-process failed: ${t.message}", diagnostics)
        }
    }
}
