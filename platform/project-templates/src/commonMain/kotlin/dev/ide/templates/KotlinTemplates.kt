package dev.ide.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

// Built-in Kotlin project templates. They scaffold a Kotlin source tree that the editor analyzes with the
// `lang-kotlin` backend (completion, resolution, go-to-definition, the inference subset), so a Kotlin
// project is editable out of the box on every host — including one with no build system, which gets the
// tree and the model and nothing else.
//
// Where there IS a build, the native one compiles these sources (the `compileKotlin` task jvm-build's
// `JavaPlugin` registers for any module with `.kt`), so a `java-lib` module builds and the console
// template's top-level `fun main()` runs.

/** The Kotlin source-dir convention, and where both templates put their sources. */
private const val KOTLIN_SOURCES = "src/main/kotlin"

/**
 * A Kotlin console app: one `app` module with a `Main.kt` that has a top-level `fun main()`. Editable,
 * buildable, and runnable — Run launches it in the interactive console.
 */
object KotlinConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-console")
    override val displayName = "Kotlin Console App"
    override val description = "A Kotlin app with a top-level main(). Full editor intelligence; builds and runs in the interactive console."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        TemplateSupport.singleModule(scaffold, args.name, "app", "java-lib", KOTLIN_SOURCES)
        val pkg = args.packageName
        scaffold.writeText(
            "app/src/main/kotlin/${TemplateSupport.pkgPath(pkg)}/Main.kt",
            """
            package $pkg

            fun main() {
                println("Hello from ${args.name}!")
            }
            """,
        )
    }
}

/** A plain Kotlin library: one `lib` module with a sample class, no entry point (nothing to run). */
object KotlinLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-library")
    override val displayName = "Kotlin Library"
    override val description = "A reusable Kotlin library module. Full editor intelligence; compiles in the native build."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        TemplateSupport.singleModule(scaffold, args.name, "lib", "java-lib", KOTLIN_SOURCES)
        val pkg = args.packageName
        val type = TemplateSupport.typeName(args.name)
        scaffold.writeText(
            "lib/src/main/kotlin/${TemplateSupport.pkgPath(pkg)}/$type.kt",
            """
            package $pkg

            /** Entry point of the ${args.name} library. */
            class $type {
                fun greet(name: String): String = "Hello, " + name + "!"
            }
            """,
        )
    }
}
