package dev.ide.core.services

import dev.ide.android.support.navigation.AndroidXmlTargets
import dev.ide.android.support.navigation.ResourceOccurrence
import dev.ide.android.support.navigation.ResourceOccurrences
import dev.ide.android.support.navigation.XmlNavTarget
import dev.ide.android.support.navigation.sanitizeResourceName
import dev.ide.android.support.resources.ResourceType
import dev.ide.core.EngineContext
import dev.ide.core.RenameInfo
import dev.ide.core.RenameOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.writeText

/**
 * Rename an Android resource from any place that names it: its declaration (`name="…"`, `@+id/…`), a use
 * (`@string/x`, `?attr/x`, `R.string.x`), a custom attribute name, or a layout/menu/drawable's root tag.
 *
 * Rewrites every occurrence [AndroidNavigationService.occurrences] finds: the XML spelling keeps the name as
 * typed (a style's dots included), code gets the `R` field (`Theme_App`), a layout's binding class follows
 * (`ActivityMainBinding` → `HomeBinding`), and child styles named after a renamed parent (`Theme.App.Dark`)
 * are renamed with it. A file resource's files are renamed in every qualifier folder.
 *
 * Only project resources rename: one declared solely by a library or the framework is refused.
 */
internal class AndroidResourceRenameService(
    private val ctx: EngineContext,
    private val files: AndroidProjectFiles,
    private val navigation: AndroidNavigationService,
) {

    /** The project resource under the caret, or null when there is none (the caller tries other renames). */
    private fun subjectAt(file: Path, text: String, offset: Int): Pair<ResourceType, String>? {
        if (files.androidModuleOf(file) == null) return null
        val subject = when (val t = navigation.targetAt(file, text, offset)) {
            is XmlNavTarget.Resource -> if (t.packageName == null) t.type to t.name else return null
            is XmlNavTarget.Attribute ->
                if (!t.isFramework && t.prefix != null && t.prefix != "tools" && t.prefix != "xmlns") ResourceType.ATTR to t.localName
                else return null
            is XmlNavTarget.ClassRef -> rootTagResource(file, text, offset, t) ?: return null
            else -> return null
        }
        return subject.takeIf { (type, name) -> isProjectResource(type, name) }
    }

    /** A layout's root tag stands for the layout too: renaming there renames the file resource. */
    private fun rootTagResource(file: Path, text: String, offset: Int, t: XmlNavTarget.ClassRef): Pair<ResourceType, String>? {
        if (!AndroidNavigationService.isXml(file)) return null
        val first = Regex("""<[A-Za-z]""").find(text)?.range?.first ?: return null
        if (t.range.first != first + 1 || offset !in t.range.first..t.range.last + 1) return null
        return AndroidXmlTargets.fileResource(file.toString())
    }

    private fun isProjectResource(type: ResourceType, name: String): Boolean =
        files.declarations(type, sanitizeResourceName(name)).any { files.isProjectResource(Paths.get(it.filePath)) }

    fun prepareRename(file: Path, text: String, offset: Int): RenameInfo? =
        subjectAt(file, text, offset)?.let { (type, name) -> RenameInfo(name, "${type.rClass} resource") }

    /** Rename the resource under the caret to [newName]; null when the caret isn't on a project resource. */
    suspend fun rename(file: Path, text: String, offset: Int, newName: String): RenameOutcome? {
        val (type, oldName) = subjectAt(file, text, offset) ?: return null
        val name = newName.trim()
        val isFileResource = ResourceType.fromFolder(type.rClass) != null && type != ResourceType.COLOR ||
            (type == ResourceType.COLOR && files.declarations(type, sanitizeResourceName(oldName)).all { !isValuesPath(it.filePath) })
        val valid = if (isFileResource) FILE_NAME.matches(name) else VALUE_NAME.matches(name)
        if (!valid) {
            return RenameOutcome(false, if (isFileResource) "'$newName' isn't a valid file resource name (use a-z, 0-9 and _)."
                else "'$newName' isn't a valid resource name.")
        }
        if (name == oldName) return RenameOutcome(false, "The new name is the same as the current one.")
        if (isProjectResource(type, name)) return RenameOutcome(false, "A ${type.rClass} resource named '$name' already exists.")

        ctx.updateDocument(file, text)
        // Moves first (checked before anything is written), so a clash leaves the project untouched.
        val moves = if (isFileResource) fileMoves(type, oldName, name) else emptyList()
        moves.firstOrNull { Files.exists(it.second) }?.let { return RenameOutcome(false, "${it.second.fileName} already exists.") }

        val codeName = sanitizeResourceName(name)
        val editsByPath = LinkedHashMap<Path, MutableList<Pair<IntRange, String>>>()
        for ((path, occ) in navigation.occurrences(type, oldName)) {
            val isCode = !AndroidNavigationService.isXml(path)
            val replacement = when {
                occ.kind == ResourceOccurrence.Kind.BINDING -> ResourceOccurrences.bindingClassName(codeName)
                occ.kind == ResourceOccurrence.Kind.BINDING_FIELD -> ResourceOccurrences.bindingFieldName(codeName)
                isCode -> codeName
                else -> name
            }
            editsByPath.getOrPut(path) { ArrayList() } += occ.range to replacement
        }
        if (editsByPath.isEmpty() && moves.isEmpty()) return RenameOutcome(false, "No occurrences of '$oldName' found.")

        var occurrences = 0
        for ((path, edits) in editsByPath) {
            val current = files.textOf(path) ?: continue
            val sb = StringBuilder(current)
            for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
                val s = range.first.coerceIn(0, sb.length)
                val e = (range.last + 1).coerceIn(s, sb.length)
                sb.replace(s, e, replacement)
                occurrences++
            }
            val updated = sb.toString()
            ctx.updateDocument(path, updated)
            runCatching { path.writeText(updated) }
        }

        var newPath: Path? = null
        val fileAbs = file.toAbsolutePath().normalize()
        for ((from, to) in moves) {
            runCatching {
                Files.move(from, to)
                ctx.removeOverlay(from)?.let { ctx.updateDocument(to, it) }
                if (from == fileAbs) newPath = to
            }
        }

        val edited = editsByPath.keys.map { p -> moves.firstOrNull { it.first == p }?.second ?: p }
        ctx.events.filesMutated(edited, moves.firstOrNull())
        moves.drop(1).forEach { (from, to) -> ctx.events.fileMoved(from, to) }
        val filesChanged = (editsByPath.keys + moves.map { it.first }).toSet().size
        return RenameOutcome(true, "Renamed '$oldName' to '$name'", occurrences + moves.size, filesChanged, newPath?.toString())
    }

    /** Every file providing file resource [oldName] (each qualifier folder of [type], in every module), paired
     *  with its renamed path. */
    private fun fileMoves(type: ResourceType, oldName: String, newName: String): List<Pair<Path, Path>> {
        val out = ArrayList<Pair<Path, Path>>()
        for (root in files.resourceRoots()) {
            val dirs = runCatching { Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.collect(Collectors.toList()) } }.getOrDefault(emptyList())
            for (dir in dirs.filter { it.fileName.toString().substringBefore('-') == type.rClass }) {
                val hits = runCatching {
                    Files.list(dir).use { s -> s.filter { it.fileName.toString().substringBefore('.') == oldName }.collect(Collectors.toList()) }
                }.getOrDefault(emptyList())
                for (f in hits) {
                    val ext = f.fileName.toString().substringAfter('.', "")
                    out += f.toAbsolutePath().normalize() to f.resolveSibling(if (ext.isEmpty()) newName else "$newName.$ext").toAbsolutePath().normalize()
                }
            }
        }
        return out
    }

    private fun isValuesPath(filePath: String): Boolean =
        Paths.get(filePath).parent?.fileName?.toString()?.substringBefore('-') == "values"

    private companion object {
        val FILE_NAME = Regex("""[a-z_][a-z0-9_]*""")
        val VALUE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
    }
}
