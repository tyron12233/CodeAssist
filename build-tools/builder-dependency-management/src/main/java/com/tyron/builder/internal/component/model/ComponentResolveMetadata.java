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

package com.tyron.builder.internal.component.model;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.component.external.model.VirtualComponentIdentifier;

import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.attributes.HasAttributes;
import com.tyron.builder.api.internal.attributes.AttributesSchemaInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The meta-data for a component instance that is required during dependency resolution.
 */
public interface ComponentResolveMetadata extends HasAttributes {
    List<String> DEFAULT_STATUS_SCHEME = Arrays.asList("integration", "milestone", "release");

    /**
     * Returns the identifier for this component.
     */
    ComponentIdentifier getId();

    /**
     * Returns the module version identifier for this component. Currently this reflects the (group, module, version) that was used to request this component.
     *
     * <p>This is a legacy identifier and is here while we transition the meta-data away from ivy-like
     * module versions to the more general component instances. Currently, the module version and component identifiers are used interchangeably. However, over
     * time more things will use the component identifier. At some point, the module version identifier will become optional for a component.
     */
    ModuleVersionIdentifier getModuleVersionId();

    /**
     * @return the sources information for this component.
     */
    @Nullable
    ModuleSources getSources();

    /**
     * Creates a copy of this meta-data with the given sources.
     */
    ComponentResolveMetadata withSources(ModuleSources sources);

    /**
     * Returns the schema used by this component.
     */
    @Nullable
    AttributesSchemaInternal getAttributesSchema();

    /**
     * Returns the names of all of the legacy configurations for this component. May be empty, in which case the component should provide at least one variant via {@link #getVariantsForGraphTraversal()}.
     */
    Set<String> getConfigurationNames();

    /**
     * Locates the configuration with the given name, if any.
     */
    @Nullable
    ConfigurationMetadata getConfiguration(String name);

    /**
     * Returns the set of variants of this component to use for variant aware resolution of the dependency graph nodes. May be empty, in which case selection falls back to the legacy configurations available via {@link #getConfiguration(String)}. The component should provide a configuration called {@value Dependency#DEFAULT_CONFIGURATION}.
     *
     * <p>Note: currently, {@link ConfigurationMetadata} is used to represent these variants. This is to help with migration. The set of objects returned by this method may or may not be the same as those returned by {@link #getConfigurationNames()}.</p>
     */
    Optional<ImmutableList<? extends ConfigurationMetadata>> getVariantsForGraphTraversal();

    /**
     * Returns true when this metadata represents the default metadata provided for components with missing metadata files.
     */
    boolean isMissing();

    boolean isChanging();

    @Nullable
    String getStatus();

    @Nullable
    List<String> getStatusScheme();

    ImmutableList<? extends VirtualComponentIdentifier> getPlatformOwners();

    @Override
    ImmutableAttributes getAttributes();
}
