// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.model.impl

import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleType
import dev.ide.model.Workspace
import dev.ide.model.template.ProjectScaffold
import dev.ide.platform.resolvePath
import dev.ide.platform.writeFileAtomically
import dev.ide.vfs.VirtualFile

/**
 * The [ProjectScaffold] a [dev.ide.model.template.ProjectTemplate] builds against, backed by a
 * [ProjectModelStore]: the store's transaction surface plus a file writer rooted at the workspace dir.
 *
 * It lives beside the store rather than in a host because a template is the same operation everywhere —
 * begin a transaction, add a module, write a file — and a host-owned scaffold is what kept the built-in
 * templates on the JVM.
 *
 * [languageLevel] is injected rather than derived: JAVA_17 against a desktop JDK, JAVA_8 on-device against
 * a non-modular `android.jar`. A template never asks which platform it is on.
 */
class StoreScaffold(
    private val store: ProjectModelStore,
    override val languageLevel: LanguageLevel,
) : ProjectScaffold {
    override val workspace: Workspace get() = store.workspace
    override val rootDir: VirtualFile get() = store.vfs.root()

    override fun moduleType(id: String): ModuleType = store.moduleTypes.resolve(id)

    /**
     * `trimIndent()` drops the leading/trailing blank lines of a triple-quoted literal and the common
     * indent, so a template can write source inline at its own nesting and still produce a file that
     * starts at column 0. The trailing newline is added for the same reason every source file has one.
     */
    override fun writeText(relPath: String, content: String) {
        write(relPath, (content.trimIndent() + "\n").encodeToByteArray())
    }

    /** Byte-exact: no trim, no appended newline, no charset round trip, so a binary asset survives. */
    override fun writeBytes(relPath: String, bytes: ByteArray) {
        write(relPath, bytes)
    }

    private fun write(relPath: String, bytes: ByteArray) {
        // writeFileAtomically creates the missing parents, which is what a template relies on: it names
        // `app/src/main/kotlin/com/example/Main.kt` and never the four directories above it.
        check(writeFileAtomically(resolvePath(store.root, relPath), bytes)) {
            "could not write $relPath under ${store.root}"
        }
    }
}
