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

data class UiChartTab(val key: String, val label: String, val entries: List<UiChartEntry>)

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

/** One ghost shelf's progress toward switching on. */
data class UiGhostShelf(val key: String, val have: Int, val need: Int)

/** A section of the feed. Sealed so the screen's `when` is exhaustive over what it can draw. */
sealed interface UiFeedSection {
    val id: String

    data class Ticker(override val id: String, val terms: List<String>) : UiFeedSection
    data class Featured(override val id: String, val items: List<UiStoreItem>) : UiFeedSection

    data class Charts(
        override val id: String,
        val tabs: List<UiChartTab>,
        val computedAt: String? = null,
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

    data class ItemList(override val id: String, val title: String, val items: List<UiStoreItem>) : UiFeedSection

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
)

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
