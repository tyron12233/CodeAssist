package dev.ide.android.support.tasks

import dev.ide.android.support.tools.ArtProfiles
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import dev.ide.build.engine.warn
import java.nio.file.Files
import java.nio.file.Path

/**
 * `compile<Variant>ArtProfile`: merge the Baseline Profile rules of the app, its library modules and its AARs,
 * and compile them against the dex about to be packaged into [profileOut] / [metadataOut] ([ArtProfiles]).
 * Release builds only, as with AGP: ART does not AOT-compile a debuggable app, so a debug APK has no use for it.
 *
 * Produces nothing (and removes an earlier build's output) when there are no rules, so the packagers can key
 * on whether the files exist. [mapping] is R8's `mapping.txt` on a minified build.
 */
internal class CompileArtProfileTask(
    override val name: TaskName,
    private val rules: List<ArtProfiles.RulesFile>,
    private val dexDirs: List<Path>,
    private val mapping: Path?,
    private val mergedOut: Path,
    private val profileOut: Path,
    private val metadataOut: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("rules", rules.map { it.path })
            dirPaths("dex", dexDirs)
            filePaths("mapping", listOfNotNull(mapping).filter { Files.exists(it) })
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            filePath("merged", mergedOut)
            filePath("profile", profileOut)
            filePath("metadata", metadataOut)
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val result = runCatching {
            ArtProfiles.compile(rules, ApkPackaging.packagedDex(dexDirs), mapping, mergedOut, profileOut, metadataOut)
        }.getOrElse { return TaskResult.Failed("Baseline Profile compilation failed: ${it.message}", it) }

        val warnings = when (result) {
            is ArtProfiles.Result.Compiled -> result.warnings
            is ArtProfiles.Result.Empty -> result.warnings
            is ArtProfiles.Result.Failed -> emptyList()
        }
        for (w in warnings) {
            ctx.warn("${name.value}: $w")
            ctx.diagnostics.report(BuildDiagnostic(BuildSeverity.WARNING, w, DiagnosticKind.PACKAGING, source = "profgen"))
        }
        return when (result) {
            is ArtProfiles.Result.Compiled -> {
                ctx.debug(
                    "${name.value}: Baseline Profile covers ${result.methods} method(s) and ${result.classes} " +
                        "class(es) from ${rules.size} rules file(s)",
                )
                TaskResult.Success
            }
            is ArtProfiles.Result.Empty -> {
                ctx.debug("${name.value}: no Baseline Profile rules matched; packaging no profile")
                TaskResult.Success
            }
            is ArtProfiles.Result.Failed -> {
                for (e in result.errors) {
                    ctx.diagnostics.report(
                        BuildDiagnostic(
                            BuildSeverity.ERROR, "Baseline Profile rule does not parse", DiagnosticKind.PACKAGING,
                            source = "profgen",
                            location = DiagnosticLocation(e.file.toString(), e.line, e.column),
                            detail = e.message,
                        ),
                    )
                }
                TaskResult.Failed("Baseline Profile rules do not parse:\n" + result.errors.joinToString("\n"))
            }
        }
    }
}
