package dev.ide.vcs.impl

import dev.ide.vcs.VcsAuthException
import dev.ide.vcs.VcsCredentials
import dev.ide.vcs.VcsException
import dev.ide.vcs.VcsProgress
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
 */
internal fun Throwable.asVcsFailure(prefix: String, host: String? = null): Exception {
    val reason = reason()
    if (looksLikeAuthFailure(reason)) {
        return VcsAuthException("$prefix: authentication failed. Sign in or check the saved credentials.", this)
    }
    networkFailureMessage(this, host)?.let { return VcsException(it, this) }
    return VcsException("$prefix: $reason", this)
}

/**
 * An actionable message for a failure that is about the network rather than about Git or the forge, or null
 * when it is something else. Android reports a DNS miss as a raw `android_getaddrinfo failed: EAI_NODATA`,
 * which tells a user nothing and hides the one thing they can do about it.
 */
internal fun networkFailureMessage(failure: Throwable, host: String? = null): String? {
    val server = host?.takeIf { it.isNotBlank() } ?: "the server"
    var cause: Throwable? = failure
    while (cause != null) {
        when (cause) {
            is UnknownHostException ->
                return "Could not reach $server. Check your internet connection, then try again."

            is SocketTimeoutException -> return "$server did not respond in time. Try again."

            is ConnectException ->
                return "Could not connect to $server. Check your internet connection, then try again."

            is SSLException -> return "The secure connection to $server could not be established."
        }
        cause = cause.cause
    }
    return null
}

private val AUTH_MARKERS = listOf(
    "not authorized",
    "authentication is required",
    "authentication not supported",
    "no credentialsprovider",
    "invalid credentials",
    "unauthorized",
    "401",
    "403",
    "permission denied",
    "auth fail",
)

private fun looksLikeAuthFailure(reason: String): Boolean {
    val lower = reason.lowercase()
    return AUTH_MARKERS.any { it in lower }
}
