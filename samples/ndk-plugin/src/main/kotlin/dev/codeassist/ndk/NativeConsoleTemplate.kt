package dev.codeassist.ndk

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * A C or C++ console program: a `main` that reads and writes the terminal, run with the Run button.
 *
 * The same module shape as [NativeCppTemplate] (a `java-lib` carrying the `[ndk]` facet, nothing Android in
 * it); what makes it runnable is the `main`, which is what [NdkRunTaskProvider] looks for. The starter asks
 * a question and waits for the answer, on purpose: a prompt that shows before the program reads its input is
 * the thing a console run most often gets wrong, and the first run is where to see it work.
 */
class NativeConsoleTemplate : ProjectTemplate {

    override val id = TemplateId("ndk-native-console")
    override val displayName = "C/C++ Console App"
    override val description =
        "A C or C++ program with a main(), compiled on the device and run in the console with Run."
    override val category = TemplateCategory.OTHER
    override val iconId = NdkFileIcons.CPP

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Choice(
            key = NdkTemplateSupport.LANGUAGE,
            label = "Language",
            options = listOf(
                TemplateParameter.Choice.Option(NdkTemplateSupport.LANG_CPP, "C++"),
                TemplateParameter.Choice.Option(NdkTemplateSupport.LANG_C, "C"),
            ),
            help = "Which of the two main is written in. Both compile in the same module: the build picks " +
                "the language from each file's extension.",
        ),
        NdkTemplateSupport.standardParam(cppOnly = false),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val cpp = args.string(NdkTemplateSupport.LANGUAGE, NdkTemplateSupport.LANG_CPP) != NdkTemplateSupport.LANG_C
        val standard = args.string(NdkTemplateSupport.STANDARD, if (cpp) "c++17" else "c17")

        NdkTemplateSupport.scaffoldModule(
            scaffold = scaffold,
            projectName = args.name,
            moduleTypeId = "java-lib",
            ndk = NdkTemplateSupport.withStandard(
                // A console program links nothing Android-specific; `log` is the activity templates'.
                NdkFacet(libraryName = "app", linkLibraries = emptyList()),
                standard,
            ),
        )

        val file = if (cpp) "main.cpp" else "main.c"
        scaffold.writeText("app/src/main/cpp/$file", if (cpp) cppMain() else cMain())
        scaffold.writeText("README.md", readme(file, standard))
    }

    private fun cppMain(): String = """
        #include <iostream>
        #include <string>

        int main() {
            std::string name;
            std::cout << "What is your name? ";
            if (!std::getline(std::cin, name) || name.empty()) {
                std::cout << "No name given.\n";
                return 1;
            }
            std::cout << "Hello, " << name << "! This program was compiled on your device.\n";
            return 0;
        }
    """.trimIndent() + "\n"

    private fun cMain(): String = """
        #include <stdio.h>
        #include <string.h>

        int main(void) {
            char name[64];
            printf("What is your name? ");
            if (!fgets(name, sizeof name, stdin) || name[0] == '\n') {
                printf("No name given.\n");
                return 1;
            }
            name[strcspn(name, "\n")] = '\0';
            printf("Hello, %s! This program was compiled on your device.\n", name);
            return 0;
        }
    """.trimIndent() + "\n"

    private fun readme(file: String, standard: String): String = """
        # Console app

        A C/C++ program compiled on the device by the clang inside the NDK plugin, and run with the Run
        button. Its output appears in the Run console, and what you type there is its standard input.

        - `app/src/main/cpp/$file` holds `main`. Add more `.c`/`.cpp` files and headers beside it; all of
          them are compiled and linked into the program.
        - The `[ndk]` table in `app/module.toml` is the build configuration (this project was created with
          `$standard`). `linkLibraries` adds `-l` libraries, `compilerFlags` anything else.
        - The program runs in a terminal, so a prompt without a newline shows before it reads, as it would
          in a shell. Stop ends it.
    """.trimIndent() + "\n"
}
