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
package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.tyron.builder.api.artifacts.ComponentMetadata;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;

import java.util.List;

public class ComponentMetadataAdapter implements ComponentMetadata {
    private final ModuleComponentResolveMetadata metadata;

    public ComponentMetadataAdapter(ModuleComponentResolveMetadata metadata) {
        this.metadata = metadata;
    }

    @Override
    public ModuleVersionIdentifier getId() {
        return metadata.getModuleVersionId();
    }

    @Override
    public boolean isChanging() {
        return metadata.isChanging();
    }

    @Override
    public String getStatus() {
        return metadata.getStatus();
    }

    @Override
    public List<String> getStatusScheme() {
        return metadata.getStatusScheme();
    }

    @Override
    public AttributeContainer getAttributes() {
        return metadata.getAttributes();
    }
}
