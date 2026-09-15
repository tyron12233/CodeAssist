package dev.ide.store.impl.platform

import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.impl.ProjectPackager
import java.io.File

/**
 * A thin adapter rather than `ProjectPackager : ProjectArchiver`, because the port is internal to this
 * module and the packager is public API its tests and `:ide-core` construct directly.
 */
internal actual fun defaultArchiver(): ProjectArchiver = object : ProjectArchiver {
    private val packager = ProjectPackager()
    override fun pack(rootPath: String, intoPath: String?): StoreResult<PackagedProject> =
        packager.pack(rootPath, intoPath?.let(::File))
}
