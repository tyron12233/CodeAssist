package dev.ide.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether one of this app's builds is compiling right now, wherever it runs (in this process or the `:build`
 * process). Background work that only makes LATER things faster (building source-doc indexes, downloading
 * prebuilt libraries) waits while it is, because on a small device the two slow each other several times over: a
 * Compose app's 3 s Kotlin compile took 22 s while the editor built its source-doc index next to it.
 *
 * Set by the host from its live build state. A user program that is running after its build doesn't count. The
 * waits are capped, so a build that never reports its end can delay that work but not stop it.
 */
object BuildActivity {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** How long background work waits for a build at most. */
    const val MAX_WAIT_MS: Long = 120_000

    fun set(active: Boolean) {
        _active.value = active
    }

    /** Suspend while a build runs, up to [maxWaitMs]. */
    suspend fun awaitIdle(maxWaitMs: Long = MAX_WAIT_MS) {
        if (!_active.value) return
        withTimeoutOrNull(maxWaitMs) { _active.first { !it } }
    }
}
