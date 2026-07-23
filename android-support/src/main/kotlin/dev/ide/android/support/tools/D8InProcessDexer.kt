package dev.ide.android.support.tools

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

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

    // In-process D8 holds its whole working set in the app heap (ART caps it ~576MB regardless of device RAM),
    // so a monolithic pass over a big classpath is GC-bound. The build prefers bounded per-library dexing here.
    override fun runsOffHeap(): Boolean = false

    override fun dex(
        inputs: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult = run(
        inputs,
        emptyList(),
        androidJar,
        minApi,
        release,
        outDir,
        OutputMode.DexIndexed,
        threads,
        desugaredLibConfig
    )

    override fun dexArchive(
        inputs: List<Path>,
        classpath: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult = run(
        inputs,
        classpath,
        androidJar,
        minApi,
        release,
        outDir,
        OutputMode.DexFilePerClassFile,
        threads,
        desugaredLibConfig
    )

    private fun run(
        inputs: List<Path>,
        classpath: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        mode: OutputMode,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult {
        Files.createDirectories(outDir)
        val programs = inputs.filter { Files.exists(it) }
        if (programs.isEmpty()) {
            return ToolResult.fail("no class inputs to dex")
        }

        val diagnostics = ArrayList<String>()

        val handler = object : DiagnosticsHandler {
            override fun info(d: Diagnostic) {
                diagnostics.add("info: ${d.diagnosticMessage}")
            }

            override fun warning(d: Diagnostic) {
                diagnostics.add("warning: ${d.diagnosticMessage}")
            }

            override fun error(d: Diagnostic) {
                diagnostics.add("error: ${d.diagnosticMessage}")
            }
        }
        return try {
            val builder =
                D8Command.builder(handler)
                    .addProgramFiles(programs)
                    .setMinApiLevel(minApi)
                    .setMode(if (release) CompilationMode.RELEASE else CompilationMode.DEBUG)
                    .setOutput(outDir, mode)
            // Per-class-file output is an *intermediate* result: cross-class desugaring is deferred to the
            // merge (the merger gets the same library and finalizes it), exactly as AGP's archive→merge flow.
            if (mode == OutputMode.DexFilePerClassFile) {
                builder.setIntermediate(true)
            }
            // Feed the desugaring classpath + android.jar (bootclasspath) as SHARED, cached resource providers
            // (AGP's ClassFileProviderFactory) instead of re-adding the files each invocation: android.jar and
            // stable library jars are then opened + class-indexed once per process and reused across every dex
            // call, not re-parsed per library. A jar that can't be opened as an archive (e.g. a directory)
            // falls back to addClasspathFiles.
            for (cp in classpath.filter { Files.exists(it) }) {
                val p = SharedDexClasspath.provider(cp)
                if (p != null) builder.addClasspathResourceProvider(p) else builder.addClasspathFiles(cp)
            }
            if (Files.exists(androidJar)) {
                val lib = SharedDexClasspath.provider(androidJar)
                if (lib != null) builder.addLibraryResourceProvider(lib) else builder.addLibraryFiles(androidJar)
            }
            // Core-library desugaring: rewrite java.* backport references per the config (the L8 step dexes
            // the runtime separately). Applied at the archive step where class->dex conversion happens.
            if (desugaredLibConfig != null && Files.exists(desugaredLibConfig)) {
                builder.addDesugaredLibraryConfiguration(Files.readAllBytes(desugaredLibConfig).decodeToString())
            }

            val command = builder.build()
            // Bound D8's internal worker pool when the dex pipeline runs many invocations in parallel (the
            // builder has no setThreadCount on this r8 version, so cap via the executor overload instead).
            if (threads > 0) {
                val pool = Executors.newFixedThreadPool(threads)
                try {
                    D8.run(command, pool)
                } finally {
                    pool.shutdown()
                }
            } else {
                D8.run(command)
            }
            val role = if (mode == OutputMode.DexFilePerClassFile) "archived" else "dexed"
            val summary = buildList {
                add("D8 (in-process) $role ${programs.size} input(s) -> ${outDir.fileName}")
                addAll(diagnostics)
            }
            ToolResult.ok(DexDiagnostics.humanize(summary))
        } catch (t: Throwable) {
            // The captured handler diagnostics carry the real cause (e.g. a duplicate-class error); humanize them
            // into an actionable Problem instead of the generic CompilationFailedException message.
            ToolResult(false, DexDiagnostics.humanize(diagnostics + "D8 dexing failed: ${t.message}"))
        }
    }
}
