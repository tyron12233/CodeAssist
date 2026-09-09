package dev.ide.core.gradle

import dev.ide.model.BuildSystemId
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.sync.BuildFileWriter
import dev.ide.model.sync.WriteOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Writes dependency declarations back into a Gradle module's `build.gradle(.kts)`, so a dependency added in
 * the IDE survives the next sync instead of being re-derived away. It edits the `dependencies { }` block
 * textually (creating it when the script has none) and leaves the rest of the file untouched: the scripts are
 * the user's, and reformatting them would be a surprise.
 */
class GradleBuildFileWriter : BuildFileWriter {

    override val id: BuildSystemId = BuildSystemId.GRADLE_COMPAT

    override fun addDependency(module: Module, coordinate: Coordinate, scope: DependencyScope): WriteOutcome {
        val file = buildFileFor(module)
            ?: return WriteOutcome.failed("Couldn't find a build.gradle for '${module.name}'.")
        val text = runCatching { file.readText() }.getOrNull()
            ?: return WriteOutcome.failed("Couldn't read ${file.fileName}.")
        val kts = file.fileName.toString().endsWith(".kts")
        val configuration = gradleConfiguration(scope)
        val entry = GradleDependencyEdits.declaration(kts, configuration, coordinate.toString(), scope.offClasspath)
        val added = GradleDependencyEdits.add(text, kts, configuration, coordinate, scope.offClasspath)
            ?: return WriteOutcome.ok(file, "${coordinate.group}:${coordinate.name} is already declared in ${file.fileName}.")
        // A scope Gradle has no configuration for (`natives`) has to be created before it can be declared
        // into, or the script the IDE just wrote no longer builds.
        val updated =
            if (scope.offClasspath) GradleDependencyEdits.ensureConfiguration(added, kts, configuration) else added
        return runCatching {
            file.writeText(updated)
            WriteOutcome.ok(file, "Declared $entry in ${file.fileName}.")
        }.getOrElse { WriteOutcome.failed("Couldn't write ${file.fileName}: ${it.message}") }
    }

    override fun removeDependency(module: Module, coordinate: Coordinate): WriteOutcome {
        val file = buildFileFor(module)
            ?: return WriteOutcome.failed("Couldn't find a build.gradle for '${module.name}'.")
        val text = runCatching { file.readText() }.getOrNull()
            ?: return WriteOutcome.failed("Couldn't read ${file.fileName}.")
        val updated = GradleDependencyEdits.remove(text, coordinate)
            ?: return WriteOutcome.failed(
                "${coordinate.group}:${coordinate.name} isn't declared on its own line in ${file.fileName}."
            )
        return runCatching {
            file.writeText(updated)
            WriteOutcome.ok(file, "Removed ${coordinate.group}:${coordinate.name} from ${file.fileName}.")
        }.getOrElse { WriteOutcome.failed("Couldn't write ${file.fileName}: ${it.message}") }
    }

    /** The module's own build script (Kotlin DSL first, as a modern project uses it), or null. */
    private fun buildFileFor(module: Module): Path? {
        val moduleDir = Paths.get(module.dir.path)
        return listOf("build.gradle.kts", "build.gradle")
            .map { moduleDir.resolve(it) }
            .firstOrNull { Files.isRegularFile(it) }
    }
}

/**
 * The text edits behind [GradleBuildFileWriter], kept pure so they can be reasoned about and tested without a
 * project: script text in, script text out. Structure is located on a comment-masked copy
 * ([GradleScript.maskComments]), so a brace or coordinate inside a comment never affects an edit.
 *
 * Scope: a declaration on one line, which is what the block conventionally holds. A declaration spread over
 * several lines, or one built by script logic, is reported as not removable rather than guessed at.
 */
internal object GradleDependencyEdits {

    private const val DEFAULT_INDENT = "    "

    /**
     * One dependency declaration: `implementation("g:a:v")` in the Kotlin DSL, `implementation 'g:a:v'` in
     * Groovy. [byName] addresses the configuration as a string (`"natives"("g:a:v")`), which is how the
     * Kotlin DSL declares into a configuration the script created itself: those have no generated accessor,
     * so the plain call form would not compile. Groovy resolves the name dynamically either way.
     */
    fun declaration(kts: Boolean, configuration: String, notation: String, byName: Boolean = false): String = when {
        kts && byName -> "\"$configuration\"(\"$notation\")"
        kts -> "$configuration(\"$notation\")"
        else -> "$configuration '$notation'"
    }

    /**
     * [text] with [coordinate] declared at [configuration], or null when it is already declared. A script with
     * no `dependencies { }` block gets one appended.
     */
    fun add(
        text: String, kts: Boolean, configuration: String, coordinate: Coordinate, byName: Boolean = false,
    ): String? {
        val mask = GradleScript.maskComments(text)
        val body = GradleScript.blockBodyRange(mask, "dependencies")
        val entry = declaration(kts, configuration, coordinate.toString(), byName)
        if (body != null && declares(mask.substring(body.first, body.last + 1), coordinate)) return null

        if (body == null) {
            val separator = if (text.isEmpty() || text.endsWith("\n")) "\n" else "\n\n"
            return text + separator + "dependencies {\n$DEFAULT_INDENT$entry\n}\n"
        }
        val insertAt = body.last + 1
        val indent = indentOf(text.substring(body.first, insertAt))
        val prefix = if (insertAt == 0 || text[insertAt - 1] == '\n') "" else "\n"
        return text.substring(0, insertAt) + prefix + indent + entry + "\n" + text.substring(insertAt)
    }

    /** [text] with every one-line declaration of [coordinate] dropped, or null when there was none. */
    fun remove(text: String, coordinate: Coordinate): String? {
        val mask = GradleScript.maskComments(text)
        val body = GradleScript.blockBodyRange(mask, "dependencies") ?: return null
        val maskedBody = mask.substring(body.first, body.last + 1)
        val kept = StringBuilder()
        var removed = 0
        var offset = 0
        for (line in maskedBody.split("\n")) {
            val original = text.substring(body.first + offset, body.first + offset + line.length)
            offset += line.length + 1
            if (declares(line, coordinate)) removed++ else kept.append(original).append("\n")
        }
        if (removed == 0) return null
        return text.substring(0, body.first) + kept.toString().dropLast(1) + text.substring(body.last + 1)
    }

    /**
     * [text] with [configuration] created, if the script does not create it already. Gradle provides no
     * configuration for a scope the model owns alone (`natives`), so a declaration into one is only valid
     * once the script has made it: `configurations { create("natives") }` in the Kotlin DSL,
     * `configurations { natives }` in Groovy. The block is added before `dependencies { }` when the script
     * has no `configurations { }` of its own, since Gradle resolves the name at that point.
     */
    fun ensureConfiguration(text: String, kts: Boolean, configuration: String): String {
        val mask = GradleScript.maskComments(text)
        val existing = GradleScript.blockBodyRange(mask, "configurations")
        val entry = if (kts) "create(\"$configuration\")" else configuration
        if (existing != null) {
            val bodyText = mask.substring(existing.first, existing.last + 1)
            if (Regex("""(^|[^\w.])${Regex.escape(configuration)}($|[^\w])""").containsMatchIn(bodyText)) return text
            val insertAt = existing.last + 1
            val indent = indentOf(text.substring(existing.first, insertAt))
            val prefix = if (insertAt == 0 || text[insertAt - 1] == '\n') "" else "\n"
            return text.substring(0, insertAt) + prefix + indent + entry + "\n" + text.substring(insertAt)
        }
        val block = "configurations {\n$DEFAULT_INDENT$entry\n}\n"
        // Before `dependencies { }` so the configuration exists by the time it is declared into; a script
        // without one (nothing was ever declared) takes it at the end.
        val deps = Regex("""(?m)^[ \t]*dependencies\s*\{""").find(mask)
            ?: return text.trimEnd('\n') + "\n\n" + block
        val at = deps.range.first   // the start of the `dependencies` line, so the blank line above it stays
        return text.substring(0, at) + block + "\n" + text.substring(at)
    }

    /** True when [text] quotes a `group:name` notation for [coordinate], whatever version it pins. */
    private fun declares(text: String, coordinate: Coordinate): Boolean {
        val ga = "${coordinate.group}:${coordinate.name}"
        return Regex("""['"]${Regex.escape(ga)}(?::[^'"]*)?['"]""").containsMatchIn(text)
    }

    /** The indentation of the last non-blank line of a block body, so an inserted line lines up with it. */
    private fun indentOf(body: String): String {
        val line = body.split("\n").lastOrNull { it.isNotBlank() } ?: return DEFAULT_INDENT
        return line.takeWhile { it == ' ' || it == '\t' }.ifEmpty { DEFAULT_INDENT }
    }
}
