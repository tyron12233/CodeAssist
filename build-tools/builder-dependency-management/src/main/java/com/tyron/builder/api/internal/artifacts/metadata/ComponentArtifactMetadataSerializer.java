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
package com.tyron.builder.api.internal.artifacts.metadata;

import com.google.common.base.Objects;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.ComponentIdentifierSerializer;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;

import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.DefaultIvyArtifactName;
import com.tyron.builder.internal.component.model.IvyArtifactName;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

public class ComponentArtifactMetadataSerializer extends AbstractSerializer<ComponentArtifactMetadata> {
    private final ComponentIdentifierSerializer componentIdentifierSerializer = new ComponentIdentifierSerializer();

    @Override
    public void write(Encoder encoder, ComponentArtifactMetadata value) throws Exception {
        if (value instanceof ModuleComponentArtifactMetadata) {
            ModuleComponentArtifactMetadata moduleComponentArtifactMetadata = (ModuleComponentArtifactMetadata) value;
            componentIdentifierSerializer.write(encoder, moduleComponentArtifactMetadata.getComponentId());
            IvyArtifactName ivyArtifactName = moduleComponentArtifactMetadata.getName();
            encoder.writeString(ivyArtifactName.getName());
            encoder.writeString(ivyArtifactName.getType());
            encoder.writeNullableString(ivyArtifactName.getExtension());
            encoder.writeNullableString(ivyArtifactName.getClassifier());
        } else {
            throw new IllegalArgumentException("Unknown artifact metadata type.");
        }
    }

    @Override
    public ComponentArtifactMetadata read(Decoder decoder) throws Exception {
        ModuleComponentIdentifier componentIdentifier = (ModuleComponentIdentifier) componentIdentifierSerializer.read(decoder);
        String artifactName = decoder.readString();
        String type = decoder.readString();
        String extension = decoder.readNullableString();
        String classifier = decoder.readNullableString();
        return new DefaultModuleComponentArtifactMetadata(componentIdentifier, new DefaultIvyArtifactName(artifactName, type, extension, classifier));
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        ComponentArtifactMetadataSerializer rhs = (ComponentArtifactMetadataSerializer) obj;
        return Objects.equal(componentIdentifierSerializer, rhs.componentIdentifierSerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), componentIdentifierSerializer);
    }
}
