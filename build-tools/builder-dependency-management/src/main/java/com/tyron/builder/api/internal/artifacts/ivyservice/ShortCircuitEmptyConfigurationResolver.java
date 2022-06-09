/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.internal.artifacts.ivyservice;

import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyLockingProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyLockingState;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedLocalComponentsResult;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedLocalComponentsResultGraphVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.DefaultResolutionResultBuilder;
import com.tyron.builder.api.internal.artifacts.repositories.ResolutionAwareRepository;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.LenientConfiguration;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ResolveException;
import com.tyron.builder.api.artifacts.ResolvedArtifact;
import com.tyron.builder.api.artifacts.ResolvedConfiguration;
import com.tyron.builder.api.artifacts.ResolvedDependency;
import com.tyron.builder.api.artifacts.UnresolvedDependency;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.result.ResolutionResult;
import com.tyron.builder.api.internal.artifacts.ConfigurationResolver;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.Module;
import com.tyron.builder.api.internal.artifacts.ResolverResults;
import com.tyron.builder.api.internal.artifacts.component.ComponentIdentifierFactory;

import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.specs.Spec;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class ShortCircuitEmptyConfigurationResolver implements ConfigurationResolver {
    private final ConfigurationResolver delegate;
    private final ComponentIdentifierFactory componentIdentifierFactory;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final BuildIdentifier thisBuild;

    public ShortCircuitEmptyConfigurationResolver(ConfigurationResolver delegate, ComponentIdentifierFactory componentIdentifierFactory, ImmutableModuleIdentifierFactory moduleIdentifierFactory, BuildIdentifier thisBuild) {
        this.delegate = delegate;
        this.componentIdentifierFactory = componentIdentifierFactory;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        this.thisBuild = thisBuild;
    }

    @Override
    public List<ResolutionAwareRepository> getRepositories() {
        return delegate.getRepositories();
    }

    @Override
    public void resolveBuildDependencies(ConfigurationInternal configuration, ResolverResults result) {
        if (configuration.getAllDependencies().isEmpty()) {
            emptyGraph(configuration, result, false);
        } else {
            delegate.resolveBuildDependencies(configuration, result);
        }
    }

    @Override
    public void resolveGraph(ConfigurationInternal configuration, ResolverResults results) throws ResolveException {
        if (configuration.getAllDependencies().isEmpty()) {
            emptyGraph(configuration, results, true);
        } else {
            delegate.resolveGraph(configuration, results);
        }
    }

    private void emptyGraph(ConfigurationInternal configuration, ResolverResults results, boolean verifyLocking) {
        if (verifyLocking && configuration.getResolutionStrategy().isDependencyLockingEnabled()) {
            DependencyLockingProvider
                    dependencyLockingProvider = configuration.getResolutionStrategy().getDependencyLockingProvider();
            DependencyLockingState lockingState = dependencyLockingProvider.loadLockState(configuration.getName());
            if (lockingState.mustValidateLockState() && !lockingState.getLockedDependencies().isEmpty()) {
                // Invalid lock state, need to do a real resolution to gather locking failures
                delegate.resolveGraph(configuration, results);
                return;
            }
            dependencyLockingProvider.persistResolvedDependencies(configuration.getName(), Collections.emptySet(), Collections.emptySet());
        }
        Module module = configuration.getModule();
        ModuleVersionIdentifier id = moduleIdentifierFactory.moduleWithVersion(module);
        ComponentIdentifier componentIdentifier = componentIdentifierFactory.createComponentIdentifier(module);
        ResolutionResult emptyResult = DefaultResolutionResultBuilder
                .empty(id, componentIdentifier, configuration.getAttributes());
        ResolvedLocalComponentsResult
                emptyProjectResult = new ResolvedLocalComponentsResultGraphVisitor(thisBuild);
        results.graphResolved(emptyResult, emptyProjectResult, EmptyResults.INSTANCE);
    }

    @Override
    public void resolveArtifacts(ConfigurationInternal configuration, ResolverResults results) throws ResolveException {
        if (configuration.getAllDependencies().isEmpty() && results.getVisitedArtifacts() == EmptyResults.INSTANCE) {
            results.artifactsResolved(new EmptyResolvedConfiguration(), EmptyResults.INSTANCE);
        } else {
            delegate.resolveArtifacts(configuration, results);
        }
    }

    private static class EmptyResults implements VisitedArtifactSet, SelectedArtifactSet {
        private static final EmptyResults INSTANCE = new EmptyResults();

        @Override
        public SelectedArtifactSet select(Predicate<? super Dependency> dependencySpec, AttributeContainerInternal requestedAttributes, Predicate<? super ComponentIdentifier> componentSpec, boolean allowNoMatchingVariant) {
            return this;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }

        @Override
        public void visitArtifacts(ArtifactVisitor visitor, boolean continueOnSelectionFailure) {
        }
    }

    private static class EmptyResolvedConfiguration implements ResolvedConfiguration {

        @Override
        public boolean hasError() {
            return false;
        }

        @Override
        public LenientConfiguration getLenientConfiguration() {
            return new LenientConfiguration() {
                @Override
                public Set<ResolvedDependency> getFirstLevelModuleDependencies() {
                    return Collections.emptySet();
                }

                @Override
                public Set<ResolvedDependency> getFirstLevelModuleDependencies(Predicate<? super Dependency> dependencySpec) {
                    return Collections.emptySet();
                }

                @Override
                public Set<ResolvedDependency> getAllModuleDependencies() {
                    return Collections.emptySet();
                }

                @Override
                public Set<UnresolvedDependency> getUnresolvedModuleDependencies() {
                    return Collections.emptySet();
                }

                @Override
                public Set<File> getFiles() {
                    return Collections.emptySet();
                }

                @Override
                public Set<File> getFiles(Predicate<? super Dependency> dependencySpec) {
                    return Collections.emptySet();
                }

                @Override
                public Set<ResolvedArtifact> getArtifacts() {
                    return Collections.emptySet();
                }

                @Override
                public Set<ResolvedArtifact> getArtifacts(Predicate<? super Dependency> dependencySpec) {
                    return Collections.emptySet();
                }
            };
        }

        @Override
        public void rethrowFailure() throws ResolveException {
        }

        @Override
        public Set<File> getFiles() throws ResolveException {
            return Collections.emptySet();
        }

        @Override
        public Set<File> getFiles(Predicate<? super Dependency> dependencySpec) {
            return Collections.emptySet();
        }

        @Override
        public Set<ResolvedDependency> getFirstLevelModuleDependencies() {
            return Collections.emptySet();
        }

        @Override
        public Set<ResolvedDependency> getFirstLevelModuleDependencies(Predicate<? super Dependency> dependencySpec) throws ResolveException {
            return Collections.emptySet();
        }

        @Override
        public Set<ResolvedArtifact> getResolvedArtifacts() {
            return Collections.emptySet();
        }
    }
}
