package dev.ide.core.backend

import dev.ide.store.LearnAccountState
import dev.ide.store.LearnRemoteService
import dev.ide.store.LearnStepRecord
import dev.ide.store.LearnStreak
import dev.ide.store.StoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Learn progress on the device, and its sync with the account.
 *
 * **On the device** each lesson's completed step ids live in one preference (`learn.completed.<lessonId>`),
 * exactly as before accounts existed, so a signed-out learner loses nothing and old installs keep their
 * progress.
 *
 * **Sync** is a union in both directions and never removes anything:
 * - Each step completed on this device is queued with the time it happened (`learn.pending`). Those count
 *   toward the day streak on the server.
 * - The first time an account is seen on this device (`learn.synced.user` differs), everything the device
 *   already knows is uploaded too, as history without timestamps, so it fills in progress but cannot
 *   fabricate streak days.
 * - Whatever the account holds comes back and is merged in, which is how progress made on another phone
 *   appears here.
 * A failed sync keeps the queue for next time. The queue survives restarts and signing out.
 */
internal class LearnProgressSync(
    private val remote: LearnRemoteService,
    private val pref: (String) -> String?,
    private val setPref: (String, String) -> Unit,
    private val catalog: () -> LearnCatalogDef,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeZone: () -> String = { java.util.TimeZone.getDefault().id },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How long to wait after a completion before syncing, so a run of steps goes up as one request. */
    private val debounceMs: Long = 1_500,
) {
    private val mutex = Mutex()
    private var pendingJob: Job? = null

    /** The account's state from the last successful sync, adjusted for steps completed since. */
    @Volatile
    var account: LearnAccountState? = null
        private set

    // ---- local progress ----

    fun completedSteps(lessonId: String): Set<String> =
        pref(keyCompleted(lessonId))?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** Every (lesson, step) the device has completed, for lessons in the current catalog. */
    fun allSteps(): Set<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (track in catalog().tracks) for (lesson in track.lessons) {
            for (step in completedSteps(lesson.id)) out += lesson.id to step
        }
        return out
    }

    /** Tracks whose every lesson has every step completed. */
    fun completedTracks(): List<String> = catalog().tracks.filter { track ->
        track.lessons.isNotEmpty() && track.lessons.all { lesson ->
            val done = completedSteps(lesson.id)
            lesson.steps.all { it.id in done }
        }
    }.map { it.id }

    /**
     * Record a step completed on this device: locally at once, queued for the account, and synced shortly
     * after when signed in. Returns false when it was already recorded (nothing new to send).
     */
    fun stepCompleted(lessonId: String, stepId: String): Boolean {
        val current = completedSteps(lessonId)
        if (stepId in current) return false
        setPref(keyCompleted(lessonId), (current + stepId).joinToString(","))
        enqueue(PendingStep(lessonId, stepId, clock()))
        // Show today as counted straight away; the next sync replaces this with the server's figure.
        account?.let { a ->
            if (!a.streak.activeToday) {
                account = a.copy(streak = a.streak.copy(current = a.streak.current + 1, activeToday = true,
                    best = maxOf(a.streak.best, a.streak.current + 1)))
            }
        }
        scheduleSync()
        return true
    }

    // ---- sync ----

    /** Sync now. True when the device's progress changed (something arrived from the account). */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { syncLocked() }
    }

    private fun scheduleSync() {
        if (!remote.learnRemoteAvailable() || remote.accountId() == null) return
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            runCatching { sync() }
        }
    }

    private fun syncLocked(): Boolean {
        if (!remote.learnRemoteAvailable()) return false
        val user = remote.accountId() ?: run {
            account = null
            return false
        }
        val pending = readPending()
        val firstTimeHere = pref(KEY_SYNCED_USER) != user
        val records = ArrayList<LearnStepRecord>()
        if (firstTimeHere) {
            // History, minus anything also queued: a queued step goes up once, with its timestamp.
            val queued = pending.map { it.lessonId to it.stepId }.toSet()
            allSteps().filterNot { it in queued }.forEach { (l, s) -> records += LearnStepRecord(l, s, null) }
        }
        pending.forEach { records += LearnStepRecord(it.lessonId, it.stepId, it.atMs) }

        var state: LearnAccountState? = null
        val batches = if (records.isEmpty()) listOf(emptyList()) else records.chunked(BATCH)
        val tracks = completedTracks()
        val tz = timeZone()
        for (batch in batches) {
            when (val r = remote.syncProgress(batch, tracks, tz)) {
                is StoreResult.Ok -> state = r.value
                // Offline, signed out underneath us, or a server error: keep the queue and try again later.
                is StoreResult.Unavailable, is StoreResult.Failed -> return false
            }
        }
        val result = state ?: return false

        removePending(pending)
        setPref(KEY_SYNCED_USER, user)
        val changed = merge(result.steps)
        account = result
        writeStreakCache(result.streak)
        return changed
    }

    /** Add the account's steps to the device. True when anything was new here. */
    private fun merge(steps: Set<Pair<String, String>>): Boolean {
        var changed = false
        for ((lessonId, group) in steps.groupBy({ it.first }, { it.second })) {
            val current = completedSteps(lessonId)
            val merged = current + group
            if (merged.size != current.size) {
                setPref(keyCompleted(lessonId), merged.joinToString(","))
                changed = true
            }
        }
        return changed
    }

    /** Reflect a "share my solutions" change made through the community service without a full sync. */
    fun overrideShareSolutions(share: Boolean) {
        account = account?.copy(shareSolutions = share)
    }

    /** The last known streak, for showing before the first sync of a session finishes. */
    fun cachedStreak(): LearnStreak? {
        val parts = pref(KEY_STREAK)?.split(",") ?: return null
        if (parts.size < 3) return null
        return LearnStreak(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, parts[2] == "1")
    }

    private fun writeStreakCache(s: LearnStreak) =
        setPref(KEY_STREAK, "${s.current},${s.best},${if (s.activeToday) 1 else 0}")

    // ---- the queue ----

    private data class PendingStep(val lessonId: String, val stepId: String, val atMs: Long) {
        fun encode() = "$lessonId|$stepId|$atMs"
    }

    private fun readPending(): List<PendingStep> =
        pref(KEY_PENDING).orEmpty().split(";").mapNotNull { e ->
            val p = e.split("|")
            if (p.size != 3) return@mapNotNull null
            PendingStep(p[0], p[1], p[2].toLongOrNull() ?: return@mapNotNull null)
        }

    private fun enqueue(step: PendingStep) = synchronized(this) {
        // Bounded: a device that never signs in should not grow this forever. The steps themselves are kept
        // locally regardless; only their streak timestamps age out.
        val next = (readPending() + step).takeLast(MAX_PENDING)
        setPref(KEY_PENDING, next.joinToString(";") { it.encode() })
    }

    /** Drop exactly what was sent; anything queued while the request was in flight stays. */
    private fun removePending(sent: List<PendingStep>) = synchronized(this) {
        val sentSet = sent.toSet()
        setPref(KEY_PENDING, readPending().filterNot { it in sentSet }.joinToString(";") { it.encode() })
    }

    internal companion object {
        fun keyCompleted(lessonId: String) = "learn.completed.$lessonId"
        const val KEY_PENDING = "learn.pending"
        const val KEY_SYNCED_USER = "learn.synced.user"
        const val KEY_STREAK = "learn.streak"
        const val MAX_PENDING = 2_000
        /** Under the server's 5000-step limit per call. */
        const val BATCH = 4_000
    }
}
