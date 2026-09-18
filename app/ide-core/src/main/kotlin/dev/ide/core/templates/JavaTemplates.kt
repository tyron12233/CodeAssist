package dev.ide.core.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.templates.TemplateSupport

/** The Java source-dir convention, and where all three Java-flavoured templates put their sources. */
internal const val JAVA_SOURCES = "src/main/java"

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
        TemplateSupport.singleModule(scaffold, args.name, "app", "java-lib", JAVA_SOURCES)
        val pkg = args.packageName
        scaffold.writeText(
            "app/src/main/java/${TemplateSupport.pkgPath(pkg)}/Main.java",
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
        TemplateSupport.singleModule(scaffold, args.name, "lib", "java-lib", JAVA_SOURCES)
        val pkg = args.packageName
        val type = TemplateSupport.typeName(args.name)
        scaffold.writeText(
            "lib/src/main/java/${TemplateSupport.pkgPath(pkg)}/$type.java",
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
