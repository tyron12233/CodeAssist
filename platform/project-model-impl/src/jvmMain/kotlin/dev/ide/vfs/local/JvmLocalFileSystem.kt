// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.vfs.local

import dev.ide.vfs.VirtualFile
import java.nio.file.Path

/**
 * [LocalFileSystem] rooted at a `java.nio.file.Path`.
 *
 * A function rather than a secondary constructor, because the class is in `commonMain` and a constructor
 * cannot be added to it from here. It is deliberately named like the class: `import
 * dev.ide.vfs.local.LocalFileSystem` brings in every declaration of that name, so `LocalFileSystem(path)`
 * keeps compiling at call sites that already have the class imported, which is all of them.
 */
@Suppress("FunctionName")
fun LocalFileSystem(root: Path): LocalFileSystem = LocalFileSystem(root.toString())

/** A handle for [path], whether or not it exists. The string overload is the member. */
fun LocalFileSystem.fileFor(path: Path): VirtualFile = fileFor(path.toString())
