package dev.ide.ios

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.icons.fileIconId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The iOS host's [dev.ide.ui.backend.IdeBackend]: real files and real projects, no language intelligence.
 *
 * It extends [StubBackend] rather than implementing the port from scratch, so every concern this host has no
 * answer for (build, dependencies, SDK, signing, completion, analysis) keeps the empty/`Unsupported`
 * behaviour the UI already renders around, and this class is only the part that is genuinely implemented.
 * That split is deliberate: everything below the port on the other hosts is JVM code (the Kotlin compiler's
 * PSI, JDT, ASM, D8) with no Kotlin/Native counterpart, so semantics are a separate project, not a port.
 *
 * What the user gets is still a real editor. The lexical layer lives entirely in `commonMain`
 * (`EditorSession.styles` -> `LineTokens`), so syntax highlighting, bracket matching and indent all work
 * against these files with no backend involvement; what is missing is completion, diagnostics and build.
 */
class IosBackend(
    /**
     * Where projects live. Defaults to the app's Documents container (see [IosFiles] for why there is no
     * alternative on iOS); a test passes a temporary directory so it never touches real user data.
     */
    private val projectsRoot: String = IosFiles.join(IosFiles.documentsDir(), "Projects"),
) : StubBackend() {

    private var active: ProjectInfo? = null

    private val fsEpoch = MutableStateFlow(0)
    private val projEpoch = MutableStateFlow(0)

    init {
        if (!IosFiles.exists(projectsRoot)) IosFiles.mkdirs(projectsRoot)
    }

    // ---- identity -----------------------------------------------------------------------------------

    override val project: ProjectInfo
        get() = active ?: ProjectInfo(name = "", rootPath = "", moduleCount = 0)

    // ---- FileService --------------------------------------------------------------------------------

    override val fileSystemEpoch: StateFlow<Int> = fsEpoch

    /**
     * The active project as a tree. [mode] is ignored: the curated "Project" view exists on the other hosts
     * to fold a Gradle module layout into source sets, and an iOS project is a plain folder of files, so the
     * raw tree IS the curated one.
     */
    override fun fileTree(mode: TreeViewMode): TreeNode {
        val root = active?.rootPath
        if (root == null || !IosFiles.isDirectory(root)) {
            return TreeNode(id = "root", name = "No project", kind = NodeKind.Workspace, filePath = null)
        }
        return TreeNode(
            id = root,
            name = IosFiles.nameOf(root),
            kind = NodeKind.Workspace,
            filePath = null,
            iconId = "workspace",
            children = childrenOf(root, depth = 0),
        )
    }

    /** Directories before files, each alphabetical, dot-entries hidden. */
    private fun childrenOf(dir: String, depth: Int): List<TreeNode> {
        // A symlink loop or a pathologically deep tree would otherwise recurse until the stack goes; the
        // limit is far past any real source layout.
        if (depth >= MAX_TREE_DEPTH) return emptyList()
        val entries = IosFiles.list(dir).filterNot { it.startsWith(".") }
        val (dirs, files) = entries.map { IosFiles.join(dir, it) }.partition { IosFiles.isDirectory(it) }
        return dirs.sortedBy { IosFiles.nameOf(it).lowercase() }.map { path ->
            TreeNode(
                id = path,
                name = IosFiles.nameOf(path),
                kind = NodeKind.Folder,
                filePath = null,
                iconId = "folder",
                children = childrenOf(path, depth + 1),
            )
        } + files.sortedBy { IosFiles.nameOf(it).lowercase() }.map { path ->
            val name = IosFiles.nameOf(path)
            TreeNode(
                id = path,
                name = name,
                kind = NodeKind.File,
                filePath = path,
                iconId = fileIconId(name),
            )
        }
    }

    override fun readFile(path: String): String = IosFiles.readText(path)

    override fun moduleNameForFile(path: String): String? =
        active?.takeIf { path.startsWith(it.rootPath) }?.name

    override fun createFile(dirPath: String, fileName: String, content: String): String? {
        val path = IosFiles.join(dirPath, fileName)
        if (IosFiles.exists(path)) return null
        if (!IosFiles.writeText(path, content)) return null
        bumpFs()
        return path
    }

    /** [name] may carry nested folders (`ui/screens/Home.kt`); the intermediate directories are created. */
    override fun createFileSmart(dirPath: String, name: String): String? {
        val path = IosFiles.join(dirPath, name.trim('/'))
        if (IosFiles.exists(path)) return null
        if (!IosFiles.writeText(path, "")) return null
        bumpFs()
        return path
    }

    override fun createDirectory(parentPath: String, name: String): String? {
        val path = IosFiles.join(parentPath, name.trim('/'))
        if (!IosFiles.mkdirs(path)) return null
        bumpFs()
        return path
    }

    override fun deletePath(path: String): Boolean =
        IosFiles.delete(path).also { if (it) bumpFs() }

    override fun listDirectory(dirPath: String): List<UiDirEntry> =
        IosFiles.list(dirPath).filterNot { it.startsWith(".") }.map { name ->
            val path = IosFiles.join(dirPath, name)
            val isDir = IosFiles.isDirectory(path)
            UiDirEntry(name, path, isDir, if (isDir) "folder" else fileIconId(name))
        }.sortedWith(compareByDescending<UiDirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    override fun movePath(path: String, destDir: String): String? {
        val dest = IosFiles.join(destDir, IosFiles.nameOf(path))
        if (IosFiles.exists(dest) || !IosFiles.move(path, dest)) return null
        bumpFs()
        return dest
    }

    override fun copyPath(path: String, destDir: String): String? {
        val dest = IosFiles.join(destDir, IosFiles.nameOf(path))
        if (IosFiles.exists(dest) || !IosFiles.copy(path, dest)) return null
        bumpFs()
        return dest
    }

    // ---- EditorService ------------------------------------------------------------------------------

    /** Nothing analyses the buffer on this host, so a live edit needs no registration. */
    override fun updateDocument(path: String, text: String) = Unit

    override fun saveFile(path: String, text: String) {
        IosFiles.writeText(path, text)
    }

    // ---- ProjectService -----------------------------------------------------------------------------

    override val projectEpoch: StateFlow<Int> = projEpoch

    override fun projectsRootPath(): String = projectsRoot

    override fun projects(): List<ProjectInfo> =
        IosFiles.list(projectsRoot)
            .filterNot { it.startsWith(".") }
            .map { IosFiles.join(projectsRoot, it) }
            .filter { IosFiles.isDirectory(it) }
            .map { ProjectInfo(IosFiles.nameOf(it), it, moduleCount = 1, lastOpened = IosFiles.modifiedMs(it)) }
            .sortedByDescending { it.lastOpened }

    override fun projectTemplates(): List<UiProjectTemplate> = listOf(
        UiProjectTemplate(
            id = TEMPLATE_EMPTY,
            displayName = "Empty project",
            description = "A folder of Kotlin sources. No build system: this host edits files, it does not compile them.",
            category = "Other",
            iconId = "module",
            // The create-project form supplies `name` and `packageName` itself; this template asks nothing more.
            parameters = emptyList(),
        ),
    )

    override suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult {
        val rawName = args["name"].orEmpty().trim()
        if (rawName.isEmpty()) return UiProjectResult(false, "A project needs a name")
        val root = IosFiles.join(projectsRoot, IosFiles.sanitize(rawName))
        if (IosFiles.exists(root)) return UiProjectResult(false, "A project called \"$rawName\" already exists")
        if (!IosFiles.mkdirs(IosFiles.join(root, "src"))) {
            return UiProjectResult(false, "Could not create the project folder")
        }
        val pkg = args["packageName"].orEmpty().trim().trim('.')
        IosFiles.writeText(IosFiles.join(root, "src/Main.kt"), starterSource(pkg))
        active = ProjectInfo(IosFiles.nameOf(root), root, moduleCount = 1, lastOpened = IosFiles.modifiedMs(root))
        bumpProjects()
        return UiProjectResult(true, "Created $rawName", root)
    }

    override suspend fun openProject(rootPath: String): Boolean {
        if (!IosFiles.isDirectory(rootPath)) return false
        active = ProjectInfo(
            IosFiles.nameOf(rootPath), rootPath, moduleCount = 1, lastOpened = IosFiles.modifiedMs(rootPath),
        )
        bumpProjects()
        return true
    }

    override suspend fun deleteProject(rootPath: String): Boolean {
        if (!IosFiles.delete(rootPath)) return false
        if (active?.rootPath == rootPath) active = null
        bumpProjects()
        return true
    }

    // ---- internals ----------------------------------------------------------------------------------

    private fun bumpFs() { fsEpoch.value += 1 }

    /** A project change moves files too, so the tree is re-read alongside the project switch. */
    private fun bumpProjects() { projEpoch.value += 1; bumpFs() }

    private fun starterSource(packageName: String): String = buildString {
        if (packageName.isNotEmpty()) append("package $packageName\n\n")
        append("fun main() {\n")
        append("    println(\"Hello from CodeAssist on iOS\")\n")
        append("}\n")
    }

    private companion object {
        const val MAX_TREE_DEPTH = 12
        const val TEMPLATE_EMPTY = "ios.empty"
    }
}
