package dev.ide.core.sync

import dev.ide.model.impl.format.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Records that a project was adopted from a folder no build system claimed: neither a CodeAssist workspace
 * nor anything a [dev.ide.model.sync.ProjectImporter] recognized.
 *
 * A cloned repository is the case this exists for. Without it the folder holds no model, so it never appears
 * in the project picker and the clone looks like it silently failed. Adoption writes a workspace with the
 * project but no modules, which makes it listable and editable, and this marker so the editor can say that
 * the project is open for editing only: nothing knows how to build it until modules are set up.
 *
 * Lives under `.platform/` like the other adoption state, so it travels with the workspace and never touches
 * the user's sources.
 */
internal object UnrecognizedProjectMarker {

    /** Where the adopted folder came from (a clone URL), or blank when the caller had nothing to record. */
    data class Info(val origin: String)

    private const val FILE = "unrecognized-project.json"
    private const val VERSION = 1

    private fun file(root: Path): Path = root.resolve(".platform").resolve(FILE)

    /** Mark the project at [root] as adopted from an unrecognized folder that came from [origin]. */
    fun write(root: Path, origin: String = "") {
        val target = file(root)
        Files.createDirectories(target.parent)
        target.writeText(Json.write(linkedMapOf("version" to VERSION, "origin" to origin)))
    }

    /** The recorded adoption, or null when [root] is a project the IDE or an importer authored. */
    fun read(root: Path): Info? {
        val target = file(root)
        if (!Files.isRegularFile(target)) return null
        return runCatching {
            val obj = Json.parse(target.readText()) as? Map<*, *> ?: return@runCatching Info("")
            Info((obj["origin"] as? String).orEmpty())
        }.getOrDefault(Info(""))
    }

    fun exists(root: Path): Boolean = Files.isRegularFile(file(root))

    /** Drop the marker, once the project is one the IDE understands. */
    fun clear(root: Path) {
        runCatching { Files.deleteIfExists(file(root)) }
    }
}
