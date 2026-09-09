package dev.ide.vcs.impl

import dev.ide.vcs.VcsChange
import dev.ide.vcs.VcsChangeArea
import dev.ide.vcs.VcsChangeKind
import dev.ide.vcs.VcsDiff
import dev.ide.vcs.VcsException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream

/**
 * Unified-diff rendering over JGit's [DiffFormatter]. The formatter needs a pair of tree iterators, so each
 * comparison here is a choice of two sides: HEAD against the index for staged changes, the index against the
 * working tree for unstaged ones, and a commit against its first parent for history.
 */
internal object GitDiffs {

    /** Lines of surrounding context in a rendered diff. */
    private const val CONTEXT_LINES = 3

    fun diff(repo: Repository, path: String, staged: Boolean, commitId: String?): VcsDiff {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repo)
            formatter.setContext(CONTEXT_LINES)
            formatter.pathFilter = PathFilter.create(path)

            val (old, new) = sides(repo, staged, commitId)
            val entries = formatter.scan(old, new)
            val entry = entries.firstOrNull { it.newPath == path || it.oldPath == path }
                ?: return VcsDiff(path)

            val header = formatter.toFileHeader(entry)
            val binary = header.patchType != FileHeader.PatchType.UNIFIED
            formatter.format(entry)
            formatter.flush()

            val (insertions, deletions) = countEdits(header)
            return VcsDiff(
                path = entry.newPath.takeUnless { it == DiffEntry.DEV_NULL } ?: entry.oldPath,
                oldPath = entry.oldPath.takeIf { it != DiffEntry.DEV_NULL && it != entry.newPath },
                text = out.toString(Charsets.UTF_8.name()),
                binary = binary,
                insertions = insertions,
                deletions = deletions,
            )
        }
    }

    /**
     * Every path changed between two trees, with the totals the commit detail header shows. [oldTree] null
     * means the empty tree, which is how a root commit compares. One formatter serves the whole scan, so a
     * large commit costs a single pass rather than one per file.
     */
    fun changes(repo: Repository, oldTree: RevTree?, newTree: RevTree): TreeChanges =
        DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(repo)
            formatter.isDetectRenames = true
            val entries = formatter.scan(
                oldTree?.let { parser(repo, it) } ?: EmptyTreeIterator(),
                parser(repo, newTree),
            )
            var insertions = 0
            var deletions = 0
            val changes = entries.map { entry ->
                val (added, removed) = countEdits(formatter.toFileHeader(entry))
                insertions += added
                deletions += removed
                entry.toVcsChange()
            }
            TreeChanges(changes, insertions, deletions)
        }

    private fun countEdits(header: FileHeader): Pair<Int, Int> {
        if (header.patchType != FileHeader.PatchType.UNIFIED) return 0 to 0
        var insertions = 0
        var deletions = 0
        for (edit in header.toEditList()) {
            insertions += edit.lengthB
            deletions += edit.lengthA
        }
        return insertions to deletions
    }

    /** The two iterators a comparison runs over, chosen by what the caller asked to see. */
    private fun sides(
        repo: Repository,
        staged: Boolean,
        commitId: String?,
    ): Pair<AbstractTreeIterator, AbstractTreeIterator> {
        if (commitId != null) {
            val id = repo.resolve(commitId) ?: throw VcsException("Unknown commit $commitId")
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(id)
                val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it.id) }
                val old = parent?.let { parser(repo, it.tree) } ?: EmptyTreeIterator()
                return old to parser(repo, commit.tree)
            }
        }
        val index = DirCacheIterator(repo.readDirCache())
        if (staged) {
            val head = repo.resolve(Constants.HEAD)
            val old: AbstractTreeIterator = if (head == null) {
                EmptyTreeIterator()
            } else {
                RevWalk(repo).use { walk -> parser(repo, walk.parseCommit(head).tree) }
            }
            return old to index
        }
        return index to FileTreeIterator(repo)
    }

    private fun parser(repo: Repository, tree: RevTree): CanonicalTreeParser =
        repo.newObjectReader().use { reader -> CanonicalTreeParser(null, reader, tree) }
}

/** The paths one commit touched plus its line totals. */
internal data class TreeChanges(val changes: List<VcsChange>, val insertions: Int, val deletions: Int)

/** Map a JGit diff entry onto the neutral change model, as history's "files in this commit" list. */
internal fun DiffEntry.toVcsChange(): VcsChange = when (changeType) {
    DiffEntry.ChangeType.ADD -> VcsChange(newPath, VcsChangeKind.ADDED, VcsChangeArea.STAGED)
    DiffEntry.ChangeType.DELETE -> VcsChange(oldPath, VcsChangeKind.DELETED, VcsChangeArea.STAGED)
    DiffEntry.ChangeType.RENAME -> VcsChange(newPath, VcsChangeKind.RENAMED, VcsChangeArea.STAGED, oldPath)
    DiffEntry.ChangeType.COPY -> VcsChange(newPath, VcsChangeKind.COPIED, VcsChangeArea.STAGED, oldPath)
    else -> VcsChange(newPath, VcsChangeKind.MODIFIED, VcsChangeArea.STAGED)
}
