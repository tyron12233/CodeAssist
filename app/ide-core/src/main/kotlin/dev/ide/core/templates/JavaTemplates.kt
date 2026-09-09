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

/** Shared helpers for the built-in Java templates. */
internal object JavaTemplateSupport {
    /** "com.example.app" → "com/example/app". */
    fun pkgPath(pkg: String): String = pkg.replace('.', '/')

    /** A `main` source set rooted at `src/main/java`. */
    fun mainSources() =
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)))

    /** Add a single-module project ([moduleName] of [typeId]) and return after committing the model. */
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

    /** A safe PascalCase Java identifier derived from a free-form project name (fallback "App"). */
    fun typeName(raw: String): String {
        val cleaned = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val candidate = cleaned.ifEmpty { "App" }
        return if (candidate.first().isDigit()) "App$candidate" else candidate
    }
}

/**
 * A runnable Java console app: one `app` module (java-lib) with a `Main` class that has a
 * `public static void main` — so `IdeServices.runTasks()` offers a `run:` task out of the box.
 */
object JavaConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("java-console")
    override val displayName = "Java Console App"
    override val description = "A runnable command-line Java application with a main() entry point."
    override val category = TemplateCategory.JAVA
    override val iconId = "java"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        JavaTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        val pkg = args.packageName
        scaffold.writeText(
            "app/src/main/java/${JavaTemplateSupport.pkgPath(pkg)}/Main.java",
            """
            package $pkg;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello from ${args.name}!");
                }
            }
            """,
        )
    }
}

/** A plain Java library: one `lib` module (java-lib) with a sample public class, no main(). */
object JavaLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("java-library")
    override val displayName = "Java Library"
    override val description = "A reusable Java library module with no entry point."
    override val category = TemplateCategory.JAVA
    override val iconId = "module"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        JavaTemplateSupport.singleModule(scaffold, args.name, "lib", "java-lib")
        val pkg = args.packageName
        val type = JavaTemplateSupport.typeName(args.name)
        scaffold.writeText(
            "lib/src/main/java/${JavaTemplateSupport.pkgPath(pkg)}/$type.java",
            """
            package $pkg;

            /** Entry point of the ${args.name} library. */
            public final class $type {
                public String greet(String name) {
                    return "Hello, " + name + "!";
                }
            }
            """,
        )
    }
}
