// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * Milliseconds since the epoch.
 *
 * Wall-clock time, not elapsed time: this is for stamping something that outlives the process — a log
 * record, a cache entry's age — and it can jump backwards when the device's clock is corrected. Measuring a
 * duration wants `kotlin.time.TimeSource.Monotonic` instead, which cannot.
 */
expect fun epochMillis(): Long
