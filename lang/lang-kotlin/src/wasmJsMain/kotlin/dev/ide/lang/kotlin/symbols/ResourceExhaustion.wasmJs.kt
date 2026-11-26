// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lang.kotlin.symbols

import kotlin.js.JsException

/**
 * Stack exhaustion reaches Kotlin/Wasm as the engine's own `RangeError` ("Maximum call stack size exceeded"),
 * surfaced as a [JsException]. It must propagate rather than be swallowed, so the inferred-type memo is not
 * poisoned with a half-computed answer.
 */
internal actual fun isResourceExhaustion(e: Throwable): Boolean {
    if (e !is JsException) return false
    val text = e.message.orEmpty()
    return "RangeError" in text || "call stack" in text
}
