package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.core.EngineContext
import dev.ide.model.Module
import dev.ide.ui.backend.UiAppIdentity
import dev.ide.ui.backend.UiAppRenamePreview
import dev.ide.ui.backend.UiAppRenameRequest
import dev.ide.ui.backend.UiAppRenameResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Renames an app's package and its name, the change Android Studio spreads over a refactoring and a few hand
 * edits.
 *
 * The package moves everywhere at once, in every module: the package directories in every source root, every
 * whole mention of it in sources, manifests, layouts and keep rules (string literals included, so an intent
 * action or a provider authority built on it follows), JNI symbol names in C/C++, and the Android namespace
 * of each module under it. The app's id goes with it unless the request keeps it, in which case the old id
 * is pinned as `defaultConfig.applicationId` so the installed or published app is still the same app.
 *
 * The name is the launcher label: its `@string/` resource in `values/`, and any translation that is just a
 * copy of the old name (a real translation is left alone), or the literal label in the manifest.
 *
 * Unsaved editor buffers are read and written along with the files, as the symbol rename does.
 */
internal class AppRenameService(private val ctx: EngineContext) {

    /** The starting values for [moduleName], or null when there is no such module. */
    fun identity(moduleName: String): UiAppIdentity? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val facet = module.facets.get(AndroidFacet.KEY)
        return UiAppIdentity(
            module = moduleName,
            packageName = basePackage(module),
            appName = label(module)?.current,
            applicationId = facet?.takeIf { it.isApplication }?.let { it.applicationId ?: it.namespace },
        )
    }

    fun preview(request: UiAppRenameRequest): UiAppRenamePreview {
        val plan = plan(request).getOrElse { return UiAppRenamePreview(error = it.message) }
        val root = ctx.workspaceRoot
        val files = (plan.edits.keys + plan.moves.keys).distinct()
            .map { root.relativize(it).toString().replace('\\', '/') }.sorted()
        return UiAppRenamePreview(files = files, moved = plan.moves.size, notes = plan.notes)
    }

    fun apply(request: UiAppRenameRequest): UiAppRenameResult {
        val plan = plan(request).getOrElse { return UiAppRenameResult(false, it.message ?: "Couldn't rename.") }
        val failed = ArrayList<Path>()

        // Edits that stay where they are.
        for ((path, text) in plan.edits) {
            if (path in plan.moves) continue
            val open = ctx.overlayText(path) != null
            runCatching { path.writeText(text) }.onFailure { failed.add(path) }
            if (open) ctx.updateDocument(path, text)
        }

        // Moves go through a staging directory first: when the new package sits inside the old one (or the
        // other way round), a file's destination can be another file's source that hasn't moved yet.
        val staging = ctx.workspaceRoot.resolve(".platform/tmp/rename-${System.currentTimeMillis()}")
        val staged = LinkedHashMap<Path, Path>()
        for ((i, from) in plan.moves.keys.withIndex()) {
            val tmp = staging.resolve(i.toString())
            runCatching { Files.createDirectories(staging); Files.move(from, tmp) }
                .onSuccess { staged[from] = tmp }
                .onFailure { failed.add(from) }
        }
        val moved = LinkedHashMap<Path, Path>()
        for ((from, tmp) in staged) {
            val to = plan.moves.getValue(from)
            runCatching {
                to.parent?.let { Files.createDirectories(it) }
                Files.move(tmp, to)
                plan.edits[from]?.let { to.writeText(it) }
            }.onSuccess {
                moved[from] = to
                ctx.removeOverlay(from)?.let { old -> ctx.updateDocument(to, plan.edits[from] ?: old) }
            }.onFailure { failed.add(from) }
        }
        runCatching { deleteEmptyDirs(staging, stopAt = staging.parent) }
        plan.emptiedDirs.forEach { (root, dir) -> runCatching { deleteEmptyDirs(dir, stopAt = root) } }

        if (plan.facets.isNotEmpty()) writeFacets(plan.facets)

        val edited = plan.edits.keys.filter { it !in plan.moves } + moved.values
        ctx.events.filesRelocated(edited, moved)
        val changed = (plan.edits.keys + plan.moves.keys).distinct().size
        val message = if (failed.isEmpty()) "Renamed. $changed file(s) changed."
        else "Renamed with ${failed.size} file(s) that couldn't be changed: " +
            failed.take(3).joinToString { ctx.workspaceRoot.relativize(it).toString() }
        return UiAppRenameResult(
            failed.isEmpty(), message, moved.entries.associate { (from, to) -> from.toString() to to.toString() },
        )
    }

    // ---- planning ---------------------------------------------------------------------------------------

    private class Plan(
        /** New text by the file's CURRENT path (a file that also moves is written at its destination). */
        val edits: LinkedHashMap<Path, String>,
        val moves: LinkedHashMap<Path, Path>,
        /** The old package directories with the source root each sits in, removed once empty. */
        val emptiedDirs: List<Pair<Path, Path>>,
        val facets: List<Pair<Module, AndroidFacet>>,
        val notes: List<String>,
    )

    private class RenameError(message: String) : Exception(message)

    private fun plan(request: UiAppRenameRequest): Result<Plan> = runCatching {
        val module = ctx.modules().firstOrNull { it.name == request.module }
            ?: throw RenameError("There is no module named '${request.module}'.")
        val facet = module.facets.get(AndroidFacet.KEY)
        val oldPackage = basePackage(module)
        val label = label(module)
        val newPackage = request.newPackage?.trim()?.takeIf { it.isNotEmpty() && it != oldPackage }
        val newName = request.newAppName?.trim()?.takeIf { it.isNotEmpty() && it != label?.current }
        if (newPackage == null && newName == null) throw RenameError("Nothing to change.")

        val edits = LinkedHashMap<Path, String>()
        val moves = LinkedHashMap<Path, Path>()
        val emptied = ArrayList<Pair<Path, Path>>()
        val facets = ArrayList<Pair<Module, AndroidFacet>>()
        val notes = ArrayList<String>()
        fun current(path: Path): String? = edits[path] ?: ctx.overlayText(path) ?: runCatching { path.readText() }.getOrNull()

        if (newPackage != null) {
            val old = oldPackage ?: throw RenameError("This module has no package to rename.")
            AppRenameText.packageError(newPackage, needsDot = facet?.isApplication == true)?.let { throw RenameError(it) }
            if (newPackage.startsWith("$old.") || old.startsWith("$newPackage.")) {
                // Legal, but every file in between changes package twice over; say what it does.
                notes += "$newPackage is inside $old (or the other way round): every package under it moves too."
            }
            for (m in ctx.modules()) {
                for (file in textFiles(m)) {
                    val text = current(file) ?: continue
                    AppRenameText.rewritePackage(text, old, newPackage, jni = isNative(file))?.let { edits[file] = it }
                }
                for (root in codeRoots(m)) {
                    val oldDir = root.resolve(old.replace('.', '/'))
                    if (!Files.isDirectory(oldDir)) continue
                    val newDir = root.resolve(newPackage.replace('.', '/'))
                    filesUnder(oldDir).forEach { moves[it] = newDir.resolve(oldDir.relativize(it).toString()) }
                    emptied += root to oldDir
                }
                m.facets.get(AndroidFacet.KEY)?.let { f -> renamedFacet(f, old, newPackage, request.changeApplicationId)?.let { facets += m to it } }
            }
            val clash = moves.values.firstOrNull { Files.exists(it) && it !in moves }
            if (clash != null) throw RenameError("${ctx.workspaceRoot.relativize(clash)} already exists.")
            if (facet?.isApplication == true) {
                val id = facet.applicationId ?: facet.namespace
                val newId = AppRenameText.renamedPackage(id, old, newPackage)
                notes += if (request.changeApplicationId && newId != id) {
                    "The app's id becomes $newId. A device installs it as a new app, and a store listing under $id " +
                        "can't be updated with it."
                } else {
                    "The app keeps the id $id, so it still updates the installed and published app."
                }
                val gms = ctx.moduleRoot(module)?.resolve("google-services.json")
                if (request.changeApplicationId && newId != id && gms != null && Files.isRegularFile(gms)) {
                    notes += "google-services.json still names $id: register the new id in Firebase and replace the file."
                }
            }
        }

        if (newName != null) {
            val l = label ?: throw RenameError("This module has no app name to change.")
            var kept = 0
            for ((file, isDefault) in l.files) {
                val text = current(file) ?: continue
                val updated = if (l.resource == null) AppRenameText.withApplicationLabel(text, newName)
                else AppRenameText.withStringValue(text, l.resource, newName, onlyIfEquals = if (isDefault) null else l.current)
                if (updated != null) edits[file] = updated
                else if (!isDefault) kept++
            }
            if (kept > 0) notes += "$kept translated name(s) are left as they are."
        }
        if (edits.isEmpty() && moves.isEmpty() && facets.isEmpty()) throw RenameError("Nothing in the project uses it.")
        Plan(edits, moves, emptied, facets, notes)
    }

    /** [facet] after its package moves from [old] to [new], or null when it isn't under [old]. */
    private fun renamedFacet(facet: AndroidFacet, old: String, new: String, changeId: Boolean): AndroidFacet? {
        val namespace = AppRenameText.renamedPackage(facet.namespace, old, new)
        if (namespace == facet.namespace) return null
        val applicationId = when {
            changeId -> facet.applicationId?.let { AppRenameText.renamedPackage(it, old, new) }
            // Pin the id the app was built with, which until now followed the namespace.
            facet.isApplication -> facet.applicationId ?: facet.namespace
            else -> facet.applicationId
        }
        return facet.copy(
            namespace = namespace,
            applicationId = applicationId?.takeIf { it != namespace },
            productFlavors = if (!changeId) facet.productFlavors else facet.productFlavors.map { flavor ->
                flavor.copy(applicationId = flavor.applicationId?.let { AppRenameText.renamedPackage(it, old, new) })
            },
        )
    }

    private fun writeFacets(updates: List<Pair<Module, AndroidFacet>>) {
        for ((project, entries) in updates.groupBy { (m, _) -> ctx.projectOf(m) }) {
            project ?: continue
            project.beginModification().apply {
                entries.forEach { (m, f) -> module(m.id).putFacet(f) }
                commit()
            }
        }
        ctx.store.save()
    }

    // ---- what the module has --------------------------------------------------------------------------

    /** The Android namespace, else the package every one of the module's sources sits in (or under). */
    private fun basePackage(module: Module): String? {
        module.facets.get(AndroidFacet.KEY)?.namespace?.takeIf { it.isNotBlank() }?.let { return it }
        val packages = codeRoots(module).flatMap { filesUnder(it) }
            .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
            .mapNotNull { file -> (ctx.overlayText(file) ?: runCatching { file.readText() }.getOrNull())?.let(::packageOf) }
        if (packages.isEmpty()) return null
        return packages.reduce { a, b -> commonPackage(a, b) }.takeIf { it.isNotEmpty() }
    }

    private fun packageOf(text: String): String? = PACKAGE_LINE.find(text)?.groupValues?.get(1)

    private fun commonPackage(a: String, b: String): String =
        a.split('.').zip(b.split('.')).takeWhile { (x, y) -> x == y }.joinToString(".") { it.first }

    /** The launcher label: where it lives ([files], each flagged when it is the default the name is read
     *  from), the resource it names (null for a literal in the manifest), and its current text. */
    private class Label(val files: List<Pair<Path, Boolean>>, val resource: String?, val current: String)

    private fun label(module: Module): Label? {
        val facet = module.facets.get(AndroidFacet.KEY)?.takeIf { it.isApplication } ?: return null
        val manifest = ctx.moduleRoot(module)?.resolve(facet.manifest)?.takeIf { Files.isRegularFile(it) } ?: return null
        val raw = AppRenameText.applicationLabel(readCurrent(manifest) ?: return null) ?: return null
        val resource = AppRenameText.stringResourceName(raw)
            ?: return Label(listOf(manifest to true), null, AppRenameText.decodeString(raw))
        var current: String? = null
        val files = ArrayList<Pair<Path, Boolean>>()
        for (res in resRoots(module)) {
            val dirs = runCatching { Files.list(res).use { s -> s.collect(Collectors.toList()) } }.getOrDefault(emptyList())
            for (dir in dirs.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("values") }) {
                val isDefault = dir.fileName.toString() == "values"
                for (file in filesUnder(dir).filter { it.toString().endsWith(".xml") }) {
                    val value = AppRenameText.stringValue(readCurrent(file) ?: continue, resource) ?: continue
                    if (isDefault && current == null) current = value
                    files += file to isDefault
                }
            }
        }
        return Label(files, resource, current ?: return null)
    }

    private fun readCurrent(path: Path): String? = ctx.overlayText(path) ?: runCatching { path.readText() }.getOrNull()

    /** Roots whose directories ARE packages: source and AIDL roots, never a build output. */
    private fun codeRoots(module: Module): List<Path> =
        (ctx.sourceRoots(module) + ctx.treeRoots(module)).map { it.toAbsolutePath().normalize() }.distinct()
            .filter { Files.isDirectory(it) && !isGenerated(module, it) && !isResRoot(it) }

    private fun resRoots(module: Module): List<Path> =
        ctx.treeRoots(module).map { it.toAbsolutePath().normalize() }.distinct().filter { isResRoot(it) }

    private fun isResRoot(root: Path): Boolean = Files.isDirectory(root.resolve("values"))

    private fun isGenerated(module: Module, path: Path): Boolean {
        val moduleRoot = ctx.moduleRoot(module)?.toAbsolutePath()?.normalize() ?: return false
        return path.startsWith(moduleRoot.resolve("build")) || path.startsWith(ctx.workspaceRoot.resolve(".platform"))
    }

    /** Every file the package sweep reads in [module]: sources, resources, the manifest and keep rules. */
    private fun textFiles(module: Module): List<Path> {
        val out = LinkedHashSet<Path>()
        for (root in (ctx.sourceRoots(module) + ctx.treeRoots(module)).map { it.toAbsolutePath().normalize() }.distinct()) {
            if (!Files.isDirectory(root) || isGenerated(module, root)) continue
            filesUnder(root).filterTo(out) { it.fileName.toString().substringAfterLast('.', "") in TEXT_EXTENSIONS }
        }
        val moduleRoot = ctx.moduleRoot(module)
        module.facets.get(AndroidFacet.KEY)?.let { f ->
            val listed = listOf(f.manifest) + f.buildTypes.flatMap { it.proguardFiles + it.consumerProguardFiles }
            for (rel in listed) {
                val file = moduleRoot?.resolve(rel)?.toAbsolutePath()?.normalize() ?: continue
                if (Files.isRegularFile(file)) out.add(file)
            }
        }
        return out.filter { runCatching { Files.size(it) <= MAX_FILE_BYTES }.getOrDefault(false) }
    }

    private fun isNative(file: Path): Boolean = file.fileName.toString().substringAfterLast('.', "") in NATIVE_EXTENSIONS

    private fun filesUnder(dir: Path): List<Path> = runCatching {
        Files.walk(dir).use { s ->
            // Collectors.toList, not Stream.toList: the latter is Java 16+, absent on ART/API 26.
            s.filter { Files.isRegularFile(it) }.map { it.toAbsolutePath().normalize() }.collect(Collectors.toList())
        }
    }.getOrDefault(emptyList())

    /** Remove the empty directories in [dir], then [dir] and its parents while empty, never [stopAt] or above. */
    private fun deleteEmptyDirs(dir: Path, stopAt: Path) {
        if (!Files.isDirectory(dir) || !dir.startsWith(stopAt)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).collect(Collectors.toList()) }
            .forEach { if (isEmptyDir(it)) Files.delete(it) } // deepest first, so a parent is checked after its children
        var parent = dir.parent
        while (parent != null && parent != stopAt && parent.startsWith(stopAt) && isEmptyDir(parent)) {
            Files.delete(parent)
            parent = parent.parent
        }
    }

    // Optional.isPresent, not isEmpty: the latter is Java 11+, absent on older ART.
    private fun isEmptyDir(dir: Path): Boolean = Files.isDirectory(dir) && Files.list(dir).use { !it.findFirst().isPresent }

    private companion object {
        val PACKAGE_LINE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val NATIVE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp")
        val TEXT_EXTENSIONS = setOf("java", "kt", "xml", "aidl", "pro") + NATIVE_EXTENSIONS
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
