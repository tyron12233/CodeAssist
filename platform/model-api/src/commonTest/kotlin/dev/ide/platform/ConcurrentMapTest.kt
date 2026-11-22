// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrentMapTest {

    /** A memo whose computation fills other keys of the same map (a member walk memoizing each supertype on the
     *  way) must not throw; on the JVM `computeIfAbsent` threw `IllegalStateException: Recursive update`. */
    @Test
    fun aComputationThatFillsTheSameMapCompletes() {
        val map = ConcurrentMap<Int, Int>()
        fun depth(n: Int): Int = map.getOrPut(n) { if (n == 0) 0 else depth(n - 1) + 1 }
        assertEquals(200, depth(200))
        assertEquals(201, map.size)
    }
}
