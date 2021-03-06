/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.UnresolvedDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransforms;
import com.tyron.builder.api.internal.artifacts.transform.ExtraExecutionGraphDependenciesResolverFactory;
import com.tyron.builder.api.internal.artifacts.transform.VariantSelector;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.specs.Spec;

import java.util.Set;
import java.util.function.Predicate;

public class BuildDependenciesOnlyVisitedArtifactSet implements VisitedArtifactSet {
    private final Set<UnresolvedDependency> unresolvedDependencies;
    private final VisitedArtifactsResults artifactsResults;
    private final ArtifactTransforms artifactTransforms;
    private final ExtraExecutionGraphDependenciesResolverFactory resolverFactory;

    public BuildDependenciesOnlyVisitedArtifactSet(Set<UnresolvedDependency> unresolvedDependencies, VisitedArtifactsResults artifactsResults, ArtifactTransforms artifactTransforms, ExtraExecutionGraphDependenciesResolverFactory resolverFactory) {
        this.unresolvedDependencies = unresolvedDependencies;
        this.artifactsResults = artifactsResults;
        this.artifactTransforms = artifactTransforms;
        this.resolverFactory = resolverFactory;
    }

    @Override
    public SelectedArtifactSet select(Predicate<? super Dependency> dependencySpec, AttributeContainerInternal requestedAttributes, Predicate<? super ComponentIdentifier> componentSpec, boolean allowNoMatchingVariant) {
        VariantSelector variantSelector = artifactTransforms.variantSelector(requestedAttributes, allowNoMatchingVariant, resolverFactory);
        ResolvedArtifactSet selectedArtifacts = artifactsResults.select(componentSpec, variantSelector).getArtifacts();
        return new BuildDependenciesOnlySelectedArtifactSet(unresolvedDependencies, selectedArtifacts);
    }

    private static class BuildDependenciesOnlySelectedArtifactSet implements SelectedArtifactSet {
        private final Set<UnresolvedDependency> unresolvedDependencies;
        private final ResolvedArtifactSet selectedArtifacts;

        BuildDependenciesOnlySelectedArtifactSet(Set<UnresolvedDependency> unresolvedDependencies, ResolvedArtifactSet selectedArtifacts) {
            this.unresolvedDependencies = unresolvedDependencies;
            this.selectedArtifacts = selectedArtifacts;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                context.visitFailure(unresolvedDependency.getProblem());
            }
            context.add(selectedArtifacts);
        }

        @Override
        public void visitArtifacts(ArtifactVisitor visitor, boolean continueOnSelectionFailure) {
            throw new UnsupportedOperationException("Artifacts have not been resolved.");
        }
    }
}
