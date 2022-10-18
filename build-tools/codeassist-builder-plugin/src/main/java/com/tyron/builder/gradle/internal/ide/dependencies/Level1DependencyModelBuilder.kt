package com.tyron.builder.gradle.internal.ide.dependencies

import com.google.common.collect.ImmutableList
import com.tyron.builder.gradle.internal.ide.DependenciesImpl
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.services.BuildServiceRegistry
import java.io.File

class Level1DependencyModelBuilder(
    buildServiceRegistry: BuildServiceRegistry
): DependencyModelBuilder<DependenciesImpl> {
    private val artifactHandler: Level1ArtifactHandler = Level1ArtifactHandler(buildServiceRegistry)

    private var runtimeClasspath = ImmutableList.of<File>()

    override fun createModel(): DependenciesImpl = DependenciesImpl(
        artifactHandler.androidLibraries,
        artifactHandler.javaLibraries,
        artifactHandler.projects,
        runtimeClasspath
    )

    override fun addArtifact(
        artifact: ResolvedArtifact,
        isProvided: Boolean,
        lintJarMap: Map<ComponentIdentifier, File>?,
        type: DependencyModelBuilder.ClasspathType
    ) {
        // there's not need to check the return value of this handler as the handler itself
        // accumulate the result.
        // This is because unlike the newer dependency model, this model accumulate the different
        // types into separate list, so it's better handler by the artifact handler.
        artifactHandler.handleArtifact(
            artifact,
            isProvided,
            lintJarMap
        )
    }

    override val needFullRuntimeClasspath: Boolean
        get() = false
    override val needRuntimeOnlyClasspath: Boolean
        get() = true

    override fun setRuntimeOnlyClasspath(files: ImmutableList<File>) {
        runtimeClasspath = files
    }
}
