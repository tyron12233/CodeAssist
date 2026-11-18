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
import java.nio.file.Path
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

        // A module may hold several projects. The one above is the module's own; each other one that builds a
        // program gets a row of its own, named by its project file.
        for (other in otherPrograms(dir)) {
            rows += RunTaskSpec(
                CsRunIds.taskId(module.name, PROJECT_MARK + dir.relativize(other).toString().replace('\\', '/')),
                "Run ${other.fileName.toString().removeSuffix(".csproj")}",
                "run",
            )
        }

        restore?.let { rows += it }
        return rows
    }

    /** The project files under [dir], other than the module's own, that build a program and not a library. */
    private fun otherPrograms(dir: Path): List<Path> {
        val own = Csproj.find(dir)
        return CsProject.files(dir, PROJECT_DEPTH, skipHidden = true)
            .filter { it.fileName.toString().endsWith(".csproj") && it != own }
            .filter { file -> runCatching { !Csproj.read(file).isLibrary }.getOrDefault(false) }
            .sorted()
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

        // A row for another project of the module: built and run from that project's own folder.
        id.mainClass?.takeIf { it.startsWith(PROJECT_MARK) }?.let { marked ->
            val file = dir.resolve(marked.removePrefix(PROJECT_MARK)).normalize()
            if (!file.startsWith(dir) || !java.nio.file.Files.isRegularFile(file)) return null
            val projectDir = file.parent
            val info = runCatching { Csproj.read(file) }.getOrNull() ?: return null
            val name = file.fileName.toString().removeSuffix(".csproj")
            val dll = Csproj.outputDir(projectDir, info).resolve(Csproj.assemblyName(info, name) + ".dll")
            val console = RunConsole(name)
            val tasks = listOf(
                CsCompileTask(
                    TaskName(":${module.name}:compileCs:$name"), projectDir, dll, sdk, console,
                    CsMains.compilerMain(projectDir, null),
                ),
                CsRunProgramTask(TaskName(":${module.name}:runCs:$name"), projectDir, dll, name, sdk, console),
            )
            return RunAction("> Run $name", LinearGraph(tasks)).also { it.console = console }
        }

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

        /** Marks the part of a Run id that names a project file in the module, where a class name would be. */
        const val PROJECT_MARK = "@"

        /** How far below the module's folder another project is looked for. */
        const val PROJECT_DEPTH = 4
    }
}