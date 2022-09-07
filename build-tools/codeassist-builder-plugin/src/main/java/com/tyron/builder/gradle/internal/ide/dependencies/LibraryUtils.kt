@file:JvmName("LibraryUtils")
package com.tyron.builder.gradle.internal.ide.dependencies

import com.google.common.collect.Lists
import com.tyron.builder.gradle.internal.attributes.VariantAttr
import com.tyron.builder.gradle.internal.ide.DependenciesImpl
import com.tyron.builder.gradle.internal.testFixtures.isLibraryTestFixturesCapability
import com.tyron.builder.gradle.internal.testFixtures.isProjectTestFixturesCapability
import com.tyron.builder.model.AndroidLibrary
import com.tyron.builder.model.AndroidProject
import com.tyron.builder.model.Dependencies
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

fun clone(dependencies: Dependencies, modelLevel: Int): Dependencies {
    if (modelLevel >= AndroidProject.MODEL_LEVEL_4_NEW_DEP_MODEL) {
        return DependenciesImpl.EMPTY
    }

    // these items are already ready for serializable, all we need to clone is
    // the Dependencies instance.
    val libraries = emptyList<AndroidLibrary>()
    val javaLibraries = Lists.newArrayList(dependencies.javaLibraries)
    val projects = emptyList<Dependencies.ProjectIdentifier>()

    return DependenciesImpl(
        libraries,
        javaLibraries,
        projects,
        Lists.newArrayList(dependencies.runtimeOnlyClasses)
    )
}

fun ResolvedArtifactResult.getVariantName(): String? {
    return variant.attributes.getAttribute(VariantAttr.ATTRIBUTE)?.name
}

/**
 * Checks if the resolved artifact is produced from a local project with testFixtures capability.
 */
fun ResolvedArtifactResult.hasProjectTestFixturesCapability(): Boolean {
    if (id.componentIdentifier !is ProjectComponentIdentifier) {
        return false
    }
    return variant.capabilities.any {
        it.isProjectTestFixturesCapability(
            (id.componentIdentifier as ProjectComponentIdentifier).projectName
        )
    }
}

/**
 * Checks if the resolved artifact is coming from an external library with testFixtures capability.
 */
fun ResolvedArtifactResult.hasLibraryTestFixturesCapability(): Boolean {
    if (id.componentIdentifier !is ModuleComponentIdentifier) {
        return false
    }
    return variant.capabilities.any {
        it.isLibraryTestFixturesCapability(
            libraryName = (id.componentIdentifier as ModuleComponentIdentifier).module
        )
    }
}
