// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

/**
 * How a plugin's [PluginManifest.minHostVersion] is compared against the IDE's own version. Published as
 * part of the SPI because it is the rule that decides whether a plugin loads, and because both the loader
 * and the editor's manifest checks have to reach the same verdict: an editor that disagreed with the loader
 * would either miss a plugin that cannot load or flag one that can.
 */
object PluginVersions {

    /**
     * Dotted-numeric comparison, ignoring any trailing qualifier, so `3.11.0-beta1` compares equal to
     * `3.11.0`. A missing or non-numeric component counts as 0, which keeps `3.12` and `3.12.0` equal.
     */
    fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val c = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }

    /** True when [hostVersion] satisfies a manifest's [minHostVersion]; a null on either side satisfies. */
    fun satisfies(hostVersion: String?, minHostVersion: String?): Boolean {
        if (minHostVersion == null || hostVersion == null) return true
        return compare(hostVersion, minHostVersion) >= 0
    }

    private fun parts(v: String): List<Int> =
        v.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
}
