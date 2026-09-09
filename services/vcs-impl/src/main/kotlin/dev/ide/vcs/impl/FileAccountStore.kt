package dev.ide.vcs.impl

import dev.ide.vcs.AccountStore
import dev.ide.vcs.VcsAccount
import dev.ide.vcs.VcsCredentials
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Accounts and their secrets on disk, under a directory the app owns rather than the open project: an
 * account belongs to the user, not to a checkout, and a project archive must never carry a token.
 *
 * Two files: `accounts.properties` holds the listable metadata, and `credentials.properties` holds the
 * secrets, each value encrypted by [Secrets]. Splitting them keeps the metadata readable for support while
 * the secrets stay opaque.
 */
class FileAccountStore(private val dir: Path) : AccountStore {

    private val accountsFile: Path get() = dir.resolve("accounts.properties")
    private val credentialsFile: Path get() = dir.resolve("credentials.properties")
    private val secrets = Secrets(dir.resolve("secret.key"))

    private val lock = Any()

    override fun accounts(): List<VcsAccount> = synchronized(lock) {
        val props = read(accountsFile)
        val ids = props.getProperty(KEY_ORDER).orEmpty().split(',').filter { it.isNotBlank() }
        ids.mapNotNull { id -> props.readAccount(id) }
    }

    override fun activeAccount(): VcsAccount? = synchronized(lock) {
        val props = read(accountsFile)
        val active = props.getProperty(KEY_ACTIVE)
        val all = props.getProperty(KEY_ORDER).orEmpty().split(',').filter { it.isNotBlank() }
        val chosen = active?.takeIf { it in all } ?: all.firstOrNull() ?: return null
        props.readAccount(chosen)
    }

    override fun setActive(accountId: String) = synchronized(lock) {
        val props = read(accountsFile)
        props.setProperty(KEY_ACTIVE, accountId)
        write(accountsFile, props)
    }

    override fun add(account: VcsAccount, token: String): VcsAccount = synchronized(lock) {
        val stored = if (account.addedMs > 0L) account else account.copy(addedMs = System.currentTimeMillis())
        val props = read(accountsFile)
        val order = props.getProperty(KEY_ORDER).orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        if (stored.id !in order) order += stored.id
        props.setProperty(KEY_ORDER, order.joinToString(","))
        if (props.getProperty(KEY_ACTIVE).isNullOrBlank()) props.setProperty(KEY_ACTIVE, stored.id)
        props.writeAccount(stored)
        write(accountsFile, props)

        val creds = read(credentialsFile)
        creds.setProperty("token.${stored.id}", secrets.encrypt(token))
        write(credentialsFile, creds)
        stored
    }

    override fun remove(accountId: String) = synchronized(lock) {
        val props = read(accountsFile)
        val order = props.getProperty(KEY_ORDER).orEmpty().split(',').filter { it.isNotBlank() && it != accountId }
        props.setProperty(KEY_ORDER, order.joinToString(","))
        if (props.getProperty(KEY_ACTIVE) == accountId) {
            val next = order.firstOrNull()
            if (next == null) props.remove(KEY_ACTIVE) else props.setProperty(KEY_ACTIVE, next)
        }
        FIELDS.forEach { props.remove("$accountId.$it") }
        write(accountsFile, props)

        val creds = read(credentialsFile)
        creds.remove("token.$accountId")
        write(credentialsFile, creds)
    }

    override fun token(accountId: String): String? = synchronized(lock) {
        read(credentialsFile).getProperty("token.$accountId")?.let { secrets.decrypt(it) }
    }

    override fun credentialsFor(remoteUrl: String): VcsCredentials {
        val host = hostOf(remoteUrl) ?: return VcsCredentials.Anonymous
        synchronized(lock) {
            val match = accounts().firstOrNull { sameHost(it.host, host) }
            if (match != null) {
                val token = token(match.id)
                if (!token.isNullOrBlank()) return VcsCredentials.Token(token, match.login)
            }
            val creds = read(credentialsFile)
            val user = creds.getProperty("host.$host.user")?.let { secrets.decrypt(it) }
            val password = creds.getProperty("host.$host.password")?.let { secrets.decrypt(it) }
            if (!user.isNullOrBlank() && password != null) return VcsCredentials.UserPassword(user, password)
        }
        return VcsCredentials.Anonymous
    }

    override fun saveHostCredentials(host: String, username: String, password: String) = synchronized(lock) {
        val creds = read(credentialsFile)
        creds.setProperty("host.$host.user", secrets.encrypt(username))
        creds.setProperty("host.$host.password", secrets.encrypt(password))
        write(credentialsFile, creds)
    }

    override fun clearHostCredentials(host: String) = synchronized(lock) {
        val creds = read(credentialsFile)
        creds.remove("host.$host.user")
        creds.remove("host.$host.password")
        write(credentialsFile, creds)
    }

    override fun credentialHosts(): List<String> = synchronized(lock) {
        read(credentialsFile).stringPropertyNames()
            .filter { it.startsWith("host.") && it.endsWith(".user") }
            .map { it.removePrefix("host.").removeSuffix(".user") }
            .sorted()
    }

    // ---- storage -------------------------------------------------------------------------------

    private fun read(file: Path): Properties {
        val props = Properties()
        if (Files.exists(file)) {
            runCatching { Files.newInputStream(file).use { props.load(it) } }
        }
        return props
    }

    private fun write(file: Path, props: Properties) {
        Files.createDirectories(dir)
        Files.newOutputStream(file).use { props.store(it, null) }
        restrictToOwner(file)
    }

    private fun Properties.readAccount(id: String): VcsAccount? {
        val login = getProperty("$id.login") ?: return null
        return VcsAccount(
            id = id,
            forgeId = getProperty("$id.forge") ?: VcsAccount.FORGE_GITHUB,
            host = getProperty("$id.host").orEmpty(),
            login = login,
            name = getProperty("$id.name") ?: login,
            email = getProperty("$id.email").orEmpty(),
            avatarUrl = getProperty("$id.avatar").orEmpty(),
            kind = runCatching { VcsAccount.Kind.valueOf(getProperty("$id.kind").orEmpty()) }
                .getOrDefault(VcsAccount.Kind.TOKEN),
            addedMs = getProperty("$id.added")?.toLongOrNull() ?: 0L,
        )
    }

    private fun Properties.writeAccount(account: VcsAccount) {
        setProperty("${account.id}.forge", account.forgeId)
        setProperty("${account.id}.host", account.host)
        setProperty("${account.id}.login", account.login)
        setProperty("${account.id}.name", account.name)
        setProperty("${account.id}.email", account.email)
        setProperty("${account.id}.avatar", account.avatarUrl)
        setProperty("${account.id}.kind", account.kind.name)
        setProperty("${account.id}.added", account.addedMs.toString())
    }

    private companion object {
        const val KEY_ORDER = "accounts"
        const val KEY_ACTIVE = "active"
        val FIELDS = listOf("forge", "host", "login", "name", "email", "avatar", "kind", "added")
    }
}

/**
 * The host a Git remote points at. Handles both URL forms Git accepts: `https://host/owner/repo.git` and the
 * SCP-like `git@host:owner/repo.git`.
 */
internal fun hostOf(remoteUrl: String): String? {
    val url = remoteUrl.trim()
    if (url.isEmpty()) return null
    if ("://" in url) {
        val host = runCatching { URI(url).host }.getOrNull()
        if (!host.isNullOrBlank()) return host
    }
    val at = url.indexOf('@')
    val colon = url.indexOf(':', startIndex = if (at >= 0) at else 0)
    if (at >= 0 && colon > at) return url.substring(at + 1, colon).ifBlank { null }
    return null
}

/**
 * Whether an account's API host serves a Git remote's host. GitHub signs in against `api.github.com` while
 * remotes point at `github.com`, so the `api.` prefix is not part of the identity.
 */
internal fun sameHost(accountHost: String, remoteHost: String): Boolean =
    accountHost.removePrefix("api.").equals(remoteHost.removePrefix("api."), ignoreCase = true)
