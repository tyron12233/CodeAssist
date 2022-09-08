package com.tyron.builder.model.v2.models

import com.tyron.builder.model.v2.AndroidModel
import java.io.File

/**
 * A Simple model that contains a map of (build-name, build rootDir).
 *
 * This can be queried on any Android module (and returns the same value regardless of the module)
 */
interface BuildMap: AndroidModel {

    /**
     * Map of build-ID, for composite builds.
     *
     * The map contais (name to rootDir) where rootDir is what is typically
     * returned by [org.gradle.tooling.model.BuildIdentifier.BuildIdenfier.rootDir] while name is
     * what is returned by [org.gradle.api.artifacts.component.BuildIdentifier.getName]
     */
    val buildIdMap: Map<String, File>
}
