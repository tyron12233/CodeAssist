package dev.codeassist.csharp.project.templates

import dev.codeassist.csharp.project.CsModuleType
import dev.codeassist.csharp.project.CsProject
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.TemplateArgs

/**
 * Shared support for C# templates: creates the project module and generates its project file.
 */
internal object CsTemplateSupport {

    const val GITIGNORE = "obj/\nbin/\n"

    /**
     * Creates a project and its module.
     *
     * The module directory is the project folder itself, and that folder is its only source root,
     * matching the structure of a cloned C# repository. The `bin/` and `obj/` directories are
     * excluded as derived-output roots.
     *
     * Returns the module (and assembly) name.
     */
    fun create(scaffold: ProjectScaffold, args: TemplateArgs): String {
        val module = CsProject.assemblyName(args.name)

        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }

        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(module, scaffold.moduleType(CsModuleType.ID)).apply {
                dirRelPath = ""
                languageLevel = scaffold.languageLevel

                addContentRoot("main", "", setOf(CsModuleType.CS_SOURCE, ContentRole.SOURCE))

                // Exclude build output inside the source root. The host dims these directories
                // and keeps its sweeps out of them (3.46.0).
                addContentRoot("main", "bin", setOf(ContentRole.EXCLUDED))
                addContentRoot("main", "obj", setOf(ContentRole.EXCLUDED))
            }
            commit()
        }

        return module
    }

    /** Generates a `.csproj` file with the specified output type (`Exe` or `Library`). */
    fun csproj(outputType: String): String = """
        <Project Sdk="Microsoft.NET.Sdk">

          <PropertyGroup>
            <OutputType>$outputType</OutputType>
            <TargetFramework>net10.0</TargetFramework>
            <ImplicitUsings>enable</ImplicitUsings>
            <Nullable>enable</Nullable>
          </PropertyGroup>

        </Project>
    """.trimIndent() + "\n"
}