package dev.codeassist.csharp.project.templates

import dev.codeassist.csharp.ui.CsFileIcons
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Creates a C# class library following the `dotnet new classlib` layout:
 * `<Name>.csproj` and a single class file directly in the project folder.
 *
 * The library has no entry point, so Run builds it instead of executing a program.
 */
class CsLibraryTemplate : ProjectTemplate {

    override val id = TemplateId("csharp-library")
    override val displayName = "C# Class Library"
    override val description = "A C# class library, compiled to a DLL on the device with Build."
    override val category = TemplateCategory.OTHER
    override val iconId = CsFileIcons.CSHARP

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val module = CsTemplateSupport.create(scaffold, args)

        scaffold.writeText("$module.csproj", CsTemplateSupport.csproj("Library"))
        scaffold.writeText("Class1.cs", classFile(module))
        scaffold.writeText(".gitignore", CsTemplateSupport.GITIGNORE)
        scaffold.writeText("README.md", readme(module))
    }

    private fun classFile(module: String) = """
        namespace $module;

        public class Class1
        {
            public static string Greet(string name) => "Hello, " + name + "!";
        }
    """.trimIndent() + "\n"

    private fun readme(module: String) = """
        # $module

        A C# class library compiled by Roslyn into `$module.dll` inside the C# plugin.

        - `$module.csproj` contains the project settings. All `.cs` files alongside it are compiled together.
        - Run builds the library; there is no executable entry point.
    """.trimIndent() + "\n"
}