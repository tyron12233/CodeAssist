package dev.codeassist.csharp.build.tasks

import dev.codeassist.csharp.editor.CsModules
import dev.codeassist.csharp.project.CsMains
import dev.codeassist.csharp.project.CsModuleType
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.CsRunConfig
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.nuget.CsPackageRestorer
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.ide.build.BuildContext
import dev.ide.build.RunAction
import dev.ide.build.RunConsole
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.TaskName
import dev.ide.model.Module
import dev.ide.model.Project
import dev.ide.platform.log.Logger
import java.nio.file.Paths

/**
 * Provides Run picker entries for C# projects with source files.
 *
 * Executable projects get a Run entry; libraries get a Build entry. A run compiles the project's `.cs` files
 * with Roslyn's `csc.dll` into the output folder (see [Csproj.outputDir]), then launches the DLL with the
 * .NET runtime. Both steps use [DotnetSdk.launcher], not `dotnet`.
 *
 * Uses a [RunTaskProvider] with its own two-task graph.
 * [CsBuildPlugin] registers the same compile task for ordinary builds.
 */
internal class CsRunTaskProvider(
    private val sdk: DotnetSdk,
    private val log: Logger,
    private val restorer: CsPackageRestorer,
) : RunTaskProvider {

    // Run picker entries

    override fun tasksFor(module: Module): List<RunTaskSpec> {
        if (module.type.id != CsModuleType.ID) return emptyList()

        CsModules.remember(module)

        val dir = Paths.get(module.dir.path)
        if (CsProject.sources(dir).isEmpty()) return emptyList()

        val project = Csproj.load(dir)

        // Offer a manual restore entry when the project has package references.
        val restore = RunTaskSpec(
            CsRunIds.restoreId(module.name),
            "Restore NuGet packages",
            "build",
        ).takeIf { project?.packageReferences?.isNotEmpty() == true }

        // A library has no entry point, so its Build entry only compiles the DLL.
        if (project?.isLibrary == true) {
            return listOfNotNull(
                RunTaskSpec(CsRunIds.taskId(module.name), "Build ${module.name}", "run"),
                restore,
            )
        }

        val rows = mutableListOf(
            RunTaskSpec(CsRunIds.taskId(module.name), "Run ${module.name}", "run"),
        )

        // The module entry runs its configured entry point. Add individual entries for projects with multiple
        // Main methods or top-level statements alongside Main methods. The host only runs entries listed here,
        // and editor gutter actions use these entries to run the corresponding class.
        if (CsMains.classRows(dir)) {
            for (main in CsMains.classes(dir)) {
                rows += RunTaskSpec(
                    CsRunIds.taskId(module.name, main),
                    "Run ${CsRunIds.simpleName(main)}",
                    "run",
                )
            }
        }

        restore?.let { rows += it }
        return rows
    }

    override fun actionFor(
        spec: RunTaskSpec,
        project: Project,
        module: Module,
        ctx: BuildContext,
    ): RunAction? {
        val dir = Paths.get(module.dir.path)

        // Manual NuGet restore
        CsRunIds.restoreModule(spec.id)?.let { restoreOf ->
            if (restoreOf != module.name) return null

            val console = RunConsole(module.name)
            val task = CsRestoreTask(
                TaskName(":${module.name}:restoreCs"),
                dir,
                restorer,
                console,
            )

            return RunAction("> Restore NuGet packages", LinearGraph(listOf(task)))
                .also { it.console = console }
        }

        // C# execution or library build
        val id = CsRunIds.parse(spec.id) ?: return null
        if (id.module != module.name) return null

        val info = Csproj.load(dir)
        val assembly = Csproj.assemblyName(info, module.name)
        val dll = Csproj.outputDir(dir, info).resolve("$assembly.dll")

        val console = RunConsole(module.name)

        // A class entry runs that class. The module entry uses the configured entry point or the first Main.
        val mainClass = id.mainClass
            ?: CsMains.compilerMain(dir, CsRunConfig.entryPoint(module, dir))

        val compile = CsCompileTask(
            TaskName(":${module.name}:compileCs"),
            dir,
            dll,
            sdk,
            console,
            mainClass,
        )

        if (info?.isLibrary == true) {
            return RunAction("> Build ${module.name}", LinearGraph(listOf(compile)))
                .also { it.console = console }
        }

        val tasks = listOf(
            compile,
            CsRunProgramTask(
                TaskName(":${module.name}:runCs"),
                dir,
                dll,
                module.name,
                sdk,
                console,
            ),
        )

        val header = id.mainClass?.let {
            "> Run ${CsRunIds.simpleName(it)}"
        } ?: "> Run ${module.name}"

        return RunAction(header, LinearGraph(tasks)).also { it.console = console }
    }

    companion object {
        /** Prefix shared by this plugin's Run entry IDs. */
        const val RUN_PREFIX = CsRunIds.PREFIX
    }
}