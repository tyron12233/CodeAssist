package dev.ide.vcs

/**
 * The hosting-service half of version control: signing in, browsing your repositories, publishing a new one,
 * and reading pull requests. Kept separate from [VcsRepository] because none of it is Git: a checkout works
 * with no account, and an account is useful before any checkout exists.
 */
interface ForgeClient {
    /** Stable id, matching [VcsAccount.forgeId]. */
    val id: String

    /** Display name for the UI. */
    val displayName: String

    /** The API host these calls go to (`api.github.com`, or an enterprise host). */
    val host: String

    /** Whether this build carries an OAuth client id, so [startDeviceAuth] can be offered. */
    val deviceAuthSupported: Boolean

    /** Begin the device-authorization grant: the user opens [DeviceAuthorization.verificationUri] and types the code. */
    fun startDeviceAuth(): DeviceAuthorization

    /**
     * Ask once whether the user has finished authorizing [deviceCode]. Returns [DeviceAuthPoll.Pending] while
     * they have not, so the caller controls the polling cadence and can cancel.
     */
    fun pollDeviceAuth(deviceCode: String): DeviceAuthPoll

    /** Identify the owner of [token]; throws [VcsAuthException] when it is rejected. */
    fun verifyToken(token: String): VcsAccount

    /**
     * Repositories the token can see, newest activity first. [query] runs a search when non-blank, otherwise
     * the account's own repositories are listed. [page] is 1-based.
     */
    fun repositories(token: String, query: String = "", page: Int = 1, perPage: Int = 30): List<ForgeRepo>

    /** Create a repository under the token owner's account. */
    fun createRepository(token: String, name: String, description: String = "", private: Boolean = true): ForgeRepo

    /** Open pull requests for `owner/name`, newest first. */
    fun pullRequests(token: String, owner: String, name: String): List<ForgePullRequest>

    /** Open a pull request from [head] into [base] on `owner/name`. */
    fun createPullRequest(
        token: String,
        owner: String,
        name: String,
        title: String,
        body: String,
        head: String,
        base: String,
    ): ForgePullRequest

    /** The clone URL [repo] should be cloned from, with the token injected when the remote needs one. */
    fun cloneUrl(repo: ForgeRepo): String = repo.cloneUrl
}

/** A repository as the forge lists it. */
data class ForgeRepo(
    val owner: String,
    val name: String,
    val description: String = "",
    val private: Boolean = false,
    val fork: Boolean = false,
    val defaultBranch: String = "main",
    val cloneUrl: String = "",
    val webUrl: String = "",
    val stars: Int = 0,
    val language: String = "",
    /** Epoch millis of the last push, for sorting. */
    val updatedMs: Long = 0L,
) {
    val fullName: String get() = "$owner/$name"
}

/** One open pull request. */
data class ForgePullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val headBranch: String,
    val baseBranch: String,
    val webUrl: String,
    val draft: Boolean = false,
    val updatedMs: Long = 0L,
)

/** The codes a device-authorization grant hands back for the user to enter in a browser. */
data class DeviceAuthorization(
    val deviceCode: String,
    /** The short code the user types on [verificationUri]. */
    val userCode: String,
    val verificationUri: String,
    /** Seconds to wait between [ForgeClient.pollDeviceAuth] calls. */
    val intervalSeconds: Int = 5,
    /** Seconds until [deviceCode] stops being accepted. */
    val expiresInSeconds: Int = 900,
)

/** The outcome of one poll of a device-authorization grant. */
sealed interface DeviceAuthPoll {
    /** The user has not finished authorizing yet. */
    data object Pending : DeviceAuthPoll

    /** The forge asked for a longer gap between polls; [intervalSeconds] is the new cadence. */
    data class SlowDown(val intervalSeconds: Int) : DeviceAuthPoll

    /** Authorized. [token] is the access token to store. */
    data class Authorized(val token: String) : DeviceAuthPoll

    /** Ended without a token: the code expired, or the user declined. */
    data class Failed(val message: String) : DeviceAuthPoll
}
