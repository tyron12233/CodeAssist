/*
 * Copyright 2011 the original author or authors.
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

import com.google.common.collect.Sets;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.DependencyVerificationOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.CompositeResolvedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.LocalDependencyFiles;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ParallelResolveArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactResults;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactsResults;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedFileDependencyResults;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.oldresult.TransientConfigurationResults;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.oldresult.TransientConfigurationResultsLoader;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.FileCollectionDependency;
import com.tyron.builder.api.artifacts.LenientConfiguration;
import com.tyron.builder.api.artifacts.ResolveException;
import com.tyron.builder.api.artifacts.ResolvedArtifact;
import com.tyron.builder.api.artifacts.ResolvedDependency;
import com.tyron.builder.api.artifacts.UnresolvedDependency;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.artifacts.DependencyGraphNodeResult;
import com.tyron.builder.api.internal.artifacts.ResolveArtifactsBuildOperationType;

import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransforms;
import com.tyron.builder.api.internal.artifacts.transform.VariantSelector;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.util.Predicates;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.graph.CachingDirectedGraphWalker;
import com.tyron.builder.internal.graph.DirectedGraphWithEdgeValues;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class DefaultLenientConfiguration implements LenientConfiguration, VisitedArtifactSet {

    private final static ResolveArtifactsBuildOperationType.Result RESULT = new ResolveArtifactsBuildOperationType.Result() {
    };

    private final ConfigurationInternal configuration;
    private final Set<UnresolvedDependency> unresolvedDependencies;
    private final VisitedArtifactsResults artifactResults;
    private final VisitedFileDependencyResults fileDependencyResults;
    private final TransientConfigurationResultsLoader transientConfigurationResultsFactory;
    private final ArtifactTransforms artifactTransforms;
    private final AttributeContainerInternal implicitAttributes;
    private final BuildOperationExecutor buildOperationExecutor;
    private final DependencyVerificationOverride dependencyVerificationOverride;

    // Selected for the configuration
    private SelectedArtifactResults artifactsForThisConfiguration;

    public DefaultLenientConfiguration(ConfigurationInternal configuration, Set<UnresolvedDependency> unresolvedDependencies, VisitedArtifactsResults artifactResults, VisitedFileDependencyResults fileDependencyResults, TransientConfigurationResultsLoader transientConfigurationResultsLoader, ArtifactTransforms artifactTransforms, BuildOperationExecutor buildOperationExecutor, DependencyVerificationOverride dependencyVerificationOverride) {
        this.configuration = configuration;
        this.implicitAttributes = configuration.getAttributes().asImmutable();
        this.unresolvedDependencies = unresolvedDependencies;
        this.artifactResults = artifactResults;
        this.fileDependencyResults = fileDependencyResults;
        this.transientConfigurationResultsFactory = transientConfigurationResultsLoader;
        this.artifactTransforms = artifactTransforms;
        this.buildOperationExecutor = buildOperationExecutor;
        this.dependencyVerificationOverride = dependencyVerificationOverride;
    }

    private SelectedArtifactResults getSelectedArtifacts() {
        if (artifactsForThisConfiguration == null) {
            artifactsForThisConfiguration = artifactResults.select(Predicates.satisfyAll(), artifactTransforms.variantSelector(implicitAttributes, false, configuration.getDependenciesResolver()));
        }
        return artifactsForThisConfiguration;
    }

    public SelectedArtifactSet select() {
        return select(Predicates.satisfyAll(), implicitAttributes, Predicates.satisfyAll(), false);
    }

    public SelectedArtifactSet select(final Predicate<? super Dependency> dependencySpec) {
        return select(dependencySpec, implicitAttributes, Predicates.satisfyAll(), false);
    }

    @Override
    public SelectedArtifactSet select(final Predicate<? super Dependency> dependencySpec, final AttributeContainerInternal requestedAttributes, final Predicate<? super ComponentIdentifier> componentSpec, boolean allowNoMatchingVariants) {
        VariantSelector selector = artifactTransforms.variantSelector(requestedAttributes, allowNoMatchingVariants, configuration.getDependenciesResolver());
        SelectedArtifactResults artifactResults = this.artifactResults.select(componentSpec, selector);

        return new SelectedArtifactSet() {
            @Override
            public void visitDependencies(TaskDependencyResolveContext context) {
                for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                    context.visitFailure(unresolvedDependency.getProblem());
                }
                context.add(artifactResults.getArtifacts());
            }

            @Override
            public void visitArtifacts(ArtifactVisitor visitor, boolean continueOnSelectionFailure) {
                if (!unresolvedDependencies.isEmpty()) {
                    for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                        visitor.visitFailure(unresolvedDependency.getProblem());
                    }
                    if (!continueOnSelectionFailure) {
                        return;
                    }
                }
                DefaultLenientConfiguration.this.visitArtifactsWithBuildOperation(dependencySpec, artifactResults, DefaultLenientConfiguration.this.fileDependencyResults, visitor);
            }
        };
    }

    private ResolveException getFailure() {
        List<Throwable> failures = new ArrayList<>();
        for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
            failures.add(unresolvedDependency.getProblem());
        }
        return new ResolveException(configuration.getDisplayName(), failures);
    }

    public boolean hasError() {
        return unresolvedDependencies.size() > 0;
    }

    @Override
    public Set<UnresolvedDependency> getUnresolvedModuleDependencies() {
        return unresolvedDependencies;
    }

    public void rethrowFailure() throws ResolveException {
        if (hasError()) {
            throw getFailure();
        }
    }

    private TransientConfigurationResults loadTransientGraphResults(SelectedArtifactResults artifactResults) {
        return transientConfigurationResultsFactory.create(artifactResults);
    }

    @Override
    public Set<ResolvedDependency> getFirstLevelModuleDependencies(Predicate<? super Dependency> dependencySpec) {
        Set<ResolvedDependency> matches = new LinkedHashSet<>();
        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            matches.add(node.getPublicView());
        }
        return matches;
    }

    private Set<DependencyGraphNodeResult> getFirstLevelNodes(Predicate<? super Dependency> dependencySpec) {
        Set<DependencyGraphNodeResult> matches = new LinkedHashSet<>();
        TransientConfigurationResults graphResults = loadTransientGraphResults(getSelectedArtifacts());
        for (Map.Entry<Dependency, DependencyGraphNodeResult> entry : graphResults.getFirstLevelDependencies().entrySet()) {
            if (dependencySpec.test(entry.getKey())) {
                matches.add(entry.getValue());
            }
        }
        return matches;
    }

    @Override
    public Set<ResolvedDependency> getAllModuleDependencies() {
        Set<ResolvedDependency> resolvedElements = new LinkedHashSet<>();
        Deque<ResolvedDependency> workQueue = new LinkedList<>(loadTransientGraphResults(getSelectedArtifacts()).getRootNode().getPublicView().getChildren());
        while (!workQueue.isEmpty()) {
            ResolvedDependency item = workQueue.removeFirst();
            if (resolvedElements.add(item)) {
                final Set<ResolvedDependency> children = item.getChildren();
                workQueue.addAll(children);
            }
        }
        return resolvedElements;
    }

    @Override
    public Set<File> getFiles() {
        return getFiles(Predicates.satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    @Override
    public Set<File> getFiles(Predicate<? super Dependency> dependencySpec) {
        LenientFilesAndArtifactResolveVisitor visitor = new LenientFilesAndArtifactResolveVisitor();
        visitArtifactsWithBuildOperation(dependencySpec, getSelectedArtifacts(), fileDependencyResults, visitor);
        return visitor.files;
    }

    @Override
    public Set<ResolvedArtifact> getArtifacts() {
        return getArtifacts(Predicates.satisfyAll());
    }

    /**
     * Recursive but excludes unsuccessfully resolved artifacts.
     */
    @Override
    public Set<ResolvedArtifact> getArtifacts(Predicate<? super Dependency> dependencySpec) {
        LenientArtifactCollectingVisitor visitor = new LenientArtifactCollectingVisitor();
        visitArtifactsWithBuildOperation(dependencySpec, getSelectedArtifacts(), fileDependencyResults, visitor);
        return visitor.artifacts;
    }

    private void visitArtifactsWithBuildOperation(final Predicate<? super Dependency> dependencySpec, final SelectedArtifactResults artifactResults, final VisitedFileDependencyResults fileDependencyResults, final ArtifactVisitor visitor) {
        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                visitArtifacts(dependencySpec, artifactResults, fileDependencyResults, visitor);
                dependencyVerificationOverride.artifactsAccessed(configuration.getDisplayName());
                context.setResult(RESULT);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                String displayName = "Resolve files of " + configuration.getIdentityPath();
                return BuildOperationDescriptor
                    .displayName(displayName)
                    .progressDisplayName(displayName)
                    .details(new ResolveArtifactsDetails(configuration.getPath()));
            }
        });
    }

    private static class ResolveArtifactsDetails implements ResolveArtifactsBuildOperationType.Details {

        private final String configuration;

        public ResolveArtifactsDetails(String configuration) {
            this.configuration = configuration;
        }

        @Override
        public String getConfigurationPath() {
            return configuration;
        }

    }

    /**
     * Recursive, includes unsuccessfully resolved artifacts
     *
     * @param dependencySpec dependency spec
     */
    private void visitArtifacts(Predicate<? super Dependency> dependencySpec, SelectedArtifactResults artifactResults, VisitedFileDependencyResults fileDependencyResults, ArtifactVisitor visitor) {

        //this is not very nice might be good enough until we get rid of ResolvedConfiguration and friends
        //avoid traversing the graph causing the full ResolvedDependency graph to be loaded for the most typical scenario
        if (dependencySpec == Predicates.SATISFIES_ALL) {
            ParallelResolveArtifactSet.wrap(artifactResults.getArtifacts(), buildOperationExecutor).visit(visitor);
            return;
        }

        List<ResolvedArtifactSet> artifactSets = new ArrayList<>();

        for (Map.Entry<FileCollectionDependency, Integer> entry : fileDependencyResults.getFirstLevelFiles().entrySet()) {
            if (dependencySpec.test(entry.getKey())) {
                artifactSets.add(artifactResults.getArtifactsWithId(entry.getValue()));
            }
        }

        CachingDirectedGraphWalker<DependencyGraphNodeResult, ResolvedArtifact> walker = new CachingDirectedGraphWalker<>(new ResolvedDependencyArtifactsGraph(artifactSets));
        for (DependencyGraphNodeResult node : getFirstLevelNodes(dependencySpec)) {
            walker.add(node);
        }
        walker.findValues();
        ParallelResolveArtifactSet.wrap(CompositeResolvedArtifactSet.of(artifactSets), buildOperationExecutor).visit(visitor);
    }

    public ConfigurationInternal getConfiguration() {
        return configuration;
    }

    @Override
    public Set<ResolvedDependency> getFirstLevelModuleDependencies() {
        return getFirstLevelModuleDependencies(Predicates.SATISFIES_ALL);
    }

    private static class LenientArtifactCollectingVisitor implements ArtifactVisitor {
        final Set<ResolvedArtifact> artifacts = Sets.newLinkedHashSet();
        final Set<File> files = Sets.newLinkedHashSet();

        @Override
        public void visitArtifact(DisplayName variantName, AttributeContainer variantAttributes, List<? extends Capability> capabilities, ResolvableArtifact artifact) {
            try {
                ResolvedArtifact resolvedArtifact = artifact.toPublicView();
                files.add(resolvedArtifact.getFile());
                artifacts.add(resolvedArtifact);
            } catch (com.tyron.builder.internal.resolve.ArtifactResolveException e) {
                //ignore
            }
        }

        @Override
        public FileCollectionStructureVisitor.VisitType prepareForVisit(FileCollectionInternal.Source source) {
            if (source instanceof LocalDependencyFiles) {
                return FileCollectionStructureVisitor.VisitType.NoContents;
            }
            return FileCollectionStructureVisitor.VisitType.Visit;
        }

        @Override
        public boolean requireArtifactFiles() {
            return false;
        }

        @Override
        public void visitFailure(Throwable failure) {
            throw UncheckedException.throwAsUncheckedException(failure);
        }
    }

    private static class LenientFilesAndArtifactResolveVisitor extends LenientArtifactCollectingVisitor {
        @Override
        public FileCollectionStructureVisitor.VisitType prepareForVisit(FileCollectionInternal.Source source) {
            return FileCollectionStructureVisitor.VisitType.Visit;
        }
    }

    public static class ArtifactResolveException extends ResolveException {
        private final String type;
        private final String displayName;

        public ArtifactResolveException(String type, String path, String displayName, Iterable<Throwable> failures) {
            super(path, failures);
            this.type = type;
            this.displayName = displayName;
        }

        // Need to override as error message is hardcoded in constructor of public type ResolveException
        @Override
        public String getMessage() {
            return String.format("Could not resolve all %s for %s.", type, displayName);
        }
    }

    private static class ResolvedDependencyArtifactsGraph implements DirectedGraphWithEdgeValues<DependencyGraphNodeResult, ResolvedArtifact> {
        private final List<ResolvedArtifactSet> dest;

        ResolvedDependencyArtifactsGraph(List<ResolvedArtifactSet> dest) {
            this.dest = dest;
        }

        @Override
        public void getNodeValues(DependencyGraphNodeResult node, Collection<? super ResolvedArtifact> values, Collection<? super DependencyGraphNodeResult> connectedNodes) {
            connectedNodes.addAll(node.getOutgoingEdges());
            dest.add(node.getArtifactsForNode());
        }

        @Override
        public void getEdgeValues(DependencyGraphNodeResult from, DependencyGraphNodeResult to, Collection<ResolvedArtifact> values) {
        }
    }
}
