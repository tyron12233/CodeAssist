package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
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

    override suspend fun search(query: String, category: String?): List<UiStoreItem> = withContext(Dispatchers.Default) {
        val all = templates().map { toItem(it, if (isSample(it)) UiStoreItemKind.Sample else UiStoreItemKind.Template) }
        val q = query.trim().lowercase()
        all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    /**
     * The server-driven Explore feed, or null when there is no remote store to ask.
     *
     * Order of attempts: network, then the on-disk cache. A cached feed is marked [UiStoreFeed.fromCache]
     * so the UI can say so rather than presenting stale ranks as live.
     */
    override suspend fun feed(seedItemId: String?): UiStoreFeed? {
        if (!source.configured()) return null
        return withContext(dev.ide.core.backend.storeIo) {
            val bundled = bundledBySlug()
            // The caller rarely knows a seed — the Explore route has none to give — so fall back to the
            // device's own most recent install. Without this the personalized shelf is unreachable in the
            // shipping app however well the server computes it, which is what it was.
            val seed = seedItemId ?: history.mostRecent()
            when (val result = source.feedDocument(seed)) {
                is dev.ide.store.StoreResult.Ok -> {
                    val parsed = dev.ide.store.impl.StoreFeedParser.parse(result.value)
                    if (parsed == null) {
                        // A response we cannot read is not evidence about the store, so behave as offline.
                        cachedFeed(bundled)
                    } else {
                        // Cache the exact bytes that were just rendered, so the cached copy cannot drift.
                        writeCache(result.value)
                        // Remember the payload coordinates so install() needs no second round trip.
                        rememberPayloads(parsed)
                        StoreFeedMapper.toUi(parsed, bundled)
                    }
                }
                // Offline or a server hiccup: fall back to whatever was last seen.
                else -> cachedFeed(bundled)
            }
        }
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
        if (!source.configured()) return
        val installId = ctx.manager?.preference(INSTALL_ID_PREF) ?: return
        runCatching { source.recordInstall(id, installId) }
    }

    /** Beside the feed cache it is read with, because the two are read on the same request. */
    private val history = StoreInstallHistory {
        ctx.manager?.storageRoot?.let { java.io.File(it.toFile(), "store/installed.txt") }
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
     * Cache a remote screenshot to disk and return its local path, or null.
     *
     * The gallery decodes files, so remote images have to become files. Caching them under the store's own
     * directory keyed by the storage path means a revisit costs nothing and the same image shared by two
     * surfaces is fetched once. Published screenshot keys are version-scoped (`slug/version/shot-0.png`,
     * written by approval), so the bytes at a path never change and a cached file cannot go stale.
     */
    override suspend fun screenshotFile(storagePath: String): String? = withContext(storeIo) {
        val root = ctx.manager?.storageRoot?.toFile() ?: return@withContext null
        val cached = java.io.File(root, "store/media/${storagePath.replace('/', '_')}")
        if (cached.isFile && cached.length() > 0) return@withContext cached.absolutePath
        when (source.downloadMedia(storagePath, cached)) {
            is dev.ide.store.StoreResult.Ok -> cached.absolutePath
            // A screenshot that will not load is not worth an error surface: the gallery simply shows the
            // ones that did.
            else -> null
        }
    }

    /**
     * Cache an avatar to disk and return its local path, or null.
     *
     * The avatar lives wherever the identity provider serves it, so this is a plain HTTPS GET rather than
     * a bucket download. Three limits, because the URL is not the store's: https only, a size cap, and a
     * short timeout.
     */
    override suspend fun avatarFile(url: String): String? = withContext(storeIo) {
        if (!url.startsWith("https://")) return@withContext null
        val root = ctx.manager?.storageRoot?.toFile() ?: return@withContext null
        val cached = java.io.File(root, "store/avatars/${url.hashCode().toUInt().toString(16)}.img")
        // Re-fetched once a week even though the name has not changed: a provider serves a new picture from
        // the same URL, so caching on the URL alone would pin the first face forever.
        val fresh = System.currentTimeMillis() - cached.lastModified() < AVATAR_CACHE_MS
        if (cached.isFile && cached.length() > 0 && fresh) return@withContext cached.absolutePath
        runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                return@runCatching null
            }
            cached.parentFile?.mkdirs()
            var written = 0L
            conn.inputStream.use { input ->
                cached.outputStream().buffered().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        written += n
                        // An avatar is a small square. Anything past this is not one, and the file is
                        // dropped rather than kept and decoded.
                        if (written > MAX_AVATAR_BYTES) return@runCatching null
                        out.write(buffer, 0, n)
                    }
                }
            }
            cached.absolutePath
        }.getOrNull().also { if (it == null) cached.delete() }
    }

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
    ): dev.ide.ui.backend.UiReviewPage = withContext(storeIo) { reviewState.page(itemId, sort, limit) }

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
    private fun versionOf(itemId: String): String? = payloads[itemId]?.version

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
        val profile = submissionState.profile() ?: return account
        return account.copy(
            handle = profile.handle,
            displayName = profile.displayName,
            avatarUrl = profile.avatarUrl ?: account.avatarUrl,
            verified = profile.verified,
            // The profile read is the only round trip that already happens on sign-in, and it now answers
            // this too, so the app knows whether to offer moderation without a second call.
            isAdmin = profile.isModerator,
        )
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
        submissionState.profile()?.also(::noticeVerification)?.let {
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

    /**
     * Put a submission on disk as a project, so a reviewer can build and run it.
     *
     * The adoption is the install path's: `adoptProjectInPlace` builds the model without opening, because
     * what opens it is the caller — this runs on the IO context and opening is the UI's move.
     */
    override suspend fun checkOutSubmission(versionId: String): dev.ide.ui.backend.UiSubmissionCheckout =
        withContext(storeIo) {
            val projectsRoot = ctx.manager?.projectsRoot?.toFile()
                ?: return@withContext dev.ide.ui.backend.UiSubmissionCheckout(
                    message = "There is no projects folder to unpack into",
                )
            val manager = ctx.manager
            val result = moderationState.checkOut(versionId, projectsRoot) { dir ->
                manager?.adoptProjectInPlace(dir.toPath()) ?: false
            }
            dev.ide.ui.backend.UiSubmissionCheckout(result.rootPath, result.message)
        }

    override suspend fun openReports(): List<dev.ide.ui.backend.UiReportedContent> =
        withContext(storeIo) { moderationState.reports() }

    override suspend fun resolveReport(reportId: String, actioned: Boolean): String? =
        withContext(storeIo) { moderationState.resolveReport(reportId, actioned) }

    /**
     * A submission's screenshot as a local file, cached like a published one.
     *
     * Kept apart from [screenshotFile] because the bucket is different and so is the authority: this one
     * is in the PRIVATE uploads bucket and needs the moderator's session, which the anonymous media
     * download has no way to present. The cache directory is separate too, so an image from a submission
     * that is later refused is not sitting under the same prefix as published art.
     */
    override suspend fun submissionImageFile(storagePath: String): String? = withContext(storeIo) {
        val root = ctx.manager?.storageRoot?.toFile() ?: return@withContext null
        val cached = java.io.File(root, "store/review/${storagePath.replace('/', '_')}")
        if (cached.isFile && cached.length() > 0) return@withContext cached.absolutePath
        if (moderationState.downloadSubmissionImage(storagePath, cached)) cached.absolutePath else null
    }

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
     * The last good feed from disk.
     *
     * Returns null rather than an empty feed when nothing is cached: an empty feed would render the
     * "nobody has published anything" screen, which is a claim about the store rather than about the
     * network.
     */
    private fun cachedFeed(bundled: Map<String, UiStoreItem>): UiStoreFeed? {
        val file = cacheFile() ?: return null
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = dev.ide.store.impl.StoreFeedParser.parse(raw) ?: return null
        // The cached rows are as good a source of payload coordinates as the live ones, and the sha256 is
        // still checked against the bytes: without this, an install from a cached feed would fail claiming
        // the item has nothing to download.
        rememberPayloads(parsed)
        return StoreFeedMapper.toUi(parsed, bundled).copy(fromCache = true)
    }

    private fun cacheFile(): java.io.File? =
        ctx.manager?.storageRoot?.let { java.io.File(it.toFile(), "store/explore-feed.json") }

    /** Best effort: a cache that cannot be written must not fail the fetch that produced it. */
    private fun writeCache(document: String) {
        val file = cacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(document)
        }
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
        val payload = payloads[id]
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
                projectsRoot = projectsRoot,
                adopt = { dir ->
                    val ok = manager?.adoptProjectInPlace(dir.toPath()) ?: false
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
    private val payloads = java.util.concurrent.ConcurrentHashMap<String, StoreInstaller.Payload>()

    private fun rememberPayloads(feed: dev.ide.store.StoreFeed) {
        feed.allItems.forEach { item ->
            val path = item.storagePath ?: return@forEach
            payloads[item.id] =
                StoreInstaller.Payload(item.id, path, item.sha256, item.sizeBytes, item.title, item.version)
        }
    }



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

        /** An avatar is a small square; a response larger than this is not one. */
        const val MAX_AVATAR_BYTES = 2L * 1024 * 1024

        /** How long a cached avatar is trusted before the URL is asked again. */
        const val AVATAR_CACHE_MS = 7L * 24 * 60 * 60 * 1000

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
