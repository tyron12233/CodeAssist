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

package com.tyron.builder.api.internal.artifacts.ivyservice;

import com.google.common.collect.Sets;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.LocalDependencyFiles;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;

import com.tyron.builder.api.artifacts.ResolvedArtifact;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.UncheckedException;

import java.util.List;
import java.util.Set;

public class ArtifactCollectingVisitor implements ArtifactVisitor {
    private final Set<ResolvedArtifact> artifacts;

    public ArtifactCollectingVisitor() {
        this(Sets.newLinkedHashSet());
    }

    public ArtifactCollectingVisitor(Set<ResolvedArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public void visitArtifact(DisplayName variantName, AttributeContainer variantAttributes, List<? extends Capability> capabilities, ResolvableArtifact artifact) {
        this.artifacts.add(artifact.toPublicView());
    }

    @Override
    public void visitFailure(Throwable failure) {
        throw UncheckedException.throwAsUncheckedException(failure);
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

    public Set<ResolvedArtifact> getArtifacts() {
        return artifacts;
    }
}
