package dev.ide.core.perf

import dev.ide.analytics.Features

/**
 * The last few features used, oldest first, for a crash report to carry: which screen was showing and which
 * tool was opened last. Some crashes have no frame of the app's own on the stack (a lazy list given the same
 * key twice throws from the measure pass), and without this the row cannot say where the user was.
 *
 * Holds the same ids [UsageCounter] counts, so it is fed only what the user's consent already covers and never
 * anything they typed or named. A feature used twice in a row is noted once. Thread-safe.
 */
internal class FeatureTrail(private val size: Int = 8) {
    private val recent = ArrayDeque<String>()

    fun note(feature: String) {
        if (!Features.isValid(feature)) return
        synchronized(recent) {
            if (recent.lastOrNull() == feature) return
            recent.addLast(feature)
            while (recent.size > size) recent.removeFirst()
        }
    }

    /** `screen_editor>tool_build>view_blocks`, or `null` when nothing was noted. */
    fun render(): String? = synchronized(recent) { recent.joinToString(">").ifEmpty { null } }

    /** Forget the trail (consent withdrawn). */
    fun clear() {
        synchronized(recent) { recent.clear() }
    }
}
