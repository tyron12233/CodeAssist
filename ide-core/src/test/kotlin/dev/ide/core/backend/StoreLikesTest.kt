package dev.ide.core.backend

import dev.ide.store.RemotePublisherProfile
import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Likes, and the device list behind them.
 *
 * The reconcile rule is what these are really about: it is a UNION, not a server-wins replace, because the
 * ordinary reason the two lists differ is a like made offline that the server has not been told about. A
 * server-wins merge would delete it and look exactly like the button never worked.
 */
class StoreLikesTest {

    private class FakeReviews(
        private val setResult: StoreResult<Unit> = StoreResult.Ok(Unit),
        private val remote: StoreResult<List<String>> = StoreResult.Ok(emptyList()),
    ) : StoreReviewService {
        val pushed = mutableListOf<Pair<String, Boolean>>()
        override fun setLike(itemSlug: String, liked: Boolean): StoreResult<Unit> {
            pushed += itemSlug to liked
            return setResult
        }
        override fun myLikes(): StoreResult<List<String>> = remote
        override fun publisherProfile(handle: String): StoreResult<RemotePublisherProfile?> =
            StoreResult.Ok(null)
    }

    /** A tiny stand-in for the device preference the real one reads and writes. */
    private class Local(initial: Set<String> = emptySet()) {
        var value: Set<String> = initial
        val read: () -> Set<String> = { value }
        val write: (Set<String>) -> Unit = { value = it }
    }

    @Test
    fun likingWritesLocallyAndPushes() {
        val local = Local()
        val fake = FakeReviews()
        val likes = StoreLikes(fake, local.read, local.write)

        assertNull(likes.setLiked("aurora", true))
        assertEquals(setOf("aurora"), local.value)
        assertEquals(listOf("aurora" to true), fake.pushed)
        assertTrue(likes.isLiked("aurora"))
    }

    @Test
    fun unlikingRemovesIt() {
        val local = Local(setOf("aurora", "nordlys"))
        val likes = StoreLikes(FakeReviews(), local.read, local.write)
        assertNull(likes.setLiked("aurora", false))
        assertEquals(setOf("nordlys"), local.value)
        assertFalse(likes.isLiked("aurora"))
    }

    /** Offline is not a refusal: the like stays and the next reconcile pushes it. */
    @Test
    fun anOfflineLikeIsKept() {
        val local = Local()
        val likes = StoreLikes(FakeReviews(setResult = StoreResult.Unavailable("No connection")), local.read, local.write)

        assertNull(likes.setLiked("aurora", true), "offline should not report an error to the user")
        assertEquals(setOf("aurora"), local.value, "the like must survive to be pushed later")
    }

    /** A refusal is different: it was never allowed, so the button must not keep claiming it worked. */
    @Test
    fun aRefusedLikeIsRolledBack() {
        val local = Local()
        val likes = StoreLikes(FakeReviews(setResult = StoreResult.Failed("Sign in to like a project")), local.read, local.write)

        assertEquals("Sign in to like a project", likes.setLiked("aurora", true))
        assertEquals(emptySet(), local.value, "a refused like must not be left showing as liked")
    }

    @Test
    fun reconcileMergesBothDirections() {
        // The device knows one the account does not, and the account knows one the device does not.
        val local = Local(setOf("made-offline"))
        val fake = FakeReviews(remote = StoreResult.Ok(listOf("liked-elsewhere")))
        val likes = StoreLikes(fake, local.read, local.write)

        val merged = likes.reconcile()

        assertEquals(setOf("made-offline", "liked-elsewhere"), merged)
        assertEquals(setOf("made-offline", "liked-elsewhere"), local.value)
        // The offline one is pushed up; the one that came down is not pushed back.
        assertEquals(listOf("made-offline" to true), fake.pushed)
    }

    @Test
    fun reconcileWithNothingLocalJustAdopisTheAccountsList() {
        val local = Local()
        val fake = FakeReviews(remote = StoreResult.Ok(listOf("a", "b")))
        val likes = StoreLikes(fake, local.read, local.write)

        assertEquals(setOf("a", "b"), likes.reconcile())
        assertEquals(emptyList(), fake.pushed, "nothing local means nothing to push")
    }

    /** A failed fetch must not empty the shelf. */
    @Test
    fun reconcileKeepsTheLocalListWhenTheAccountCannotBeReached() {
        val local = Local(setOf("aurora", "nordlys"))
        val likes = StoreLikes(
            FakeReviews(remote = StoreResult.Unavailable("No connection")),
            local.read,
            local.write,
        )
        assertEquals(setOf("aurora", "nordlys"), likes.reconcile())
        assertEquals(setOf("aurora", "nordlys"), local.value)
    }

    @Test
    fun reconcileIsIdempotent() {
        val local = Local(setOf("a"))
        val fake = FakeReviews(remote = StoreResult.Ok(listOf("a")))
        val likes = StoreLikes(fake, local.read, local.write)
        assertEquals(setOf("a"), likes.reconcile())
        assertEquals(setOf("a"), likes.reconcile())
        assertEquals(emptyList(), fake.pushed, "an already-synced list has nothing to push")
    }
}
