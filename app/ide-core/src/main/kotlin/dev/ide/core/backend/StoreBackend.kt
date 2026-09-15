package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.store.bridge.StoreAccounts
import dev.ide.store.bridge.StoreFeedCache
import dev.ide.store.bridge.StoreFeedMapper
import dev.ide.store.bridge.StoreMediaCache
import dev.ide.store.bridge.StoreInstallHistory
import dev.ide.store.bridge.StoreInstaller
import dev.ide.store.bridge.StoreLikes
import dev.ide.store.bridge.StoreModeration
import dev.ide.store.bridge.StoreReviews
import dev.ide.store.bridge.StoreSearchPaging
import dev.ide.store.bridge.StoreSubmissions
import dev.ide.store.bridge.storeIo
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiSignInPhase
import dev.ide.ui.backend.UiStoreAccount
import dev.ide.ui.backend.UiStoreAuthState
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [StoreService] for the home screen's Explore tab.
 *
 * Two sources, and the precedence between them is the whole design:
 *
 *  1. The **bundled** [ProjectTemplate]s. Always present, always work offline, and the only content a
 *     build with no store endpoint has.
 *  2. The **remote** catalog, via [source]. When reachable it supplies the server-driven feed — modes,
 *     shelf ordering, charts — and *overlays* the bundled items by id rather than duplicating them.
 *
 * A remote failure is never an error state here. [feed] returns null and the caller falls back to
 * [catalog]'s bundled shelves, because "we cannot reach the store" and "nobody has published anything"
 * are opposite claims and must not render the same screen.
 *
 * The last good feed is cached on disk, so a cold launch offline shows content immediately instead of a
 * spinner.
 */
internal class StoreBackend(
    private val ctx: BackendContext,
    private val source: dev.ide.store.StoreCatalogSource = dev.ide.store.StoreCatalogSource.Unconfigured,
    private val accounts: dev.ide.store.StoreAccountService = dev.ide.store.StoreAccountService.Unsupported,
    private val submissions: dev.ide.store.StoreSubmissionService =
        dev.ide.store.StoreSubmissionService.Unsupported,
    /** Told when a submission's review state changes. Null in tests and on hosts with no storage. */
    private val notifications: NotificationCenter? = null,
    private val moderationService: dev.ide.store.StoreModerationService =
        dev.ide.store.StoreModerationService.Unsupported,
    private val reviewService: dev.ide.store.StoreReviewService =
        dev.ide.store.StoreReviewService.Unsupported,
) : StoreService {

    private fun templates(): List<ProjectTemplate> =
        ctx.servicesOrNull?.projectTemplates() ?: ctx.manager?.projectTemplates() ?: emptyList()

    override fun storeAvailable(): Boolean = templates().isNotEmpty()

    override suspend fun catalog(): UiStoreCatalog = withContext(Dispatchers.Default) {
        val (sampleTemplates, starterTemplates) = templates().partition { isSample(it) }
        val starters = starterTemplates.map { toItem(it, UiStoreItemKind.Template) }
        val samples = sampleTemplates.map { toItem(it, UiStoreItemKind.Sample) }
        // Featured = the curated highlights, falling back to the first few starters so the carousel is
        // never empty on a host that contributes its own (uncurated) templates.
        val featured = starters.filter { it.featured }.ifEmpty { starters.take(3) }
        val categories = buildList {
            (starters + samples).map { it.category }.distinct().forEach(::add)
            add(CATEGORY_COMMUNITY)
        }
        val sections = listOf(
            UiStoreSection("templates", "Starter templates", "Spin up a new project from a curated scaffold", starters),
            UiStoreSection("samples", "Sample projects", "Complete, documented example apps you can build and run", samples),
            UiStoreSection("community", "Community", "Projects shared by the community", emptyList()),
        )
        UiStoreCatalog(featured = featured, categories = categories, sections = sections)
    }

    /**
     * One page of search, over the remote catalogue and the bundled templates together.
     *
     * The two sources page differently, so they are combined rather than interleaved: the bundled
     * templates are a fixed local list and ride on the first page only, ahead of the remote hits,
     * while [offset] counts purely within the remote result set. Anything else would need a cursor that
     * means two things at once.
     *
     * A bundled template the store also publishes is dropped from the bundled half when the same page
     * carries its remote row, because the remote row IS that template with the store's metadata over it
     * ([StoreFeedMapper] overlays them by id) and the two would otherwise read as two projects.
     *
     * With no remote store the bundled list is the whole result and is paged locally, so the caller's
     * scroll behaves the same either way.
     */
    override suspend fun searchPage(
        query: String,
        category: String?,
        offset: Int,
        limit: Int,
    ): dev.ide.ui.backend.UiStoreSearchPage {
        val local = withContext(Dispatchers.Default) { bundledMatches(query, category) }
        if (!source.configured()) return StoreSearchPaging.local(local, offset, limit)
        val result = withContext(storeIo) {
            source.search(
                dev.ide.store.StoreQuery(
                    text = query.trim(),
                    // Lowercased because the store's categories are slugs and the column is matched
                    // exactly: a caller that had only the bundled catalog to go on passes a display name
                    // ("Kotlin"), and an unnormalised one matches nothing at all rather than narrowing.
                    category = category?.lowercase(),
                    limit = limit,
                    offset = offset,
                ),
                source.appBuild ?: 0,
            )
        }
        return StoreSearchPaging.page(local, result, bundledBySlug(), offset, limit)
    }

    /**
     * The categories a search filters by: the store's own list, or the bundled catalog's.
     *
     * The store's slugs are what [searchPage] sends, so a tile that says "Android apps" has to carry
     * `android-apps` to filter anything. With no remote store the bundled categories are their own ids,
     * which is what the local filter matches on.
     */
    override suspend fun searchCategories(): List<dev.ide.ui.backend.UiStoreCategory> {
        if (source.configured()) {
            val remote = withContext(storeIo) { source.categories() }
            if (remote is dev.ide.store.StoreResult.Ok && remote.value.isNotEmpty()) {
                return remote.value.map { (slug, title) -> dev.ide.ui.backend.UiStoreCategory(slug, title) }
            }
        }
        return withContext(Dispatchers.Default) {
            catalog().categories.map { dev.ide.ui.backend.UiStoreCategory(it, it) }
        }
    }

    /** The bundled templates matching a query, in the order the catalog lists them. */
    private fun bundledMatches(query: String, category: String?): List<UiStoreItem> {
        val all = templates().map { toItem(it, if (isSample(it)) UiStoreItemKind.Sample else UiStoreItemKind.Template) }
        val q = query.trim().lowercase()
        return all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    /**
     * The server-driven Explore feed, or null when there is no remote store to ask.
     *
     * Order of attempts: memo, network, then the on-disk cache. A cached feed is marked
     * [UiStoreFeed.fromCache] so the UI can say so rather than presenting stale ranks as live.
     *
     * Memoized, and the memo is the point: the Store tab is a tab, so the screen leaves composition every
     * time the reader looks at their projects, so "fetch when the screen appears" was a full request per
     * visit, and the deep-link lookup made a second one of its own. The window is short, because the feed
     * is ranked content that moves; a feed that came off the disk cache is held for much less than that,
     * since the only reason it is being shown is that the network was down a moment ago.
     *
     * The lock is held across the fetch on purpose: two screens asking at once make one request and share
     * the answer, rather than racing to write the same cache file.
     */
    override suspend fun feed(seedItemId: String?, refresh: Boolean): UiStoreFeed? {
        // The caller rarely knows a seed (the Explore route has none to give), so fall back to the
        // device's own most recent install. Without this the personalized shelf is unreachable in the
        // shipping app however well the server computes it, which is what it was. Read on the store's own
        // dispatcher: it comes off disk.
        if (!source.configured()) return null
        val seed = seedItemId ?: withContext(storeIo) { history.mostRecent() }
        return feedCache.feed(seed, refresh)
    }

    /**
     * Count one install, deduplicated server-side by the anonymous install id.
     *
     * Never throws, but it does **block** on a short HTTP POST, so it has to be called from a background
     * context. [install] does the counting itself, from the IO context, which is the only place it should
     * happen: the count belongs to a finished install, not to a tap.
     */
    override fun recordInstall(id: String) {
        // Remembered before the network call and regardless of it: the seed is about this device, and a
        // store that cannot be reached is exactly when the cached feed still needs one.
        history.remember(id)
        // The install count on the card just changed, and so did the seed the personalized shelf is built
        // from, so the memoized feed is now describing the store as it was before this install.
        feedCache.clear()
        if (!source.configured()) return
        val installId = ctx.manager?.preference(INSTALL_ID_PREF) ?: return
        runCatching { source.recordInstall(id, installId) }
    }

    /**
     * The feed, its disk cache and the payload coordinates it carries.
     *
     * Shared with the iOS host: the ordering (memo, network, cache), the `fromCache` marking and the
     * "null is not an empty store" rule are the same wherever the app runs.
     */
    private val feedCache = StoreFeedCache(
        source = source,
        cachePath = { ctx.manager?.storageRoot?.let { java.io.File(it.toFile(), "store/explore-feed.json").absolutePath } },
        bundled = { bundledBySlug() },
    )

    /** Beside the feed cache it is read with, because the two are read on the same request. */
    private val history = StoreInstallHistory {
        ctx.manager?.storageRoot?.let { java.io.File(it.toFile(), "store/installed.txt").absolutePath }
    }

    // ---- likes, and the Saved shelf they back ----

    /**
     * The same preference key the UI's local list already used, so an existing device's saves survive this
     * becoming an account-backed feature instead of silently starting empty.
     */
    private val likeStore = StoreLikes(
        reviews = reviewService,
        readLocal = {
            // Both separators: the UI's former local-only list wrote this same preference newline-separated,
            // and reading one of those as a single id would turn someone's whole saved shelf into one
            // nonexistent entry. The first write normalises it to commas.
            ctx.manager?.preference(LIKES_PREF)?.split(',', '\n')?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        },
        writeLocal = { ids -> ctx.manager?.setPreference(LIKES_PREF, ids.joinToString(",")) },
    )

    override fun isLiked(itemId: String): Boolean = likeStore.isLiked(itemId)

    override suspend fun setLike(itemId: String, liked: Boolean): String? =
        withContext(storeIo) { likeStore.setLiked(itemId, liked) }

    override suspend fun likedItems(): Set<String> = withContext(storeIo) { likeStore.reconcile() }

    /**
     * The image caches, shared with the iOS host.
     *
     * Published art, a submission's private art and an avatar differ in bucket, authority and limits, and
     * the caching, the per-path lanes and the weekly avatar refresh are the same on every host.
     */
    private val media = StoreMediaCache(
        source = source,
        root = { ctx.manager?.storageRoot?.toFile()?.absolutePath },
        // By name, not by value: the moderation adapter is declared further down with the rest of the
        // moderation surface, and a property cannot read one that has not been initialised yet.
        moderation = { moderationState },
    )

    override suspend fun screenshotFile(storagePath: String): String? = media.screenshot(storagePath)

    override suspend fun avatarFile(url: String): String? = media.avatar(url)

    // ---- publisher profiles ----

    override suspend fun publisherProfile(handle: String): dev.ide.ui.backend.UiPublisherProfile? =
        withContext(storeIo) {
            when (val result = reviewService.publisherProfile(handle)) {
                is dev.ide.store.StoreResult.Ok -> result.value?.let { p ->
                    dev.ide.ui.backend.UiPublisherProfile(
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
                        items = p.items.map { StoreFeedMapper.itemToUi(it) },
                    )
                }
                // A failure is a page that says why, not a missing publisher: "no such publisher" is
                // Ok(null) and must not be confused with "could not load".
                is dev.ide.store.StoreResult.Unavailable ->
                    dev.ide.ui.backend.UiPublisherProfile(handle, handle, error = result.reason)
                is dev.ide.store.StoreResult.Failed ->
                    dev.ide.ui.backend.UiPublisherProfile(handle, handle, error = result.message)
            }
        }

    override suspend fun setFollowing(handle: String, following: Boolean): String? = withContext(storeIo) {
        when (val result = reviewService.setFollowing(handle, following)) {
            is dev.ide.store.StoreResult.Ok -> null
            is dev.ide.store.StoreResult.Unavailable -> result.reason
            is dev.ide.store.StoreResult.Failed -> result.message
        }
    }

    // ---- launch notification ----

    override fun launchNotificationEnabled(): Boolean =
        ctx.manager?.preference(LAUNCH_NOTIFY_PREF) == "true"

    /**
     * Subscribe or unsubscribe from the "projects have arrived" broadcast.
     *
     * The preference is written only when the server accepted the change, so a switch that appears on
     * cannot mean a device the backend will never notify. Without a push token there is nothing to
     * subscribe — that is a real answer, not a failure to hide.
     */
    override suspend fun setLaunchNotification(enabled: Boolean): String? = withContext(storeIo) {
        val installId = ctx.manager?.preference(INSTALL_ID_PREF)
            ?: return@withContext "This build has no install id to register"
        val token = ctx.manager?.preference(PUSH_TOKEN_PREF)
            ?: return@withContext "Push isn't set up on this device yet"

        val topics = if (enabled) listOf(LAUNCH_TOPIC) else emptyList()
        when (val result = source.setTopics(installId, token, topics)) {
            is dev.ide.store.StoreResult.Ok -> {
                ctx.manager?.setPreference(LAUNCH_NOTIFY_PREF, enabled.toString())
                null
            }
            is dev.ide.store.StoreResult.Unavailable -> result.reason
            is dev.ide.store.StoreResult.Failed -> result.message
        }
    }

    // ---- ratings and reviews ----

    private val reviewState = StoreReviews(reviewService)

    override fun reviewsAvailable(): Boolean = reviewState.available()

    override suspend fun reviews(
        itemId: String,
        sort: dev.ide.ui.backend.UiReviewSort,
        limit: Int,
        offset: Int,
    ): dev.ide.ui.backend.UiReviewPage =
        withContext(storeIo) { reviewState.page(itemId, sort, limit, offset) }

    override suspend fun rate(itemId: String, stars: Int, review: String?): String? = withContext(storeIo) {
        // The versions are context the reader never types but a publisher wants: which build of the IDE and
        // which release of the project the review is about.
        reviewState.rate(
            itemId = itemId,
            stars = stars,
            review = review,
            appVersion = source.appBuild?.toString(),
            itemVersion = versionOf(itemId),
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
        reason: dev.ide.ui.backend.UiReportReason,
        detail: String?,
    ): String? = withContext(storeIo) { reviewState.report(itemId, authorId, reason, detail) }

    override suspend fun reportItem(
        itemId: String,
        reason: dev.ide.ui.backend.UiReportReason,
        detail: String?,
    ): String? = withContext(storeIo) { reviewState.reportItem(itemId, reason, detail) }

    override suspend fun setReviewHidden(itemId: String, authorId: String, hidden: Boolean): String? =
        withContext(storeIo) { reviewState.setHidden(itemId, authorId, hidden) }

    /** The version the catalog last advertised for [itemId], when it said. */
    private fun versionOf(itemId: String): String? = feedCache.payload(itemId)?.version

    // ---- accounts ----
    //
    // Delegated: sign-in state depends on the account port and nothing else in the IDE, so it lives in a
    // class that can be built and tested without a project, an engine or a host.

    // Declared before [accountState] because that one restores a stored session as soon as it is built,
    // and the restore runs [adoptAccount], which reads the profile through this.
    private val submissionState = StoreSubmissions(
        submissions,
        // The publisher is never asked for an icon: their project already has one.
        launcherIcon = { rootPath -> ctx.manager?.launcherIconBytes(rootPath) },
    )

    private val accountState = StoreAccounts(accounts, source, onSignedIn = ::adoptAccount)

    /**
     * What a known account needs doing to it, once, on the thread that learned about it.
     *
     * The device binding is the important half. Registration for push runs anonymously (it has to: a
     * review decision has to reach a device whose user signed out days ago), so the device row carries no
     * account until this binds it, and a notification addressed to an account reaches nothing until then.
     *
     * The profile read is what gives the account a name: the publisher row holds the handle and display
     * name, the session does not, and asking for it creates one from the identity provider if this
     * account has never published.
     */
    private fun adoptAccount(account: dev.ide.store.StoreAccount): dev.ide.store.StoreAccount {
        bindPushDevice()
        // The profile half is shared with the iOS host: the fields it fills in (the handle, the name, and
        // whether this account moderates) are the same wherever the app runs, and the device binding
        // above is the only part that is this host's.
        return submissionState.adopt(account)
    }

    /**
     * Bind this device's push token to the signed-in account.
     *
     * Silent and idempotent. The token is written by the host when FCM hands it over, which can be after
     * a session is restored, so this is also called from the host once the token exists.
     */
    internal fun bindPushDevice() {
        val token = ctx.manager?.preference(PUSH_TOKEN_PREF) ?: return
        runCatching { accounts.bindPushDevice(token) }
    }

    override fun authProviders(): List<String> = accountState.authProviders()

    override fun authState(): kotlinx.coroutines.flow.StateFlow<UiStoreAuthState> = accountState.authState()

    /**
     * Ask the backend which providers to offer, then report them.
     *
     * Called when a sign-in surface opens rather than at startup: it is the only moment the answer matters,
     * and it means a provider enabled server-side appears on the next sheet open without an app release.
     */
    override suspend fun refreshAuthProviders(): List<String> = withContext(storeIo) {
        accountState.refreshProviders()
        accountState.authProviders()
    }

    override fun beginSignIn(provider: String): String? = accountState.beginSignIn(provider)

    override fun completeSignIn(redirect: String) = accountState.completeSignIn(redirect)

    override fun signOut() = accountState.signOut()

    // ---- submitting ----

    override fun submissionsAvailable(): Boolean = submissionState.available()

    override suspend fun packProject(rootPath: String): dev.ide.ui.backend.UiPackagedProject? =
        withContext(storeIo) { submissionState.pack(rootPath) }

    override suspend fun packFailure(rootPath: String): String? = submissionState.packError(rootPath)

    override suspend fun submitCategories(): List<Pair<String, String>> = withContext(storeIo) {
        when (val result = source.categories()) {
            is dev.ide.store.StoreResult.Ok -> result.value
            // No list means the form cannot offer a valid slug, so it offers none rather than guessing.
            else -> emptyList()
        }
    }

    override suspend fun submit(
        draft: dev.ide.ui.backend.UiSubmissionDraft,
        packaged: dev.ide.ui.backend.UiPackagedProject,
    ): dev.ide.ui.backend.UiSubmitResult = withContext(storeIo) {
        val result = submissionState.submit(draft, packaged)
        result.submission?.let { sub ->
            // Remember which listing this project belongs to, so the next publish of it offers an update
            // rather than a second listing. Here because this is the only place that holds both halves:
            // the screen knows the project, the backend knows the slug it was published under.
            ctx.manager?.setPreference(listingKey(packaged.rootPath), sub.itemId)
            // Keyed on the item and version, so re-submitting the same version updates the entry rather
            // than stacking a second one, and a later review decision replaces this with its outcome.
            notifications?.post(
                kind = dev.ide.ui.backend.UiNotificationKind.STORE_SUBMISSION,
                title = "${draft.title} ${sub.version} is in review",
                body = "A moderator reviews every submission. Nothing is public until it is approved.",
                target = dev.ide.ui.backend.UiNotificationTarget.Submissions,
                key = "submission:${sub.itemId}:${sub.version}",
            )
        }
        result
    }

    /**
     * The account's submissions, and a notification for anything whose state changed since last time.
     *
     * The change has to be noticed here because there is nothing to push it: a review happens on someone
     * else's schedule, days later, with the app closed. Comparing on each read is what turns that into
     * something the user finds out about at all.
     *
     * Only the newest submission per listing can produce one. Reading this list is what happens
     * immediately after publishing an update, and the approval of the version that update replaces is
     * usually being noticed for the first time right then: "your app is live" arriving one second after
     * sending a new version for review reads as a decision on that version, which it is not. The state is
     * still recorded, so the same decision cannot surface later either.
     */
    override suspend fun mySubmissions(): List<dev.ide.ui.backend.UiStoreSubmission> = withContext(storeIo) {
        val current = submissionState.mine()
        val newest = StoreSubmissions.newestPerItem(current)
        current.forEach { sub ->
            val seenKey = "store.submission.seen.${sub.itemId}.${sub.version}"
            // Blank is "never seen": the preference store can only write a key, so deleting a submission
            // blanks the one it left behind rather than removing it.
            val previous = ctx.manager?.preference(seenKey)?.takeIf { it.isNotEmpty() }
            if (previous == sub.status.name) return@forEach
            ctx.manager?.setPreference(seenKey, sub.status.name)
            // The first sighting of a submission this device did not create is not news; only a change
            // from a state we had already recorded is.
            if (previous == null) return@forEach
            // A decision the publisher has already answered by sending a newer version is not news either.
            if (newest[sub.itemId] != sub.version) return@forEach
            notifications?.post(
                kind = dev.ide.ui.backend.UiNotificationKind.STORE_SUBMISSION,
                title = submissionHeadline(sub),
                body = sub.note,
                target = dev.ide.ui.backend.UiNotificationTarget.Submissions,
                key = "submission:${sub.itemId}:${sub.version}",
            )
        }
        current
    }

    override suspend fun myPublishedItems(): List<dev.ide.ui.backend.UiPublishedItem> =
        withContext(storeIo) { submissionState.myItems() }

    override suspend fun listingSlugForProject(rootPath: String): String? = withContext(storeIo) {
        ctx.manager?.preference(listingKey(rootPath))?.takeIf { it.isNotBlank() }
    }

    /**
     * Where a project's listing slug is remembered.
     *
     * The path itself is the key. `Properties` escapes what it has to on the way out and reverses it on
     * the way in, so a path with spaces or a colon in it round-trips, and a readable preferences file is
     * worth more here than a hash nobody can trace back to a project.
     */
    private fun listingKey(rootPath: String) = "store.project.listing.$rootPath"

    // ---- your own profile ----

    override suspend fun myProfile(): dev.ide.ui.backend.UiMyProfile? = withContext(storeIo) {
        val profile = submissionState.profile()
        // The profile is the first authenticated call the You screen makes, so it is where a session that
        // died while the app was open is noticed. Without this the screen keeps its signed-in state and
        // reports the account as unreachable, which sends the user looking at their connection.
        if (profile == null) accountState.recheckSession()
        profile?.also(::noticeVerification)?.let {
            dev.ide.ui.backend.UiMyProfile(
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

    /**
     * Raise a notification the first time a profile read comes back verified.
     *
     * The push is the primary path and this is the same fallback [mySubmissions] is for a review decision:
     * verification is granted days later by someone else, and a device that was unreachable when the row
     * was claimed would otherwise never hear about it.
     *
     * Only a change from a state this device had already recorded counts. A first sighting is not news:
     * an account that was verified before this install existed did not just become verified.
     */
    private fun noticeVerification(profile: dev.ide.store.StorePublisherProfile) {
        // Keyed by account, matching the dedupe key the backend's own push carries, so a user who got both
        // sees one entry rather than the same sentence twice.
        val account = accounts.current()?.userId ?: profile.handle
        val seenKey = "$VERIFIED_SEEN_PREF$account"
        val previous = ctx.manager?.preference(seenKey)
        if (previous == profile.verified.toString()) return
        ctx.manager?.setPreference(seenKey, profile.verified.toString())
        if (previous != "false" || !profile.verified) return
        notifications?.post(
            kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM,
            title = "You are verified",
            body = "Your projects now carry the verified tick in the store.",
            target = dev.ide.ui.backend.UiNotificationTarget.Screen("You"),
            key = "publisher:verified:$account",
        )
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

    override suspend fun handleAvailable(handle: String): Boolean? = withContext(storeIo) {
        submissionState.handleAvailable(handle)
    }

    /**
     * What the change actually means, in the words a submitter would use.
     *
     * The version is named, as it is in the backend's own push. A publisher who has sent three versions
     * has three things this sentence could be about, and the one time it matters most is when a decision
     * about an older one arrives late.
     */
    private fun submissionHeadline(sub: dev.ide.ui.backend.UiStoreSubmission): String {
        val what = "${sub.projectName} ${sub.version}"
        return when (sub.status) {
            dev.ide.ui.backend.UiSubmissionStatus.PUBLISHED -> "$what is live in the store"
            dev.ide.ui.backend.UiSubmissionStatus.REJECTED -> "$what was not accepted"
            dev.ide.ui.backend.UiSubmissionStatus.CHANGES_REQUESTED -> "$what needs changes"
            dev.ide.ui.backend.UiSubmissionStatus.BUILDING -> "$what is being built"
            dev.ide.ui.backend.UiSubmissionStatus.SUBMITTED -> "$what is in review"
        }
    }

    override suspend fun withdrawSubmission(itemId: String, version: String): Boolean =
        withContext(storeIo) { submissionState.withdraw(itemId, version) }

    /**
     * Delete a rejected or withdrawn submission.
     *
     * The remembered state goes with it, so a later submission of the same version number is judged on its
     * own: without that, resubmitting `1.0.1` after deleting a rejected `1.0.1` would compare against the
     * rejection and announce a decision the publisher never got.
     */
    override suspend fun deleteSubmission(itemId: String, version: String): String? = withContext(storeIo) {
        submissionState.delete(itemId, version).also { error ->
            if (error == null) ctx.manager?.setPreference("store.submission.seen.$itemId.$version", "")
        }
    }

    // ---- moderation ----
    //
    // Delegated like the rest: [StoreModeration] depends on the moderation port alone, so which proposed
    // edits count as changes is testable without a project, an engine or a host.

    private val moderationState = StoreModeration(moderationService)

    override fun moderationAvailable(): Boolean = moderationState.available()

    /**
     * Whether the signed-in account moderates.
     *
     * Read during composition, so it answers from the session the sign-in already adopted rather than
     * asking anything: [adoptAccount] put the profile's answer on the account. That also means it is false
     * for the moment between a session being restored and its profile landing, which is correct — an entry
     * point that appeared before the app knew who was signed in would be a guess.
     */
    override fun isModerator(): Boolean =
        moderationState.available() && accountState.authState().value.account?.isAdmin == true

    override suspend fun reviewQueue(): dev.ide.ui.backend.UiModerationQueue =
        withContext(storeIo) { moderationState.queue() }

    override suspend fun approveSubmission(
        versionId: String,
        note: String?,
        clearIconIfMissing: Boolean,
    ): String? = withContext(storeIo) { moderationState.approve(versionId, note, clearIconIfMissing) }

    override suspend fun rejectSubmission(versionId: String, note: String): String? =
        withContext(storeIo) { moderationState.reject(versionId, note) }

    override suspend fun openReports(): List<dev.ide.ui.backend.UiReportedContent> =
        withContext(storeIo) { moderationState.reports() }

    override suspend fun resolveReport(reportId: String, actioned: Boolean): String? =
        withContext(storeIo) { moderationState.resolveReport(reportId, actioned) }

    override suspend fun submissionImageFile(storagePath: String): String? =
        media.submissionImage(storagePath)

    /**
     * The bundled catalog keyed by the id a remote row would use, for the overlay.
     *
     * Keys are the bare template id (`sample-calculator`), matching `store_items.slug`, NOT the
     * `sample:`/`template:`-prefixed id the bundled [catalog] emits for its own rows.
     */
    private fun bundledBySlug(): Map<String, UiStoreItem> =
        templates().associate { t ->
            t.id.value to toItem(t, if (isSample(t)) UiStoreItemKind.Sample else UiStoreItemKind.Template)
        }

    /**
     * Download, verify and unpack a community project into the workspace.
     *
     * Templates and samples never reach here: the UI routes anything with a `templateId` through the
     * Create-Project flow, because that scaffold is already on the device.
     *
     * Counting happens last, and only on success. Counting a tap instead would inflate the very numbers
     * the trending chart ranks on, with the items that fail to install ranking highest.
     */
    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        val payload = feedCache.payload(id)
            ?: return UiStoreInstallResult(false, "That project has nothing to download yet")
        val projectsRoot = ctx.manager?.projectsRoot?.toFile()
            ?: return UiStoreInstallResult(false, "There is no projects folder to install into")

        val manager = ctx.manager
        // Everything here, the counting included, stays on the IO context: recordInstall performs a
        // blocking POST, and this is called from a UI scope, so counting outside would run it on the main
        // thread — where it throws, gets swallowed, and the install silently never counts.
        //
        // ProjectManager.list() rescans on every call, so the unpacked project is already visible to the
        // picker; the UI only has to be told to ask again (CodeAssistAppState.refreshProjects).
        return withContext(storeIo) {
            val result = installer.install(
                payload = payload,
                projectsRoot = projectsRoot.absolutePath,
                adopt = { dir ->
                    val ok = manager?.adoptProjectInPlace(java.nio.file.Path.of(dir)) ?: false
                    if (ok) null else "That download isn't a project CodeAssist can open"
                },
                onProgress = { p -> progressState.value = progressState.value + (p.itemId to p) },
            )
            // Remembered with the directory it landed in, so the next launch still knows this item is on
            // the device and where. That is what turns its button into an Open that has something to open.
            if (result.success) {
                history.remember(id, result.rootPath)
                recordInstall(id)
            }
            result
        }
    }

    override fun installProgress(): kotlinx.coroutines.flow.StateFlow<Map<String, UiInstallProgress>> =
        progressState

    /**
     * Live install progress, seeded with what this device already has.
     *
     * Seeded, rather than starting empty, because "installed" is a fact about the workspace and not about
     * this session: a store row for a project already on disk has to offer Open after a relaunch too. The
     * ledger drops entries whose directory is gone, so a deleted project goes back to offering Install.
     *
     * Read once, lazily, off a file of at most fifty short lines: the same shape of read `isLiked` already
     * does during composition.
     */
    private val progressState by lazy {
        val installed = history.installedPaths()
            .mapValues { (id, path) -> UiInstallProgress(id, UiInstallState.INSTALLED, 1f, rootPath = path) }
        kotlinx.coroutines.flow.MutableStateFlow(installed)
    }

    private val installer = StoreInstaller(source)

    /**
     * Payload coordinates from the last feed, so an install needs no second round trip.
     *
     * Taken from the catalog row and never from the archive: the size and hash are what the server
     * promised, and checking the download against them is the point.
     */
    /** Sample projects are registered as `sample-`-prefixed templates so they share the create path but list
     *  under "Sample projects" rather than "Starter templates". */
    private fun isSample(t: ProjectTemplate): Boolean = t.id.value.startsWith("sample-")

    private fun matchesCategory(item: UiStoreItem, category: String?): Boolean = when (category) {
        null -> true
        CATEGORY_COMMUNITY -> item.kind == UiStoreItemKind.Community
        else -> item.category.equals(category, ignoreCase = true)
    }

    private fun matchesQuery(item: UiStoreItem, q: String): Boolean = q.isEmpty() ||
        item.title.lowercase().contains(q) ||
        item.summary.lowercase().contains(q) ||
        item.category.lowercase().contains(q) ||
        item.tags.any { it.lowercase().contains(q) }

    private fun toItem(t: ProjectTemplate, kind: UiStoreItemKind): UiStoreItem {
        val meta = CURATION[t.id.value]
        return UiStoreItem(
            id = "${if (kind == UiStoreItemKind.Sample) "sample" else "template"}:${t.id.value}",
            kind = kind,
            title = t.displayName,
            summary = t.description,
            category = t.category.displayName,
            iconId = t.iconId,
            tags = meta?.tags ?: listOf(t.category.displayName),
            featured = meta?.featured ?: false,
            accentColor = meta?.accent,
            installs = meta?.installs ?: -1,
            templateId = t.id.value,
            available = true,
            highlights = meta?.highlights ?: emptyList(),
            language = meta?.language ?: t.category.displayName.takeIf { it == "Java" || it == "Kotlin" },
            previewKey = t.id.value.takeIf { it in PREVIEW_SAMPLES },
        )
    }

    /** Per-template curation layered over the template's own metadata (featured flag, brand accent, tags,
     *  a soft usage count, "what you get" highlights, and the primary language) — all shown in the store. */
    private data class Curation(
        val featured: Boolean = false,
        val accent: Long? = null,
        val tags: List<String> = emptyList(),
        val installs: Int = -1,
        val highlights: List<String> = emptyList(),
        val language: String? = null,
    )

    private companion object {
        const val CATEGORY_COMMUNITY = "Community"

        /** The anonymous install id the analytics service already generates; reused for install dedupe. */
        const val INSTALL_ID_PREF = "analytics.install.id"

        /** Whether this install asked to hear about new projects. Mirrors the server-side topic. */
        const val LAUNCH_NOTIFY_PREF = "store.notify.launch"

        /** Where the host parks the FCM token; the engine reads it to manage subscriptions. */
        const val PUSH_TOKEN_PREF = "store.push.token"

        /** The broadcast topic name, matched by `store_push_claim`'s topic join. */
        const val LAUNCH_TOPIC = "store-launch"

        /** Whether this account was verified the last time its profile was read, by account id. */
        const val VERIFIED_SEEN_PREF = "store.profile.verified."

        /** Shared with the UI's former local-only list, so existing saves carry over. */
        const val LIKES_PREF = "store.favorites"

        /** Sample template ids that ship a built-in preview screenshot ([UiStoreItem.previewKey]). */
        val PREVIEW_SAMPLES = setOf("sample-snake", "sample-tictactoe", "sample-memory", "sample-2048")

        val CURATION: Map<String, Curation> = mapOf(
            "compose-app" to Curation(
                featured = true, accent = 0xFF3FBDD9, tags = listOf("Jetpack Compose", "Material 3", "Kotlin"), installs = 12800, language = "Kotlin",
                highlights = listOf("Jetpack Compose UI", "Material 3 theming", "A ready-to-run Activity", "Builds to an installable APK"),
            ),
            "android-material-you" to Curation(
                featured = true, accent = 0xFFB487F7, tags = listOf("Material You", "Views", "Kotlin"), installs = 6400, language = "Kotlin",
                highlights = listOf("Material You (dynamic color)", "XML layouts + Views", "A ready-to-run Activity"),
            ),
            "android-app" to Curation(
                featured = true, accent = 0xFF3DDC84, tags = listOf("Android", "Activity", "XML layouts"), installs = 21500, language = "Kotlin",
                highlights = listOf("An Activity + XML layout", "Resources wired up", "Builds to an installable APK"),
            ),
            "kotlin-console" to Curation(tags = listOf("Kotlin", "Console"), installs = 9300, language = "Kotlin", highlights = listOf("A top-level main()", "Full editor intelligence", "Runs in the console")),
            "java-console" to Curation(tags = listOf("Java", "Console"), installs = 8100, language = "Java", highlights = listOf("A main() entry point", "Full editor intelligence", "Runs in the console")),
            "android-library" to Curation(tags = listOf("Android", "AAR"), installs = 3200, language = "Kotlin", highlights = listOf("A reusable android-lib module", "Publishes as an AAR")),
            // Sample projects (complete, runnable examples).
            "sample-calculator" to Curation(
                accent = 0xFFF89820, tags = listOf("Java", "Parser", "REPL"), installs = 4200, language = "Java",
                highlights = listOf("An interactive read-eval-print loop", "A recursive-descent expression parser", "Operator precedence + parentheses", "No dependencies, thoroughly documented"),
            ),
            "sample-notes" to Curation(
                accent = 0xFF7F52FF, tags = listOf("Kotlin", "CRUD", "CLI"), installs = 5600, language = "Kotlin",
                highlights = listOf("A command loop: add/list/done/rm/find", "Reads input a line at a time", "Model split from view (testable)", "No dependencies, thoroughly documented"),
            ),
            "sample-weather" to Curation(
                accent = 0xFF3FBDD9, tags = listOf("Kotlin", "CLI", "Data"), installs = 3100, language = "Kotlin",
                highlights = listOf("Type a city, get its forecast", "Reads input in a loop", "Shows how to swap in a real API", "No dependencies"),
            ),
            // Jetpack Compose sample games (complete, runnable Compose apps).
            "sample-snake" to Curation(
                accent = 0xFF00E676, tags = listOf("Jetpack Compose", "Game", "Canvas"), installs = 7400, language = "Kotlin",
                highlights = listOf("Canvas rendering + a game loop", "Swipe gesture controls", "Live score + high score", "A neon Material 3 look"),
            ),
            "sample-tictactoe" to Curation(
                accent = 0xFF22D3EE, tags = listOf("Jetpack Compose", "Game", "Material 3"), installs = 5200, language = "Kotlin",
                highlights = listOf("Two-player game logic", "Animated marks + winning-line highlight", "State hoisting done right", "Material 3 theming"),
            ),
            "sample-memory" to Curation(
                accent = 0xFF7C3AED, tags = listOf("Jetpack Compose", "Game", "Animation"), installs = 4300, language = "Kotlin",
                highlights = listOf("3D card-flip animation", "Match logic + move/timer counters", "A colorful gradient UI", "A great intro to Compose animation"),
            ),
            "sample-2048" to Curation(
                accent = 0xFFEDC22E, tags = listOf("Jetpack Compose", "Game", "Puzzle"), installs = 6100, language = "Kotlin",
                highlights = listOf("Swipe-to-merge tile logic", "Animated tile colors", "Score + best tracking", "Clean grid-state modeling"),
            ),
        )
    }
}
