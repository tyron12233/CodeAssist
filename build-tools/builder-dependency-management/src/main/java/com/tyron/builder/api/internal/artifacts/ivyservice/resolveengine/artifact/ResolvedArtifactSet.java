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

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.artifacts.transform.TransformationNode;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.FileCollectionStructureVisitor;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.internal.operations.BuildOperationQueue;
import com.tyron.builder.internal.operations.RunnableBuildOperation;

/**
 * A container for a set of files or artifacts. May or may not be immutable, and may require building and further resolution.
 */
public interface ResolvedArtifactSet extends TaskDependencyContainer {
    /**
     * Visits the contents of the set, adding any remaining work to finalise the set of artifacts to the given queue.
     */
    void visit(Visitor visitor);

    void visitTransformSources(TransformSourceVisitor visitor);

    interface TransformSourceVisitor {
        void visitArtifact(ResolvableArtifact artifact);

        void visitTransform(TransformationNode source);
    }

    /**
     * Visits the external artifacts of this set.
     */
    void visitExternalArtifacts(Action<ResolvableArtifact> visitor);

    ResolvedArtifactSet EMPTY = new ResolvedArtifactSet() {
        @Override
        public void visit(Visitor visitor) {
        }

        @Override
        public void visitTransformSources(TransformSourceVisitor visitor) {
        }

        @Override
        public void visitExternalArtifacts(Action<ResolvableArtifact> visitor) {
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }
    };

    interface Artifacts {
        /**
         * Queues up any work still remaining to finalize the set of artifacts contained in this set.
         */
        void startFinalization(BuildOperationQueue<RunnableBuildOperation> actions, boolean requireFiles);

        /**
         * Finalize the set of artifacts now.
         */
        void finalizeNow(boolean requireFiles);

        /**
         * Invoked once all async work as completed, to visit the final result. The result is visited using the current thread and in the relevant order.
         */
        void visit(ArtifactVisitor visitor);
    }

    /**
     * A listener that is notified as artifacts are made available while visiting the contents of a set. Implementations must be thread safe as they are notified from multiple threads concurrently.
     */
    interface Visitor {
        /**
         * Called prior to scheduling resolution of a set of the given type. Should be called in result order.
         */
        FileCollectionStructureVisitor.VisitType prepareForVisit(FileCollectionInternal.Source source);

        /**
         * Visits zero or more artifacts.
         */
        void visitArtifacts(Artifacts artifacts);
    }
}
