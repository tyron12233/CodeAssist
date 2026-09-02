package dev.ide.ui.backend

/**
 * The Explore feed, at the UI seam.
 *
 * Mirrors the engine's `dev.ide.store.StoreFeed` rather than reusing it, for the same reason every other
 * `Ui*` type exists: `ide-ui` must not depend on the store transport, so a build with no store at all
 * still compiles and renders. The mapping in `StoreBackend` is mechanical.
 *
 * The server owns which sections exist, in what order, and which mode the store is in; the UI owns
 * rendering. Unknown section types never arrive here — they are dropped during parsing.
 */

/** Which of the three Explore layouts to draw. */
enum class UiStoreMode { EMPTY, SPARSE, POPULATED }

/** The thresholds at which each shelf switches on. Server-controlled; these are only absent-field defaults. */
data class UiStoreThresholds(
    val charts: Int = 10,
    val collections: Int = 12,
    val recommendations: Int = 8,
)

/**
 * What the store looks like right now.
 *
 * [publishedProjectCount] drives the header badge — the honest disclosure that stops a three-item page
 * from reading as a bug.
 */
data class UiStoreState(
    val publishedProjectCount: Int = 0,
    /** False on an instance with publishing locked down: the pitch is hidden, the catalogue stays. */
    val acceptingSubmissions: Boolean = true,
    val thresholds: UiStoreThresholds = UiStoreThresholds(),
)

/** One entry on a ranked chart. */
data class UiChartEntry(
    val rank: Int,
    /** Null for a new entrant — the design renders an arrow with no number, so this must stay nullable. */
    val previousRank: Int?,
    val item: UiStoreItem,
) {
    /** Positions gained (positive), lost (negative), 0 when flat, null for a new entrant. */
    val delta: Int? get() = previousRank?.let { it - rank }
}

/**
 * One tab of the Top charts shelf.
 *
 * [metric] names what the tab ranks on. It is server-sent rather than derived from [key] so a tab added
 * to the store does not silently label itself with a count it is not ordered by.
 */
data class UiChartTab(
    val key: String,
    val label: String,
    val entries: List<UiChartEntry>,
    val metric: String? = null,
)

/** An editorial shelf. The title is an outcome, never a category. */
data class UiStoreCollection(
    val id: String,
    val eyebrow: String,
    val title: String,
    val iconId: String? = null,
    val projectCount: Int = 0,
    /** Up to three glyph ids for the overlapping icon stack. */
    val previewIconIds: List<String> = emptyList(),
)

data class UiStorePublisher(
    val id: String,
    val handle: String? = null,
    val name: String,
    val bio: String? = null,
    val verified: Boolean = false,
    val projectCount: Int = 0,
    val installCount: Int = 0,
    val rating: Float? = null,
    val followerCount: Int = 0,
)

/**
 * One ghost shelf's progress toward switching on.
 *
 * [title] and [note] come from the shelf being gated, so a shelf added server-side shows its own copy
 * instead of a generic card. The screen keeps hand-written copy for the shelves it knows about.
 */
data class UiGhostShelf(
    val key: String,
    val have: Int,
    val need: Int,
    val title: String? = null,
    val note: String? = null,
)

/**
 * How a shelf is drawn.
 *
 * Chosen per shelf by the server, which is what lets a new shelf arrive with a new look and no app
 * release. Anything unrecognised has already become [ROWS] by the time it reaches here.
 */
enum class UiShelfLayout { ROWS, CAROUSEL, POSTER, GRID, RANK }

/** A section of the feed. Sealed so the screen's `when` is exhaustive over what it can draw. */
sealed interface UiFeedSection {
    val id: String

    data class Ticker(override val id: String, val terms: List<String>) : UiFeedSection
    data class Featured(override val id: String, val items: List<UiStoreItem>) : UiFeedSection

    data class Charts(
        override val id: String,
        val tabs: List<UiChartTab>,
        val computedAt: String? = null,
        val title: String? = null,
    ) : UiFeedSection

    data class Collections(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val items: List<UiStoreCollection>,
    ) : UiFeedSection

    data class Categories(
        override val id: String,
        val title: String,
        val categories: List<String>,
        /** Project count per category, keyed by name; absent means unknown rather than zero. */
        val counts: Map<String, Int> = emptyMap(),
    ) : UiFeedSection

    data class Personalized(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val items: List<UiStoreItem>,
    ) : UiFeedSection

    data class Spotlight(override val id: String, val publisher: UiStorePublisher) : UiFeedSection

    /**
     * A merchandised shelf: a title and a list of projects, drawn the way [layout] says.
     *
     * One section type covers every server-defined shelf, so adding "Most liked" or a seasonal promotion
     * needs a row in the store's shelf registry and nothing here.
     */
    data class Shelf(
        override val id: String,
        val title: String?,
        val subtitle: String? = null,
        val eyebrow: String? = null,
        val iconId: String? = null,
        val layout: UiShelfLayout = UiShelfLayout.ROWS,
        val items: List<UiStoreItem>,
    ) : UiFeedSection

    /** The sparse state's single list: everything, newest first. */
    data class Catalogue(
        override val id: String,
        val title: String,
        val items: List<UiStoreItem>,
    ) : UiFeedSection

    /** The publish argument. [projectCount] drives the interpolated headline and its ordinal. */
    data class PublishPitch(override val id: String, val projectCount: Int) : UiFeedSection

    /** The offline scaffolds. Rows come from the bundled templates, not the feed. */
    data class Bundled(override val id: String, val items: List<UiStoreItem> = emptyList()) : UiFeedSection

    data class GhostShelves(override val id: String, val shelves: List<UiGhostShelf>) : UiFeedSection
}

/** The whole feed, in server order, already filtered to sections this build can render. */
data class UiStoreFeed(
    val mode: UiStoreMode = UiStoreMode.POPULATED,
    val state: UiStoreState = UiStoreState(),
    val sections: List<UiFeedSection> = emptyList(),
    /** True when this came from the on-disk cache rather than the network — the UI says so. */
    val fromCache: Boolean = false,
) {
    /**
     * Every item mentioned anywhere in the feed, deduplicated by id.
     *
     * For looking one up by id — following a notification, for instance — where which shelf it happened to
     * appear on is irrelevant.
     */
    val allItems: List<UiStoreItem>
        get() = sections.flatMap { section ->
            when (section) {
                is UiFeedSection.Featured -> section.items
                is UiFeedSection.Shelf -> section.items
                is UiFeedSection.Catalogue -> section.items
                is UiFeedSection.Personalized -> section.items
                is UiFeedSection.Charts -> section.tabs.flatMap { tab -> tab.entries.map { it.item } }
                is UiFeedSection.Bundled -> section.items
                else -> emptyList()
            }
        }.distinctBy { it.id }
}

/**
 * Where a submission stands.
 *
 * The full set the client must render, per the empty-state handoff. Each carries a different line and a
 * different action, so collapsing any two of them would hide something the submitter needs to act on.
 */
enum class UiSubmissionStatus {
    /** In review, waiting for a human. */
    SUBMITTED,

    /** Being built on a clean machine. */
    BUILDING,

    /** The reviewer wants changes; the notes are the point. */
    CHANGES_REQUESTED,

    /** Not accepted. Carries a reason. */
    REJECTED,

    /** Live. This one dismisses the empty state, because the store now has a project. */
    PUBLISHED,
}

/** One of the signed-in account's own submissions, as the status card shows it. */
data class UiStoreSubmission(
    val itemId: String,
    val projectName: String,
    val version: String,
    val status: UiSubmissionStatus,
    /** The reviewer's note, for [UiSubmissionStatus.CHANGES_REQUESTED] and [UiSubmissionStatus.REJECTED]. */
    val note: String? = null,
)

/** Where an install has got to. Mirrors the install button's state machine. */
/**
 * The phases an install passes through.
 *
 * There is deliberately no VERIFYING: the sha256 is computed while the archive streams, so verification
 * is not a phase the user waits through, it is a condition on [DOWNLOADING] completing at all.
 */
enum class UiInstallState { DOWNLOADING, IMPORTING, INSTALLED, FAILED }

/**
 * One item's install progress.
 *
 * Keyed by item id at the call site rather than held as a single value, because several installs can run
 * at once and the design is explicit that a row must show its OWN progress — the prototype shares one
 * value across rows and production must not.
 */
data class UiInstallProgress(
    val itemId: String,
    val state: UiInstallState,
    /** 0f..1f while downloading; meaningless in the other states. */
    val fraction: Float = 0f,
    /** Why it failed, for [UiInstallState.FAILED]. */
    val message: String? = null,
)

// ---- Store accounts ----

/**
 * The signed-in store account.
 *
 * [handle] and [displayName] come from the publisher row rather than the OAuth profile, and stay null
 * until the account has published something: the publisher row is created on first submit, not at sign-up.
 * So a freshly signed-in account legitimately has nothing but an id.
 */
data class UiStoreAccount(
    val userId: String,
    val email: String? = null,
    val handle: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val isAdmin: Boolean = false,
) {
    /** What to show for this account, falling back through the fields that may not be set yet. */
    val label: String get() = displayName ?: handle ?: email ?: "Signed in"
}

/**
 * Where a sign-in has got to.
 *
 * [AwaitingBrowser] is its own phase because the app genuinely cannot tell what is happening during it:
 * the user is in a browser, may take a minute, and may simply never come back. A UI that showed a
 * spinner would be claiming progress it cannot observe.
 */
enum class UiSignInPhase { SignedOut, AwaitingBrowser, Completing, SignedIn, Failed }

/** [message] carries the reason in [UiSignInPhase.Failed], for the UI to show verbatim. */
data class UiStoreAuthState(
    val phase: UiSignInPhase = UiSignInPhase.SignedOut,
    val account: UiStoreAccount? = null,
    val message: String? = null,
) {
    val signedIn: Boolean get() = account != null
}

// ---- submitting ----

/**
 * The result of packaging a project, shown before anything is uploaded.
 *
 * [excluded] is the point of this screen, not a footnote: the archive is about to be made public, and the
 * packager drops keystores, `local.properties`, `.env` and `google-services.json`. Showing what was left
 * out is how the user can tell that happened, rather than trusting it silently.
 */
data class UiPackagedProject(
    val rootPath: String,
    val fileCount: Int,
    val totalBytes: Long,
    val sha256: String,
    val excluded: List<String>,
    /** Where the built zip is; the engine uploads and deletes it. Not shown to the user. */
    val archivePath: String,
)

/** What the submit form collects. Mirrors the engine's request, minus anything the engine can derive. */
data class UiSubmissionDraft(
    val title: String = "",
    val summary: String = "",
    val description: String = "",
    val category: String = "",
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val version: String = "1.0.0",
    /** Local image paths to publish with the project, at most six. */
    val screenshotPaths: List<String> = emptyList(),
    /** Set to publish a new version of an item you already own; null creates a new one. */
    val itemSlug: String? = null,
    val changelog: String? = null,
)

/** [message] is shown verbatim on failure: the backend's quota and validation messages are user-facing. */
data class UiSubmitResult(
    val success: Boolean,
    val message: String,
    val submission: UiStoreSubmission? = null,
)

// ---- ratings and reviews ----

/** How the review list is ordered. */
enum class UiReviewSort { HELPFUL, RECENT }

/**
 * One review.
 *
 * [authorName] is null for a reviewer who has never published: the display name lives on the publisher
 * row, so the UI shows a neutral label rather than inventing one from an id.
 */
data class UiStoreReview(
    val authorId: String,
    val authorName: String? = null,
    val authorHandle: String? = null,
    val verified: Boolean = false,
    val stars: Int,
    val review: String? = null,
    val helpful: Int = 0,
    val votedByMe: Boolean = false,
    val appVersion: String? = null,
    val itemVersion: String? = null,
    /** Epoch millis, resolved by the engine; 0 when the backend sent nothing parseable. */
    val postedAtMs: Long = 0L,
    /** The publisher's answer to this review. */
    val reply: String? = null,
    val mine: Boolean = false,
)

/**
 * Everything the reviews panel draws.
 *
 * [average] and [count] come from the reviews themselves, so the headline agrees with the list beneath it;
 * the item's own rating fields are for ranking and can lag. [mine] is separate so the UI can pin the
 * reader's own review above the rest.
 */
data class UiReviewPage(
    val average: Float = -1f,
    val count: Int = 0,
    /** Stars 1..5 to how many reviews gave them; absent means zero. */
    val distribution: Map<Int, Int> = emptyMap(),
    val mine: UiStoreReview? = null,
    val reviews: List<UiStoreReview> = emptyList(),
    val loading: Boolean = false,
    /** Set when the page could not be fetched; shown verbatim. */
    val error: String? = null,
    /** Whether the reader publishes this project, and so may answer its reviews. Decided by the backend. */
    val canReply: Boolean = false,
    /** Whether the reader is a moderator, and so may hide a review. */
    val canModerate: Boolean = false,
) {
    val hasAny: Boolean get() = count > 0 || mine != null
}

/**
 * Why something is being reported.
 *
 * A fixed set rather than free text: a moderator triaging a queue needs to sort by kind, and "other"
 * carries the detail field for everything that does not fit.
 */
enum class UiReportReason { MALWARE, SPAM, COPYRIGHT, INAPPROPRIATE, BROKEN, OTHER }


// ---- publisher profiles ----

/**
 * A publisher's page.
 *
 * [averageRating] is weighted by each project's rating count, so a project with one five-star review does
 * not outweigh one with two hundred ratings. Null means nothing they published has been rated, which is a
 * different statement from an average of zero.
 */
data class UiPublisherProfile(
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val location: String? = null,
    val linkUrl: String? = null,
    val verified: Boolean = false,
    val followers: Int = 0,
    val following: Boolean = false,
    val projectCount: Int = 0,
    val totalInstalls: Int = 0,
    val totalLikes: Int = 0,
    val averageRating: Float? = null,
    val items: List<UiStoreItem> = emptyList(),
    val loading: Boolean = false,
    /** Set when the profile could not be loaded; shown verbatim. Distinct from "no such publisher". */
    val error: String? = null,
)
