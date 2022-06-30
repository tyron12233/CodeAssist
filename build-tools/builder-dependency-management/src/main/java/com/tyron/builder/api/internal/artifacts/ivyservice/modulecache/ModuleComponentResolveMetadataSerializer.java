/*
 * Copyright 2018 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.tyron.builder.internal.component.external.model.ivy.DefaultIvyModuleResolveMetadata;
import com.tyron.builder.internal.component.external.model.ivy.RealisedIvyModuleResolveMetadata;
import com.tyron.builder.internal.component.external.model.ivy.RealisedIvyModuleResolveMetadataSerializationHelper;
import com.tyron.builder.internal.component.external.model.maven.DefaultMavenModuleResolveMetadata;
import com.tyron.builder.internal.component.external.model.maven.MavenDependencyDescriptor;
import com.tyron.builder.internal.component.external.model.maven.RealisedMavenModuleResolveMetadata;
import com.tyron.builder.internal.component.external.model.maven.RealisedMavenModuleResolveMetadataSerializationHelper;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.internal.component.external.model.AbstractLazyModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.AbstractRealisedModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.DefaultVirtualModuleComponentIdentifier;
import com.tyron.builder.internal.component.external.model.ExternalDependencyDescriptor;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.VirtualComponentIdentifier;
import com.tyron.builder.internal.resolve.caching.DesugaringAttributeContainerSerializer;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializer for {@link ModuleComponentResolveMetadata}.
 *
 * This serializer will first transform any {@link  AbstractLazyModuleComponentResolveMetadata lazy} metadata
 * in the {@link AbstractRealisedModuleComponentResolveMetadata realised} version so that the complete state can be serialized.
 */
public class ModuleComponentResolveMetadataSerializer extends AbstractSerializer<ModuleComponentResolveMetadata> {

    private final RealisedIvyModuleResolveMetadataSerializationHelper ivySerializationHelper;
    private final RealisedMavenModuleResolveMetadataSerializationHelper mavenSerializationHelper;
    private final ModuleMetadataSerializer delegate;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

    public ModuleComponentResolveMetadataSerializer(ModuleMetadataSerializer delegate, DesugaringAttributeContainerSerializer attributeContainerSerializer, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.delegate = delegate;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        ivySerializationHelper = new RealisedIvyModuleResolveMetadataSerializationHelper(attributeContainerSerializer, moduleIdentifierFactory);
        mavenSerializationHelper = new RealisedMavenModuleResolveMetadataSerializationHelper(attributeContainerSerializer, moduleIdentifierFactory);
    }

    @Override
    public ModuleComponentResolveMetadata read(Decoder decoder) throws EOFException, Exception {

        Map<Integer, MavenDependencyDescriptor> deduplicationDependencyCache = Maps.newHashMap();
        MutableModuleComponentResolveMetadata mutable = delegate.read(decoder, moduleIdentifierFactory, deduplicationDependencyCache);
        readPlatformOwners(decoder, mutable);
        AbstractLazyModuleComponentResolveMetadata resolveMetadata = (AbstractLazyModuleComponentResolveMetadata) mutable.asImmutable();

        if (resolveMetadata instanceof DefaultIvyModuleResolveMetadata) {
            return ivySerializationHelper.readMetadata(decoder, (DefaultIvyModuleResolveMetadata) resolveMetadata);
        } else if (resolveMetadata instanceof DefaultMavenModuleResolveMetadata) {
            return mavenSerializationHelper.readMetadata(decoder, (DefaultMavenModuleResolveMetadata) resolveMetadata, deduplicationDependencyCache);
        } else {
            throw new IllegalStateException("Unknown resolved metadata type: " + resolveMetadata.getClass());
        }
    }

    private void readPlatformOwners(Decoder decoder, MutableModuleComponentResolveMetadata mutable) throws IOException {
        int len = decoder.readSmallInt();
        if (len>0) {
            for (int i=0; i<len; i++) {
                VirtualComponentIdentifier moduleComponentIdentifier = readModuleIdentifier(decoder);
                mutable.belongsTo(moduleComponentIdentifier);
            }
        }
    }

    private VirtualComponentIdentifier readModuleIdentifier(Decoder decoder) throws IOException {
        String group = decoder.readString();
        String module = decoder.readString();
        String version = decoder.readString();
        ModuleIdentifier moduleIdentifier = DefaultModuleIdentifier.newId(group, module);
        return new DefaultVirtualModuleComponentIdentifier(moduleIdentifier, version);
    }

    @Override
    public void write(Encoder encoder, ModuleComponentResolveMetadata value) throws Exception {
        AbstractRealisedModuleComponentResolveMetadata transformed = assertRealized(value);
        HashMap<ExternalDependencyDescriptor, Integer> deduplicationDependencyCache = Maps.newHashMap();
        delegate.write(encoder, transformed, deduplicationDependencyCache);
        writeOwners(encoder, value.getPlatformOwners());
        if (transformed instanceof RealisedIvyModuleResolveMetadata) {
            ivySerializationHelper.writeRealisedVariantsData(encoder, transformed);
            ivySerializationHelper.writeRealisedConfigurationsData(encoder, transformed, deduplicationDependencyCache);
        } else if (transformed instanceof RealisedMavenModuleResolveMetadata) {
            mavenSerializationHelper.writeRealisedVariantsData(encoder, transformed);
            mavenSerializationHelper.writeRealisedConfigurationsData(encoder, transformed, deduplicationDependencyCache);
        } else {
            throw new IllegalStateException("Unexpected realised module component resolve metadata type: " + transformed.getClass());
        }
    }

    private void writeOwners(Encoder encoder, ImmutableList<? extends VirtualComponentIdentifier> platformOwners) throws IOException {
        encoder.writeSmallInt(platformOwners.size());
        for (ComponentIdentifier platformOwner : platformOwners) {
            writeComponentIdentifier(encoder, (ModuleComponentIdentifier) platformOwner);
        }
    }

    private void writeComponentIdentifier(Encoder encoder, ModuleComponentIdentifier platformOwner) throws IOException {
        encoder.writeString(platformOwner.getGroup());
        encoder.writeString(platformOwner.getModule());
        encoder.writeString(platformOwner.getVersion());
    }

    private AbstractRealisedModuleComponentResolveMetadata assertRealized(ModuleComponentResolveMetadata metadata) {
        if (metadata instanceof AbstractRealisedModuleComponentResolveMetadata) {
            return (AbstractRealisedModuleComponentResolveMetadata) metadata;
        }
        throw new IllegalStateException("The type of metadata received is not supported - " + metadata.getClass().getName());
    }
}
