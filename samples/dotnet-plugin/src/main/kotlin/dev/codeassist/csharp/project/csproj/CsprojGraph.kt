package dev.codeassist.csharp.project.csproj

import dev.codeassist.csharp.project.CsProject
import java.nio.file.Files
import java.nio.file.Path

/**
 * The projects a project references, directly and through others.
 *
 * A `<ProjectReference>` reaches everything the referenced project references in turn, as it does in an
 * SDK-style build, so the answer is the whole closure. It is ordered with dependencies first: each project
 * comes after the ones it needs, which is the order to build them in.
 */
internal object CsprojGraph {

    /** A referenced project: its file, what the file says, and the name of the assembly it builds. */
    class Node(val file: Path, val info: CsprojInfo) {
        val dir: Path get() = file.parent
        val assemblyName: String get() = Csproj.assemblyName(info, file.fileName.toString().removeSuffix(".csproj"))

        /** Where a build puts this project's DLL. */
        val output: Path get() = Csproj.outputDir(dir, info).resolve("$assemblyName.dll")
    }

    /** [nodes] in build order, and what could not be followed: a missing or unreadable file, a cycle. */
    class Result(val nodes: List<Node>, val problems: List<String>)

    /**
     * The closure of the references of the project at [projectFile], which says [info]. [read] parses a
     * project file and gives null for one it cannot read.
     */
    fun referenced(
        projectFile: Path,
        info: CsprojInfo,
        read: (Path) -> CsprojInfo? = { runCatching { Csproj.read(it) }.getOrNull() },
    ): Result {
        val root = projectFile.toAbsolutePath().normalize()
        val done = LinkedHashMap<Path, Node>()
        val problems = LinkedHashSet<String>()
        val open = ArrayList<Path>()

        fun visit(file: Path, declared: CsprojInfo) {
            open.add(file)
            for (include in declared.projectReferences) {
                val target = resolve(file, include)
                if (target == null || !Files.isRegularFile(target)) {
                    problems.add("referenced project not found: $include (from ${file.fileName})")
                    continue
                }
                if (target in open) {
                    val cycle = (open.subList(open.indexOf(target), open.size) + target).joinToString(" -> ") { it.fileName.toString() }
                    problems.add("project references form a cycle: $cycle")
                    continue
                }
                if (target in done) continue
                val read = read(target)
                if (read == null) {
                    problems.add("referenced project could not be read: $include (from ${file.fileName})")
                    continue
                }
                visit(target, read)
                done[target] = Node(target, read)
            }
            open.removeAt(open.size - 1)
        }

        visit(root, info)
        done.remove(root)
        return Result(done.values.toList(), problems.toList())
    }

    /** The file an `Include` names: relative to the referencing project's folder, with either slash. */
    private fun resolve(from: Path, include: String): Path? = runCatching {
        from.parent.resolve(include.trim().replace('\\', '/')).toAbsolutePath().normalize()
    }.getOrNull()

    /**
     * The `.cs` files of the project in [projectDir]: every one under it that [info]'s Compile items keep,
     * except those under a folder with a project file of its own. A solution laid out with one project inside
     * another's folder would otherwise compile the inner project's files twice.
     */
    fun sources(projectDir: Path, info: CsprojInfo?): List<Path> {
        val files = CsProject.files(projectDir, Int.MAX_VALUE, skipHidden = true)
        val inner = files.filter { it.fileName.toString().endsWith(".csproj") }
            .map { it.parent }
            .filter { it != projectDir }
            .distinct()
        val candidates = CsProject.sources(projectDir).filter { file -> inner.none { file.startsWith(it) } }
        return info?.let { Csproj.sources(it, projectDir, candidates) } ?: candidates
    }

    /**
     * The folder of the project [file] belongs to: the nearest folder above it that holds a `.csproj`
     * directly, or null when there is none within [levels] folders.
     */
    fun owningDir(file: Path, levels: Int = 8): Path? {
        var dir: Path? = file.toAbsolutePath().normalize().parent
        repeat(levels) {
            val current = dir ?: return null
            val has = runCatching {
                Files.list(current).use { entries -> entries.anyMatch { it.fileName.toString().endsWith(".csproj") } }
            }.getOrDefault(false)
            if (has) return current
            dir = current.parent
        }
        return null
    }
}
