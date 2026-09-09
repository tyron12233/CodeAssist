package dev.ide.vcs

/**
 * What a transport authenticates with. Git over HTTPS carries both forms in the same basic-auth header, so a
 * host token is just a password with a conventional username.
 */
sealed interface VcsCredentials {
    /** A username and password pair, as entered for a self-hosted server. */
    data class UserPassword(val username: String, val password: String) : VcsCredentials

    /**
     * A host access token. [username] is what the forge expects alongside it: GitHub accepts the account
     * login or the literal `x-access-token`.
     */
    data class Token(val token: String, val username: String = "x-access-token") : VcsCredentials

    /** No credentials, for a public read-only remote. */
    data object Anonymous : VcsCredentials
}

/**
 * A signed-in forge account. The token never lives on this value: it is held by the [AccountStore] and
 * fetched by [id] only when a transport needs it, so an account can be listed, logged, and passed around
 * without carrying the secret.
 */
data class VcsAccount(
    /** Stable local id, `<forge>:<host>:<login>`. */
    val id: String,
    /** Which forge this is an account on, e.g. [FORGE_GITHUB]. */
    val forgeId: String,
    /** API host, so an enterprise server is a distinct account from github.com. */
    val host: String,
    val login: String,
    val name: String = login,
    val email: String = "",
    val avatarUrl: String = "",
    /** How the token was obtained, for the UI to explain what "sign out" revokes. */
    val kind: Kind = Kind.TOKEN,
    /** Epoch millis the account was added. */
    val addedMs: Long = 0L,
) {
    enum class Kind { OAUTH, TOKEN }

    companion object {
        const val FORGE_GITHUB: String = "github"

        fun idOf(forgeId: String, host: String, login: String): String = "$forgeId:$host:$login"
    }
}

/**
 * Where accounts and their tokens live. Implementations persist outside any project (accounts are the
 * user's, not the checkout's) and keep tokens out of anything that gets backed up or shared.
 */
interface AccountStore {
    /** Every signed-in account, in the order they were added. */
    fun accounts(): List<VcsAccount>

    /** The account new operations default to, or null when none is signed in. */
    fun activeAccount(): VcsAccount?

    /** Make [accountId] the default. */
    fun setActive(accountId: String)

    /** Store [account] with [token], replacing any account with the same id. Returns the stored account. */
    fun add(account: VcsAccount, token: String): VcsAccount

    /** Forget the account and its token. */
    fun remove(accountId: String)

    /** The token for [accountId], or null when the account is unknown. */
    fun token(accountId: String): String?

    /**
     * Credentials for a remote URL, chosen by matching the URL's host against the signed-in accounts. Falls
     * back to any manually saved credentials for that host, then to [VcsCredentials.Anonymous].
     */
    fun credentialsFor(remoteUrl: String): VcsCredentials

    /** Save a username and password for [host], for a server with no forge integration. */
    fun saveHostCredentials(host: String, username: String, password: String)

    /** Forget the saved credentials for [host]. */
    fun clearHostCredentials(host: String)

    /** Hosts with saved username and password credentials. */
    fun credentialHosts(): List<String>
}
