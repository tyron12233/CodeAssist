package dev.ide.vcs

import dev.ide.platform.ExtensionPoint
import java.nio.file.Path

/**
 * Opens working copies of one version-control system. The host asks every registered provider whether a
 * directory is a checkout it owns and uses the first that answers, so support for another system is one more
 * registration rather than a host change.
 */
interface VcsProvider {
    /** Stable id, also the value persisted with a project's VCS mapping. `git` for the built-in provider. */
    val id: String

    /** Display name for the UI. */
    val displayName: String

    /** The checkout root at or above [dir], or null when [dir] is not inside a working copy. */
    fun findRoot(dir: Path): Path?

    /** Open the checkout rooted at [root]. Throws [VcsException] when it cannot be read. */
    fun open(root: Path): VcsRepository

    /** Create an empty repository at [dir], with [defaultBranch] as the unborn HEAD. */
    fun init(dir: Path, defaultBranch: String = "main"): VcsRepository

    /**
     * Clone [url] into [target]. [branch] checks out a branch other than the remote's default and [depth],
     * when positive, requests a shallow history.
     */
    fun clone(
        url: String,
        target: Path,
        branch: String? = null,
        depth: Int = 0,
        auth: VcsCredentials? = null,
        progress: VcsProgress = VcsProgress.None,
    ): VcsRepository
}

/** Providers the host consults, in registration order. The Git provider registers here. */
val VCS_PROVIDER_EP = ExtensionPoint<VcsProvider>("platform.vcsProvider")
