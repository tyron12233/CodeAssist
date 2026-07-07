package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.TreeRootInfo
import dev.ide.model.ContentRole
import dev.ide.model.IconTarget
import dev.ide.model.Module
import dev.ide.ui.backend.FileService
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiSourceRootRole
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Above this size a file is treated as too big to open as editor text (see [FileBackend.readFile]). */
private const val MAX_TEXT_BYTES = 5_000_000L

/** [FileService] over the engine: the workspace tree (curated + raw views) and file/directory operations.
 *  Tree-changing mutations bump the file-system epoch via [BackendContext.bumpFileSystemEpoch]. */
internal class FileBackend(private val ctx: BackendContext) : FileService {

    override val fileSystemEpoch: StateFlow<Int> get() = ctx.fileSystemEpoch

    override fun fileTree(mode: TreeViewMode): TreeNode {
        // Picker-safe: with no project open (the lazy-start path), the engine-backed tree builders would hit
        // the non-null `services` accessor. Hand back an empty root so the shared UI state can be constructed
        // before a project is opened; it is rebuilt for real once `openProject` bumps the epoch.
        if (ctx.servicesOrNull == null) return TreeNode(id = "empty", name = "", kind = NodeKind.Workspace, filePath = null, iconId = "module")
        return when (mode) {
            TreeViewMode.Project -> projectTree()
            TreeViewMode.AllFiles -> allFilesTree()
        }
    }

    // File-tree expansion is remembered per project + view mode in the project's generic settings store
    // (`.platform/settings.properties`), keyed by the stable path-based TreeNode ids. Empty stored value =
    // "user collapsed everything" (a real state); absent key = "never persisted" → null → caller defaults.
    private fun treeStateKey(mode: TreeViewMode) = "tree.expanded.${mode.name}"

    override fun expandedTreeState(mode: TreeViewMode): List<String>? {
        val raw = ctx.servicesOrNull?.projectPref(treeStateKey(mode)) ?: return null
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun saveExpandedTreeState(mode: TreeViewMode, ids: List<String>) {
        ctx.servicesOrNull?.setProjectPref(treeStateKey(mode), ids.joinToString("\n"))
    }

    /** module-root dir → module name, for surfacing `module.toml` as a "open module settings" node. */
    private fun moduleRoots(): Map<Path, String> =
        ctx.services.modules().mapNotNull { m -> ctx.services.moduleRoot(m)?.let { it.normalize() to m.name } }.toMap()

    /** Curated module view: manifest + code/res/assets roots, plus each module's root config files. */
    private fun projectTree(): TreeNode {
        val root = ctx.services.workspaceRoot
        val moduleNodes = ctx.services.modules().sortedBy { it.name }.map { module ->
            val moduleDir = ctx.services.moduleRoot(module)
            val children = ArrayList<TreeNode>()
            // The AndroidManifest sits above the source roots, so surface it explicitly (first, like Studio).
            ctx.services.manifestPath(module)?.takeIf { Files.isRegularFile(it) }?.let { mf -> children.add(fileNode(mf, module)) }
            // Code/resources/assets roots, each with a distinct icon by role — sources first, then res/assets.
            ctx.services.treeRootsDetailed(module)
                .filter { Files.isDirectory(it.path) }
                .sortedWith(compareBy({ it.roles.rootRank() }, { it.path.toString() }))
                .forEach { info -> children.add(sourceRootNode(info, module, root)) }
            // The module's root-level files (module.toml, build scripts, README…) — visible config, not just
            // creatable source files. `module.toml` opens the Module Settings editor instead of a text view.
            if (moduleDir != null) moduleRootFiles(moduleDir, module).forEach { children.add(it) }
            // A curated, dimmed "build outputs" node (APK/AAB/jar/mapping) at the bottom — like Studio's, so
            // the user can locate and export the artifacts without leaving the project view.
            buildOutputsNode(module)?.let { children.add(it) }
            TreeNode(
                id = "module:${module.name}",
                name = module.name,
                kind = NodeKind.Module,
                filePath = null,
                iconId = ctx.services.iconFor(IconTarget.ModuleNode(module)) ?: "module",
                children = children,
                // The module node itself opens its settings (the touch path; the gear icon is hover-only).
                moduleConfigName = module.name,
                dirPath = moduleDir?.toString(),
            )
        }
        return TreeNode(
            id = "workspace",
            name = root.fileName?.toString() ?: "workspace",
            kind = NodeKind.Workspace,
            filePath = null,
            iconId = "workspace",
            children = moduleNodes,
            dirPath = root.toString(),
        )
    }

    /** Regular files directly in a module's root dir (config/docs), excluding ones already shown as roots. */
    private fun moduleRootFiles(moduleDir: Path, module: Module): List<TreeNode> {
        val (_, files) = childPartition(moduleDir)
        return files.map { fileNode(it, module) }
    }

    /**
     * A curated, dimmed "build outputs" node for a module: the deliverables under `<module>/build/` — the
     * `outputs/` tree (APK/AAB/`mapping.txt`) and `libs/` (jars) — read live from disk so it always reflects
     * the latest build (no stale paths). Null until the module has been built. Every node is marked
     * `styleHint = "excluded"` so the UI renders it as derived output, IntelliJ-style.
     */
    private fun buildOutputsNode(module: Module): TreeNode? {
        // `<module>/build/classes`.parent == `<module>/build` (the same anchor AndroidBuildSystem uses).
        val buildDir = runCatching { Paths.get(module.outputDir.path).parent }.getOrNull() ?: return null
        val children = listOf(buildDir.resolve("outputs"), buildDir.resolve("libs"))
            .filter { Files.isDirectory(it) }
            .flatMap { excludedChildren(it) }
        if (children.isEmpty()) return null
        return TreeNode(
            id = "build-outputs:${module.name}",
            name = "build outputs",
            kind = NodeKind.Folder,
            filePath = null,
            iconId = "build-output",
            children = children,
            dirPath = buildDir.toString(),
            styleHint = "excluded",
        )
    }

    /** Real files/dirs under [dir], recursively, every node tagged as derived output (dimmed + output icon). */
    private fun excludedChildren(dir: Path): List<TreeNode> {
        val (dirs, files) = childPartition(dir)
        return dirs.map { d ->
            TreeNode(
                id = "dir:$d",
                name = d.fileName.toString(),
                kind = NodeKind.Folder,
                filePath = null,
                iconId = "build-output",
                children = excludedChildren(d),
                dirPath = d.toString(),
                styleHint = "excluded",
            )
        } + files.map { fileNode(it, ctx.services.moduleForFile(it)).copy(styleHint = "excluded") }
    }

    /** IntelliJ "Project Files"-style raw tree from the workspace root — everything but bulky derived output. */
    private fun allFilesTree(): TreeNode {
        val root = ctx.services.workspaceRoot
        return TreeNode(
            id = "workspace",
            name = root.fileName?.toString() ?: "workspace",
            kind = NodeKind.Workspace,
            filePath = null,
            iconId = "workspace",
            children = rawChildren(root, moduleRoots()),
            dirPath = root.toString(),
        )
    }

    /**
     * Recursive raw-filesystem children, skipping transient dirs (`.gradle`/`.idea`/platform caches). The
     * `build/` tree IS shown here (so an APK is locatable) but dimmed IntelliJ-style: once inside a `build/`
     * dir, [excluded] propagates so the whole derived subtree renders muted with the output icon.
     */
    private fun rawChildren(dir: Path, moduleRoots: Map<Path, String>, excluded: Boolean = false): List<TreeNode> {
        val entries = runCatching { Files.list(dir).use { it.collect(Collectors.toList()) } }.getOrDefault(emptyList())
        val dirs = entries.filter { Files.isDirectory(it) && !isDerivedDir(it) }.sortedBy { it.fileName.toString().lowercase() }
        val files = entries.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString().lowercase() }
        return dirs.map { d ->
            val derived = excluded || d.fileName?.toString() == "build"
            TreeNode(
                id = "dir:$d",
                name = d.fileName.toString(),
                kind = NodeKind.Folder,
                filePath = null,
                iconId = if (derived) "build-output" else (ctx.services.iconFor(IconTarget.Directory(d.fileName.toString(), emptySet())) ?: "folder"),
                children = rawChildren(d, moduleRoots, derived),
                dirPath = d.toString(),
                styleHint = if (derived) "excluded" else null,
            )
        } + files.map { f ->
            val node = fileNode(f, ctx.services.moduleForFile(f), moduleRoots)
            if (excluded) node.copy(styleHint = "excluded") else node
        }
    }

    /** Bulky/derived directories not worth showing even in the All-Files view. (`build/` IS shown — dimmed.) */
    private fun isDerivedDir(dir: Path): Boolean {
        val name = dir.fileName?.toString() ?: return false
        if (name == ".gradle" || name == ".idea") return true
        // The platform caches can be large + transient; keep `.platform/workspace.json` etc. visible though.
        return dir.parent?.fileName?.toString() == ".platform" && name == "caches"
    }

    /** A surfaced content root. `SOURCE`/`GENERATED` roots are package contexts (compactable, new-class
     *  targets); everything else (`res/`, `assets/`, resources) is a plain folder tree. */
    private fun sourceRootNode(info: TreeRootInfo, module: Module, workspaceRoot: Path): TreeNode {
        val packageRoot = ContentRole.SOURCE in info.roles || ContentRole.GENERATED in info.roles
        val label = runCatching { workspaceRoot.relativize(info.path).toString() }.getOrDefault(info.path.toString())
        val children = if (packageRoot) packageChildren(info.path, info.path, module)
        else resourceChildren(info.path, info.roles, module)
        return TreeNode(
            id = "root:${info.path}",
            name = label,
            kind = NodeKind.SourceRoot,
            filePath = null,
            iconId = ctx.services.iconFor(IconTarget.SourceRoot(info.sourceSetName, info.roles, module, info.path.fileName?.toString() ?: "")) ?: "sourceset.java",
            children = children,
            sourceRootPath = if (packageRoot) info.path.toString() else null,
            // The Android res root is an XML new-file target (the dialog routes into res/<kind>/).
            resDirPath = if (ContentRole.ANDROID_RES in info.roles) info.path.toString() else null,
            dirPath = info.path.toString(),
        )
    }

    /** Direct children of a Java/Kotlin package context: package nodes + any default-package files. */
    private fun packageChildren(dir: Path, sourceRoot: Path, module: Module): List<TreeNode> {
        val (dirs, files) = childPartition(dir)
        return dirs.map { packageNode(it, sourceRoot, module) } + files.map { fileNode(it, module, sourceRoot = sourceRoot) }
    }

    /**
     * A package node that **compacts middle packages**: starting at [startDir], it follows the chain while
     * each level has exactly one subdirectory and no files, so `com/ → tyron/ → codeassist/` becomes one
     * `com.tyron.codeassist` node. The chain's [PackageSegment]s (one per level, each with its dotted
     * package and directory) are kept so a New-Class action can target any intermediate level.
     */
    private fun packageNode(startDir: Path, sourceRoot: Path, module: Module): TreeNode {
        val segments = ArrayList<PackageSegment>()
        var cur = startDir
        while (true) {
            val pkg = sourceRoot.relativize(cur).toString().replace(File.separatorChar, '.')
            segments.add(PackageSegment(pkg, cur.toString()))
            val (dirs, files) = childPartition(cur)
            if (dirs.size == 1 && files.isEmpty()) cur = dirs[0] else break
        }
        val displayName = (startDir.parent ?: sourceRoot).relativize(cur).toString().replace(File.separatorChar, '.')
        val (dirs, files) = childPartition(cur)
        val children = dirs.map { packageNode(it, sourceRoot, module) } + files.map { fileNode(it, module, sourceRoot = sourceRoot) }
        return TreeNode(
            id = "pkg:$cur",
            name = displayName,
            kind = NodeKind.Package,
            filePath = null,
            iconId = ctx.services.iconFor(IconTarget.PackageDir(segments.last().packageName)) ?: "package",
            children = children,
            sourceRootPath = sourceRoot.toString(),
            packageSegments = segments,
            dirPath = cur.toString(),
        )
    }

    /** Children of a non-package root (res/assets/resources): plain folders, no compaction. */
    private fun resourceChildren(dir: Path, roles: Set<ContentRole>, module: Module): List<TreeNode> {
        val (dirs, files) = childPartition(dir)
        return dirs.map { resourceDirNode(it, roles, module) } + files.map { fileNode(it, module) }
    }

    private fun resourceDirNode(dir: Path, roles: Set<ContentRole>, module: Module): TreeNode = TreeNode(
        id = "dir:$dir",
        name = dir.fileName.toString(),
        kind = NodeKind.Folder,
        filePath = null,
        iconId = ctx.services.iconFor(IconTarget.Directory(dir.fileName.toString(), roles)) ?: "folder",
        children = resourceChildren(dir, roles, module),
        // A folder under an Android res root (layout/values/drawable/menu/…) is an XML new-file target.
        resDirPath = if (ContentRole.ANDROID_RES in roles) dir.toString() else null,
        dirPath = dir.toString(),
    )

    private fun fileNode(file: Path, module: Module?, moduleRoots: Map<Path, String> = emptyMap(), sourceRoot: Path? = null): TreeNode {
        val name = file.fileName.toString()
        // A `module.toml` opens the Module Settings editor (not a text view) for the module it configures —
        // resolved via the prebuilt root map (All-Files view) or the owning module (Project view).
        val parent = file.parent?.normalize()
        val configModule = if (name == "module.toml" && parent != null) {
            moduleRoots[parent] ?: module?.takeIf { ctx.services.moduleRoot(it)?.normalize() == parent }?.name
        } else null
        return TreeNode(
            id = "file:$file",
            name = name,
            kind = NodeKind.File,
            filePath = file.toString(),
            iconId = ctx.services.iconFor(IconTarget.File(name, module)) ?: "file",
            moduleConfigName = configModule,
            // A file in a Java/Kotlin package carries its source root so the "New ▸" menu can offer a typed
            // class/file alongside it (created in the same package).
            sourceRootPath = sourceRoot?.toString(),
        )
    }

    /** Visible entries of [dir], split into (sorted subdirectories, sorted files); dotfiles dropped. */
    private fun childPartition(dir: Path): Pair<List<Path>, List<Path>> {
        val entries = runCatching { Files.list(dir).use { it.collect(Collectors.toList()) } }.getOrDefault(emptyList())
            .filterNot { it.fileName.toString().startsWith(".") }
        val dirs = entries.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        val files = entries.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
        return dirs to files
    }

    /** Sort key so a module's roots list sources first, then resources, android-res, assets. */
    private fun Set<ContentRole>.rootRank(): Int = when {
        ContentRole.SOURCE in this || ContentRole.GENERATED in this -> 0
        ContentRole.RESOURCE in this -> 1
        ContentRole.ANDROID_RES in this -> 2
        ContentRole.ASSETS in this -> 3
        else -> 4
    }

    override fun createFile(dirPath: String, fileName: String, content: String): String? =
        writeNewFile(dirPath, fileName) { it.writeText(content) }

    override fun createFileBytes(dirPath: String, fileName: String, bytes: ByteArray): String? =
        writeNewFile(dirPath, fileName) { Files.write(it, bytes) }

    override fun createFileSmart(dirPath: String, name: String): String? {
        // The name may carry intermediate folders (`a/b/Helper.kt`); split them off and create them.
        val rel = name.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return null
        val fileName = rel.substringAfterLast('/')
        val subDir = rel.substringBeforeLast('/', "")
        val targetDir = if (subDir.isEmpty()) Paths.get(dirPath) else Paths.get(dirPath).resolve(subDir)
        return writeNewFile(targetDir.toString(), fileName) { it.writeText(scaffoldContent(targetDir, fileName)) }
    }

    /** Starter content for a new file, chosen by extension: a class stub (with the resolved package) for
     *  `.java`/`.kt`, a root element for `.xml`, otherwise empty. */
    private fun scaffoldContent(dir: Path, fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val typeName = base.takeIf { it.isNotEmpty() && it.first().isLetter() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
        return when (ext) {
            "java" -> {
                if (typeName == null) return ""
                val pkg = ctx.services.packageOf(dir).orEmpty()
                (if (pkg.isEmpty()) "" else "package $pkg;\n\n") + "public class $typeName {\n}\n"
            }
            "kt" -> {
                if (typeName == null) return ""
                val pkg = ctx.services.packageOf(dir).orEmpty()
                (if (pkg.isEmpty()) "" else "package $pkg\n\n") + "class $typeName {\n}\n"
            }
            "xml" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root>\n\n</root>\n"
            else -> ""
        }
    }

    override fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate): String? {
        val typeName = name.trim()
        if (typeName.isEmpty() || !typeName.first().isLetter() || !typeName.all { it.isLetterOrDigit() || it == '_' }) return null
        val isKotlin = template.name.startsWith("Kotlin")
        val dir = Paths.get(dirPath)
        val pkg = ctx.services.packageOf(dir).orEmpty()
        val content = sourceTemplate(template, typeName, pkg)
        return writeNewFile(dirPath, "$typeName.${if (isKotlin) "kt" else "java"}") { it.writeText(content) }
    }

    /** The body for a typed [UiNewFileTemplate], with the resolved [pkg] declaration prepended. */
    private fun sourceTemplate(template: UiNewFileTemplate, name: String, pkg: String): String {
        val javaPkg = if (pkg.isEmpty()) "" else "package $pkg;\n\n"
        val ktPkg = if (pkg.isEmpty()) "" else "package $pkg\n\n"
        return when (template) {
            UiNewFileTemplate.JavaClass -> javaPkg + "public class $name {\n}\n"
            UiNewFileTemplate.JavaInterface -> javaPkg + "public interface $name {\n}\n"
            UiNewFileTemplate.JavaEnum -> javaPkg + "public enum $name {\n}\n"
            UiNewFileTemplate.JavaAbstractClass -> javaPkg + "public abstract class $name {\n}\n"
            UiNewFileTemplate.JavaAnnotation -> javaPkg + "public @interface $name {\n}\n"
            UiNewFileTemplate.KotlinFile -> ktPkg
            UiNewFileTemplate.KotlinClass -> ktPkg + "class $name {\n}\n"
            UiNewFileTemplate.KotlinInterface -> ktPkg + "interface $name {\n}\n"
            UiNewFileTemplate.KotlinDataClass -> ktPkg + "data class $name(\n)\n"
            UiNewFileTemplate.KotlinEnum -> ktPkg + "enum class $name {\n}\n"
            UiNewFileTemplate.KotlinObject -> ktPkg + "object $name\n"
        }
    }

    override fun createDirectory(parentPath: String, name: String): String? = runCatching {
        val target = Paths.get(parentPath).resolve(name)
        if (Files.exists(target)) null
        else {
            Files.createDirectories(target)
            // A conventionally-named folder under a source-set base (e.g. `src/main/resources`) becomes a
            // typed content root automatically, so the build engine + tree icon pick it up immediately.
            ctx.services.moduleService.maybeRegisterSourceRoot(target)
            ctx.services.events.fileCreated(target) // dir events are no-op locally; remote hints see them
            ctx.bumpFileSystemEpoch()
            target.toString()
        }
    }.getOrNull()

    /** Create `[dirPath]/[fileName]` via [write] (fails if it exists); publish the created-file event (the
     *  hub's reaction refreshes R for `res/`, invalidates analyzers + re-syncs for a source file), bump the
     *  fs epoch. */
    private fun writeNewFile(dirPath: String, fileName: String, write: (Path) -> Unit): String? = runCatching {
        val dir = Paths.get(dirPath)
        Files.createDirectories(dir)
        val target = dir.resolve(fileName)
        if (Files.exists(target)) null
        else {
            write(target)
            ctx.services.events.fileCreated(target)
            ctx.bumpFileSystemEpoch()
            target.toString()
        }
    }.getOrNull()

    override fun deletePath(path: String): Boolean {
        val ok = ctx.services.deletePath(Paths.get(path))
        if (ok) ctx.bumpFileSystemEpoch()
        return ok
    }

    override suspend fun renamePath(path: String, newName: String): UiRenameResult =
        withContext(ctx.engineDispatcher) {
            val r = ctx.services.renameFile(Paths.get(path), newName)
            if (r.success) ctx.bumpFileSystemEpoch()
            UiRenameResult(r.success, r.message, r.occurrences, r.filesChanged, r.newPath)
        }

    override fun movePath(path: String, destDir: String): String? =
        ctx.services.movePath(Paths.get(path), Paths.get(destDir))?.toString()?.also { ctx.bumpFileSystemEpoch() }

    override fun copyPath(path: String, destDir: String): String? =
        ctx.services.copyPath(Paths.get(path), Paths.get(destDir))?.toString()?.also { ctx.bumpFileSystemEpoch() }

    override fun listDirectory(dirPath: String): List<UiDirEntry> {
        val dir = Paths.get(dirPath)
        if (!Files.isDirectory(dir)) return emptyList()
        val entries = runCatching { Files.list(dir).use { it.collect(Collectors.toList()) } }.getOrDefault(emptyList())
            .filterNot { it.fileName.toString().startsWith(".") }
        val dirs = entries.filter { Files.isDirectory(it) && !isDerivedDir(it) }.sortedBy { it.fileName.toString().lowercase() }
        val files = entries.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString().lowercase() }
        val module = ctx.services.moduleForFile(dir)
        return dirs.map {
            UiDirEntry(it.fileName.toString(), it.toString(), true,
                ctx.services.iconFor(IconTarget.Directory(it.fileName.toString(), emptySet())) ?: "folder")
        } + files.map {
            UiDirEntry(it.fileName.toString(), it.toString(), false,
                ctx.services.iconFor(IconTarget.File(it.fileName.toString(), module)) ?: "file")
        }
    }

    override fun readFile(path: String): String = runCatching {
        val p = Paths.get(path)
        val size = Files.size(p)
        val binary = looksBinary(p)
        // A binary or very large file (an APK/jar/dex, etc.) isn't decodable text — don't read megabytes into
        // the editor. Show a short placeholder pointing at Export instead (the file itself is untouched).
        if (binary || size > MAX_TEXT_BYTES) {
            val kind = if (binary) "binary" else "large"
            "${p.fileName} — $kind file (${humanSize(size)}).\n\n" +
                "Not shown in the text editor. Long-press it ▸ Export to save it out, or open it in your file manager."
        } else p.readText()
    }.getOrDefault("")

    /** True if [p]'s first block contains a NUL byte — a cheap, reliable "this isn't text" heuristic. */
    private fun looksBinary(p: Path): Boolean = runCatching {
        Files.newInputStream(p).use { ins ->
            val buf = ByteArray(8000)
            val n = ins.read(buf)
            if (n <= 0) return false
            for (i in 0 until n) if (buf[i].toInt() == 0) return true
            false
        }
    }.getOrDefault(false)

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    override fun moduleNameForFile(path: String): String? =
        ctx.services.moduleForFile(Paths.get(path))?.name
}
