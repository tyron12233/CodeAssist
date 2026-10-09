package dev.ide.vcs

/**
 * A message meant for the user, kept as a [VcsMessage] and its arguments rather than as finished English, so a
 * UI can show it in the user's language. [english] renders the same message in English, for logs and for a
 * host that does not localize.
 *
 * An argument is a plain value (a branch name, a path, a count) or another [VcsText], which lets one message
 * name another ("Could not push to origin: authentication failed"). A [literal] carries words the IDE did not
 * write, such as a transport's own error text, and is shown as given.
 */
class VcsText private constructor(
    /** The message, or null for a [literal]. */
    val message: VcsMessage?,
    /** Each argument as a [String] or a nested [VcsText], in placeholder order. */
    val args: List<Any>,
    private val literal: String,
) {
    /** The message in English. */
    val english: String by lazy {
        val template = message?.english ?: return@lazy literal
        args.foldIndexed(template) { index, text, arg ->
            text.replace("%${index + 1}\$s", if (arg is VcsText) arg.english else arg.toString())
        }
    }

    override fun toString(): String = english

    companion object {
        /** [message] with [args] filling its `%1$s`, `%2$s`, ... placeholders in order. */
        fun of(message: VcsMessage, vararg args: Any): VcsText =
            VcsText(message, args.map { if (it is VcsText) it else it.toString() }, "")

        /** Words shown exactly as given, untranslated. */
        fun literal(text: String): VcsText = VcsText(null, emptyList(), text)
    }
}

/**
 * Every message the version-control engine and its host show a user, with its English wording. [key] names
 * the string resource a UI translates it with; the English here and the English resource are kept identical
 * by a test, so a message cannot be added on one side only.
 */
enum class VcsMessage(val english: String) {
    WITH_REASON("%1\$s: %2\$s"),
    SOMETHING_WENT_WRONG("Something went wrong (%1\$s)"),
    SOME_FILES("Some files"),
    AND_MORE("%1\$s and %2\$s more"),
    THE_SERVER("the server"),
    AUTH_FAILED("%1\$s: authentication failed. Sign in or check the saved credentials."),
    NET_UNREACHABLE("Could not reach %1\$s. Check your internet connection, then try again."),
    NET_TIMEOUT("No answer from %1\$s in time. Try again."),
    NET_CONNECT("Could not connect to %1\$s. Check your internet connection, then try again."),
    NET_TLS("The secure connection to %1\$s could not be established."),
    NOT_A_REPOSITORY("%1\$s is not a Git repository"),
    OPEN_FAILED("Could not open the Git repository at %1\$s"),
    INIT_FAILED("Could not create a Git repository in %1\$s"),
    FOLDER_NOT_EMPTY("%1\$s already exists and is not empty"),
    CLONE_FAILED("Could not clone %1\$s"),
    STATUS_FAILED("Could not read the repository status"),
    LIST_BRANCHES_FAILED("Could not list branches"),
    LIST_REMOTES_FAILED("Could not list remotes"),
    HISTORY_FAILED("Could not read the commit history"),
    UNKNOWN_REVISION("Unknown revision %1\$s"),
    READ_COMMIT_FAILED("Could not read commit %1\$s"),
    UNKNOWN_COMMIT("Unknown commit %1\$s"),
    DIFF_FAILED("Could not diff %1\$s"),
    SHOW_FAILED("Could not read %1\$s at %2\$s"),
    LIST_STASHES_FAILED("Could not list stashes"),
    STAGE_FAILED("Could not stage the selected files"),
    UNSTAGE_FAILED("Could not unstage the selected files"),
    DISCARD_FAILED("Could not discard the selected changes"),
    DISCARD_CONFLICTED("%1\$s has a merge conflict. Resolve it, or abort the merge to undo everything."),
    RESOLVE_FAILED("Could not mark the conflicts resolved"),
    COMMIT_MESSAGE_REQUIRED("Enter a commit message"),
    IDENTITY_REQUIRED("Set your name and email in Settings before committing"),
    COMMIT_FAILED("Could not create the commit"),
    CREATE_BRANCH_FAILED("Could not create branch %1\$s"),
    CHECKOUT_FAILED("Could not switch to %1\$s"),
    UNKNOWN_BRANCH_OR_REVISION("Unknown branch or revision %1\$s"),
    DELETE_BRANCH_FAILED("Could not delete branch %1\$s"),
    DELETE_CURRENT_BRANCH("%1\$s is the current branch. Switch to another branch first."),
    RENAME_BRANCH_FAILED("Could not rename %1\$s to %2\$s"),
    MERGE_FAILED("Could not merge %1\$s"),
    UNKNOWN_BRANCH("Unknown branch %1\$s"),
    ABORT_FAILED("Could not abort"),
    ADD_REMOTE_FAILED("Could not add remote %1\$s"),
    REMOVE_REMOTE_FAILED("Could not remove remote %1\$s"),
    STASH_FAILED("Could not stash the changes"),
    STASH_APPLY_FAILED("Could not apply the stash"),
    STASH_DROP_FAILED("Could not drop the stash"),
    FETCH_FAILED("Could not fetch from %1\$s"),
    PULL_FAILED("Could not pull from %1\$s"),
    PUSH_FAILED("Could not push to %1\$s"),
    PUSH_DETACHED("HEAD is detached, so there is no branch to push. Switch to a branch first."),
    PUSH_UNBORN("%1\$s has no commits yet. Commit something before pushing."),
    IDENTITY_SAVE_FAILED("Could not save the commit identity"),
    IGNORE_FAILED("Could not update .gitignore"),
    BRANCH_NAME_REQUIRED("Enter a branch name"),
    BRANCH_NAME_INVALID("“%1\$s” is not a valid branch name"),
    MERGE_CONFLICTS("The merge left %1\$s file(s) conflicted. Resolve them, then commit."),
    ALREADY_UP_TO_DATE("Already up to date"),
    FAST_FORWARDED("Fast-forwarded"),
    MERGED("Merged"),
    MERGE_INCOMPLETE("The merge did not complete (%1\$s)"),
    REBASED("Rebased onto the remote"),
    REBASE_STOPPED("The pull stopped while replaying your commits. Resolve the conflicts, or abort to undo the pull."),
    PULL_INCOMPLETE("The pull did not complete (%1\$s)"),
    BLOCKED_BY_LOCAL_EDITS("%1\$s have uncommitted changes that this would overwrite. Commit or stash them first, then try again."),
    PUSH_BEHIND("The remote %1\$s has commits you do not have yet. Pull first, then push again."),
    PUSH_NO_DELETE("The remote does not allow deleting %1\$s."),
    GH_NO_DEVICE_CODE("GitHub did not return a device code"),
    SIGN_IN_CODE_EXPIRED("The sign-in code expired. Start again."),
    SIGN_IN_DENIED("Sign-in was cancelled on GitHub."),
    GH_UNEXPECTED("GitHub returned an unexpected response."),
    GH_UNEXPECTED_USER("GitHub returned an unexpected response for the signed-in user"),
    GH_TOKEN_REJECTED("GitHub did not accept this token"),
    GH_UNREACHABLE("Could not reach GitHub"),
    GH_CREDENTIALS_REJECTED("GitHub rejected the credentials (HTTP %1\$s)"),
    GH_HTTP_ERROR("GitHub returned HTTP %1\$s"),
    GH_UNREADABLE("GitHub returned a response that could not be read"),
    REPO_NAME_REQUIRED("Enter a repository name"),
    REPO_CREATED("Repository created"),
    COMMITTED("Committed %1\$s"),
    GITIGNORE_UPDATED("Updated .gitignore"),
    BRANCH_CREATED("Created %1\$s"),
    SWITCHED("Switched to %1\$s"),
    BRANCH_DELETED("Deleted %1\$s"),
    BRANCH_RENAMED("Renamed to %1\$s"),
    ABORTED("Aborted. Your branch is back where it was."),
    STASHED("Changes stashed"),
    NOTHING_TO_STASH("There was nothing to stash"),
    STASH_APPLIED("Stash applied"),
    STASH_DROPPED("Stash dropped"),
    REMOTE_ADDED("Remote %1\$s added"),
    REMOTE_REMOVED("Remote %1\$s removed"),
    FETCHED("Up to date with the remote"),
    PULLED("Pulled"),
    PULL_NOT_COMPLETED("The pull did not complete"),
    PUSHED("Pushed"),
    PUSH_REJECTED("The remote rejected the push"),
    IDENTITY_SAVED("Identity saved"),
    TOKEN_REQUIRED("Paste a personal access token"),
    SIGNED_IN("Signed in as %1\$s"),
    SIGNED_OUT("Signed out"),
    HOST_CREDENTIALS_REQUIRED("Enter the host and your username"),
    HOST_CREDENTIALS_SAVED("Saved credentials for %1\$s"),
    HOST_CREDENTIALS_REMOVED("Removed credentials for %1\$s"),
    CLONE_FOLDER_REQUIRED("Enter a folder name for the clone"),
    CLONED("Cloned into %1\$s"),
    PUBLISH_NEEDS_COMMIT("Make a first commit before publishing"),
    PUBLISH_DETACHED("HEAD is detached. Switch to a branch before publishing."),
    PUBLISH_REMOTE_EXISTS("This project already has a remote named %1\$s"),
    PUBLISHED("Published to %1\$s"),
    NO_GITHUB_REMOTE("This project has no GitHub remote"),
    PR_TITLE_REQUIRED("Enter a title for the pull request"),
    PR_DETACHED("HEAD is detached, so there is no branch to propose"),
    PR_SAME_BRANCH("Pick a base branch other than %1\$s"),
    PR_OPENED("Opened pull request #%1\$s"),
    NOT_UNDER_VCS("This project is not under version control"),
    NO_PROJECT("Open a project first"),
    NO_ENGINE("Version control is not available in this build"),
    SIGN_IN_FIRST("Sign in to GitHub first"),
    NO_REMOTE("This project has no remote yet. Publish it or add a remote in GitHub."),
    ACTIVITY_FETCHING("Fetching"),
    ACTIVITY_PULLING("Pulling"),
    ACTIVITY_PUSHING("Pushing"),
    ACTIVITY_CLONING("Cloning"),
    ACTIVITY_PUBLISHING("Publishing"),
    ;

    /** The string-resource name a UI looks this message up by. */
    val key: String get() = "vcs_msg_" + name.lowercase()
}
