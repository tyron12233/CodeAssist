// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.lang.kotlin.interp

/** One thread runs all analysis in the browser, so a plain map is already safe. */
internal actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()
