package dev.ide.ui.ads

import dev.ide.ui.backend.AdPlacement

/** Native ads are not shown once they are this old: AdMob treats an ad older than an hour as expired. */
const val NATIVE_AD_MAX_AGE_MS = 55 * 60_000L

/**
 * How long after its impression an ad may keep being reused. A slot that comes back after this still shows the
 * old ad at once (never a blank slot) and asks for a replacement, which takes its place once it arrives.
 */
const val NATIVE_AD_REFRESH_AFTER_MS = 60_000L

/** After a failed request (usually NO_FILL), a placement waits this long before asking again. */
const val NATIVE_AD_FAILURE_BACKOFF_MS = 20_000L

/** Loaded ads waiting for a slot, kept per placement. Extra ones are destroyed. */
const val NATIVE_AD_MAX_IDLE_PER_PLACEMENT = 2

/**
 * Keeps native ads alive independently of the slots that show them.
 *
 * A slot used to request a fresh ad every time it entered composition and destroy it on leaving, so closing
 * the sidebar, finishing a build or scrolling a list threw away ads that had loaded but were never seen. The
 * pool instead hands each visible slot a [Lease]: the slot reuses an idle ad when one is ready and only requests
 * a new one when none is, or when the one it got has already been seen for [NATIVE_AD_REFRESH_AFTER_MS]. When a
 * slot goes away, its ad (or its still-running request) goes back to the pool for the next slot of the same
 * placement instead of being destroyed.
 *
 * Generic over the ad type so the policy stays free of the ad SDK: the host supplies [load] (start a request,
 * report back through [Callbacks]) and [destroy]. Not thread-safe: every call, including the callbacks, must
 * happen on the main thread.
 */
class NativeAdPool<A : Any>(
    private val load: (AdPlacement, Callbacks<A>) -> Boolean,
    private val destroy: (A) -> Unit,
    private val now: () -> Long,
) {
    /** Reports the outcome of one request started by [load]. */
    interface Callbacks<A> {
        fun loaded(ad: A)
        fun failed()
        fun impression()
    }

    /** One loaded ad and when it was loaded and first seen. */
    internal class Entry<A>(val ad: A, val placement: AdPlacement, val loadedAt: Long) {
        var impressionAt = NO_IMPRESSION
    }

    /** One request in flight. [owner] is the slot waiting for it, or null once that slot has gone away. */
    internal inner class Request(val placement: AdPlacement, var owner: Lease?) : Callbacks<A> {
        var entry: Entry<A>? = null

        override fun loaded(ad: A) = onLoaded(this, ad)

        override fun failed() = onFailed(this)

        override fun impression() {
            entry?.let { if (it.impressionAt == NO_IMPRESSION) it.impressionAt = now() }
        }
    }

    /**
     * One slot's claim on the pool. [ad] is what the slot should show (null: nothing yet, show the fallback).
     * [onChange] runs whenever [ad] changes, so the slot can redraw.
     */
    inner class Lease internal constructor(val placement: AdPlacement) {
        var onChange: () -> Unit = {}
        val ad: A? get() = entry?.ad

        internal var entry: Entry<A>? = null
        internal var request: Request? = null
        internal var acquired = false

        /** Ads this slot replaced; destroyed once the slot shows the replacement (see [bound]). */
        internal val replaced = mutableListOf<Entry<A>>()
    }

    private val idle = mutableListOf<Entry<A>>()
    private val requests = mutableListOf<Request>()
    private val failedAt = HashMap<AdPlacement, Long>()
    private var closed = false

    fun lease(placement: AdPlacement): Lease = Lease(placement)

    /** The slot is on screen: give it an ad, requesting one if needed. Repeat calls are no-ops. */
    fun acquire(lease: Lease) {
        if (closed || lease.acquired) return
        lease.acquired = true
        expireIdle()
        val entry = takeIdle(lease.placement)
        if (entry != null) show(lease, entry)
        if (entry == null || entry.dueForRefresh()) request(lease)
    }

    /** The slot has left composition: its ad and any pending request go back to the pool. */
    fun release(lease: Lease) {
        if (!lease.acquired) return
        lease.acquired = false
        lease.request?.owner = null
        lease.request = null
        bound(lease)
        lease.entry?.let { park(it) }
        lease.entry = null
    }

    /** The slot now shows [Lease.ad], so the ads it replaced can be destroyed. */
    fun bound(lease: Lease) {
        lease.replaced.forEach { destroy(it.ad) }
        lease.replaced.clear()
    }

    /** Destroys every idle ad. Ads still on a slot are destroyed when that slot is released. */
    fun close() {
        closed = true
        idle.forEach { destroy(it.ad) }
        idle.clear()
        requests.forEach { it.owner = null }
    }

    private fun Entry<A>.dueForRefresh(): Boolean =
        impressionAt != NO_IMPRESSION && now() - impressionAt >= NATIVE_AD_REFRESH_AFTER_MS

    private fun Entry<A>.expired(): Boolean = now() - loadedAt >= NATIVE_AD_MAX_AGE_MS

    /** An unseen ad first (oldest, so it is used before it expires), otherwise the most recently seen one. */
    private fun takeIdle(placement: AdPlacement): Entry<A>? {
        val candidates = idle.filter { it.placement == placement }
        val pick = candidates.filter { it.impressionAt == NO_IMPRESSION }.minByOrNull { it.loadedAt }
            ?: candidates.maxByOrNull { it.impressionAt }
            ?: return null
        idle.remove(pick)
        return pick
    }

    private fun request(lease: Lease) {
        // A request whose slot went away is still coming: adopt it rather than asking again.
        requests.firstOrNull { it.placement == lease.placement && it.owner == null }?.let { orphan ->
            orphan.owner = lease
            lease.request = orphan
            return
        }
        failedAt[lease.placement]?.let { if (now() - it < NATIVE_AD_FAILURE_BACKOFF_MS) return }
        val request = Request(lease.placement, lease)
        requests += request
        lease.request = request
        if (!load(lease.placement, request)) {
            requests -= request
            lease.request = null
        }
    }

    private fun onLoaded(request: Request, ad: A) {
        requests -= request
        if (closed) {
            destroy(ad)
            return
        }
        failedAt -= request.placement
        val entry = Entry(ad, request.placement, now())
        request.entry = entry
        val owner = request.owner
        if (owner != null && owner.acquired) {
            owner.request = null
            // The ad it showed until now was due for refresh; drop it once the new one is on screen.
            owner.entry?.let { owner.replaced += it }
            show(owner, entry)
        } else {
            park(entry)
        }
    }

    private fun onFailed(request: Request) {
        requests -= request
        failedAt[request.placement] = now()
        request.owner?.let { if (it.request === request) it.request = null }
    }

    private fun show(lease: Lease, entry: Entry<A>) {
        lease.entry = entry
        lease.onChange()
    }

    private fun park(entry: Entry<A>) {
        if (closed || entry.expired()) {
            destroy(entry.ad)
            return
        }
        idle += entry
        val surplus = idle.filter { it.placement == entry.placement }
            // Keep unseen ads first, then the most recently seen; destroy the rest.
            .sortedWith(compareBy<Entry<A>> { it.impressionAt != NO_IMPRESSION }.thenByDescending { it.impressionAt })
            .drop(NATIVE_AD_MAX_IDLE_PER_PLACEMENT)
        surplus.forEach {
            idle -= it
            destroy(it.ad)
        }
    }

    private fun expireIdle() {
        val stale = idle.filter { it.expired() }
        stale.forEach {
            idle -= it
            destroy(it.ad)
        }
    }

    private companion object {
        const val NO_IMPRESSION = -1L
    }
}
