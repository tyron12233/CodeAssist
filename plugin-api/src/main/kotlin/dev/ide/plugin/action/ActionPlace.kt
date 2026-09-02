// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.plugin.action

/**
 * Where an action can appear. An open, string-backed set so a plugin (or a new UI surface) can introduce its
 * own place without a change here. The built-in places are in [ActionPlaces].
 */
@JvmInline
value class ActionPlace(val id: String)