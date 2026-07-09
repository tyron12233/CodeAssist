package dev.ide.core.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * Built-in **sample projects** — complete, documented, runnable example apps (not bare starters). Each one
 * scaffolds a real multi-file project whose sources are bundled as classpath resources under
 * `resources/samples/<id>/…` (so the example code stays clean, idiomatic, and easy to maintain), then copies
 * them into the new project verbatim. They're plain Java/Kotlin console apps, so they build and run with no
 * SDK or network — guaranteed to work out of the box.
 *
 * Sample template ids are prefixed `sample-` so the store lists them under "Sample projects" (not "Starter
 * templates"); they otherwise flow through the exact same create path as any other template.
 */
internal object SampleSupport {
    /** Read a bundled sample resource, or fail loudly (a missing sample is a build/packaging bug). */
    private fun readResource(path: String): String =
        SampleSupport::class.java.classLoader.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("Missing bundled sample resource: $path")

    /** Copy each of [files] (paths relative to both the sample resource root and the project root). */
    fun copyFiles(scaffold: ProjectScaffold, sampleId: String, files: List<String>) {
        for (rel in files) scaffold.writeText(rel, readResource("samples/$sampleId/$rel"))
    }
}

/** Calculator — a Java console app that parses and evaluates arithmetic expressions. */
object CalculatorSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-calculator")
    override val displayName = "Calculator"
    override val description = "An interactive command-line calculator: type expressions and it evaluates them."
    override val category = TemplateCategory.JAVA
    override val iconId = "java"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        JavaTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        SampleSupport.copyFiles(
            scaffold, "calculator",
            listOf(
                "app/src/main/java/calculator/Calculator.java",
                "app/src/main/java/calculator/Main.java",
                "README.md",
            ),
        )
    }
}

/** Notes — a Kotlin console note-taking app (add/list/search/complete), model split from view. */
object NotesSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-notes")
    override val displayName = "Notes"
    override val description = "An interactive note-taking CLI: type commands to add, list, search, and complete notes."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        SampleSupport.copyFiles(
            scaffold, "notes",
            listOf(
                "app/src/main/kotlin/notes/Notebook.kt",
                "app/src/main/kotlin/notes/Main.kt",
                "README.md",
            ),
        )
    }
}

/** Weather — a Kotlin console app that formats a multi-day forecast from bundled sample data. */
object WeatherSampleTemplate : ProjectTemplate {
    override val id = TemplateId("sample-weather")
    override val displayName = "Weather"
    override val description = "An interactive weather report: type a city to see its forecast."
    override val category = TemplateCategory.KOTLIN
    override val iconId = "kotlin"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        KotlinTemplateSupport.singleModule(scaffold, args.name, "app", "java-lib")
        SampleSupport.copyFiles(
            scaffold, "weather",
            listOf(
                "app/src/main/kotlin/weather/Forecast.kt",
                "app/src/main/kotlin/weather/Main.kt",
                "README.md",
            ),
        )
    }
}
