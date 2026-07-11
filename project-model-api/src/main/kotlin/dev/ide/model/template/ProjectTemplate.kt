package dev.ide.model.template

import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.Workspace
import dev.ide.platform.ExtensionPoint
import dev.ide.vfs.VirtualFile

/**
 * project-template SPI — how a new project is scaffolded, behind an extension point so the set of
 * project kinds (Android app/library, Java console/library, …) is pluggable just like [ModuleType].
 *
 * A template is purely declarative about its inputs ([parameters]) so the Create-Project UI can be
 * data-driven: it renders one control per [TemplateParameter] and hands the collected values back as
 * [TemplateArgs]. The template's [generate] then authors the project against a [ProjectScaffold] (the
 * model transaction surface plus a file-write helper), the same way `SampleProject` and
 * `SampleAndroidProject` do, except the workspace dir, SDK, and language level are supplied by the host.
 */
interface ProjectTemplate {
    val id: TemplateId
    val displayName: String
    val description: String
    val category: TemplateCategory

    /** Icon id resolved by the UI's `TreeIcons` registry (e.g. `module.android`, `java`). */
    val iconId: String

    /**
     * Inputs the UI collects beyond the always-present [TemplateArgs.NAME] and [TemplateArgs.PACKAGE]
     * (which the screen renders itself). Templates add extras here — language level, minSdk, etc.
     */
    fun parameters(): List<TemplateParameter>

    /**
     * Author the project into [scaffold] from the collected [args]. The workspace dir
     * ([ProjectScaffold.rootDir]) already exists and is empty; the SDK is already seeded.
     */
    fun generate(scaffold: ProjectScaffold, args: TemplateArgs)

    /**
     * Maven dependencies the generated project needs (e.g. a Material You app declares
     * `com.google.android.material:material`). The host resolves and attaches each one *after* [generate]
     * (resolution is a `suspend`/network step the synchronous scaffold can't do), reusing the same Maven
     * resolver + offline cache as the Dependencies screen. Empty for the dependency-free templates.
     */
    fun dependencies(args: TemplateArgs): List<TemplateDependency> = emptyList()
}

/**
 * A Maven dependency a [ProjectTemplate] declares for the project it scaffolds. The host resolves
 * [coordinate] (`group:name:version`) against the configured repositories and adds it to the named
 * [module] with [scope] (`implementation`/`api`/`compileOnly`/…) once generation has run.
 */
data class TemplateDependency(
    val module: String,
    val coordinate: String,
    val scope: String = "implementation",
)

/** The `platform.projectTemplate` extension point. Plugins contribute their [ProjectTemplate]s here. */
val ProjectTemplateExtensionPoint: ExtensionPoint<ProjectTemplate> = ExtensionPoint("platform.projectTemplate")

@JvmInline value class TemplateId(val value: String)

/** Buckets templates in the gallery. Open enough that a plugin can drop into [OTHER]. */
enum class TemplateCategory(val displayName: String) {
    ANDROID("Android"),
    JAVA("Java"),
    KOTLIN("Kotlin"),
    OTHER("Other"),
}

// ---------------------------------------------------------------------------
// Declarative parameters (the UI renders one control per entry)
// ---------------------------------------------------------------------------

/** A single input the Create-Project screen renders. [key] is how its value is read from [TemplateArgs]. */
sealed interface TemplateParameter {
    val key: String
    val label: String
    val help: String?

    /** A free-text field, optionally validated as an identifier/package/project name. */
    data class Text(
        override val key: String,
        override val label: String,
        val default: String = "",
        val placeholder: String = "",
        val validation: TextValidation = TextValidation.NONE,
        override val help: String? = null,
    ) : TemplateParameter

    /** A one-of-many choice (rendered as segmented chips or a dropdown). */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val defaultIndex: Int = 0,
        override val help: String? = null,
    ) : TemplateParameter {
        data class Option(val value: String, val label: String)
    }

    /** A boolean toggle. */
    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
        override val help: String? = null,
    ) : TemplateParameter
}

/** Client-side validation hint for a [TemplateParameter.Text]. */
enum class TextValidation { NONE, IDENTIFIER, PACKAGE_NAME, PROJECT_NAME }

// ---------------------------------------------------------------------------
// Collected argument values
// ---------------------------------------------------------------------------

/**
 * The values the UI collected, keyed by [TemplateParameter.key]. [NAME] and [PACKAGE] are reserved keys
 * the Create-Project screen always provides (project name + base package); templates read their own
 * extras with [string]/[int]/[bool].
 */
class TemplateArgs(private val values: Map<String, String>) {
    fun string(key: String, default: String = ""): String = values[key]?.takeIf { it.isNotBlank() } ?: default
    fun int(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean = false): Boolean = values[key]?.toBooleanStrictOrNull() ?: default

    /** Project name, e.g. "MyApp" (reserved key). */
    val name: String get() = string(NAME, "Untitled")

    /** Base package / Android namespace, e.g. "com.example.myapp" (reserved key). */
    val packageName: String get() = string(PACKAGE, "com.example.app")

    companion object {
        const val NAME = "name"
        const val PACKAGE = "packageName"
    }
}

// ---------------------------------------------------------------------------
// Scaffold surface a template builds against
// ---------------------------------------------------------------------------

/**
 * What a [ProjectTemplate] uses to build a project: the model transaction surface ([workspace]) plus a
 * file-write helper ([writeText]), rooted at the already-created workspace dir. The host injects
 * [languageLevel] (JAVA_17 on a desktop JVM, JAVA_8 on-device against a non-modular `android.jar`) so a
 * template stays platform-agnostic — mirrors `SampleAndroidProject.generate(..., languageLevel)`.
 */
interface ProjectScaffold {
    val workspace: Workspace
    val rootDir: VirtualFile
    val languageLevel: LanguageLevel

    /** Resolve a registered [ModuleType] by id (e.g. `"android-app"`, `"java-lib"`). */
    fun moduleType(id: String): ModuleType

    /** Write a UTF-8 text file at [relPath] relative to [rootDir], creating intermediate directories. */
    fun writeText(relPath: String, content: String)

    /** Write raw [bytes] verbatim at [relPath] (byte-exact — no charset decode, no trim, no appended
     *  newline), creating intermediate directories. Use for binary assets (PNG/WebP/fonts/…); [writeText]
     *  would corrupt them by round-tripping through UTF-8. */
    fun writeBytes(relPath: String, bytes: ByteArray)
}
