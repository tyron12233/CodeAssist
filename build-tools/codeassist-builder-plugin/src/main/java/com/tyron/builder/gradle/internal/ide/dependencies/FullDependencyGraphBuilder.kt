package com.tyron.builder.gradle.internal.ide.dependencies

import com.tyron.builder.gradle.internal.attributes.VariantAttr
import com.tyron.builder.gradle.internal.dependency.ResolutionResultProvider
import com.tyron.builder.gradle.internal.ide.v2.ArtifactDependenciesImpl
import com.tyron.builder.gradle.internal.ide.v2.GraphItemImpl
import com.tyron.builder.gradle.internal.ide.v2.UnresolvedDependencyImpl
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.testFixtures.isProjectTestFixturesCapability
import com.tyron.builder.model.v2.ide.ArtifactDependencies
import com.tyron.builder.model.v2.ide.GraphItem
import com.tyron.builder.model.v2.ide.UnresolvedDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File

class FullDependencyGraphBuilder(
    private val inputs: ArtifactCollectionsInputs,
    private val resolutionResultProvider: ResolutionResultProvider,
    private val libraryService: LibraryService
) {

    private val unresolvedDependencies = mutableMapOf<String, UnresolvedDependency>()

    fun build(): ArtifactDependencies = ArtifactDependenciesImpl(
        buildGraph(AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH),
        buildGraph(AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH),
        unresolvedDependencies.values.toList()
    )

    private fun buildGraph(
        configType: AndroidArtifacts.ConsumedConfigType,
    ): List<GraphItem> {
        // query for the actual graph, and get the first level children.
        val roots: Set<DependencyResult> = resolutionResultProvider.getResolutionResult(configType).root.dependencies

        // get the artifact first. This is a flat list of items that have been computed
        // to contain information about the actual artifacts (whether they are sub-projects
        // or external dependencies, whether they are java or android, whether they are
        // wrapper local jar/aar, etc...)
        val artifacts = inputs.getAllArtifacts(configType)

        val artifactMap = artifacts.associateBy { it.variant.toKey() }

        // Keep a list of the visited nodes so that we don't revisit them in different branches.
        // This is a map so that we can easy get the matching GraphItem for it,
        val visited = mutableMapOf<ResolvedVariantResult, GraphItem>()

        val items = mutableListOf<GraphItem>()
        // at the top level, there can be a duplicate of all the dependencies if the graph is
        // setup via constraints, which is the case for our compile classpath always as the
        // constraints come from the runtime classpath
        for (dependency in roots.filter { !it.isConstraint }) {
            handleDependency(dependency, visited, artifactMap)?.let {
                items.add(it)
            }
        }

        // handle local Jars. They are not visited via the roots but they are part
        // of the artifacts list.
        val unvisitedArtifacts = artifacts.filter { it.componentIdentifier is OpaqueComponentArtifactIdentifier }

        for (artifact in unvisitedArtifacts) {
            val library = libraryService.getLibrary(artifact)
            items.add(GraphItemImpl(library.key, null))
        }

        return items.toList()
    }

    private fun handleDependency(
        dependency: DependencyResult,
        visited: MutableMap<ResolvedVariantResult, GraphItem>,
        artifactMap: Map<VariantKey, ResolvedArtifact>
    ): GraphItem? {
        if (dependency.isConstraint) return null
        if (dependency !is ResolvedDependencyResult) {
            (dependency as? UnresolvedDependencyResult)?.let {
                val name = it.attempted.toString()
                if (!unresolvedDependencies.containsKey(name)) {
                    unresolvedDependencies[name] = UnresolvedDependencyImpl(
                        name,
                        it.failure.cause?.message
                    )
                }
            }
            return null
        }

        // ResolvedVariantResult getResolvedVariant() should not return null, but there seems to be
        // some corner cases when it is null. https://issuetracker.google.com/214259374
        val variant: ResolvedVariantResult? = dependency.resolvedVariant
        if (variant == null) {
            val name = dependency.requested.toString()
            if (!unresolvedDependencies.containsKey(name)) {
                unresolvedDependencies[name] = UnresolvedDependencyImpl(
                    name,
                    "Internal error: ResolvedVariantResult getResolvedVariant() should not return null. https://issuetracker.google.com/214259374"
                )
            }
            return null
        }

        // check if we already visited this.
        val graphItem = visited[variant]
        if (graphItem != null) {
            return graphItem
        }

        val variantKey = variant.toKey()
        val artifact = artifactMap[variantKey]
        val variantDependencies by lazy {
            dependency.selected.getDependenciesForVariant(variant)
        }

        val library = if (artifact == null) {
            val owner = variant.owner

            // There are 4 (currently known) reasons this can happen:
            // 1. when an artifact is relocated via Gradle's module "available-at" feature.
            // 2. when resolving a test graph, as one of the roots will be the same module and this
            //    is not included in the other artifact-based API.
            // 3. when an external dependency is without artifact file, but with transitive
            //    dependencies
            // 4. when resolving a dynamic-feature dependency graph; e.g., the app module does not
            //    publish an ArtifactType.JAR artifact to runtimeElements
            //
            // In cases 1, 2, and 3, there are still dependencies, so we need to create a library
            // object, and traverse the dependencies.
            //
            // In case 4, we want to ignore the app dependency and any transitive dependencies.
            if (variant.externalVariant.isPresent) {
                // Scenario 1
                libraryService.getLibrary(
                    ResolvedArtifact(
                        owner,
                        variant,
                        variantName = "unknown",
                        artifactFile = null,
                        isTestFixturesArtifact = false,
                        extractedFolder = null,
                        publishedLintJar = null,
                        dependencyType = ResolvedArtifact.DependencyType.RELOCATED_ARTIFACT,
                        isWrappedModule = false,
                        buildMapping = inputs.buildMapping
                    )
                )
            } else if (owner is ProjectComponentIdentifier && inputs.projectPath == owner.projectPath) {
                // Scenario 2
                // create on the fly a ResolvedArtifact around this project
                // and get the matching library item
                libraryService.getLibrary(
                    ResolvedArtifact(
                        owner,
                        variant,
                        variantName = variant.attributes
                            .getAttribute(VariantAttr.ATTRIBUTE)
                            ?.toString()
                            ?: "unknown",
                        artifactFile = File("wont/matter"),
                        isTestFixturesArtifact = variant.capabilities.any {
                            it.isProjectTestFixturesCapability(owner.projectName)
                        },
                        extractedFolder = null,
                        publishedLintJar = null,
                        dependencyType = ResolvedArtifact.DependencyType.ANDROID,
                        isWrappedModule = false,
                        buildMapping = inputs.buildMapping
                    )
                )
            } else if (owner !is ProjectComponentIdentifier && variantDependencies.isNotEmpty()) {
                // Scenario 3
                libraryService.getLibrary(
                    ResolvedArtifact(
                        owner,
                        variant,
                        variantName = "unknown",
                        artifactFile = null,
                        isTestFixturesArtifact = false,
                        extractedFolder = null,
                        publishedLintJar = null,
                        dependencyType = ResolvedArtifact.DependencyType.NO_ARTIFACT_FILE,
                        isWrappedModule = false,
                        buildMapping = inputs.buildMapping
                    )
                )
            } else {
                // Scenario 4 or other unknown scenario
                null
            }
        } else {
            // get the matching library item
            libraryService.getLibrary(artifact)
        }

        if (library != null) {
            // Create GraphItem for the library first and add it to cache in order to avoid cycles.
            // See http://b/232075280.
            val libraryGraphItem = GraphItemImpl(
                library.key,
                null
            ).also {
                visited[variant] = it
            }

            // Now visit children, and add them as dependencies
            variantDependencies.forEach {
                handleDependency(it, visited, artifactMap)?.let { childGraphItem ->
                    libraryGraphItem.addDependency(childGraphItem)
                }
            }
            return libraryGraphItem
        }

        return null
    }
}
