package dev.codeassist.swift

import dev.ide.model.BuildSystemId
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * A Swift command-line app, laid out as `swift package init --type executable` lays one out: `Package.swift` at the
 * project root and the program in `Sources/<Name>/`.
 *
 * The program asks a question and waits for the answer, on purpose: a prompt that shows before the program reads its
 * input is the thing a console Run most often gets wrong, and the first Run is where to see it work. It uses
 * Foundation too, so the first Run shows that works.
 */
class SwiftConsoleTemplate : ProjectTemplate {

    override val id = TemplateId("swift-console")
    override val displayName = "Swift Console App"
    override val description = "A Swift command-line program with Foundation, compiled and run on the device with Run."
    override val category = TemplateCategory.OTHER
    override val iconId = SwiftFileIcons.SWIFT

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val target = targetName(args.name)
        scaffold.workspace.beginModification().apply {
            addProject(args.name, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == args.name }.beginModification().apply {
            addModule(target, scaffold.moduleType(SwiftModuleType.ID)).apply {
                // The package is the project root, where SwiftPM (and `git clone` of a Swift repo) puts it.
                dirRelPath = ""
                languageLevel = scaffold.languageLevel
                addContentRoot("main", SwiftPackages.SOURCES, setOf(SwiftModuleType.SWIFT_SOURCE))
            }
            commit()
        }
        scaffold.writeText(SwiftPackages.MANIFEST, manifest(target))
        scaffold.writeText("${SwiftPackages.SOURCES}/$target/main.swift", program())
        scaffold.writeText("${SwiftPackages.SOURCES}/$target/Greeting.swift", greeting())
        scaffold.writeText(".gitignore", ".build/\n.swiftpm/\n")
        scaffold.writeText("README.md", readme(target))
    }

    /** `my app` becomes `MyApp`: SwiftPM target names are type-like. */
    private fun targetName(name: String): String {
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val joined = words.joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        return SwiftPackages.moduleName(joined.ifEmpty { "App" })
    }

    private fun manifest(target: String) = """
        // swift-tools-version: 6.0
        import PackageDescription

        let package = Package(
            name: "$target",
            targets: [
                .executableTarget(name: "$target"),
            ]
        )
    """.trimIndent() + "\n"

    private fun program() = """
        import Foundation

        print("What is your name? ", terminator: "")
        let name = readLine()?.trimmingCharacters(in: .whitespaces) ?? ""

        if name.isEmpty {
            print("No name given.")
        } else {
            print(Greeting(name: name).text)
        }
    """.trimIndent() + "\n"

    private fun greeting() = """
        import Foundation

        /// A greeting for someone, with the time of day it was made.
        struct Greeting {
            let name: String

            var text: String {
                let hour = Calendar.current.component(.hour, from: Date())
                let part = hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening"
                return "Good \(part), \(name)! This Swift program ran on your device."
            }
        }
    """.trimIndent() + "\n"

    private fun readme(target: String) = """
        # $target

        A Swift program, compiled and run on the device by the Swift toolchain inside the Swift plugin.

        - `Sources/$target/main.swift` is the program. Press Run: its output appears in the Run console, and what you
          type there is its standard input.
        - Other `.swift` files in `Sources/$target/` are part of the same program.
        - The standard library and Foundation are available. Packages from `Package.swift` dependencies are not
          fetched yet.
    """.trimIndent() + "\n"
}
