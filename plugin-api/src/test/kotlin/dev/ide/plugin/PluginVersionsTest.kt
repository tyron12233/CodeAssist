// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether a plugin's `minHostVersion` is satisfied. Both the loader and the editor's
 * manifest checks read it, so a change here changes what loads.
 */
class PluginVersionsTest {

    @Test
    fun `a newer host satisfies an older floor`() {
        assertTrue(PluginVersions.satisfies("3.12.0", "3.11.0"))
        assertTrue(PluginVersions.satisfies("3.12.0", "3.12.0"))
        assertTrue(PluginVersions.satisfies("4.0.0", "3.12.9"))
    }

    @Test
    fun `an older host does not`() {
        assertFalse(PluginVersions.satisfies("3.11.0", "3.12.0"))
        assertFalse(PluginVersions.satisfies("3.9.9", "3.12.0"))
    }

    @Test
    fun `component counts do not have to match`() {
        // A missing component is zero, so these are the same version.
        assertTrue(PluginVersions.satisfies("3.12", "3.12.0"))
        assertTrue(PluginVersions.satisfies("3.12.0", "3.12"))
        assertFalse(PluginVersions.satisfies("3.12", "3.12.1"))
    }

    @Test
    fun `a trailing qualifier is ignored`() {
        // A prerelease of the floor still counts as the floor, which is what a tester on a beta needs.
        assertTrue(PluginVersions.satisfies("3.12.0-beta1", "3.12.0"))
        assertTrue(PluginVersions.satisfies("3.12.0", "3.12.0-rc1"))
    }

    @Test
    fun `a missing version on either side satisfies`() {
        // No declared floor means the plugin makes no demand; no host version means the host cannot judge.
        assertTrue(PluginVersions.satisfies("3.12.0", null))
        assertTrue(PluginVersions.satisfies(null, "3.12.0"))
        assertTrue(PluginVersions.satisfies(null, null))
    }

    @Test
    fun `an unparseable component counts as zero rather than throwing`() {
        assertTrue(PluginVersions.satisfies("3.x.0", "3.0.0"))
        assertFalse(PluginVersions.satisfies("3.x.0", "3.1.0"))
    }
}
