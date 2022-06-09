/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.collect.Maps;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphSelector;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.RootGraphNode;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.component.local.model.LocalFileDependencyMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.resolve.resolver.ArtifactSelector;

import java.util.List;
import java.util.Map;

/**
 * Adapts a {@link DependencyArtifactsVisitor} to a {@link DependencyGraphVisitor}. Calculates the artifacts contributed by each edge in the graph and forwards the results to the artifact visitor.
 */
public class ResolvedArtifactsGraphVisitor implements DependencyGraphVisitor {
    private int nextId;
    private final Map<Long, ArtifactsForNode> artifactsByNodeId = Maps.newHashMap();
    private final ArtifactSelector artifactSelector;
    private final DependencyArtifactsVisitor artifactResults;

    public ResolvedArtifactsGraphVisitor(DependencyArtifactsVisitor artifactsBuilder, ArtifactSelector artifactSelector) {
        this.artifactResults = artifactsBuilder;
        this.artifactSelector = artifactSelector;
    }

    @Override
    public void start(RootGraphNode root) {
        artifactResults.startArtifacts(root);
    }

    @Override
    public void visitNode(DependencyGraphNode node) {
        artifactResults.visitNode(node);
    }

    @Override
    public void visitSelector(DependencyGraphSelector selector) {
    }

    @Override
    public void visitEdges(DependencyGraphNode node) {
        for (DependencyGraphEdge dependency : node.getIncomingEdges()) {
            if (dependency.contributesArtifacts()) {
                DependencyGraphNode parent = dependency.getFrom();
                ArtifactsForNode artifacts = getArtifacts(dependency, node);
                artifactResults.visitArtifacts(parent, node, artifacts.artifactSetId, artifacts.artifactSet);
            }
        }
        for (LocalFileDependencyMetadata fileDependency : node.getOutgoingFileEdges()) {
            int id = nextId++;
            artifactResults.visitArtifacts(node, fileDependency, id, artifactSelector.resolveArtifacts(fileDependency));
        }
    }

    @Override
    public void finish(DependencyGraphNode root) {
        artifactResults.finishArtifacts();
        artifactsByNodeId.clear();
    }

    private ArtifactsForNode getArtifacts(DependencyGraphEdge dependency, DependencyGraphNode toConfiguration) {
        ConfigurationMetadata targetConfiguration = toConfiguration.getMetadata();
        ComponentResolveMetadata component = toConfiguration.getOwner().getMetadata();
        ImmutableAttributes overriddenAttributes = dependency.getAttributes();

        List<? extends ComponentArtifactMetadata> artifacts = dependency.getArtifacts(targetConfiguration);
        if (!artifacts.isEmpty()) {
            int id = nextId++;
            ArtifactSet artifactSet = artifactSelector.resolveArtifacts(component, artifacts, overriddenAttributes);
            return new ArtifactsForNode(id, artifactSet);
        }

        ArtifactsForNode configurationArtifactSet = artifactsByNodeId.get(toConfiguration.getNodeId());
        if (configurationArtifactSet == null) {
            ExcludeSpec exclusions = dependency.getExclusions();
            ArtifactSet nodeArtifacts = artifactSelector.resolveArtifacts(component, targetConfiguration, exclusions, overriddenAttributes);
            int id = nextId++;
            configurationArtifactSet = new ArtifactsForNode(id, nodeArtifacts);

            // Only share an ArtifactSet if the artifacts are not filtered by the dependency
            if (!exclusions.mayExcludeArtifacts()) {
                artifactsByNodeId.put(toConfiguration.getNodeId(), configurationArtifactSet);
            }
        }

        return configurationArtifactSet;
    }

    private static class ArtifactsForNode {
        private final int artifactSetId;
        private final ArtifactSet artifactSet;

        ArtifactsForNode(int artifactSetId, ArtifactSet artifactSet) {
            this.artifactSetId = artifactSetId;
            this.artifactSet = artifactSet;
        }
    }
}
