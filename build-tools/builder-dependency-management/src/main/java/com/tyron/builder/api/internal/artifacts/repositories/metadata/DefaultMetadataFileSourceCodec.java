/*
 * Copyright 2019 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.repositories.metadata;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.api.internal.filestore.ArtifactIdentifierFileStore;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentFileArtifactIdentifier;
import com.tyron.builder.internal.component.model.PersistentModuleSource;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

import java.io.File;
import java.io.IOException;

/**
 * A codec for {@link MetadataFileSource}. This codec is particular because of the persistent cache
 * which must be relocatable. As a consequence, it would be an error to serialize the file path
 * because
 * it would contain an absolute path to the descriptor file.
 * <p>
 * Therefore, the deserialized metadata file source reconstructs the file path from the component
 * module artifact identifier.
 */
public class DefaultMetadataFileSourceCodec implements PersistentModuleSource.Codec<MetadataFileSource> {
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final ArtifactIdentifierFileStore fileStore;

    public DefaultMetadataFileSourceCodec(ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                          ArtifactIdentifierFileStore fileStore) {
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        this.fileStore = fileStore;
    }

    @Override
    public void encode(MetadataFileSource moduleSource, Encoder encoder) throws IOException {
        ModuleComponentArtifactIdentifier artifactId = moduleSource.getArtifactId();
        ModuleComponentIdentifier componentIdentifier = artifactId.getComponentIdentifier();
        encoder.writeString(componentIdentifier.getGroup());
        encoder.writeString(componentIdentifier.getModule());
        encoder.writeString(componentIdentifier.getVersion());
        encoder.writeString(artifactId.getFileName());
        encoder.writeBinary(moduleSource.getSha1().asBytes());
    }

    @Override
    public MetadataFileSource decode(Decoder decoder) throws IOException {
        String group = decoder.readString();
        String module = decoder.readString();
        String version = decoder.readString();
        String name = decoder.readString();
        byte[] sha1 = decoder.readBinary();
        return createSource(sha1, group, module, version, name);
    }

    private DefaultMetadataFileSource createSource(byte[] sha1,
                                                   String group,
                                                   String module,
                                                   String version,
                                                   String name) {
        ModuleComponentArtifactIdentifier artifactId =
                createArtifactId(group, module, version, name);
        HashCode hashCode = HashCode.fromBytes(sha1);
        File metadataFile = fileStore.whereIs(artifactId, hashCode.toString());
        return new DefaultMetadataFileSource(artifactId, metadataFile, hashCode);
    }

    private ModuleComponentFileArtifactIdentifier createArtifactId(String group,
                                                                   String module,
                                                                   String version,
                                                                   String name) {
        return new ModuleComponentFileArtifactIdentifier(DefaultModuleComponentIdentifier
                .newId(moduleIdentifierFactory.module(group, module), version), name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
        return moduleIdentifierFactory.hashCode();
    }
}
