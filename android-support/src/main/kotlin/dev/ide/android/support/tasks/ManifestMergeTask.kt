package dev.ide.android.support.tasks

import dev.ide.android.support.manifest.ManifestMerger
import dev.ide.build.DiagnosticKind
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import dev.ide.build.engine.reportToolDiagnostics
import java.nio.file.Files
import java.nio.file.Path

/**
 * `processManifest`/`process<Variant>Manifest`: merge the app manifest with every dependency-library and
 * AAR manifest ([ManifestMerger]) into one manifest fed to `aapt2 link`. Without this a library's
 * `<service>`/`<receiver>`/`<provider>`/`<meta-data>`/permission contributions are silently dropped — the
 * reason Firebase/Play Services need it. [libraryManifests] are in decreasing priority.
 */
internal class ManifestMergeTask(
    override val name: TaskName,
    private val primaryManifest: Path,
    private val libraryManifests: List<Path>,
    private val placeholders: Map<String, String>,
    private val outManifest: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("primary", listOf(primaryManifest).filter { Files.exists(it) })
            filePaths("libs", libraryManifests.filter { Files.exists(it) })
            // Placeholder values are part of the merged output, so a change must re-run the merge.
            property("placeholders", placeholders.toSortedMap().toString())
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("manifest", outManifest) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(primaryManifest))
            return TaskResult.Failed("app manifest not found: $primaryManifest")
        val libs = libraryManifests.filter { Files.isRegularFile(it) }
        // Catch Throwable (not just Exception): an XML/regex impl quirk on ART can surface as an Error
        // (e.g. ExceptionInInitializerError) — report it as a build failure with the cause, never crash.
        val result = try {
            ManifestMerger.merge(primaryManifest, libs, placeholders)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            return TaskResult.Failed("manifest merge crashed: ${cause::class.simpleName}: ${cause.message}", t)
        }

        val logs = result.messages.map { "${it.severity}: ${it.text}" }
        logs.forEach(ctx.logger())
        ctx.reportToolDiagnostics("manifest-merger", logs, DiagnosticKind.GENERIC)
        if (result.hasErrors) return TaskResult.Failed("manifest merge failed (see diagnostics)")

        outManifest.parent?.let { Files.createDirectories(it) }
        Files.write(outManifest, result.xml.toByteArray(Charsets.UTF_8))
        ctx.logger()("processManifest -> ${outManifest.fileName} (merged ${libs.size} library manifest(s))")
        return TaskResult.Success
    }
}
