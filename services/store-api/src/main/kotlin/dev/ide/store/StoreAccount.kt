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
 * [handle] and [displayName] come from the publisher row, not the OAuth profile: a publisher can rename
 * themselves without it rewriting their provider identity. They are filled in once the profile has been
 * read, which happens as soon as a session is adopted, and are null until then.
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
 * A link that opens the store, for a host that can receive one.
 *
 * The same scheme as the sign-in redirect under a different host, so the activity can tell the two apart
 * without parsing either: `codeassist://store` opens the Store tab, `codeassist://store/<item id>` opens
 * that item's page. Used by the moderation page to jump from a submission into the app on the same phone,
 * and by anything else that has an item id and wants to hand it to the app.
 *
 * The id is the store's own item id, which for a published item is its slug.
 */
object StoreLink {
    const val HOST = "store"

    /** A link to the store itself. */
    const val PREFIX = "${StoreAuth.SCHEME}://$HOST"

    /** A link to one item, by the id [StoreCatalog] knows it by. */
    fun forItem(itemId: String): String = "$PREFIX/$itemId"

    /**
     * Whether [url] is a store link.
     *
     * The host is matched whole rather than by prefix, so a future `codeassist://storefront` would not be
     * swallowed by this one.
     */
    fun isStoreLink(url: String?): Boolean {
        if (url == null) return false
        if (url.equals(PREFIX, ignoreCase = true)) return true
        val next = url.getOrNull(PREFIX.length)
        return url.startsWith(PREFIX, ignoreCase = true) && (next == '/' || next == '?' || next == '#')
    }

    /** The item id in [url], or null if it names the store itself (or is not a store link at all). */
    fun itemId(url: String?): String? {
        if (!isStoreLink(url)) return null
        return url!!.substring(PREFIX.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .ifEmpty { null }
    }
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

    /**
     * The signed-in account, or null.
     *
     * Cheap while a session is live, but NOT on the first call after a restart: an implementation that
     * remembers the sign-in has to exchange its stored credential for a session, which is a network round
     * trip with a network timeout behind it. Call it off the main thread and off the startup path;
     * [hasStoredSession] answers "is anyone signed in" without going anywhere.
     */
    fun current(): StoreAccount? = null

    /**
     * Whether a session is live, or can be restored from a stored credential, WITHOUT a network call.
     *
     * The cheap half of [current]: it says a restore is worth starting, not that it will succeed (the
     * stored credential may have been revoked, which only the exchange finds out).
     */
    fun hasStoredSession(): Boolean = false

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

    /**
     * Tell the backend that this session owns the device holding [pushToken].
     *
     * Push registration is anonymous and has to be: a review decision has to reach a device whose user
     * signed out days ago, and registration happens at launch, before any session is restored. So the
     * device row carries no account until this binds it, and a notification addressed to an account
     * reaches an unbound device never. Re-registering with a session would bind it too, but registration
     * replaces the device's topic list wholesale and would silently unsubscribe it from every broadcast.
     *
     * Lives on this port because it is a statement about the session, and the session is what this owns.
     * False means the backend has no device with that token, so registration has not happened yet.
     */
    fun bindPushDevice(pushToken: String): StoreResult<Boolean> =
        StoreResult.Unavailable("Sign-in is not available in this build")

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
    /**
     * The project's own launcher icon, as a local image file. Null when it has none to publish.
     *
     * Travels the same private-bucket-then-approval route as the screenshots, and for the same reason.
     * The engine resolves it from the project rather than asking the submitter for one: the app already
     * has an icon, and a store that made you upload it again would mostly be a store of default glyphs.
     */
    val iconPath: String? = null,
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

/**
 * One listing the signed-in account publishes, as the submit screen needs it to offer an UPDATE.
 *
 * Only what that choice needs: something to recognise the listing by, where it stands with review, and the
 * version to start from. The listing's own text (summary, description, category, tags) is deliberately
 * absent — a new version does not rewrite it, so a form that offered those fields would be promising an
 * edit that never happens.
 */
data class StorePublishedItem(
    val slug: String,
    val title: String,
    /** The item's own review state: `approved`, `pending`, `rejected`, `unpublished`. */
    val status: String,
    /** The version people can install right now, or null while the first one is still under review. */
    val publishedVersion: String? = null,
    /** The highest version this account has SENT for the item, approved or not. Null when none parses. */
    val highestVersion: String? = null,
    /**
     * The listing's published app icon, as a path in the public media bucket.
     *
     * Null for an item whose first version is still in review: the icon is copied into the public bucket
     * by approval, so before then there is nothing a client may read.
     */
    val iconPath: String? = null,
)

/**
 * The signed-in account's own publisher profile: the row a reader sees, as its owner sees it.
 *
 * Distinct from [StoreAccount], which is the session. This is the public identity the store shows next to
 * a project, and the only part of it the owner can change is the first five fields.
 */
data class StorePublisherProfile(
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val location: String? = null,
    val linkUrl: String? = null,
    /** From the identity provider. Not owner-editable, which is why it is not in the group above. */
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val banned: Boolean = false,
    val followers: Int = 0,
    /** Listings that are live. */
    val publishedCount: Int = 0,
    /** Versions still waiting on a moderator. */
    val pendingCount: Int = 0,
    val totalInstalls: Int = 0,
    val totalLikes: Int = 0,
    /** Weighted across the account's catalogue; null until something has been rated. */
    val averageRating: Float? = null,
)

/** The outcome of a submission. [reviewNote] carries a rejection reason once a moderator has answered. */
data class StoreSubmissionStatus(
    val itemSlug: String,
    val version: String,
    val status: String,
    val reviewNote: String? = null,
    val submittedAt: String? = null,
    /** The listing's title. Null only for a row read back before the item row existed. */
    val itemTitle: String? = null,
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

    /**
     * The listings this account publishes, so a submission can be offered as an update to one of them.
     *
     * Separate from [mine], which answers "what is under review": an item whose only version was approved
     * months ago has nothing in that list and is exactly what someone wants to update.
     */
    fun myItems(): StoreResult<List<StorePublishedItem>> = StoreResult.Ok(emptyList())

    /** Withdraw a still-pending submission. */
    fun withdraw(itemSlug: String, version: String): StoreResult<Unit> =
        StoreResult.Unavailable("Submissions are not available in this build")

    /**
     * The caller's own profile, created from the identity provider if this account has none yet.
     *
     * Get-or-create rather than a plain read: the publisher row is what a handle and a display name live
     * on, and until this existed it was written by the submit flow alone, named after a fragment of a
     * user id. An account that has signed in has an identity to show whether or not it has published.
     */
    fun myProfile(): StoreResult<StorePublisherProfile?> =
        StoreResult.Unavailable("Submissions are not available in this build")

    /** Save the owner-editable half of the profile. [StoreResult.Failed] carries a message to show as it is. */
    fun saveProfile(
        handle: String,
        displayName: String,
        bio: String? = null,
        location: String? = null,
        linkUrl: String? = null,
    ): StoreResult<StorePublisherProfile?> =
        StoreResult.Unavailable("Submissions are not available in this build")

    /** Whether [handle] is free, for the form to answer while it is being typed. Your own handle is free. */
    fun handleAvailable(handle: String): StoreResult<Boolean> =
        StoreResult.Unavailable("Submissions are not available in this build")

    companion object {
        val Unsupported: StoreSubmissionService = object : StoreSubmissionService {}
    }
}
