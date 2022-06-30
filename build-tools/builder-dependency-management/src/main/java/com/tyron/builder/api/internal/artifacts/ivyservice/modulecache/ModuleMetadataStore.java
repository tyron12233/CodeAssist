/*
 * Copyright 2011 the original author or authors.
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

import com.google.common.base.Joiner;
import com.google.common.collect.Interner;
import com.google.common.collect.Maps;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.resource.local.LocallyAvailableResource;
import com.tyron.builder.internal.resource.local.PathKeyFileStore;
import com.tyron.builder.internal.serialize.kryo.KryoBackedDecoder;
import com.tyron.builder.internal.serialize.kryo.KryoBackedEncoder;

import java.io.FileInputStream;
import java.io.FileOutputStream;

public class ModuleMetadataStore {

    private static final Joiner PATH_JOINER = Joiner.on("/");
    private final PathKeyFileStore metaDataStore;
    private final ModuleMetadataSerializer moduleMetadataSerializer;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final Interner<String> stringInterner;

    public ModuleMetadataStore(PathKeyFileStore metaDataStore,
                               ModuleMetadataSerializer moduleMetadataSerializer,
                               ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                               Interner<String> stringInterner) {
        this.metaDataStore = metaDataStore;
        this.moduleMetadataSerializer = moduleMetadataSerializer;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
        this.stringInterner = stringInterner;
    }

    public MutableModuleComponentResolveMetadata getModuleDescriptor(ModuleComponentAtRepositoryKey component) {
        String[] filePath = getFilePath(component);
        LocallyAvailableResource resource = metaDataStore.get(filePath);
        if (resource != null) {
            try {
                try (StringDeduplicatingDecoder decoder = new StringDeduplicatingDecoder(new KryoBackedDecoder(new FileInputStream(resource.getFile())), stringInterner)) {
                    return moduleMetadataSerializer.read(decoder, moduleIdentifierFactory, Maps.newHashMap());
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not load module metadata from " + resource.getDisplayName(), e);
            }
        }
        return null;
    }

    public LocallyAvailableResource putModuleDescriptor(ModuleComponentAtRepositoryKey component, final ModuleComponentResolveMetadata metadata) {
        String[] filePath = getFilePath(component);
        return metaDataStore.add(PATH_JOINER.join(filePath), moduleDescriptorFile -> {
            try {
                try (KryoBackedEncoder encoder = new KryoBackedEncoder(new FileOutputStream(moduleDescriptorFile))) {
                    moduleMetadataSerializer.write(encoder, metadata, Maps.newHashMap());
                }
            } catch (Exception e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        });
    }

    private String[] getFilePath(ModuleComponentAtRepositoryKey componentId) {
        ModuleComponentIdentifier moduleComponentIdentifier = componentId.getComponentId();
        return new String[] {
            moduleComponentIdentifier.getGroup(),
            moduleComponentIdentifier.getModule(),
            moduleComponentIdentifier.getVersion(),
            componentId.getRepositoryId(),
            "descriptor.bin"
        };
    }

}
