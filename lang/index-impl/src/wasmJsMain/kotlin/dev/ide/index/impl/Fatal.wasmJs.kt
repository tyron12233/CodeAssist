// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.index.impl

import kotlin.js.JsException

/** A stack overflow reaches Kotlin/Wasm as the engine's `RangeError`: the caller's problem, not a corrupt segment. */
internal actual fun rethrowIfFatal(t: Throwable) {
    if (t is JsException && ("RangeError" in t.message.orEmpty() || "call stack" in t.message.orEmpty())) throw t
}
