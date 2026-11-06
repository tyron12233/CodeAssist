package dev.codeassist.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * A Dart command-line app, laid out as `dart create -t console` lays one out: the package at the project
 * root, `bin/` holding the program, `lib/` the code it imports, `test/` for tests.
 *
 * It has no dependencies, so the first Run works offline. The program asks a question and waits for the
 * answer, on purpose: a prompt that shows before the program reads its input is the thing a console run most
 * often gets wrong, and the first run is where to see it work.
 */
class DartConsoleTemplate : ProjectTemplate {

    override val id = TemplateId("dart-console")
    override val displayName = "Dart Console App"
    override val description = "A Dart command-line program, run on the device with Run. Errors, completion and " +
        "quick fixes come from Dart's own analysis server."
    override val category = TemplateCategory.OTHER
    override val iconId = DartFileIcons.DART

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = DartPackages.packageName(args.name)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(pkg, scaffold.moduleType(DartModuleType.ID)).apply {
                // The package is the project root, where Dart tools (and `git clone` of a Dart repo) put it.
                dirRelPath = ""
                languageLevel = scaffold.languageLevel
                for (dir in listOf("lib", "bin")) addContentRoot("main", dir, setOf(DartModuleType.DART_SOURCE))
                // No test yet (package:test would need the network for the first Run), but where they go.
                addContentRoot("test", "test", setOf(DartModuleType.DART_SOURCE))
            }
            commit()
        }
        scaffold.writeText(DartPackages.PUBSPEC, pubspec(pkg))
        scaffold.writeText("bin/$pkg.dart", program(pkg))
        scaffold.writeText("lib/$pkg.dart", library())
        scaffold.writeText(".gitignore", ".dart_tool/\nbuild/\n")
        scaffold.writeText("README.md", readme(pkg))
    }

    private fun pubspec(pkg: String) = """
        name: $pkg
        description: A command-line application.
        version: 1.0.0
        publish_to: none

        environment:
          sdk: ^3.9.0
    """.trimIndent() + "\n"

    private fun program(pkg: String) = """
        import 'dart:io';

        import 'package:$pkg/$pkg.dart';

        void main(List<String> arguments) {
          stdout.write('What is your name? ');
          final name = stdin.readLineSync()?.trim() ?? '';
          if (name.isEmpty) {
            print('No name given.');
            exitCode = 1;
            return;
          }
          print(greet(name));
        }
    """.trimIndent() + "\n"

    private fun library() = """
        /// The greeting for [name].
        String greet(String name) => 'Hello, ${'$'}name! This program ran on your device.';
    """.trimIndent() + "\n"

    private fun readme(pkg: String) = """
        # $pkg

        A Dart program, run on the device by the Dart SDK inside the Dart plugin.

        - `bin/$pkg.dart` is the program. Press Run: its output appears in the Run console, and what you type
          there is its standard input. Every `.dart` file in `bin/` gets a Run entry of its own.
        - `lib/` holds the code the program imports as `package:$pkg/...`.
        - Add packages in `pubspec.yaml`, then pick `pub get` in the Run menu (Run also does it when the
          pubspec changed).
    """.trimIndent() + "\n"
}
