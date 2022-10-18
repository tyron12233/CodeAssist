package com.tyron.builder.gradle.internal.ide.dependencies

import com.tyron.builder.gradle.internal.ide.dependencies.DependencyModelBuilder.ClasspathType
import com.tyron.builder.gradle.internal.ide.level2.FullDependencyGraphsImpl
import com.tyron.builder.gradle.internal.ide.level2.GraphItemImpl
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.model.level2.DependencyGraphs
import com.tyron.builder.model.level2.GraphItem
import com.google.common.collect.ImmutableList
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.services.BuildServiceRegistry
import java.io.File

class Level2DependencyModelBuilder(buildServiceRegistry: BuildServiceRegistry) :
    DependencyModelBuilder<DependencyGraphs> {

    private val compileItems = ImmutableList.builder<GraphItem>()
    private val runtimeItems = ImmutableList.builder<GraphItem>()
    private val providedLibraries = ImmutableList.builder<String>()

    private val libraryDependencyCache =
        getBuildService(
            buildServiceRegistry,
            LibraryDependencyCacheBuildService::class.java
        )
            .get()

    private val mavenCoordinatesCacheBuildService =
        getBuildService(
            buildServiceRegistry,
            MavenCoordinatesCacheBuildService::class.java
        )
            .get()

    override fun createModel() : DependencyGraphs = FullDependencyGraphsImpl(
        compileItems.build(),
        runtimeItems.build(),
        providedLibraries.build(),
        ImmutableList.of() /* skipped items*/
    )

    override fun addArtifact(
        artifact: ResolvedArtifact,
        isProvided: Boolean,
        lintJarMap: Map<ComponentIdentifier, File>?,
        type: ClasspathType
    ) {
        val graphItem = GraphItemImpl(
            artifact.computeModelAddress(mavenCoordinatesCacheBuildService),
            ImmutableList.of())

        when (type) {
            ClasspathType.COMPILE -> {
                compileItems.add(graphItem)
                if (isProvided) {
                    providedLibraries.add(graphItem.artifactAddress)
                }
            }
            ClasspathType.RUNTIME -> runtimeItems.add(graphItem)
        }

        // force creation of the Library instance
        libraryDependencyCache.libraryCache[artifact]
    }

    override val needFullRuntimeClasspath: Boolean
        get() = true
    override val needRuntimeOnlyClasspath: Boolean
        get() = false

    override fun setRuntimeOnlyClasspath(files: ImmutableList<File>) {
        throw RuntimeException("Level2 does not support runtimeOnlyClasspath")
    }
}
