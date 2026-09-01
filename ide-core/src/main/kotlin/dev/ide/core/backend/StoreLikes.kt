package dev.ide.core.backend

import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService

/**
 * Likes, and the local list that makes them usable offline.
 *
 * A like is the same act as saving a project, so this is also what backs the Saved shelf. It was device-only
 * before — one preference, never sent anywhere — which meant a reinstall lost the list, a second device
 * never saw it, and the public count the store now shows had nothing behind it.
 *
 * The device list stays as the fast, offline answer: [isLiked] is read during composition and cannot wait
 * on a network call. [reconcile] merges the account's list into it. The merge is deliberately a UNION rather
 * than a server-wins replace, because the common case for a difference is a like made offline on this
 * device that the server has not been told about yet, and silently dropping it would look like the button
 * did nothing.
 */
internal class StoreLikes(
    private val reviews: StoreReviewService,
    private val readLocal: () -> Set<String>,
    private val writeLocal: (Set<String>) -> Unit,
) {

    fun liked(): Set<String> = readLocal()

    fun isLiked(itemId: String): Boolean = itemId in readLocal()

    /**
     * Like or unlike, locally first.
     *
     * The local write happens before the call so the UI can reflect the tap immediately, and is rolled back
     * only if the server refuses for a reason that is not "offline": an offline like is a like the next
     * reconcile will push, while a refusal means it was never allowed.
     */
    fun setLiked(itemId: String, liked: Boolean): String? {
        val before = readLocal()
        writeLocal(if (liked) before + itemId else before - itemId)

        return when (val result = reviews.setLike(itemId, liked)) {
            is StoreResult.Ok -> null
            // Offline: keep the local change. The account picks it up on the next reconcile.
            is StoreResult.Unavailable -> null
            is StoreResult.Failed -> {
                writeLocal(before)
                result.message
            }
        }
    }

    /** Merge the account's likes into the device's. Returns the merged set. */
    fun reconcile(): Set<String> {
        val local = readLocal()
        val remote = reviews.myLikes()
        if (remote !is StoreResult.Ok) return local
        val merged = local + remote.value
        // Push anything this device knows and the account does not, so an offline like is not lost.
        (local - remote.value.toSet()).forEach { reviews.setLike(it, true) }
        if (merged != local) writeLocal(merged)
        return merged
    }
}
