package dev.ide.store

/**
 * Sign-in and submission — the phase-2 half of the store contract.
 *
 * Browsing never touches these. The store is readable and installable anonymously; an account is only
 * needed to publish, rate or report, and the sign-in prompt appears at that moment rather than on launch.
 */

/** Which identity provider a sign-in uses. The store deliberately supports these two only. */
enum class StoreProvider(val wire: String) { GITHUB("github"), GOOGLE("google") }

/**
 * A signed-in account.
 *
 * [handle] and [displayName] come from the publisher row, not the OAuth profile — a publisher can rename
 * themselves without it rewriting their provider identity. Both are null until the account has published
 * something, because the publisher row is created on first submit, not on signup.
 */
data class StoreAccount(
    val userId: String,
    val email: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val isAdmin: Boolean = false,
)

/**
 * Where a provider sends the user back to, defined once.
 *
 * The scheme has to agree in three places that cannot check each other: the Android manifest's
 * intent-filter, the Supabase project's allow-list, and the activity that has to recognise the incoming
 * link as a sign-in rather than a file to open. A typo in any one of them fails at runtime only, so they
 * all read from here.
 */
object StoreAuth {
    /** The Android deep link. Must match the intent-filter and Supabase's `additional_redirect_urls`. */
    const val ANDROID_REDIRECT = "codeassist://auth-callback"

    /** The desktop loopback redirect, for a host that can run a one-shot local listener. */
    const val DESKTOP_REDIRECT = "http://127.0.0.1:8976/auth-callback"

    const val SCHEME = "codeassist"
    const val HOST = "auth-callback"

    /**
     * Whether [url] is a sign-in redirect coming back into the app.
     *
     * Checked by prefix rather than parsed, because the tokens arrive in the fragment and a URI parser is
     * not needed to tell a sign-in from a file.
     */
    fun isAuthRedirect(url: String?): Boolean =
        url != null && url.startsWith("$SCHEME://$HOST", ignoreCase = true)
}

/**
 * One step of an OAuth sign-in.
 *
 * The flow cannot complete in one call: the client has to open a browser, the user consents, and the
 * provider redirects back into the app. So [begin] hands back a URL to open and [complete] takes the
 * redirect it eventually receives.
 */
data class StoreAuthChallenge(
    /** Open this in a browser / custom tab. */
    val authorizeUrl: String,
    /** The redirect the provider will send the user back to; the host must be able to receive it. */
    val redirectUrl: String,
)

/**
 * The account port.
 *
 * Session persistence is the implementation's problem, not the caller's: [current] answers from a stored
 * refresh token where one exists, so a signed-in user stays signed in across launches.
 */
interface StoreAccountService {
    fun authAvailable(): Boolean = false

    /**
     * The providers this build can actually sign in with.
     *
     * Not the same as [StoreProvider.entries]: a provider needs an OAuth app registered on the Supabase
     * project before it works, and offering a button that cannot succeed is worse than not offering it.
     * The launcher decides, because it is what knows which credentials were configured.
     */
    fun providers(): List<StoreProvider> = emptyList()

    /** The signed-in account, or null. Cheap and safe to call during composition. */
    fun current(): StoreAccount? = null

    /** Start a sign-in with [provider]; the caller opens [StoreAuthChallenge.authorizeUrl]. */
    fun begin(provider: StoreProvider): StoreResult<StoreAuthChallenge> =
        StoreResult.Unavailable("Sign-in is not available in this build")

    /**
     * Finish a sign-in from the redirect the provider sent back.
     *
     * [redirect] is the whole URL, because Supabase returns the tokens in the fragment or as a `code`
     * depending on the flow, and picking them apart is the implementation's job.
     */
    fun complete(redirect: String): StoreResult<StoreAccount> =
        StoreResult.Unavailable("Sign-in is not available in this build")

    fun signOut() {}

    companion object {
        val Unsupported: StoreAccountService = object : StoreAccountService {}
    }
}

/** What a submission is asking the moderators to publish. */
data class StoreSubmissionRequest(
    /** Null for a first submission (a new item is created); set to publish a new version of one you own. */
    val itemSlug: String? = null,
    val title: String,
    val summary: String,
    val description: String,
    val category: String,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val highlights: List<String> = emptyList(),
    /**
     * Local image files to publish with the project, at most six.
     *
     * Uploaded to the PRIVATE bucket with the archive and copied to the public one on approval, exactly as
     * the payload is: a submitter writing straight to the public bucket would be free image hosting no
     * moderator ever sees.
     */
    val screenshotPaths: List<String> = emptyList(),
    val version: String = "1.0.0",
    val changelog: String? = null,
)

/**
 * One file that will go into the submitted zip.
 *
 * Surfaced to the caller so the submit screen can show exactly what is about to be uploaded — the design
 * calls for the file list and total size to be confirmed, and a user publishing their own project has a
 * right to see precisely what leaves the device.
 */
data class PackagedFile(val path: String, val sizeBytes: Long)

/**
 * A project packaged and ready to submit, before anything is uploaded.
 *
 * [excluded] is the interesting half: it names what was deliberately left out (build output, `.git`,
 * keystores, `local.properties`) so the confirmation step can prove a keystore is not in the archive
 * rather than merely promising it.
 */
data class PackagedProject(
    val files: List<PackagedFile>,
    val excluded: List<String>,
    val totalBytes: Long,
    val sha256: String,
    /** Where the built zip landed on disk; the caller uploads and then deletes it. */
    val archivePath: String,
) {
    val fileCount: Int get() = files.size
}

/** The outcome of a submission. [reviewNote] carries a rejection reason once a moderator has answered. */
data class StoreSubmissionStatus(
    val itemSlug: String,
    val version: String,
    val status: String,
    val reviewNote: String? = null,
    val submittedAt: String? = null,
)

/**
 * The submission port.
 *
 * Packaging is separate from uploading on purpose. [pack] is local and cheap to re-run; the submit screen
 * shows its result and only then does [submit] send anything. That split is what makes "show the file
 * list and total size for confirmation" possible.
 */
interface StoreSubmissionService {
    fun submissionsAvailable(): Boolean = false

    /** Zip [projectRoot] into a submittable archive, excluding build output and secrets. */
    fun pack(projectRoot: String): StoreResult<PackagedProject> =
        StoreResult.Unavailable("Submissions are not available in this build")

    /** Upload [packaged] and create the pending item/version rows. Requires a signed-in account. */
    fun submit(request: StoreSubmissionRequest, packaged: PackagedProject): StoreResult<StoreSubmissionStatus> =
        StoreResult.Unavailable("Submissions are not available in this build")

    /** The signed-in account's own submissions, newest first — the "under review" list. */
    fun mine(): StoreResult<List<StoreSubmissionStatus>> = StoreResult.Ok(emptyList())

    /** Withdraw a still-pending submission. */
    fun withdraw(itemSlug: String, version: String): StoreResult<Unit> =
        StoreResult.Unavailable("Submissions are not available in this build")

    companion object {
        val Unsupported: StoreSubmissionService = object : StoreSubmissionService {}
    }
}
