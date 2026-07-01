package dev.ide.android.support.tasks

import dev.ide.android.support.AarMetadataRef
import dev.ide.android.support.tools.AarMetadata
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
 * `checkAarMetadata`: the on-device analogue of AGP's `CheckAarMetadataTask`. Reads each compile-scope AAR's
 * `aar-metadata.properties` ([AarMetadata]) and fails the build when a dependency requires a higher
 * `minCompileSdk` than the app compiles against — before compilation, so the error is a clear "raise
 * compileSdk" message rather than a cascade of missing-symbol compile errors. Runs ahead of `processManifest`.
 *
 * The output [stamp] is a marker (no real product); it exists only so the check is up-to-date-cacheable and
 * so downstream tasks can depend on it. Re-runs when a dependency's metadata changes or [compileSdk] moves.
 */
internal class CheckAarMetadataTask(
    override val name: TaskName,
    private val aarMetadata: List<AarMetadataRef>,
    private val compileSdk: Int,
    private val stamp: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("metadata", aarMetadata.map { it.propertiesFile }.filter { Files.exists(it) })
            // Names back the diagnostics; a rename (e.g. a version bump in the coordinate) should re-run the check.
            property("names", aarMetadata.joinToString(";") { it.name })
            property("compileSdk", compileSdk)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("stamp", stamp) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val errors = ArrayList<String>()
        for (ref in aarMetadata) {
            ctx.checkCanceled()
            val info = AarMetadata.read(ref.propertiesFile)
            if (info.isEmpty) continue
            errors += AarMetadata.check(compileSdk, ref.name, info)
        }
        if (errors.isNotEmpty()) {
            errors.forEach { ctx.logger()("ERROR: $it") }
            ctx.reportToolDiagnostics("aar-metadata", errors, DiagnosticKind.GENERIC)
            return TaskResult.Failed("AAR metadata check failed: ${errors.size} incompatible dependency(ies) (see diagnostics)")
        }
        stamp.parent?.let { Files.createDirectories(it) }
        Files.write(stamp, "ok".toByteArray(Charsets.UTF_8))
        ctx.logger()("checkAarMetadata -> ${aarMetadata.size} library metadata file(s) OK (compileSdk $compileSdk)")
        return TaskResult.Success
    }
}
