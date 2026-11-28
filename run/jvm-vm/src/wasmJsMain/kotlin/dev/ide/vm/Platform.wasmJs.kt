// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.vm

@JsFun("() => performance.now()")
private external fun jsNowMillis(): Double

/**
 * Kotlin/Wasm has no identity hash. A plain object (every [VmObject], array and host handle) does not override
 * `hashCode`, so the default is identity-based; a value type (`String`, a box) hashes by value, which is also
 * what its Java identity hash would effectively be used for here.
 */
internal actual fun identityHash(o: Any): Int = o.hashCode()

// Kotlin/Wasm's Float/Double.toString follow the Kotlin spec (shortest round-trip digits, `1.0E10`), which is
// Java's format; VmCasesTest holds that to the JVM's answers.
internal actual fun javaFloatToString(f: Float): String = f.toString()
internal actual fun javaDoubleToString(d: Double): String = d.toString()

internal actual fun nanoTime(): Long = (jsNowMillis() * 1_000_000.0).toLong()
