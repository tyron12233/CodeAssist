/*
 * Copyright 2020 the original author or authors.
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

import com.tyron.builder.api.artifacts.result.ResolvedArtifactResult;
import com.tyron.builder.api.internal.artifacts.ivyservice.ResolvedArtifactCollectingVisitor;
import com.tyron.builder.api.internal.artifacts.ivyservice.ResolvedFileCollectionVisitor;
import com.tyron.builder.api.internal.file.AbstractFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.util.Set;

@ServiceScope(Scopes.BuildSession.class)
public class ArtifactSetToFileCollectionFactory {
    private final BuildOperationExecutor buildOperationExecutor;

    public ArtifactSetToFileCollectionFactory(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    /**
     * Presents the contents of the given artifacts as a partial {@link FileCollectionInternal} implementation.
     *
     * <p>This produces only a minimal implementation to use for artifact sets loaded from the configuration cache
     * Over time, this should be merged with the FileCollection implementation in DefaultConfiguration
     */
    public FileCollectionInternal asFileCollection(ResolvedArtifactSet artifacts) {
        return new AbstractFileCollection() {
            @Override
            public String getDisplayName() {
                return "files";
            }

            @Override
            protected void visitContents(FileCollectionStructureVisitor visitor) {
                ResolvedFileCollectionVisitor collectingVisitor = new ResolvedFileCollectionVisitor(visitor);
                ParallelResolveArtifactSet.wrap(artifacts, buildOperationExecutor).visit(collectingVisitor);
                if (!collectingVisitor.getFailures().isEmpty()) {
                    throw UncheckedException.throwAsUncheckedException(collectingVisitor.getFailures().iterator().next());
                }
            }
        };
    }

    /**
     * Presents the contents of the given artifacts as a supplier of {@link ResolvedArtifactResult} instances.
     *
     * <p>Over time, this should be merged with the ArtifactCollection implementation in DefaultConfiguration
     */
    public Set<ResolvedArtifactResult> asResolvedArtifacts(ResolvedArtifactSet artifacts) {
        ResolvedArtifactCollectingVisitor collectingVisitor = new ResolvedArtifactCollectingVisitor();
        ParallelResolveArtifactSet.wrap(artifacts, buildOperationExecutor).visit(collectingVisitor);
        if (!collectingVisitor.getFailures().isEmpty()) {
            throw UncheckedException.throwAsUncheckedException(collectingVisitor.getFailures().iterator().next());
        }
        return collectingVisitor.getArtifacts();
    }
}
