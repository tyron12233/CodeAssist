@file:JvmName("ConstraintHandler")
package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.gradle.internal.services.StringCachingBuildService
import com.tyron.builder.gradle.internal.ide.dependencies.getIdString
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * Synchronizes this configuration to the specified one, so they resolve to the same dependencies.
 *
 * It does that by leveraging [ResolvableDependencies.beforeResolve].
 */
internal fun Configuration.alignWith(
    srcConfiguration: Configuration,
    dependencyHandler: DependencyHandler,
    isTest: Boolean,
    cachedStringBuildService: Provider<StringCachingBuildService>
) {
    incoming.beforeResolve {
        val srcConfigName = srcConfiguration.name

        val configName = this.name
        val stringCachingService = cachedStringBuildService.get()

        srcConfiguration.incoming.resolutionResult.allDependencies { dependency ->
            if (dependency is ResolvedDependencyResult) {
                val componentIdentifier = dependency.selected.id
                if (componentIdentifier is ModuleComponentIdentifier) {
                    // using a repository with a flatDir to stock local AARs will result in an
                    // external module dependency with no version.
                    if (!componentIdentifier.version.isNullOrEmpty()) {
                        if (!isTest || componentIdentifier.module != "listenablefuture" || componentIdentifier.group != "com.google.guava" || componentIdentifier.version != "1.0") {
                            dependencyHandler.constraints.add(
                                configName,
                                "${componentIdentifier.group}:${componentIdentifier.module}:${componentIdentifier.version}"
                            ) { constraint ->
                                constraint.because(stringCachingService.cacheString("$srcConfigName uses version ${componentIdentifier.version}"))
                                constraint.version { versionConstraint ->
                                    versionConstraint.strictly(componentIdentifier.version)
                                }
                            }
                        }
                    }
                } else if (componentIdentifier is ProjectComponentIdentifier
                    && componentIdentifier.build.isCurrentBuild
                    && dependency.requested is ModuleComponentSelector
                ) {
                    // Requested external library has been replaced with the project dependency,
                    // add the same substitution to the target configuration, so it can be chosen
                    // instead of the external library as well.
                    // We should avoid doing this for composite builds, so we check if the selected
                    // project is from the current build.
                    resolutionStrategy.dependencySubstitution.let { sb ->
                        sb.substitute(dependency.requested)
                            .because(stringCachingService.cacheString(
                                "$srcConfigName uses project ${componentIdentifier.getIdString()}"))
                            .using(sb.project(componentIdentifier.getIdString()))
                    }
                }
            }
        }
    }
}
