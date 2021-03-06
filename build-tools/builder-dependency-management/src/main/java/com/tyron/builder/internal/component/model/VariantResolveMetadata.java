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

package com.tyron.builder.internal.component.model;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.capabilities.CapabilitiesMetadata;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.internal.DisplayName;

import javax.annotation.Nullable;

/**
 * Metadata for a basic variant of a component, that defines only artifacts and no dependencies.
 */
public interface VariantResolveMetadata {
    String getName();

    /**
     * An identifier for this variant, if available. A variant may not necessarily have an identifier associated with it, for example if it represents some ad hoc variant.
     */
    @Nullable
    Identifier getIdentifier();

    DisplayName asDescribable();

    AttributeContainerInternal getAttributes();

    ImmutableList<? extends ComponentArtifactMetadata> getArtifacts();

    CapabilitiesMetadata getCapabilities();

    boolean isExternalVariant();

    interface Identifier {
    }
}
