package dev.codeassist.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * A Flutter app: the package at the project root, `lib/main.dart` the app. Run starts it in the Flutter player
 * on this device and hot-reloads it as files are saved.
 *
 * The first `pub get` needs the network: the framework's own dependencies (`characters`, `vector_math`, ...)
 * come from pub.dev even though the framework itself comes with the plugin.
 */
class FlutterAppTemplate : ProjectTemplate {

    override val id = TemplateId("flutter-app")
    override val displayName = "Flutter App"
    override val description = "A Flutter app, run on this device with hot reload. Flutter ${FlutterSdk.VERSION}; " +
        "the first pub get needs the network."
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
                dirRelPath = ""
                languageLevel = scaffold.languageLevel
                addContentRoot("main", "lib", setOf(DartModuleType.DART_SOURCE))
                addContentRoot("test", "test", setOf(DartModuleType.DART_SOURCE))
            }
            commit()
        }
        scaffold.writeText(DartPackages.PUBSPEC, pubspec(pkg))
        scaffold.writeText("lib/main.dart", main(args.name))
        scaffold.writeText(".gitignore", ".dart_tool/\nbuild/\n")
        scaffold.writeText("README.md", readme(pkg))
    }

    private fun pubspec(pkg: String) = """
        name: $pkg
        description: A Flutter app.
        version: 1.0.0
        publish_to: none

        environment:
          sdk: ^3.9.0

        dependencies:
          flutter:
            sdk: flutter
          cupertino_icons: ^1.0.8

        flutter:
          uses-material-design: true
    """.trimIndent() + "\n"

    private fun main(title: String) = """
        import 'package:flutter/material.dart';
        import 'package:flutter/widget_previews.dart';

        void main() => runApp(const App());

        class App extends StatelessWidget {
          const App({super.key});

          @override
          Widget build(BuildContext context) {
            return MaterialApp(
              title: ${dartString(title)},
              theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
              home: const CounterPage(title: ${dartString(title)}),
            );
          }
        }

        class CounterPage extends StatefulWidget {
          const CounterPage({super.key, required this.title});

          final String title;

          @override
          State<CounterPage> createState() => _CounterPageState();
        }

        class _CounterPageState extends State<CounterPage> {
          int _count = 0;

          @override
          Widget build(BuildContext context) {
            return Scaffold(
              appBar: AppBar(title: Text(widget.title)),
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Taps so far:'),
                    Text('${'$'}_count', style: Theme.of(context).textTheme.displayMedium),
                  ],
                ),
              ),
              floatingActionButton: FloatingActionButton(
                onPressed: () => setState(() => _count++),
                tooltip: 'Tap',
                child: const Icon(Icons.add),
              ),
            );
          }
        }

        // Previews: pick "Preview widgets" in the Run menu to see these, updating as you save.
        @Preview(name: 'Counter page')
        Widget counterPagePreview() => const CounterPage(title: 'Preview');

        @Preview(name: 'Counter page, small phone', size: Size(320, 480), textScaleFactor: 1.3)
        Widget smallCounterPagePreview() => const CounterPage(title: 'Small');
    """.trimIndent() + "\n"

    private fun readme(pkg: String) = """
        # $pkg

        A Flutter app, run on this device by the Dart plugin's Flutter player.

        - `lib/main.dart` is the app. Press Run: it starts in the player, and every file you save hot-reloads
          into it, keeping its state. In the Run console, type `r` to hot reload, `R` to restart, `q` to quit.
        - Functions marked `@Preview` show in "Preview widgets" in the Run menu: every preview in `lib/` in one
          gallery, hot-reloading as you save.
        - Add packages in `pubspec.yaml`; saving it runs `pub get` and restarts the app. Packages with their own
          Android code (plugins) do not work in the player.
        - This is debug mode only: release builds (AOT) are not something the device can make.
    """.trimIndent() + "\n"

    private fun dartString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$") + "'"
}
