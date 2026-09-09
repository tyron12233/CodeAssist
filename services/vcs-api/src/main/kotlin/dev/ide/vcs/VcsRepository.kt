package dev.ide.vcs

import java.nio.file.Path

/**
 * An open working copy. Every method is blocking and must be called off the UI thread; the host wraps them
 * in its own dispatcher. Implementations are not required to be thread safe, so the host serializes calls
 * per repository.
 *
 * Failures surface as [VcsException] with a message already fit to show, or [VcsAuthException] when a
 * remote refused the supplied credentials.
 */
interface VcsRepository : AutoCloseable {

    /** The working-tree root (the directory holding `.git`). */
    val root: Path

    // ---- reading ----

    /** A fresh snapshot of the working tree. */
    fun status(): VcsStatus

    /** Local branches, plus remote-tracking ones when [includeRemote]. */
    fun branches(includeRemote: Boolean = true): List<VcsBranch>

    /** Configured remotes. */
    fun remotes(): List<VcsRemote>

    /**
     * Commit history newest first, skipping [skip] and returning at most [limit]. [path] narrows it to the
     * commits touching one repository-relative path; [ref] selects a starting point other than HEAD.
     */
    fun log(limit: Int = 50, skip: Int = 0, path: String? = null, ref: String? = null): List<VcsCommit>

    /** One commit with the paths it touched. */
    fun commitDetail(id: String): VcsCommitDetail

    /**
     * The unified diff for [path]. [staged] compares HEAD against the index; otherwise the index against the
     * working tree. When [commitId] is set the diff is that commit against its first parent.
     */
    fun diff(path: String, staged: Boolean = false, commitId: String? = null): VcsDiff

    /** The full text of [path] at [ref] (`HEAD`, a branch, or a commit id), or null when absent there. */
    fun show(path: String, ref: String = "HEAD"): String?

    /** The stash stack, most recent first. */
    fun stashes(): List<VcsStash>

    // ---- working tree ----

    /** Add [paths] to the index. A directory path stages everything beneath it. */
    fun stage(paths: List<String>)

    /** Remove [paths] from the index, keeping the working-tree content. */
    fun unstage(paths: List<String>)

    /** Restore [paths] from the index (or HEAD when unstaged), discarding working-tree edits. */
    fun discard(paths: List<String>)

    /** Mark a conflicted path resolved by staging the content currently in the working tree. */
    fun markResolved(paths: List<String>)

    // ---- history ----

    /**
     * Record the index as a commit. [amend] replaces the current HEAD commit instead of adding one.
     * [author] overrides the configured identity. Returns the new commit.
     */
    fun commit(message: String, author: VcsAuthor? = null, amend: Boolean = false): VcsCommit

    // ---- branches ----

    /** Create [name] at [startPoint] (default HEAD) and optionally check it out. */
    fun createBranch(name: String, startPoint: String? = null, checkout: Boolean = true): VcsBranch

    /**
     * Switch the working tree to [name]. A remote-tracking name such as `origin/feature` creates the matching
     * local branch and sets its upstream.
     */
    fun checkout(name: String)

    /** Delete the local branch [name]; [force] drops it even when unmerged. */
    fun deleteBranch(name: String, force: Boolean = false)

    /** Rename the local branch [from] to [to]. */
    fun renameBranch(from: String, to: String)

    /** Merge [name] into the current branch. */
    fun merge(name: String): VcsMergeResult

    /** Abandon an in-progress merge and restore the pre-merge state. */
    fun abortMerge()

    // ---- remotes ----

    /** Add remote [name] pointing at [url], replacing any existing entry with that name. */
    fun addRemote(name: String, url: String)

    /** Remove remote [name]. */
    fun removeRemote(name: String)

    // ---- stash ----

    /** Stash the working-tree and index changes under [message]. Returns false when there was nothing to stash. */
    fun stashPush(message: String, includeUntracked: Boolean = false): Boolean

    /** Re-apply the stash at [index]; [drop] removes it from the stack afterwards. */
    fun stashApply(index: Int, drop: Boolean = true)

    /** Remove the stash at [index] without applying it. */
    fun stashDrop(index: Int)

    // ---- network ----

    /** Fetch [remote] and update its remote-tracking refs. */
    fun fetch(remote: String = DEFAULT_REMOTE, auth: VcsCredentials? = null, progress: VcsProgress = VcsProgress.None): VcsSyncResult

    /** Fetch and merge the current branch's upstream. */
    fun pull(remote: String = DEFAULT_REMOTE, auth: VcsCredentials? = null, progress: VcsProgress = VcsProgress.None): VcsSyncResult

    /**
     * Push [branch] (default: the current branch) to [remote]. [setUpstream] records the tracking link on a
     * first push; [force] overwrites the remote ref.
     */
    fun push(
        remote: String = DEFAULT_REMOTE,
        branch: String? = null,
        force: Boolean = false,
        setUpstream: Boolean = true,
        auth: VcsCredentials? = null,
        progress: VcsProgress = VcsProgress.None,
    ): VcsSyncResult

    // ---- config ----

    /** The commit identity this repository will use, resolved from repository then global config. */
    fun identity(): VcsAuthor?

    /** Record the commit identity in the repository config. */
    fun setIdentity(author: VcsAuthor)

    /** Append [patterns] to the repository's `.gitignore`, skipping ones already present. */
    fun ignore(patterns: List<String>)

    override fun close() {}

    companion object {
        const val DEFAULT_REMOTE: String = "origin"
    }
}

/** Coarse progress from a long-running transport operation, reported on the calling thread. */
fun interface VcsProgress {
    /**
     * @param task what is running now, in the transport's own words
     * @param completed units done so far, or -1 when the total is unknown
     * @param total units in this task, or -1 when unknown
     */
    fun update(task: String, completed: Int, total: Int)

    object None : VcsProgress {
        override fun update(task: String, completed: Int, total: Int) {}
    }
}
