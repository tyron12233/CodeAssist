// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where work that BLOCKS runs: a socket waiting on a reply, a file being read or written.
 *
 * Never `Dispatchers.Default`, which is the CPU pool — its width is the core count, and a thread parked on
 * a response occupies one of those slots doing nothing. On a phone that is what a stutter is made of.
 *
 * Expect/actual because `Dispatchers.IO` is a JVM declaration; coroutines marks it internal on Kotlin/Native,
 * where the right primitive is a GCD queue instead. One dispatcher for the whole process on each: the UI's
 * file reads, the store's requests and the dependency resolver's downloads are all the same kind of work,
 * and splitting them across pools only makes the sizing harder to reason about.
 */
expect val ioDispatcher: CoroutineDispatcher
