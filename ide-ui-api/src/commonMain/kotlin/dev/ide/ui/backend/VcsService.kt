package dev.ide.ui.backend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Version control across the UI boundary: the working copy of the open project, the branches and history
 * behind it, and the forge account the remotes authenticate with. Everything crosses as plain DTOs, so the
 * UI never sees a Git type and a backend with no VCS engine inherits [VcsService.Unsupported].
 *
 * Reads that the whole UI shares are [StateFlow]s the backend refreshes; one-shot commands are `suspend`
 * and return a [UiVcsResult] carrying a message already fit to show.
 */
interface VcsService {

    /** The open project's working-tree state, refreshed after every command and on file-system changes. */
    val status: StateFlow<UiVcsStatus> get() = MutableStateFlow(UiVcsStatus())

    /** What a long-running command is doing right now (clone, fetch, push), for the panel's progress row. */
    val activity: StateFlow<UiVcsActivity> get() = MutableStateFlow(UiVcsActivity())

    /** Signed-in forge accounts, most recently added last. */
    val accounts: StateFlow<List<UiVcsAccount>> get() = MutableStateFlow(emptyList())

    /** Where the browser-based sign-in flow has got to. */
    val signIn: StateFlow<UiVcsSignIn> get() = MutableStateFlow(UiVcsSignIn.Idle)

    /** Whether this build carries a VCS engine at all (false hides every version-control surface). */
    fun supported(): Boolean = false

    /** Whether the open project is inside a working copy. */
    fun underVersionControl(): Boolean = false

    // ---- working copy --------------------------------------------------------------------------

    /** Re-read the working-tree state into [status]. */
    suspend fun refresh() {}

    /** Create a repository at the project root and stage nothing. */
    suspend fun initRepository(): UiVcsResult = UNSUPPORTED

    suspend fun stage(paths: List<String>): UiVcsResult = UNSUPPORTED

    suspend fun unstage(paths: List<String>): UiVcsResult = UNSUPPORTED

    /** Throw away the working-tree edits to [paths]; an untracked path is deleted. */
    suspend fun discard(paths: List<String>): UiVcsResult = UNSUPPORTED

    /** Stage [paths] as the resolution of their merge conflict. */
    suspend fun markResolved(paths: List<String>): UiVcsResult = UNSUPPORTED

    /** Commit the index. [amend] replaces the current HEAD commit. */
    suspend fun commit(message: String, amend: Boolean = false): UiVcsResult = UNSUPPORTED

    /** Add the IDE's own build output and caches to `.gitignore`. */
    suspend fun addDefaultIgnores(): UiVcsResult = UNSUPPORTED

    // ---- branches ------------------------------------------------------------------------------

    suspend fun branches(includeRemote: Boolean = true): List<UiVcsBranch> = emptyList()

    suspend fun createBranch(name: String, startPoint: String? = null, checkout: Boolean = true): UiVcsResult = UNSUPPORTED

    suspend fun checkoutBranch(name: String): UiVcsResult = UNSUPPORTED

    suspend fun deleteBranch(name: String, force: Boolean = false): UiVcsResult = UNSUPPORTED

    suspend fun renameBranch(from: String, to: String): UiVcsResult = UNSUPPORTED

    /** Merge [name] into the current branch. */
    suspend fun mergeBranch(name: String): UiVcsResult = UNSUPPORTED

    /** Abandon an in-progress merge and restore the pre-merge tree. */
    suspend fun abortMerge(): UiVcsResult = UNSUPPORTED

    // ---- history -------------------------------------------------------------------------------

    /** Commits newest first. [path] narrows to one file's history. */
    suspend fun log(limit: Int = 50, skip: Int = 0, path: String? = null): List<UiVcsCommit> = emptyList()

    suspend fun commitDetail(id: String): UiVcsCommitDetail? = null

    /**
     * The unified diff for [path]: HEAD against the index when [staged], the index against the working tree
     * otherwise, or [commitId] against its first parent when set.
     */
    suspend fun diff(path: String, staged: Boolean = false, commitId: String? = null): UiVcsDiff? = null

    // ---- stash ---------------------------------------------------------------------------------

    suspend fun stashes(): List<UiVcsStash> = emptyList()

    suspend fun stashPush(message: String, includeUntracked: Boolean = false): UiVcsResult = UNSUPPORTED

    suspend fun stashApply(index: Int, drop: Boolean = true): UiVcsResult = UNSUPPORTED

    suspend fun stashDrop(index: Int): UiVcsResult = UNSUPPORTED

    // ---- remotes and sync ----------------------------------------------------------------------

    suspend fun remotes(): List<UiVcsRemote> = emptyList()

    suspend fun addRemote(name: String, url: String): UiVcsResult = UNSUPPORTED

    suspend fun removeRemote(name: String): UiVcsResult = UNSUPPORTED

    suspend fun fetch(): UiVcsResult = UNSUPPORTED

    suspend fun pull(): UiVcsResult = UNSUPPORTED

    suspend fun push(force: Boolean = false): UiVcsResult = UNSUPPORTED

    // ---- identity ------------------------------------------------------------------------------

    /** The name and email commits are recorded under. */
    suspend fun identity(): UiVcsIdentity = UiVcsIdentity()

    suspend fun setIdentity(name: String, email: String): UiVcsResult = UNSUPPORTED

    // ---- accounts ------------------------------------------------------------------------------

    /** Whether the browser sign-in flow is available (it needs an OAuth client id in this build). */
    fun deviceAuthSupported(): Boolean = false

    /** Begin the browser sign-in flow; progress arrives on [signIn]. */
    suspend fun startSignIn() {}

    /** Abandon an in-progress browser sign-in. */
    fun cancelSignIn() {}

    /** Sign in with a personal access token instead of the browser flow. */
    suspend fun signInWithToken(token: String): UiVcsResult = UNSUPPORTED

    suspend fun signOut(accountId: String): UiVcsResult = UNSUPPORTED

    suspend fun setActiveAccount(accountId: String): UiVcsResult = UNSUPPORTED

    /** Hosts with a saved username and password, for servers with no forge integration. */
    suspend fun credentialHosts(): List<String> = emptyList()

    /** Save a username and password for [host] (a self-hosted GitLab, Gitea, or similar over HTTPS). */
    suspend fun saveHostCredentials(host: String, username: String, password: String): UiVcsResult = UNSUPPORTED

    /** Forget the saved credentials for [host]. */
    suspend fun clearHostCredentials(host: String): UiVcsResult = UNSUPPORTED

    // ---- forge ---------------------------------------------------------------------------------

    /** Repositories the signed-in account can see; [query] searches instead of listing when non-blank. */
    suspend fun forgeRepositories(query: String = "", page: Int = 1): List<UiForgeRepo> = emptyList()

    /**
     * Clone [url] into a new project directory named [directoryName] and open it. Progress arrives on
     * [activity]; the result carries the new project path when it succeeded.
     */
    suspend fun cloneRepository(url: String, directoryName: String): UiVcsResult = UNSUPPORTED

    /**
     * Create a repository on the forge, point `origin` at it, and push the current branch. Used to publish a
     * project that has commits but no remote.
     */
    suspend fun publishToForge(name: String, description: String, private: Boolean): UiVcsResult = UNSUPPORTED

    /** Open pull requests on the repository `origin` points at. */
    suspend fun pullRequests(): List<UiForgePullRequest> = emptyList()

    /** Open a pull request from the current branch into [base]. */
    suspend fun createPullRequest(title: String, body: String, base: String): UiVcsResult = UNSUPPORTED

    /** A no-op service for backends that wire no version control. */
    object Unsupported : VcsService

    companion object {
        /** Screen ids the version-control UI contributes, so the shell and the panel agree on the routes. */
        const val SCREEN_ACCOUNTS: String = "vcs.accounts"
        const val SCREEN_BRANCHES: String = "vcs.branches"
        const val SCREEN_HISTORY: String = "vcs.history"
        const val SCREEN_DIFF: String = "vcs.diff"
        const val SCREEN_CLONE: String = "vcs.clone"
        const val SCREEN_STASHES: String = "vcs.stashes"
        const val SCREEN_GITHUB: String = "vcs.github"

        private val UNSUPPORTED = UiVcsResult(false, "Version control is not available in this build")
    }
}

// ---- DTOs --------------------------------------------------------------------------------------

/** What a command did, with a message the UI can show as-is. [authRequired] asks the UI to offer sign-in. */
data class UiVcsResult(
    val ok: Boolean,
    val message: String = "",
    val authRequired: Boolean = false,
    /** Set by commands that produce a path, e.g. the directory a clone landed in. */
    val path: String? = null,
    /** Paths left conflicted by a merge or pull. */
    val conflicts: List<String> = emptyList(),
    /**
     * What a clone's destination turned out to hold, once it was adopted as a project. [UiProjectFolderKind.UNKNOWN]
     * means no build system recognized it, so the clone opens for editing only and the screen should say so.
     * Null for every command that is not a clone.
     */
    val projectKind: UiProjectFolderKind? = null,
) {
    companion object {
        val Ok: UiVcsResult = UiVcsResult(true)
        fun ok(message: String): UiVcsResult = UiVcsResult(true, message)
        fun failed(message: String): UiVcsResult = UiVcsResult(false, message)
    }
}

/** One changed path as the panel lists it. [status] and [area] are the stable ids below. */
data class UiVcsChange(
    val path: String,
    /** File name only, for the primary label. */
    val name: String,
    /** Parent directory, repository-relative, for the secondary label. Empty at the repository root. */
    val directory: String,
    /** One of [STATUS_ADDED], [STATUS_MODIFIED], [STATUS_DELETED], [STATUS_RENAMED], [STATUS_UNTRACKED], [STATUS_CONFLICTED]. */
    val status: String,
    val staged: Boolean,
    val conflicted: Boolean = false,
    val oldPath: String? = null,
) {
    companion object {
        const val STATUS_ADDED = "added"
        const val STATUS_MODIFIED = "modified"
        const val STATUS_DELETED = "deleted"
        const val STATUS_RENAMED = "renamed"
        const val STATUS_COPIED = "copied"
        const val STATUS_UNTRACKED = "untracked"
        const val STATUS_CONFLICTED = "conflicted"
    }
}

/** The working-tree snapshot the Git panel renders. */
data class UiVcsStatus(
    /** False when the open project is not inside a working copy (the panel then offers to create one). */
    val present: Boolean = false,
    val branch: String = "",
    val detached: Boolean = false,
    /** True for a repository with no commits yet. */
    val unborn: Boolean = false,
    val upstream: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    /** One of [OP_NONE], [OP_MERGE], [OP_REBASE], [OP_CHERRY_PICK], [OP_REVERT], [OP_BISECT]. */
    val operation: String = OP_NONE,
    val staged: List<UiVcsChange> = emptyList(),
    val unstaged: List<UiVcsChange> = emptyList(),
    val conflicted: List<UiVcsChange> = emptyList(),
    /** Summary line of the commit HEAD points at, empty in an unborn repository. */
    val headSummary: String = "",
    val headShortId: String = "",
    /** Set when the last refresh failed, so the panel can show why instead of an empty list. */
    val error: String = "",
) {
    val clean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && conflicted.isEmpty()
    val changeCount: Int get() = staged.size + unstaged.size + conflicted.size

    companion object {
        const val OP_NONE = "none"
        const val OP_MERGE = "merge"
        const val OP_REBASE = "rebase"
        const val OP_CHERRY_PICK = "cherryPick"
        const val OP_REVERT = "revert"
        const val OP_BISECT = "bisect"
    }
}

/** A long-running command in flight. [fraction] is -1 when the total is unknown. */
data class UiVcsActivity(
    val busy: Boolean = false,
    val task: String = "",
    val fraction: Float = -1f,
)

data class UiVcsBranch(
    val name: String,
    val remote: Boolean,
    val current: Boolean = false,
    val upstream: String = "",
    val shortId: String = "",
)

data class UiVcsCommit(
    val id: String,
    val shortId: String,
    val summary: String,
    val body: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val timeMs: Long = 0L,
    /** Host-formatted age (`2h`, `3d`, or a short date); the UI layer stays platform-neutral. */
    val timeLabel: String = "",
    val refs: List<String> = emptyList(),
    val merge: Boolean = false,
)

data class UiVcsCommitDetail(
    val commit: UiVcsCommit,
    val files: List<UiVcsChange> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0,
)

data class UiVcsDiff(
    val path: String,
    val text: String = "",
    val binary: Boolean = false,
    val insertions: Int = 0,
    val deletions: Int = 0,
)

data class UiVcsRemote(val name: String, val url: String)

data class UiVcsStash(val index: Int, val message: String, val timeMs: Long = 0L, val timeLabel: String = "")

data class UiVcsIdentity(val name: String = "", val email: String = "") {
    val configured: Boolean get() = name.isNotBlank() && email.isNotBlank()
}

/** A signed-in forge account. */
data class UiVcsAccount(
    val id: String,
    val forgeId: String,
    val host: String,
    val login: String,
    val name: String,
    val avatarUrl: String = "",
    val active: Boolean = false,
)

/** Where the browser sign-in flow has got to. */
sealed interface UiVcsSignIn {
    /** Nothing in progress. */
    data object Idle : UiVcsSignIn

    /** Asking the forge for a code. */
    data object Starting : UiVcsSignIn

    /** The user must enter [userCode] at [verificationUri]; the app polls until they do. */
    data class AwaitingUser(
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int,
    ) : UiVcsSignIn

    /** Signed in as [account]. */
    data class Done(val account: UiVcsAccount) : UiVcsSignIn

    /** The flow ended without an account. */
    data class Failed(val message: String) : UiVcsSignIn
}

/** A repository as the forge lists it, for the clone picker and the publish flow. */
data class UiForgeRepo(
    val owner: String,
    val name: String,
    val fullName: String,
    val description: String = "",
    val private: Boolean = false,
    val fork: Boolean = false,
    val defaultBranch: String = "main",
    val cloneUrl: String = "",
    val webUrl: String = "",
    val stars: Int = 0,
    val language: String = "",
    val updatedMs: Long = 0L,
    /** Host-formatted age of the last push. */
    val updatedLabel: String = "",
)

data class UiForgePullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val headBranch: String,
    val baseBranch: String,
    val webUrl: String,
    val draft: Boolean = false,
    val updatedMs: Long = 0L,
    /** Host-formatted age of the last update. */
    val updatedLabel: String = "",
)
