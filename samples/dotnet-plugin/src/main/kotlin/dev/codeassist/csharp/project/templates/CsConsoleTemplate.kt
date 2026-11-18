package dev.codeassist.csharp.project.templates

import dev.codeassist.csharp.ui.CsFileIcons
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Creates a C# console project following the `dotnet new console` layout:
 * `<Name>.csproj` and `Program.cs` directly in the project folder.
 *
 * No dependencies are required, so the first Run works offline.
 *
 * The sample program asks for input and waits for an answer. This demonstrates console input and output
 * immediately, including whether the prompt appears before the program waits for input.
 */
class CsConsoleTemplate : ProjectTemplate {

    override val id = TemplateId("csharp-console")
    override val displayName = "C# Console App"
    override val description = "A C# command-line program, compiled and run on the device with Run."
    override val category = TemplateCategory.OTHER
    override val iconId = CsFileIcons.CSHARP

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val module = CsTemplateSupport.create(scaffold, args)

        scaffold.writeText("$module.csproj", CsTemplateSupport.csproj("Exe"))
        scaffold.writeText("Program.cs", program())
        scaffold.writeText(".gitignore", CsTemplateSupport.GITIGNORE)
        scaffold.writeText("README.md", readme(module))
    }

    // Avoid `$"..."`: dollar signs in Kotlin raw strings are treated as template syntax.
    private fun program() = """
        class Program
        {
            static void Main(string[] args)
            {
                Console.Write("What is your name? ");
                string name = (Console.ReadLine() ?? "").Trim();
                if (name.Length == 0)
                {
                    Console.WriteLine("No name given.");
                    Environment.ExitCode = 1;
                    return;
                }
                Console.WriteLine("Hello, " + name + "! This program ran on your device.");
            }
        }
    """.trimIndent() + "\n"

    private fun readme(module: String) = """
        # $module

        A C# program compiled by Roslyn and run by .NET inside the C# plugin.

        - `$module.csproj` contains the project settings. All `.cs` files alongside it are compiled together.
        - Press Run to execute the program. Output appears in the Run console, and text entered there is passed
          to the program as standard input.
        - Roslyn compilation errors appear in the console.
    """.trimIndent() + "\n"
}