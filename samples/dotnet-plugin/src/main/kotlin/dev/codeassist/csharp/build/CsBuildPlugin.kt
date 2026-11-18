package dev.codeassist.csharp.build

import dev.codeassist.csharp.build.tasks.CsCompileTask
import dev.codeassist.csharp.project.CsModuleType
import dev.codeassist.csharp.project.CsProject
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildGoal
import dev.ide.build.BuildPlugin
import dev.ide.build.Lifecycle
import dev.ide.build.TaskName
import java.nio.file.Paths

/**
 * Compiles every C# module during the ordinary build, leaving the DLL in the project's `bin/` folder
 * for Run to use. Libraries without an entry point are built here as well.
 *
 * Registers one `compileCs` task per module and wires it to `assemble`. The Run picker uses the same task name and output, so a Build followed by a Run can reuse
 * the up-to-date output.
 */
internal class CsBuildPlugin(private val sdk: DotnetSdk) : BuildPlugin {

    override val id = "csharp"

    // Applicability

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.request.goal != BuildGoal.CLEAN &&
            config.project.modules.any { it.type.id == CsModuleType.ID }

    override fun apply(config: BuildConfiguration) {
        val variant = config.request.variant.name

        for (module in config.project.modules) {
            if (module.type.id != CsModuleType.ID) continue

            val dir = Paths.get(module.dir.path)
            if (CsProject.sources(dir).isEmpty()) continue

            val info = Csproj.load(dir)
            val assembly = Csproj.assemblyName(info, module.name)
            val dll = Csproj.outputDir(dir, info).resolve("$assembly.dll")

            val taskName = TaskName(":${module.name}:compileCs")

            config.tasks.register(taskName) {
                CsCompileTask(taskName, dir, dll, sdk, console = null)
            }

            config.tasks.named(Lifecycle.assemble(module.name)).configure {
                dependsOn(taskName)
            }
            config.tasks.named(Lifecycle.assemble(module.name, variant)).configure {
                dependsOn(taskName)
            }
        }
    }
}