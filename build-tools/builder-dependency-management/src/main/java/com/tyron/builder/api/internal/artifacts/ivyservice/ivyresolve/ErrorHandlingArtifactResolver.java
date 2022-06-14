/*
 * Copyright 2014 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import com.tyron.builder.internal.resolve.ArtifactResolveException;

import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;

public class ErrorHandlingArtifactResolver implements ArtifactResolver {
    private final ArtifactResolver resolver;

    public ErrorHandlingArtifactResolver(ArtifactResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        try {
            resolver.resolveArtifactsWithType(component, artifactType, result);
        } catch (Exception t) {
            result.failed(new ArtifactResolveException(component.getId(), t));
        }
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result) {
        try {
            resolver.resolveArtifact(artifact, moduleSources, result);
        } catch (Exception t) {
            result.failed(new ArtifactResolveException(artifact.getId(), t));
        }
    }
}
