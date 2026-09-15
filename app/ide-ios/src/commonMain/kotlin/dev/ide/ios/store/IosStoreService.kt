package dev.ide.ios.store

import dev.ide.store.StoreAuth
import dev.ide.store.bridge.StoreAccounts
import dev.ide.store.bridge.StoreFeedCache
import dev.ide.store.bridge.StoreInstallHistory
import dev.ide.store.bridge.StoreInstaller
import dev.ide.store.bridge.StoreLikes
import dev.ide.store.bridge.StoreMediaCache
import dev.ide.store.bridge.StoreModeration
import dev.ide.store.bridge.StoreReviews
import dev.ide.store.bridge.StoreSubmissions
import dev.ide.store.bridge.storeIo
import dev.ide.store.impl.SupabaseAccountService
import dev.ide.store.impl.SupabaseModerationService
import dev.ide.store.impl.SupabaseReviewService
import dev.ide.store.impl.SupabaseStoreSource
import dev.ide.store.impl.SupabaseSubmissionService
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiModerationQueue
import dev.ide.ui.backend.UiMyProfile
import dev.ide.ui.backend.UiPublisherProfile
import dev.ide.ui.backend.UiReportReason
import dev.ide.ui.backend.UiReportedContent
import dev.ide.ui.backend.UiReviewPage
import dev.ide.ui.backend.UiReviewSort
import dev.ide.ui.backend.UiStoreAuthState
import dev.ide.ui.backend.UiStoreCategory
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreSearchPage
import dev.ide.ui.backend.UiStoreSubmission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The Projects Store on iOS.
 *
 * Almost nothing here is about iOS. The transport is `:store-impl` and the translation onto the UI's
 * contract is `:store-bridge` — the same code the Android and desktop hosts run, down to which status
 * means offline and how long a cached feed is reused. What this class supplies is the four things only a
 * host knows: where files go (the app container), where the projects root is, how a signed-in account is
 * carried, and what "install" means here.
 *
 * What it does NOT offer is publishing. Packaging a project means writing a zip, and Foundation has no
 * compressor a Kotlin/Native target reaches; [submissionsAvailable] answers false and every publish entry
 * point is drawn conditionally on it. Browsing, installing, signing in, reviewing and moderating are all
 * whole.
 */
class IosStoreService(
    /** The catalog: feed, search, payload downloads. */
    private val source: dev.ide.store.StoreCatalogSource,
    private val accountService: dev.ide.store.StoreAccountService,
    private val submissionService: dev.ide.store.StoreSubmissionService,
    private val reviewService: dev.ide.store.StoreReviewService,
    private val moderationService: dev.ide.store.StoreModerationService,
    /** Where installed projects are unpacked. The picker lists whatever is under it. */
    private val projectsRoot: () -> String,
    /** The app's support container: the feed's offline copy, cached screenshots, the install record. */
    private val cacheRoot: String,
    /** Small durable values (the anonymous install id, the saved list). NOT credentials — see [IosTokenStore]. */
    private val preferences: IosPreferences,
    /** Adopt an unpacked directory as a project; null when it succeeded, else why it did not. */
    private val adopt: (String) -> String?,
) : StoreService {

    companion object {
        private const val INSTALL_ID_PREF = "analytics.installId"

        /** The same key the other hosts use, so a saved shelf is not per-platform by accident. */
        private const val LIKES_PREF = "store.favorites"

        /**
         * The real thing: the four Supabase ports, wired to one project.
         *
         * A factory rather than the constructor so the ports stay injectable. They are what a test needs to
         * replace — the install path is worth proving end to end on this platform, because the zip reader
         * underneath it is iOS's own — and a class that built its own transport could only be tested
         * against a server.
         */
        fun supabase(
            url: String,
            apiKey: String,
            appBuild: Int?,
            projectsRoot: () -> String,
            cacheRoot: String,
            preferences: IosPreferences,
            adopt: (String) -> String?,
        ): IosStoreService {
            val accounts = SupabaseAccountService(
                url = url,
                apiKey = apiKey,
                // The same redirect the Android host registers. It is allow-listed on the Supabase project
                // by its literal value, so both mobile hosts use one entry rather than two that can drift.
                redirectUrl = StoreAuth.MOBILE_REDIRECT,
                tokens = IosTokenStore(),
                enabledProviders = listOf(
                    dev.ide.store.StoreProvider.GITHUB,
                    dev.ide.store.StoreProvider.GOOGLE,
                ),
            )
            return IosStoreService(
                source = SupabaseStoreSource(url = url, apiKey = apiKey, appBuild = appBuild),
                accountService = accounts,
                submissionService = SupabaseSubmissionService(url, apiKey, accounts),
                reviewService = SupabaseReviewService(url, apiKey, accounts),
                moderationService = SupabaseModerationService(url, apiKey, accounts),
                projectsRoot = projectsRoot,
                cacheRoot = cacheRoot,
                preferences = preferences,
                adopt = adopt,
            )
        }
    }

    private val configured = source.configured()

    // ---- the shared machinery ------------------------------------------------------------------------

    /**
     * Declared before [accountState], and that is not cosmetic: the account state restores a stored
     * session as soon as it is built, and the restore adopts the account, which reads the profile through
     * this. The other way round, a cold start with a session in the keychain reads a property that has not
     * been initialised yet.
     */
    private val submissionState = StoreSubmissions(submissionService)

    private val accountState = StoreAccounts(
        accounts = accountService,
        source = source,
        // Adopting an account reads the publisher row — creating it from the identity provider on a first
        // sign-in — and folds what it says back into the account: the handle and name to show, and
        // whether this account moderates, which is what decides that the moderation surface exists at
        // all. There is no push device to bind here, which is the only other thing the Android host does
        // at this moment.
        onSignedIn = submissionState::adopt,
    )

    private val reviewState = StoreReviews(reviewService)
    private val moderationState = StoreModeration(moderationService)

    private val history = StoreInstallHistory { joinPath(cacheRoot, "store/installed.txt") }

    private val feedCache = StoreFeedCache(
        source = source,
        cachePath = { joinPath(cacheRoot, "store/explore-feed.json") },
    )

    private val media = StoreMediaCache(
        source = source,
        root = { cacheRoot },
        moderation = { moderationState },
    )

    private val likeStore = StoreLikes(
        reviews = reviewService,
        readLocal = {
            preferences.get(LIKES_PREF)?.split(',', '\n')?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        },
        writeLocal = { ids -> preferences.put(LIKES_PREF, ids.joinToString(",")) },
    )

    private val installer = StoreInstaller(source)
    private val progressState = MutableStateFlow<Map<String, UiInstallProgress>>(emptyMap())

    // ---- browsing ------------------------------------------------------------------------------------

    override fun storeAvailable(): Boolean = configured

    /**
     * The feed, or null when there is no store to ask and nothing cached.
     *
     * Null is not an empty store: the caller then falls back to its bundled shelves rather than rendering
     * "nobody has published anything", which would be a claim about the store rather than about the
     * network.
     */
    override suspend fun feed(seedItemId: String?, refresh: Boolean): UiStoreFeed? {
        if (!configured) return null
        // The Explore route has no seed to give, so the device's own most recent install is the only thing
        // that can personalise the shelf: the recommendation model is anonymous, and the server has no way
        // to know which install id is this phone.
        val seed = seedItemId ?: withContext(storeIo) { history.mostRecent() }
        return feedCache.feed(seed, refresh)
    }

    override suspend fun searchPage(
        query: String,
        category: String?,
        offset: Int,
        limit: Int,
    ): UiStoreSearchPage {
        if (!configured) return UiStoreSearchPage()
        val result = withContext(storeIo) {
            source.search(
                dev.ide.store.StoreQuery(
                    text = query.trim(),
                    // Lowercased because the store's categories are slugs matched exactly: a caller that
                    // had only a display name to go on ("Kotlin") would otherwise match nothing at all.
                    category = category?.lowercase(),
                    limit = limit,
                    offset = offset,
                ),
                source.appBuild ?: 0,
            )
        }
        return dev.ide.store.bridge.StoreSearchPaging.page(emptyList(), result, emptyMap(), offset, limit)
    }

    override suspend fun searchCategories(): List<UiStoreCategory> {
        if (!configured) return emptyList()
        val remote = withContext(storeIo) { source.categories() }
        return if (remote is dev.ide.store.StoreResult.Ok) {
            remote.value.map { (slug, title) -> UiStoreCategory(slug, title) }
        } else {
            emptyList()
        }
    }

    override suspend fun screenshotFile(storagePath: String): String? = media.screenshot(storagePath)

    override suspend fun avatarFile(url: String): String? = media.avatar(url)

    // ---- installing ----------------------------------------------------------------------------------

    /**
     * Download, verify and unpack a project into the app's Documents container.
     *
     * Both gates hold here exactly as they do on the other hosts: the sha256 has to match what the catalog
     * row promised, and the archive has to survive the extractor's checks (zip slip, symlinks, the
     * uncompressed ceiling). The zip reader underneath is this platform's own — iOS has no
     * `java.util.zip` — which is why it is the piece with tests of its own.
     */
    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        val payload = feedCache.payload(id)
            ?: return UiStoreInstallResult(false, "That project has nothing to download yet")
        return withContext(storeIo) {
            val result = installer.install(
                payload = payload,
                projectsRoot = projectsRoot(),
                adopt = adopt,
                onProgress = { p -> progressState.value = progressState.value + (p.itemId to p) },
            )
            // Remembered with the directory it landed in, so the next launch still knows this item is on
            // the device and where — which is what turns its button into an Open that has something to open.
            if (result.success) {
                history.remember(id, result.rootPath)
                recordInstall(id)
            }
            result
        }
    }

    override fun installProgress(): StateFlow<Map<String, UiInstallProgress>> = progressState

    /**
     * Count one install, deduplicated server-side by the anonymous install id.
     *
     * Blocks on a short POST, so it is only ever called from [install]'s IO context. Counting a tap
     * instead of a finished install would inflate the very numbers the trending chart ranks on.
     */
    override fun recordInstall(id: String) {
        history.remember(id)
        // The install count on that card just changed, and so did the seed the personalised shelf is built
        // from, so the memoized feed now describes the store as it was before this install.
        feedCache.clear()
        if (!configured) return
        source.recordInstall(id, installId())
    }

    // ---- accounts ------------------------------------------------------------------------------------

    override fun authProviders(): List<String> = accountState.authProviders()

    override suspend fun refreshAuthProviders(): List<String> = withContext(storeIo) {
        accountState.refreshProviders()
        accountState.authProviders()
    }

    override fun authState(): StateFlow<UiStoreAuthState> = accountState.authState()

    override fun beginSignIn(provider: String): String? = accountState.beginSignIn(provider)

    override fun completeSignIn(redirect: String) = accountState.completeSignIn(redirect)

    override fun signOut() = accountState.signOut()

    // ---- your own profile ----------------------------------------------------------------------------

    override suspend fun myProfile(): UiMyProfile? = withContext(storeIo) {
        val profile = submissionState.profile()
        // The profile is the first authenticated call the You screen makes, so it is where a session that
        // died while the app was open is noticed. Without this the screen keeps its signed-in state and
        // reports the account as unreachable, which sends the user looking at their connection.
        if (profile == null) accountState.recheckSession()
        profile?.let {
            UiMyProfile(
                handle = it.handle,
                displayName = it.displayName,
                bio = it.bio,
                location = it.location,
                linkUrl = it.linkUrl,
                avatarUrl = it.avatarUrl,
                verified = it.verified,
                followers = it.followers,
                publishedCount = it.publishedCount,
                pendingCount = it.pendingCount,
                totalInstalls = it.totalInstalls,
                totalLikes = it.totalLikes,
                averageRating = it.averageRating,
                isModerator = it.isModerator,
                moderationQueue = it.moderationQueue,
            )
        }
    }

    override suspend fun saveProfile(
        handle: String,
        displayName: String,
        bio: String?,
        location: String?,
        linkUrl: String?,
    ): String? = withContext(storeIo) {
        submissionState.saveProfile(handle, displayName, bio, location, linkUrl)
    }

    override suspend fun handleAvailable(handle: String): Boolean? =
        withContext(storeIo) { submissionState.handleAvailable(handle) }

    /**
     * The account's submissions, so a review decision that never arrived as a push is still seen.
     *
     * Readable even though this host cannot publish: an account publishes from a phone or a desktop and
     * then reads the outcome wherever it happens to be.
     */
    override suspend fun mySubmissions(): List<UiStoreSubmission> =
        withContext(storeIo) { submissionState.mine() }

    override suspend fun myPublishedItems(): List<dev.ide.ui.backend.UiPublishedItem> =
        withContext(storeIo) { submissionState.myItems() }

    override suspend fun publisherProfile(handle: String): UiPublisherProfile? = withContext(storeIo) {
        when (val result = reviewService.publisherProfile(handle)) {
            is dev.ide.store.StoreResult.Ok -> result.value?.let { p ->
                UiPublisherProfile(
                    handle = p.handle,
                    displayName = p.displayName,
                    bio = p.bio,
                    avatarUrl = p.avatarUrl,
                    location = p.location,
                    linkUrl = p.linkUrl,
                    verified = p.verified,
                    followers = p.followers,
                    following = p.following,
                    projectCount = p.projectCount,
                    totalInstalls = p.totalInstalls,
                    totalLikes = p.totalLikes,
                    averageRating = p.averageRating,
                    items = p.items.map { dev.ide.store.bridge.StoreFeedMapper.itemToUi(it) },
                )
            }
            // A failure is a page that says why, not a missing publisher: "no such publisher" is Ok(null)
            // and must not be confused with "could not load".
            is dev.ide.store.StoreResult.Unavailable ->
                UiPublisherProfile(handle, handle, error = result.reason)
            is dev.ide.store.StoreResult.Failed ->
                UiPublisherProfile(handle, handle, error = result.message)
        }
    }

    override suspend fun setFollowing(handle: String, following: Boolean): String? = withContext(storeIo) {
        when (val result = reviewService.setFollowing(handle, following)) {
            is dev.ide.store.StoreResult.Ok -> null
            is dev.ide.store.StoreResult.Unavailable -> result.reason
            is dev.ide.store.StoreResult.Failed -> result.message
        }
    }

    // ---- reviews and likes ---------------------------------------------------------------------------

    override fun reviewsAvailable(): Boolean = reviewState.available()

    override suspend fun reviews(
        itemId: String,
        sort: UiReviewSort,
        limit: Int,
        offset: Int,
    ): UiReviewPage = withContext(storeIo) { reviewState.page(itemId, sort, limit, offset) }

    override suspend fun rate(itemId: String, stars: Int, review: String?): String? = withContext(storeIo) {
        // The versions are context the reader never types but a publisher wants: which build of the IDE and
        // which release of the project the review is about.
        reviewState.rate(
            itemId = itemId,
            stars = stars,
            review = review,
            appVersion = source.appBuild?.toString(),
            itemVersion = feedCache.payload(itemId)?.version,
        )
    }

    override suspend fun deleteMyReview(itemId: String): Boolean =
        withContext(storeIo) { reviewState.deleteMine(itemId) }

    override suspend fun voteReview(itemId: String, authorId: String, helpful: Boolean): String? =
        withContext(storeIo) { reviewState.vote(itemId, authorId, helpful) }

    override suspend fun replyToReview(itemId: String, authorId: String, body: String): String? =
        withContext(storeIo) { reviewState.reply(itemId, authorId, body) }

    override suspend fun deleteReply(itemId: String, authorId: String): String? =
        withContext(storeIo) { reviewState.deleteReply(itemId, authorId) }

    override suspend fun reportReview(
        itemId: String,
        authorId: String,
        reason: UiReportReason,
        detail: String?,
    ): String? = withContext(storeIo) { reviewState.report(itemId, authorId, reason, detail) }

    override suspend fun reportItem(itemId: String, reason: UiReportReason, detail: String?): String? =
        withContext(storeIo) { reviewState.reportItem(itemId, reason, detail) }

    override suspend fun setReviewHidden(itemId: String, authorId: String, hidden: Boolean): String? =
        withContext(storeIo) { moderationState.setReviewHidden(itemId, authorId, hidden) }

    override fun isLiked(itemId: String): Boolean = likeStore.isLiked(itemId)

    override suspend fun setLike(itemId: String, liked: Boolean): String? =
        withContext(storeIo) { likeStore.setLiked(itemId, liked) }

    override suspend fun likedItems(): Set<String> = withContext(storeIo) { likeStore.reconcile() }

    // ---- moderation ----------------------------------------------------------------------------------

    override fun moderationAvailable(): Boolean = moderationState.available()

    /**
     * Whether to DRAW the moderation surface. The backend re-checks every call against `store_admins`
     * regardless of what this says, so being wrong here costs a wasted screen, not access.
     */
    override fun isModerator(): Boolean =
        moderationState.available() && accountState.authState().value.account?.isAdmin == true

    override suspend fun reviewQueue(): UiModerationQueue = withContext(storeIo) { moderationState.queue() }

    override suspend fun approveSubmission(
        versionId: String,
        note: String?,
        clearIconIfMissing: Boolean,
    ): String? = withContext(storeIo) { moderationState.approve(versionId, note, clearIconIfMissing) }

    override suspend fun rejectSubmission(versionId: String, note: String): String? =
        withContext(storeIo) { moderationState.reject(versionId, note) }

    override suspend fun openReports(): List<UiReportedContent> =
        withContext(storeIo) { moderationState.reports() }

    override suspend fun resolveReport(reportId: String, actioned: Boolean): String? =
        withContext(storeIo) { moderationState.resolveReport(reportId, actioned) }

    override suspend fun submissionImageFile(storagePath: String): String? =
        media.submissionImage(storagePath)

    // ---- internals -----------------------------------------------------------------------------------

    /**
     * The anonymous id an install is deduplicated by.
     *
     * Not an account and not a device id: it is a random value this install made up, kept so the same
     * phone installing the same project twice counts once. Created on first use.
     */
    private fun installId(): String {
        preferences.get(INSTALL_ID_PREF)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = randomInstallId()
        preferences.put(INSTALL_ID_PREF, fresh)
        return fresh
    }

    private fun randomInstallId(): String {
        val digits = "0123456789abcdef"
        return buildString { repeat(32) { append(digits.random()) } }
    }


}
