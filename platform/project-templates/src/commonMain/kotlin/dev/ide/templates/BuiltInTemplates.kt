// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.templates

import dev.ide.model.template.ProjectTemplate

/**
 * The templates every host offers, in gallery order.
 *
 * A list rather than each host naming them one by one, so adding one reaches all of them. A host still
 * registers them itself (through its own plugin id), because which templates it offers is its decision:
 * the Android hosts add theirs, and a host with no Java editor is right not to offer the Java ones.
 */
val BUILT_IN_KOTLIN_TEMPLATES: List<ProjectTemplate> = listOf(KotlinConsoleAppTemplate, KotlinLibraryTemplate)
