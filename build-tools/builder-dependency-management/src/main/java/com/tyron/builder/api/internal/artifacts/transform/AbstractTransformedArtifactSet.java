/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.EndCollection;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.model.CalculatedValueContainer;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.model.ValueCalculator;

import java.util.List;

/**
 * Transformed artifact set that performs the transformation itself when visited.
 */
public abstract class AbstractTransformedArtifactSet implements ResolvedArtifactSet, FileCollectionInternal.Source {
    private final CalculatedValueContainer<ImmutableList<ResolvedArtifactSet.Artifacts>, CalculateArtifacts> result;

    public AbstractTransformedArtifactSet(
        ComponentIdentifier componentIdentifier,
        ResolvedArtifactSet delegate,
        ImmutableAttributes targetVariantAttributes,
        List<? extends Capability> capabilities,
        Transformation transformation,
        ExtraExecutionGraphDependenciesResolverFactory dependenciesResolverFactory,
        CalculatedValueContainerFactory calculatedValueContainerFactory
    ) {
        TransformUpstreamDependenciesResolver dependenciesResolver = dependenciesResolverFactory.create(componentIdentifier, transformation);
        ImmutableList.Builder<BoundTransformationStep> builder = ImmutableList.builder();
        transformation.visitTransformationSteps(transformationStep -> builder.add(new BoundTransformationStep(transformationStep, dependenciesResolver.dependenciesFor(transformationStep))));
        ImmutableList<BoundTransformationStep> steps = builder.build();
        this.result = calculatedValueContainerFactory.create(Describables.of(componentIdentifier), new CalculateArtifacts(componentIdentifier, delegate, targetVariantAttributes, capabilities, steps));
    }

    public AbstractTransformedArtifactSet(CalculatedValueContainer<ImmutableList<ResolvedArtifactSet.Artifacts>, CalculateArtifacts> result) {
        this.result = result;
    }

    public CalculatedValueContainer<ImmutableList<Artifacts>, CalculateArtifacts> getResult() {
        return result;
    }

    @Override
    public void visit(Visitor visitor) {
        FileCollectionStructureVisitor.VisitType visitType = visitor.prepareForVisit(this);
        if (visitType == FileCollectionStructureVisitor.VisitType.NoContents) {
            visitor.visitArtifacts(new EndCollection(this));
            return;
        }

        // Calculate the artifacts now
        result.finalizeIfNotAlready();
        for (Artifacts artifacts : result.get()) {
            visitor.visitArtifacts(artifacts);
        }
        // Need to fire an "end collection" event. Should clean this up so it is not necessary
        visitor.visitArtifacts(new EndCollection(this));
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        result.visitDependencies(context);
    }

    @Override
    public void visitTransformSources(TransformSourceVisitor visitor) {
        // Should never be called
        throw new IllegalStateException();
    }

    @Override
    public void visitExternalArtifacts(Action<ResolvableArtifact> visitor) {
        // Should never be called
        throw new IllegalStateException();
    }

    public static class CalculateArtifacts implements ValueCalculator<ImmutableList<Artifacts>> {
        private final ComponentIdentifier ownerId;
        private final ResolvedArtifactSet delegate;
        private final ImmutableList<BoundTransformationStep> steps;
        private final ImmutableAttributes targetVariantAttributes;
        private final List<? extends Capability> capabilities;

        public CalculateArtifacts(ComponentIdentifier ownerId, ResolvedArtifactSet delegate, ImmutableAttributes targetVariantAttributes, List<? extends Capability> capabilities, ImmutableList<BoundTransformationStep> steps) {
            this.ownerId = ownerId;
            this.delegate = delegate;
            this.steps = steps;
            this.targetVariantAttributes = targetVariantAttributes;
            this.capabilities = capabilities;
        }

        public ComponentIdentifier getOwnerId() {
            return ownerId;
        }

        public ResolvedArtifactSet getDelegate() {
            return delegate;
        }

        public ImmutableList<BoundTransformationStep> getSteps() {
            return steps;
        }

        public ImmutableAttributes getTargetVariantAttributes() {
            return targetVariantAttributes;
        }

        public List<? extends Capability> getCapabilities() {
            return capabilities;
        }

        @Override
        public boolean usesMutableProjectState() {
            return false;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return null;
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            for (BoundTransformationStep step : steps) {
                context.add(step.getUpstreamDependencies());
            }
        }

        @Override
        public ImmutableList<Artifacts> calculateValue(NodeExecutionContext context) {
            // Isolate the transformation parameters, if not already done
            for (BoundTransformationStep step : steps) {
                step.getTransformation().isolateParametersIfNotAlready();
                step.getUpstreamDependencies().finalizeIfNotAlready();
            }

            ImmutableList.Builder<Artifacts> builder = ImmutableList.builderWithExpectedSize(1);
            delegate.visit(new TransformingAsyncArtifactListener(steps, targetVariantAttributes, capabilities, builder));
            return builder.build();
        }
    }
}
