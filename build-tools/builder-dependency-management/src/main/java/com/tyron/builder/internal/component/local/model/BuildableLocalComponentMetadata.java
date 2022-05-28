/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.internal.component.local.model;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.LocalConfigurationMetadataBuilder;
import com.tyron.builder.internal.component.external.model.ImmutableCapabilities;

import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationInternal;

import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.DisplayName;

import com.tyron.builder.internal.component.model.VariantResolveMetadata;
import com.tyron.builder.internal.deprecation.DeprecationMessageBuilder;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public interface BuildableLocalComponentMetadata {
    /**
     * Returns the identifier for this component.
     */
    ComponentIdentifier getId();

    /**
     * Adds some artifacts to this component. Artifacts are attached to the given configuration and each of its children. These are used only for publishing.
     */
    void addArtifacts(String configuration, Collection<? extends PublishArtifact> artifacts);

    /**
     * Adds a variant to this component, extending from the given configuration. Every configuration should include at least one variant.
     */
    void addVariant(String configuration, String name, VariantResolveMetadata.Identifier identifier, DisplayName displayName, ImmutableAttributes attributes, ImmutableCapabilities capabilities, Collection<? extends PublishArtifact> artifacts);

    /**
     * Adds a configuration to this component.
     * @param hierarchy Must include name
     * @param attributes the attributes of the configuration.
     * @param consistentResolutionConstraints the consistent resolution constraints
     */
    BuildableLocalConfigurationMetadata addConfiguration(String name, @Nullable String description, Set<String> extendsFrom, ImmutableSet<String> hierarchy, boolean visible, boolean transitive, ImmutableAttributes attributes, boolean canBeConsumed, @Nullable DeprecationMessageBuilder.WithDocumentation consumptionDeprecation, boolean canBeResolved, ImmutableCapabilities capabilities, Supplier<List<DependencyConstraint>> consistentResolutionConstraints);

    /**
     * Provides a backing configuration instance from which dependencies and excludes will be sourced.
     *
     * @param configuration The configuration instance that provides dependencies and excludes
     * @param localConfigurationMetadataBuilder A builder for translating Configuration to LocalConfigurationMetadata
     */
    void addDependenciesAndExcludesForConfiguration(ConfigurationInternal configuration, LocalConfigurationMetadataBuilder localConfigurationMetadataBuilder);
}
