package dev.codeassist.csharp.editor


import dev.codeassist.csharp.build.tasks.CsRunIds
import dev.codeassist.csharp.project.CsMainScan
import dev.codeassist.csharp.project.CsMains
import dev.codeassist.csharp.project.CsRunConfig
import dev.codeassist.csharp.project.csproj.Csproj
import dev.ide.plugin.editor.DecorationTint
import dev.ide.plugin.editor.EditorDecorationContext
import dev.ide.plugin.editor.EditorDecorationProvider
import dev.ide.plugin.editor.EditorDecorations
import dev.ide.plugin.editor.GutterMark
import java.nio.file.Paths

/**
 * Adds Run gutter marks for C# entry points.
 *
 * Top-level statements get one mark on the first statement, which runs the module's default task.
 * Otherwise, every `Main` gets a mark. The configured entry point uses the module's task; other
 * classes use their own tasks when the project has multiple class run configurations.
 *
 * The action ID is the plugin API's run action (`GutterActions.RUN_TASK_PREFIX`) followed by a Run task ID,
 * which the editor starts as a run.
 *
 * The module is resolved through [CsModules], which is populated when diagnostics or the Run
 * picker first encounters it. Marks may therefore appear after the editor's first check.
 * A `Main` added but not yet saved may refer to a task that does not exist yet.
 */
internal class CsRunGutter : EditorDecorationProvider {

    override val id = "csharp.run.gutter"

    override fun appliesTo(ctx: EditorDecorationContext): Boolean =
        ctx.path.endsWith(".cs", ignoreCase = true)

    override suspend fun decorate(ctx: EditorDecorationContext): EditorDecorations {
        val module = CsModules.forPath(ctx.path) ?: return EditorDecorations.EMPTY
        val dir = Paths.get(module.dir.path)

        if (Csproj.load(dir)?.isLibrary == true) {
            return EditorDecorations.EMPTY
        }

        val topLevel = CsMainScan.topLevel(ctx.text)
        if (topLevel != null) {
            return topLevelDecoration(ctx, module, topLevel)
        }

        return mainDecorations(ctx, module, dir)
    }

    private fun topLevelDecoration(
        ctx: EditorDecorationContext,
        module: dev.ide.model.Module,
        firstStatement: Int,
    ): EditorDecorations {
        return EditorDecorations(
            gutter = listOf(
                GutterMark(
                    line = CsMainScan.lineOf(ctx.text, firstStatement),
                    iconId = "run",
                    tint = DecorationTint.Success,
                    tooltip = "Run ${module.name}",
                    actionId = CsRunIds.GUTTER_ACTION_PREFIX + CsRunIds.taskId(module.name),
                    order = 100,
                ),
            ),
        )
    }

    private fun mainDecorations(
        ctx: EditorDecorationContext,
        module: dev.ide.model.Module,
        dir: java.nio.file.Path,
    ): EditorDecorations {
        val hits = CsMainScan.hits(ctx.text)
        if (hits.isEmpty()) return EditorDecorations.EMPTY

        val entry = CsMains.entry(dir, CsRunConfig.entryPoint(module, dir))
        val hasClassRows = CsMains.classRows(dir)

        return EditorDecorations(
            gutter = hits.map { hit ->
                val task = if (hasClassRows && hit.className != entry) {
                    CsRunIds.taskId(module.name, hit.className)
                } else {
                    CsRunIds.taskId(module.name)
                }

                GutterMark(
                    line = CsMainScan.lineOf(ctx.text, hit.offset),
                    iconId = "run",
                    tint = DecorationTint.Success,
                    tooltip = "Run ${CsRunIds.simpleName(hit.className)}",
                    actionId = CsRunIds.GUTTER_ACTION_PREFIX + task,
                    order = 100,
                )
            },
        )
    }
}
