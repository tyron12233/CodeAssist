package dev.codeassist.csharp.build.tasks

import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.nuget.CsPackageRestorer
import dev.ide.build.AlwaysRun
import dev.ide.build.RunConsole
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import java.nio.file.Path

/**
 * Restores the project's NuGet packages when requested from the Run picker.
 *
 * Retries even if an earlier attempt failed, such as when the network becomes available again
 * after the editor's automatic restore attempts. Reports the result in the task console.
 *
 * Always runs because the restorer determines whether packages need restoring.
 */
internal class CsRestoreTask(
    override val name: TaskName,
    private val dir: Path,
    private val restorer: CsPackageRestorer,
    private val console: RunConsole,
) : Task, AlwaysRun {

    override val inputs: TaskInputs
        get() = TaskInputsImpl()

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        val report = alsoToConsole(ctx.logger(), console)
        val project = Csproj.load(dir)
            ?: return TaskResult.Failed("no .csproj in ${dir.fileName}")

        if (project.packageReferences.isEmpty()) {
            report("The project has no PackageReference.")
            return TaskResult.Success
        }

        report("Restoring NuGet packages: ${project.packageReferences.joinToString { it.id }}")

        val problems = restorer.restore(project, dir, force = true, notify = false)
        problems.forEach(report)

        if (problems.isNotEmpty()) return TaskResult.Failed(problems.first())

        report("Packages are restored.")
        return TaskResult.Success
    }
}