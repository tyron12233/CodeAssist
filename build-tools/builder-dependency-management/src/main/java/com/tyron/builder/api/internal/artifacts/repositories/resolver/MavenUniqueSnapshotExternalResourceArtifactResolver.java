/*
 * Copyright 2013 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.tyron.builder.internal.component.external.model.DefaultModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.UrlBackedArtifactMetadata;
import com.tyron.builder.internal.resolve.result.ResourceAwareResolveResult;
import com.tyron.builder.internal.resource.local.LocallyAvailableExternalResource;

class MavenUniqueSnapshotExternalResourceArtifactResolver implements ExternalResourceArtifactResolver {
    private final ExternalResourceArtifactResolver delegate;
    private final MavenUniqueSnapshotModuleSource snapshot;

    public MavenUniqueSnapshotExternalResourceArtifactResolver(ExternalResourceArtifactResolver delegate, MavenUniqueSnapshotModuleSource snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    @Override
    public boolean artifactExists(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        if (artifact instanceof UrlBackedArtifactMetadata) {
            return delegate.artifactExists(artifact, result);
        } else {
            return delegate.artifactExists(timestamp(artifact), result);
        }
    }

    @Override
    public LocallyAvailableExternalResource resolveArtifact(ModuleComponentArtifactMetadata artifact, ResourceAwareResolveResult result) {
        if (artifact instanceof UrlBackedArtifactMetadata) {
            return delegate.resolveArtifact(artifact, result);
        } else {
            return delegate.resolveArtifact(timestamp(artifact), result);
        }
    }

    protected ModuleComponentArtifactMetadata timestamp(ModuleComponentArtifactMetadata artifact) {
        MavenUniqueSnapshotComponentIdentifier snapshotComponentIdentifier =
                new MavenUniqueSnapshotComponentIdentifier(artifact.getId().getComponentIdentifier(), snapshot.getTimestamp());
        return new DefaultModuleComponentArtifactMetadata(snapshotComponentIdentifier, artifact.getName());
    }
}
