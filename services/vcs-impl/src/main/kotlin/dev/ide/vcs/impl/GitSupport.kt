package dev.ide.vcs.impl

import dev.ide.vcs.VcsAuthException
import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsMessage
import dev.ide.vcs.VcsProgress
import dev.ide.vcs.VcsText
import org.eclipse.jgit.lib.EmptyProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Adapts JGit's push/fetch progress callbacks onto the neutral [VcsProgress] the UI observes. */
internal class GitProgressMonitor(private val progress: VcsProgress) : EmptyProgressMonitor() {
    private var task: String = ""
    private var total: Int = -1
    private var done: Int = 0

    override fun beginTask(title: String?, totalWork: Int) {
        task = title.orEmpty()
        total = if (totalWork > 0) totalWork else -1
        done = 0
        progress.update(task, 0, total)
    }

    override fun update(completed: Int) {
        done += completed
        progress.update(task, done, total)
    }

    override fun endTask() {
        if (task.isNotEmpty()) progress.update(task, if (total > 0) total else done, total)
        task = ""
    }
}

/** Map neutral credentials onto JGit's provider, which carries both forms as HTTP basic auth. */
internal fun VcsCredentials?.toJGit(): CredentialsProvider? = when (this) {
    null, VcsCredentials.Anonymous -> null
    is VcsCredentials.Token -> UsernamePasswordCredentialsProvider(username, token)
    is VcsCredentials.UserPassword -> UsernamePasswordCredentialsProvider(username, password)
}

/** The most specific message in a cause chain, so a wrapped transport error still reads usefully. */
internal fun Throwable.reason(): String {
    var cause: Throwable? = this
    var best = ""
    while (cause != null) {
        val message = cause.message?.trim()
        if (!message.isNullOrEmpty()) best = message
        cause = cause.cause
    }
    return best.ifEmpty { this::class.java.simpleName }
}

/**
 * Wrap a JGit failure in the right neutral exception. An authentication refusal becomes [VcsAuthException] so
 * the UI can offer sign-in, and a network failure becomes something the user can act on; anything else keeps
 * the transport's own words behind [prefix]. [host] names the server in a network message when known.
 *
 * Only a [network] call can be an authentication failure. A local one that reads "Permission denied" is the
 * file system refusing a write (Android's EACCES on shared storage), and offering sign-in for it sends the user
 * after the wrong problem.
 */
internal fun Throwable.asVcsFailure(prefix: VcsText, host: String? = null, network: Boolean = true): Exception {
    val reason = reason()
    if (network && looksLikeAuthFailure(reason)) {
        return VcsAuthException(msg(VcsMessage.AUTH_FAILED, prefix), this)
    }
    networkFailureText(this, host)?.let { return VcsException(it, this) }
    // The reason is JGit's or the platform's own wording, so it is carried as given rather than translated.
    return VcsException(msg(VcsMessage.WITH_REASON, prefix, VcsText.literal(reason)), this)
}

/** Shorthand for [VcsText.of], which nearly every engine message goes through. */
internal fun msg(message: VcsMessage, vararg args: Any): VcsText = VcsText.of(message, *args)

/**
 * An actionable message for a failure that is about the network rather than about Git or the forge, or null
 * when it is something else. Android reports a DNS miss as a raw `android_getaddrinfo failed: EAI_NODATA`,
 * which tells a user nothing and hides the one thing they can do about it.
 */
internal fun networkFailureText(failure: Throwable, host: String? = null): VcsText? {
    val server: Any = host?.takeIf { it.isNotBlank() } ?: msg(VcsMessage.THE_SERVER)
    var cause: Throwable? = failure
    while (cause != null) {
        when (cause) {
            is UnknownHostException -> return msg(VcsMessage.NET_UNREACHABLE, server)
            is SocketTimeoutException -> return msg(VcsMessage.NET_TIMEOUT, server)
            is ConnectException -> return msg(VcsMessage.NET_CONNECT, server)
            is SSLException -> return msg(VcsMessage.NET_TLS, server)
        }
        cause = cause.cause
    }
    return null
}

/** [networkFailureText] in English. */
internal fun networkFailureMessage(failure: Throwable, host: String? = null): String? =
    networkFailureText(failure, host)?.english

private val AUTH_MARKERS = listOf(
    "not authorized",
    "authentication is required",
    "authentication not supported",
    "no credentialsprovider",
    "invalid credentials",
    "unauthorized",
    "forbidden",
    // How JGit words an HTTP 403 on push: "git-receive-pack not permitted on '<url>'", which is what GitHub
    // answers for a token without write access to the repository.
    "not permitted",
    "permission denied",
    "auth fail",
)

/** A bare 401 or 403 status, not those digits inside an object id, a byte count, or a file name. */
private val AUTH_STATUS = Regex("""(?<![\w.])40[13](?![\w.])""")

private fun looksLikeAuthFailure(reason: String): Boolean {
    val lower = reason.lowercase()
    return AUTH_MARKERS.any { it in lower } || AUTH_STATUS.containsMatchIn(lower)
}

/**
 * [url] with any `user:secret@` part removed, for messages and records. People paste clone URLs with a token
 * embedded (`https://ghp_x@github.com/...`), and that must not end up in an error, a log, or project metadata.
 */
fun redactUrl(url: String): String = url.replace(Regex("""(://)[^/@\s]+@"""), "\$1")
