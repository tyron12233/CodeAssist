package com.tyron.builder.api.artifact

import org.gradle.api.file.RegularFile

/**
 * List of [ScopedArtifacts.Scope] artifacts.
 */
sealed class ScopedArtifact: Artifact.Single<RegularFile>(FILE, Category.INTERMEDIATES) {

    /**
     * .class files, result of sources compilation and/or external dependencies depending on the
     * scope
     */
    object CLASSES: ScopedArtifact()
}