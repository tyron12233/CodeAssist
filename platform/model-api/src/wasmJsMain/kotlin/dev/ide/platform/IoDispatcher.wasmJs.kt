// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** No thread pool in the browser: "IO" shares the one event-loop dispatcher. */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
