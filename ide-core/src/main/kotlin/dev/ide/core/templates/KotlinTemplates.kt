package dev.ide.core.templates

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Built-in Kotlin project templates. These scaffold a Kotlin source tree (`src/main/kotlin`) that
 * the editor analyzes with the `lang-kotlin` backend (completion, resolution, go-to-definition, and the
 * inference subset), so a Kotlin project is editable out of the box.
 *
 * The Kotlin backend is editor-only: it does not compile Kotlin to bytecode/dex (that is a separate
 * build track). The module is a `java-lib`, so a Run/Build does not produce output until Kotlin codegen
 * lands. Each template's description notes this.
 */
private object KotlinTemplateSupport {
    /** A `main` source set rooted at `src/main/kotlin` (the Kotlin source-dir convention). */
    private fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/kotlin" to setOf(ContentRole.SOURCE)))

    /** Add a single-module Kotlin project ([moduleName] of [typeId]) and commit the model. */
    fun singleModule(scaffold: ProjectScaffold, projectName: String, moduleName: String, typeId: String) {
        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(moduleName, scaffold.moduleType(typeId)).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(mainSources())
            }
            commit()
        }
    }
}

/**
 * A Kotlin console app: one `app` module with a `Main.kt` that has a top-level `fun main()`. Editor-only;
 * see the file header (Kotlin compilation is not wired).
 */
object KotlinConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-console")
    override val displayName = "Kotlin Console App"
    override val description = "A Kotlin app with a top-level main(). Full editor intelligence; Kotlin → bytecode build is not yet wired."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        val pkg = args.packageName
        scaffold.writeText(
            "app/src/main/kotlin/${JavaTemplateSupport.pkgPath(pkg)}/Main.kt",
            """
            package $pkg

            fun main() {
                println("Hello from ${args.name}!")
            }
            """,
        )
    }
}

/** A plain Kotlin library: one `lib` module with a sample class, no entry point. Editor-only for now. */
object KotlinLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("kotlin-library")
    override val displayName = "Kotlin Library"
    override val description = "A reusable Kotlin library module. Full editor intelligence; Kotlin → bytecode build is not yet wired."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "lib", "java-lib")
        val pkg = args.packageName
        val type = JavaTemplateSupport.typeName(args.name)
        scaffold.writeText(
            "lib/src/main/kotlin/${JavaTemplateSupport.pkgPath(pkg)}/$type.kt",
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
