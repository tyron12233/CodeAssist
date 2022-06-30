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
package com.tyron.builder.api.internal.artifacts.dsl;

import com.tyron.builder.internal.component.external.model.VariantDerivationStrategy;
import com.tyron.builder.api.internal.artifacts.ComponentMetadataProcessor;
import com.tyron.builder.api.internal.artifacts.ComponentMetadataProcessorFactory;
import com.tyron.builder.api.internal.artifacts.MetadataResolutionContext;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.management.DependencyResolutionManagementInternal;

import java.util.function.Consumer;

public interface ComponentMetadataHandlerInternal {
    ComponentMetadataProcessor createComponentMetadataProcessor(MetadataResolutionContext resolutionContext);
    void setVariantDerivationStrategy(VariantDerivationStrategy strategy);
    VariantDerivationStrategy getVariantDerivationStrategy();
    void onAddRule(Consumer<DisplayName> consumer);

    ComponentMetadataProcessorFactory createFactory(DependencyResolutionManagementInternal dependencyResolutionManagement);
}
