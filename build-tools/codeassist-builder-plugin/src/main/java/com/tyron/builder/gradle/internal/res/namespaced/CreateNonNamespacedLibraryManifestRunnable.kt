package com.tyron.builder.gradle.internal.res.namespaced

import org.gradle.api.file.RegularFileProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

abstract class CreateNonNamespacedLibraryManifestRunnable :
    WorkAction<CreateNonNamespacedLibraryManifestRequest> {

    override fun execute() {
        NamespaceRemover.rewrite(
            parameters.originalManifestFile.asFile.get().toPath(),
            parameters.strippedManifestFile.asFile.get().toPath()
        )
    }
}

abstract class CreateNonNamespacedLibraryManifestRequest : WorkParameters {
    abstract val originalManifestFile: RegularFileProperty
    abstract val strippedManifestFile: RegularFileProperty
}
