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

package com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.transform.VariantSelector;
import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.internal.component.local.model.LocalFileDependencyMetadata;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;

import java.util.function.Predicate;

public class FileDependencyArtifactSet implements ArtifactSet {
    private final LocalFileDependencyMetadata fileDependency;
    private final ArtifactTypeRegistry artifactTypeRegistry;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;

    public FileDependencyArtifactSet(LocalFileDependencyMetadata fileDependency, ArtifactTypeRegistry artifactTypeRegistry, CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.fileDependency = fileDependency;
        this.artifactTypeRegistry = artifactTypeRegistry;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    @Override
    public ResolvedArtifactSet select(Predicate<? super ComponentIdentifier> componentFilter, VariantSelector selector) {
        // Select the artifacts later, as this is a function of the file names and these may not be known yet because the producing tasks have not yet executed
        return new LocalFileDependencyBackedArtifactSet(fileDependency, componentFilter, selector, artifactTypeRegistry, calculatedValueContainerFactory);
    }

}
