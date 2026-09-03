package dev.ide.android.support.tasks

import dev.ide.android.support.AarMetadataRef
import dev.ide.android.support.tools.AarMetadata
import dev.ide.android.support.tools.AndroidSdk
import dev.ide.build.DiagnosticKind
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.reportToolDiagnostics
import java.nio.file.Files
import java.nio.file.Path

/**
 * `checkAarMetadata`: the on-device analogue of AGP's `CheckAarMetadataTask`. Reads each compile-scope AAR's
 * `aar-metadata.properties` ([AarMetadata]) and fails the build when a dependency requires a higher
 * `minCompileSdk` than the app compiles against — before compilation, so the error is a clear "raise
 * compileSdk" message rather than a cascade of missing-symbol compile errors. Runs ahead of `processManifest`.
 *
 * It also warns when the platform the build RESOLVED is older than [compileSdk]: `AndroidSdk.detect` falls
 * back to the highest installed platform when the requested one is absent, so a module set to compile against
 * API 36 on a machine that has only `android-34` silently builds against 34 and fails later on a symbol that
 * does not exist there. A warning, not a failure: the build is still the best one this machine can do, and
 * the IDE cannot install a platform for the user.
 *
 * The output [stamp] is a marker (no real product); it exists only so the check is up-to-date-cacheable and
 * so downstream tasks can depend on it. Re-runs when a dependency's metadata changes or [compileSdk] moves.
 */
internal class CheckAarMetadataTask(
    override val name: TaskName,
    private val aarMetadata: List<AarMetadataRef>,
    private val compileSdk: Int,
    private val stamp: Path,
    /** The `platforms/android-<level>/android.jar` the build resolved, whose directory names its real level. */
    private val platformJar: Path? = null,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("metadata", aarMetadata.map { it.propertiesFile }.filter { Files.exists(it) })
            // Names back the diagnostics; a rename (e.g. a version bump in the coordinate) should re-run the check.
            property("names", aarMetadata.joinToString(";") { it.name })
            property("compileSdk", compileSdk)
            // The resolved platform is part of the answer, so a newly installed SDK re-runs the check.
            property("platform", platformJar?.parent?.fileName?.toString().orEmpty())
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("stamp", stamp) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        resolvedLevel()?.takeIf { it < compileSdk }?.let { level ->
            ctx.logger()(
                "WARNING: compileSdk $compileSdk is not installed; compiling against API $level instead. " +
                    "Install the android-$compileSdk platform, or lower compileSdk in Module Settings.",
            )
        }
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

    /** The API level of the platform jar the build resolved (`platforms/android-36/android.jar` -> 36), or
     *  null when there is no jar to read a level from (the on-device bundled asset has no platform dir). */
    private fun resolvedLevel(): Int? =
        platformJar?.parent?.fileName?.toString()?.let { AndroidSdk.apiLevelOf(it) }?.takeIf { it > 0 }
}
