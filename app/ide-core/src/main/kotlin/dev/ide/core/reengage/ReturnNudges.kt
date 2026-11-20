package dev.ide.core.reengage

/**
 * A build that failed and has not been followed by one that worked. [atMs] names it: a later failure of the
 * same project is a different thing to be reminded of.
 */
data class FailedBuild(
    val projectRoot: String,
    val projectName: String,
    val atMs: Long,
)

/** A project by where it is and what the user calls it. */
data class ProjectRef(val root: String, val name: String)

/**
 * What the device remembers about someone's use of the app, which is all a reminder is decided from. Nothing
 * here leaves the device: the reminders are scheduled and shown locally.
 */
data class ReturnState(
    val installedAtMs: Long,
    /** When the app was last in front of the user. */
    val lastUsedAtMs: Long,
    /** Whether any build or run has ever succeeded on this install. */
    val everBuilt: Boolean,
    val failedBuild: FailedBuild?,
    /** The project last opened, which is the one a user who has built nothing left behind. */
    val openedProject: ProjectRef? = null,
    /** When this install's first build succeeded, or 0 when it has not or the time is not known. */
    val firstBuiltAtMs: Long = 0,
    /** The project whose build last succeeded. */
    val builtProject: ProjectRef? = null,
    /** How many reminders this install has been shown, ever. */
    val sentCount: Int,
    /** When the last one was shown, or 0 for never. */
    val lastSentAtMs: Long,
    /** The [ReturnNudge.key] of each reminder shown, so none repeats. */
    val sentKeys: Set<String>,
    /** The user's switch in Settings. */
    val enabled: Boolean,
)

enum class ReturnNudgeKind(val wire: String) {
    /** The last thing the user did was a build that failed. */
    FAILED_BUILD("failed_build"),

    /** The user got a first build to work and did not come back. */
    FIRST_BUILD("first_build"),

    /** A project was opened and nothing has ever been built. */
    UNBUILT_PROJECT("unbuilt_project"),

    /** Days after installing, there is no project and nothing has been built. */
    NEVER_BUILT("never_built"),
}

/** A reminder that is due. [projectRoot] and [projectName] are set when it is about one project. */
data class ReturnNudge(
    val kind: ReturnNudgeKind,
    val key: String,
    val projectRoot: String? = null,
    val projectName: String? = null,
)

/**
 * When someone who stopped using the app is reminded of it.
 *
 * The reminders are few on purpose. Each is about something the user did (a build they left broken, a first
 * build that worked, a project or an install they never built anything with), each is shown once, there is at most one a week, and an install that has
 * been shown [MAX_SENT] hears nothing more. A reminder waits on the user having been away, not on the clock
 * alone: opening the app moves every one of them back.
 */
object ReturnNudges {
    const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** A failed build is mentioned after the user has been away this long. */
    const val FAILED_BUILD_AFTER_MS: Long = DAY_MS

    /** An install that never built is reminded this long after installing and after its last use. */
    const val NEVER_BUILT_AFTER_MS: Long = 2 * DAY_MS

    /** A first build that worked is followed up after the user has been away this long. */
    const val FIRST_BUILD_AFTER_MS: Long = 3 * DAY_MS

    /**
     * Only someone who left within this long of their first build is followed up on it. After that they are
     * a user who stopped, not one who had just started.
     */
    const val FIRST_BUILD_WINDOW_MS: Long = 7 * DAY_MS

    /** The least time between two reminders. */
    const val MIN_GAP_MS: Long = 7 * DAY_MS

    /** The most reminders an install is ever shown. */
    const val MAX_SENT: Int = 4

    private const val NEVER_BUILT_KEY = "never_built"
    private const val UNBUILT_PROJECT_KEY = "unbuilt_project"
    private const val FIRST_BUILD_KEY = "first_build"

    /** The reminder to show at [nowMs], or null when none is due. */
    fun due(state: ReturnState, nowMs: Long): ReturnNudge? =
        candidates(state).firstOrNull { (_, at) -> at <= nowMs }?.first

    /**
     * The earliest time a reminder could be due, to check again then; null when there is nothing to wait for
     * (switched off, the install's share used up, or nothing left to remind about).
     */
    fun nextCheckAt(state: ReturnState): Long? = candidates(state).minOfOrNull { it.second }

    /** Each reminder still possible, with the time it becomes due, the one that takes precedence first. */
    private fun candidates(state: ReturnState): List<Pair<ReturnNudge, Long>> {
        if (!state.enabled || state.sentCount >= MAX_SENT) return emptyList()
        val notBefore = if (state.lastSentAtMs > 0) state.lastSentAtMs + MIN_GAP_MS else 0L
        val out = ArrayList<Pair<ReturnNudge, Long>>(2)
        state.failedBuild?.let { failed ->
            val key = "${ReturnNudgeKind.FAILED_BUILD.wire}:${failed.atMs}"
            if (key !in state.sentKeys) {
                val at = maxOf(failed.atMs, state.lastUsedAtMs) + FAILED_BUILD_AFTER_MS
                out += ReturnNudge(ReturnNudgeKind.FAILED_BUILD, key, failed.projectRoot, failed.projectName) to maxOf(at, notBefore)
            }
        }
        val built = state.builtProject
        if (built != null && state.firstBuiltAtMs > 0 && FIRST_BUILD_KEY !in state.sentKeys &&
            state.lastUsedAtMs - state.firstBuiltAtMs <= FIRST_BUILD_WINDOW_MS
        ) {
            val at = state.lastUsedAtMs + FIRST_BUILD_AFTER_MS
            out += ReturnNudge(ReturnNudgeKind.FIRST_BUILD, FIRST_BUILD_KEY, built.root, built.name) to maxOf(at, notBefore)
        }
        if (!state.everBuilt) {
            val at = maxOf(state.installedAtMs, state.lastUsedAtMs) + NEVER_BUILT_AFTER_MS
            val opened = state.openedProject
            // One or the other: with a project to go back to, the reminder is about that project.
            if (opened != null) {
                if (UNBUILT_PROJECT_KEY !in state.sentKeys) {
                    out += ReturnNudge(ReturnNudgeKind.UNBUILT_PROJECT, UNBUILT_PROJECT_KEY, opened.root, opened.name) to maxOf(at, notBefore)
                }
            } else if (NEVER_BUILT_KEY !in state.sentKeys) {
                out += ReturnNudge(ReturnNudgeKind.NEVER_BUILT, NEVER_BUILT_KEY) to maxOf(at, notBefore)
            }
        }
        return out
    }
}
