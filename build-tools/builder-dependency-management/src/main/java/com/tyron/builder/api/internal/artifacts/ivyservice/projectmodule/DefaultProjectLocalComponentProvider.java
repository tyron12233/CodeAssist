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
package com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.Module;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.LocalComponentMetadataBuilder;
import com.tyron.builder.api.internal.attributes.AttributesSchemaInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.internal.component.local.model.DefaultLocalComponentMetadata;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;
import com.tyron.builder.internal.model.CalculatedValueContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Provides the metadata for a component consumed from the same build that produces it.
 * <p>
 * Currently, the metadata for a component is different based on whether it is consumed from the
 * producing build or from another build. This difference should go away.
 */
public class DefaultProjectLocalComponentProvider implements LocalComponentProvider {
    private final LocalComponentMetadataBuilder metadataBuilder;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
    private final Map<ProjectComponentIdentifier, CalculatedValueContainer<LocalComponentMetadata
            , ?>>
            projects = new ConcurrentHashMap<>();

    public DefaultProjectLocalComponentProvider(LocalComponentMetadataBuilder metadataBuilder,
                                                ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
        this.metadataBuilder = metadataBuilder;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    @Nullable
    @Override
    public LocalComponentMetadata getComponent(ProjectStateUnk projectState) {
        projectState.ensureConfigured();
        return projectState.fromMutableState(p -> getLocalComponentMetadata(projectState, p));
    }

    private LocalComponentMetadata getLocalComponentMetadata(ProjectStateUnk projectState,
                                                             ProjectInternal project) {
        Module module = project.getDependencyMetaDataProvider().getModule();
        ModuleVersionIdentifier moduleVersionIdentifier = moduleIdentifierFactory
                .moduleWithVersion(module.getGroup(), module.getName(), module.getVersion());
        ProjectComponentIdentifier componentIdentifier = projectState.getComponentIdentifier();
        DefaultLocalComponentMetadata metaData =
                new DefaultLocalComponentMetadata(moduleVersionIdentifier, componentIdentifier,
                        module.getStatus(),
                        (AttributesSchemaInternal) project.getDependencies().getAttributesSchema());
        for (ConfigurationInternal configuration : project.getConfigurations()
                .withType(ConfigurationInternal.class)) {
            metadataBuilder.addConfiguration(metaData, configuration);
        }
        return metaData;
    }
}
