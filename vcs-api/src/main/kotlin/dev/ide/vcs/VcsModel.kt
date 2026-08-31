package dev.ide.vcs

/**
 * The neutral version-control data model: what a working tree looks like right now, which refs exist, and
 * what history holds. Every type here is a plain immutable value so it crosses the engine/UI boundary and
 * the process boundary without adapting, and so a provider other than Git can produce the same shapes.
 */

/** How one path differs between HEAD, the index, and the working tree. */
enum class VcsChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    /** Present in the working tree and not tracked. */
    UNTRACKED,
    /** Tracked but matched by an ignore rule (reported only when explicitly requested). */
    IGNORED,
    /** A merge left conflict markers or an unresolved index stage for this path. */
    CONFLICTED,
}

/** Which side of the index a change sits on. */
enum class VcsChangeArea {
    /** Recorded in the index and will go into the next commit. */
    STAGED,

    /** Present in the working tree only. */
    UNSTAGED,

    /** Unresolved after a merge, rebase, or cherry-pick. */
    CONFLICTED,
}

/**
 * One changed path. [path] is repository-relative with `/` separators (the form Git itself records), so it
 * is stable across hosts; [oldPath] is set for a rename or copy.
 */
data class VcsChange(
    val path: String,
    val kind: VcsChangeKind,
    val area: VcsChangeArea,
    val oldPath: String? = null,
)

/** How far the current branch has drifted from the remote-tracking branch it follows. */
data class VcsTracking(
    /** The upstream ref short name, e.g. `origin/main`, or null when the branch tracks nothing. */
    val upstream: String? = null,
    /** Commits on the local branch the upstream does not have. */
    val ahead: Int = 0,
    /** Commits on the upstream the local branch does not have. */
    val behind: Int = 0,
)

/** An in-progress multi-commit operation the working tree is parked in. */
enum class VcsOperation { NONE, MERGE, REBASE, CHERRY_PICK, REVERT, BISECT }

/**
 * A snapshot of the working tree. [branch] is the current branch's short name, null when HEAD is detached
 * (then [head] carries the commit it points at).
 */
data class VcsStatus(
    val branch: String? = null,
    val head: VcsCommit? = null,
    val detached: Boolean = false,
    val changes: List<VcsChange> = emptyList(),
    val tracking: VcsTracking = VcsTracking(),
    val operation: VcsOperation = VcsOperation.NONE,
    /** True for a repository with no commits yet, where HEAD points at an unborn branch. */
    val unborn: Boolean = false,
) {
    val staged: List<VcsChange> get() = changes.filter { it.area == VcsChangeArea.STAGED }
    val unstaged: List<VcsChange> get() = changes.filter { it.area == VcsChangeArea.UNSTAGED }
    val conflicted: List<VcsChange> get() = changes.filter { it.area == VcsChangeArea.CONFLICTED }
    val clean: Boolean get() = changes.isEmpty()
}

/** A local or remote-tracking branch. */
data class VcsBranch(
    /** Short name: `main` for a local branch, `origin/main` for a remote-tracking one. */
    val name: String,
    /** Full ref name, e.g. `refs/heads/main`. */
    val ref: String,
    val remote: Boolean,
    val current: Boolean = false,
    /** The commit the branch points at, or null when it could not be read. */
    val tip: String? = null,
    /** For a local branch, the remote-tracking ref it follows. */
    val upstream: String? = null,
)

/** A person recorded on a commit. */
data class VcsAuthor(val name: String, val email: String) {
    /** `Name <email>`, the form Git writes into the commit header. */
    override fun toString(): String = if (email.isBlank()) name else "$name <$email>"
}

/** One commit as history lists it. [id] is the full object id; [shortId] is the abbreviated form. */
data class VcsCommit(
    val id: String,
    val shortId: String,
    val summary: String,
    val body: String = "",
    val author: VcsAuthor,
    val committer: VcsAuthor = author,
    /** Author time, epoch millis. */
    val timeMs: Long = 0L,
    val parents: List<String> = emptyList(),
    /** Branch and tag names that point at this commit. */
    val refs: List<String> = emptyList(),
) {
    val merge: Boolean get() = parents.size > 1
}

/** A commit plus the paths it touched, for the detail view. */
data class VcsCommitDetail(
    val commit: VcsCommit,
    val changes: List<VcsChange> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0,
)

/** A configured remote and the URL it fetches from. */
data class VcsRemote(val name: String, val fetchUrl: String, val pushUrl: String = fetchUrl)

/** One entry of the stash stack. Index 0 is the most recent. */
data class VcsStash(val index: Int, val id: String, val message: String, val timeMs: Long = 0L)

/** A unified diff for one path, already rendered as text. */
data class VcsDiff(
    val path: String,
    val oldPath: String? = null,
    /** Unified diff text, empty when the two sides are identical. */
    val text: String = "",
    /** True when the content is binary and no textual diff was produced. */
    val binary: Boolean = false,
    val insertions: Int = 0,
    val deletions: Int = 0,
)

/** What a merge attempt ended in. */
data class VcsMergeResult(
    val status: Status,
    /** Paths left unresolved when [status] is [Status.CONFLICTS]. */
    val conflicts: List<String> = emptyList(),
    val message: String = "",
) {
    enum class Status { ALREADY_UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICTS, ABORTED, FAILED }
}

/** What a fetch, pull, or push moved. */
data class VcsSyncResult(
    val ok: Boolean,
    val message: String = "",
    /** Per-ref outcome lines, in the order the transport reported them. */
    val updates: List<String> = emptyList(),
    /** Set when a pull's merge step left the tree conflicted. */
    val merge: VcsMergeResult? = null,
)

/** Anything the engine could not do, carrying a message already fit to show a user. */
class VcsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Distinguishes an authentication failure so the UI can offer sign-in rather than a generic error. */
class VcsAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
