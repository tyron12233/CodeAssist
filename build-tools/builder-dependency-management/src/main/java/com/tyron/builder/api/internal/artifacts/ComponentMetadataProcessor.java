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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.internal.component.external.model.ModuleComponentResolveMetadata;

import com.tyron.builder.api.artifacts.ComponentMetadata;

public interface ComponentMetadataProcessor {
    ComponentMetadataProcessor NO_OP = new ComponentMetadataProcessor() {
        @Override
        public ModuleComponentResolveMetadata processMetadata(ModuleComponentResolveMetadata metadata) {
            return metadata;
        }

        @Override
        public ComponentMetadata processMetadata(ComponentMetadata metadata) {
            return metadata;
        }

        @Override
        public int getRulesHash() {
            return 0;
        };
    };

    ModuleComponentResolveMetadata processMetadata(ModuleComponentResolveMetadata metadata);

    /**
     * Processes "shallow" metadata, only for selecting a version. This metadata is typically
     * provided by a custom metadata processor.
     * @param metadata the metadata to be processed
     * @return updated metadata, if any component metadata rule applies.
     */
    ComponentMetadata processMetadata(ComponentMetadata metadata);

    int getRulesHash();
}
