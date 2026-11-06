package dev.ide.core.backend

import dev.ide.store.LearnAccountState
import dev.ide.store.LearnRemoteService
import dev.ide.store.LearnStepRecord
import dev.ide.store.LearnStreak
import dev.ide.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Progress sync is a union that must never lose anything: not a step done offline, not progress made
 * before signing in, not progress made on another phone. And importing history must not fake a streak.
 */
class LearnProgressSyncTest {

    private val catalog = LearnContent.catalog
    private val lessonA = catalog.tracks[0].lessons[0]
    private val lessonB = catalog.tracks[0].lessons[1]

    /** A server holding the union, recording every upload. */
    private class FakeServer : LearnRemoteService {
        var user: String? = null
        var down = false
        val held = LinkedHashSet<Pair<String, String>>()
        val uploads = ArrayList<List<LearnStepRecord>>()
        var streak = LearnStreak(3, 5, activeToday = false)

        override fun learnRemoteAvailable() = true
        override fun accountId() = user
        override fun syncProgress(steps: List<LearnStepRecord>, completedTracks: List<String>, timeZone: String): StoreResult<LearnAccountState> {
            if (down) return StoreResult.Unavailable("offline")
            uploads += steps
            steps.forEach { held += it.lessonId to it.stepId }
            return StoreResult.Ok(LearnAccountState(held.toSet(), emptyList(), streak))
        }
    }

    private fun newSync(server: FakeServer, prefs: MutableMap<String, String>) = LearnProgressSync(
        remote = server,
        pref = { prefs[it] },
        setPref = { k, v -> prefs[k] = v },
        catalog = { catalog },
        clock = { 1_000L },
        debounceMs = 60_000, // tests sync explicitly
    )

    @Test
    fun signedOutProgressIsKeptAndUploadedOnFirstSignIn() = runBlocking {
        val server = FakeServer()
        val prefs = HashMap<String, String>()
        // Progress from before this feature existed: already in prefs, never queued.
        prefs[LearnProgressSync.keyCompleted(lessonA.id)] = lessonA.steps[0].id
        val sync = newSync(server, prefs)

        assertTrue(sync.stepCompleted(lessonB.id, lessonB.steps[0].id))
        assertFalse(sync.sync(), "signed out: nothing to sync")
        assertTrue(server.uploads.isEmpty())

        server.user = "u1"
        sync.sync()
        val sent = server.uploads.single()
        // History goes up without a timestamp (no streak credit); the fresh step carries its time.
        assertEquals(null, sent.first { it.lessonId == lessonA.id }.completedAtMs)
        assertEquals(1_000L, sent.first { it.lessonId == lessonB.id }.completedAtMs)
        assertEquals(setOf(lessonA.id to lessonA.steps[0].id, lessonB.id to lessonB.steps[0].id), server.held)
        assertEquals("", prefs[LearnProgressSync.KEY_PENDING])
    }

    @Test
    fun laterSyncsSendOnlyWhatIsNew() = runBlocking {
        val server = FakeServer().apply { user = "u1" }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        sync.stepCompleted(lessonA.id, lessonA.steps[0].id)
        sync.sync()
        sync.stepCompleted(lessonA.id, lessonA.steps[1].id)
        sync.sync()
        assertEquals(listOf(lessonA.steps[1].id), server.uploads.last().map { it.stepId })
        // Completing the same step twice queues nothing.
        assertFalse(sync.stepCompleted(lessonA.id, lessonA.steps[1].id))
    }

    @Test
    fun progressFromAnotherDeviceIsMergedIn() = runBlocking {
        val server = FakeServer().apply { user = "u1"; held += lessonB.id to lessonB.steps[0].id }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        assertTrue(sync.sync(), "something arrived")
        assertEquals(setOf(lessonB.steps[0].id), sync.completedSteps(lessonB.id))
        assertFalse(sync.sync(), "nothing new the second time")
    }

    @Test
    fun aFailedSyncKeepsTheQueue() = runBlocking {
        val server = FakeServer().apply { user = "u1"; down = true }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        sync.stepCompleted(lessonA.id, lessonA.steps[0].id)
        assertFalse(sync.sync())
        assertTrue(prefs[LearnProgressSync.KEY_PENDING].orEmpty().contains(lessonA.id))
        server.down = false
        sync.sync()
        assertTrue((lessonA.id to lessonA.steps[0].id) in server.held)
        assertEquals("", prefs[LearnProgressSync.KEY_PENDING])
    }

    @Test
    fun aDifferentAccountGetsTheDevicesHistoryToo() = runBlocking {
        val server = FakeServer().apply { user = "u1" }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        sync.stepCompleted(lessonA.id, lessonA.steps[0].id)
        sync.sync()
        server.user = "u2"
        server.held.clear()
        sync.sync()
        assertTrue((lessonA.id to lessonA.steps[0].id) in server.held, "full upload for a new account")
    }

    @Test
    fun aCompletionCountsTodayBeforeTheNextSync() = runBlocking {
        val server = FakeServer().apply { user = "u1" }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        sync.sync()
        assertEquals(3, sync.account?.streak?.current)
        sync.stepCompleted(lessonA.id, lessonA.steps[0].id)
        assertEquals(LearnStreak(4, 5, activeToday = true), sync.account?.streak)
        // And the cache survives for the next launch.
        assertEquals(LearnStreak(3, 5, false), newSync(server, prefs).cachedStreak())
    }

    @Test
    fun signingOutForgetsTheAccountButNotTheProgress() = runBlocking {
        val server = FakeServer().apply { user = "u1" }
        val prefs = HashMap<String, String>()
        val sync = newSync(server, prefs)
        sync.stepCompleted(lessonA.id, lessonA.steps[0].id)
        sync.sync()
        server.user = null
        sync.sync()
        assertNull(sync.account)
        assertEquals(setOf(lessonA.steps[0].id), sync.completedSteps(lessonA.id))
    }
}
