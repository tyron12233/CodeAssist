// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.concurrent.Volatile

/** How much memory the device can spare for the IDE, as far as caches and background work are concerned. */
enum class MemoryTier {
    /** Enough memory for the default cache sizes and background warm-ups. */
    NORMAL,

    /** A device where every retained megabyte makes the system more likely to kill the app: smaller caches,
     *  no speculative warm-ups, and memory released as soon as the IDE leaves the screen. */
    LOW,
}

/**
 * The device's [MemoryTier], shared by every layer that sizes a cache or decides whether to run speculative
 * work.
 *
 * Two inputs. The host [detected] a tier from the hardware at startup (on Android: the low-RAM flag, the app
 * heap class and the physical RAM); a desktop host never sets it, so it stays [MemoryTier.NORMAL]. The user
 * may then force either tier through the "Low memory mode" setting, which the host applies with [applyMode].
 * Readers only ever look at [tier].
 */
object DeviceMemory {
    /** Setting value: follow the detected tier. */
    const val MODE_AUTO = "auto"

    /** Setting value: always behave as a low-memory device. */
    const val MODE_ON = "on"

    /** Setting value: never behave as a low-memory device. */
    const val MODE_OFF = "off"

    @Volatile
    var detected: MemoryTier = MemoryTier.NORMAL

    @Volatile
    private var mode: String = MODE_AUTO

    /** The tier in effect: the user's forced choice, or [detected] under [MODE_AUTO]. */
    val tier: MemoryTier
        get() = when (mode) {
            MODE_ON -> MemoryTier.LOW
            MODE_OFF -> MemoryTier.NORMAL
            else -> detected
        }

    val isLow: Boolean get() = tier == MemoryTier.LOW

    /** Apply the "Low memory mode" setting's raw value; anything unrecognised (including null) means auto. */
    fun applyMode(value: String?) {
        mode = when (value?.trim()) {
            MODE_ON -> MODE_ON
            MODE_OFF -> MODE_OFF
            else -> MODE_AUTO
        }
    }

    /** [normal] on a [MemoryTier.NORMAL] device, [low] on a [MemoryTier.LOW] one: the one-liner every cache
     *  bound is written with. */
    fun <T> pick(normal: T, low: T): T = if (isLow) low else normal
}
