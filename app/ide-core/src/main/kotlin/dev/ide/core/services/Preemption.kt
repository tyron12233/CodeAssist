package dev.ide.core.services

import dev.ide.platform.EngineCanceledException
import kotlinx.coroutines.CancellationException

/**
 * [block]'s result, or [default] when it fails, EXCEPT for a preemption or a cancellation, which propagate.
 *
 * The language services are plugin-backed, so their failures degrade to "nothing to show" rather than
 * surfacing. A preemption is not a failure, though: the pass was cut short so a completion could run, and
 * answering with the empty default would reach the editor as a real result and clear its highlighting,
 * folds or hints until the next edit, instead of letting the host retry and keep what it shows.
 */
internal inline fun <T> orDefaultUnlessPreempted(default: T, block: () -> T): T =
    try {
        block()
    } catch (e: EngineCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        default
    }
