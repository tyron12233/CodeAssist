/*
 * Copyright 2012 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.result;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.builder.VariantNameBuilder;

import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.result.ComponentSelectionReason;
import com.tyron.builder.api.artifacts.result.DependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedDependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedVariantResult;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultResolvedComponentResult implements ResolvedComponentResultInternal {
    private final ModuleVersionIdentifier moduleVersion;
    private final Set<DependencyResult> dependencies = new LinkedHashSet<>();
    private final Set<ResolvedDependencyResult> dependents = new LinkedHashSet<>();
    private final ComponentSelectionReason selectionReason;
    private final ComponentIdentifier componentId;
    private final List<ResolvedVariantResult> variants;
    private final String repositoryName;
    private final Multimap<ResolvedVariantResult, DependencyResult> variantDependencies = ArrayListMultimap.create();

    public DefaultResolvedComponentResult(ModuleVersionIdentifier moduleVersion, ComponentSelectionReason selectionReason, ComponentIdentifier componentId, List<ResolvedVariantResult> variants, String repositoryName) {
        assert moduleVersion != null;
        assert selectionReason != null;
        assert variants != null;

        this.moduleVersion = moduleVersion;
        this.selectionReason = selectionReason;
        this.componentId = componentId;
        this.variants = variants;
        this.repositoryName = repositoryName;
    }

    @Override
    public ComponentIdentifier getId() {
        return componentId;
    }

    @Nullable
    @Override
    public String getRepositoryName() {
        return repositoryName;
    }

    @Override
    public Set<DependencyResult> getDependencies() {
        return Collections.unmodifiableSet(dependencies);
    }

    @Override
    public Set<ResolvedDependencyResult> getDependents() {
        return Collections.unmodifiableSet(dependents);
    }

    public DefaultResolvedComponentResult addDependency(DependencyResult dependency) {
        this.dependencies.add(dependency);
        return this;
    }

    public DefaultResolvedComponentResult addDependent(ResolvedDependencyResult dependent) {
        this.dependents.add(dependent);
        return this;
    }

    @Override
    public ComponentSelectionReason getSelectionReason() {
        return selectionReason;
    }

    @Override
    @Nullable
    public ModuleVersionIdentifier getModuleVersion() {
        return moduleVersion;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ResolvedVariantResult getVariant() {
        if (variants.isEmpty()) {
            return new DefaultResolvedVariantResult(componentId, Describables.of("<empty>"), ImmutableAttributes.EMPTY, Collections.emptyList(), null);
        }
        // Returns an approximation of a composite variant
        List<String> parts = variants.stream()
            .map(ResolvedVariantResult::getDisplayName)
            .collect(Collectors.toList());
        DisplayName variantName = new VariantNameBuilder().getVariantName(parts);
        ResolvedVariantResult firstVariant = variants.get(0);
        return new DefaultResolvedVariantResult(componentId, variantName, firstVariant.getAttributes(), firstVariant.getCapabilities(), null);
    }

    @Override
    public String toString() {
        return getId().getDisplayName();
    }

    @Override
    public List<ResolvedVariantResult> getVariants() {
        return variants;
    }

    @Override
    public List<DependencyResult> getDependenciesForVariant(ResolvedVariantResult variant) {
        if (!variants.contains(variant)) {
            reportInvalidVariant(variant);
        }
        return ImmutableList.copyOf(variantDependencies.get(variant));
    }

    private void reportInvalidVariant(ResolvedVariantResult variant) {
        Optional<ResolvedVariantResult> sameName = variants.stream()
            .filter(v -> v.getDisplayName().equals(variant.getDisplayName()))
            .findFirst();
        String moreInfo = sameName.isPresent()
            ? "A variant with the same name exists but is not the same instance."
            : "There's no resolved variant with the same name.";
        throw new InvalidUserCodeException("Variant '" + variant.getDisplayName() + "' doesn't belong to resolved component '" + this + "'. " + moreInfo + " Most likely you are using a variant from another component to get the dependencies of this component.");
    }

    public void associateDependencyToVariant(DependencyResult dependencyResult, ResolvedVariantResult fromVariant) {
        variantDependencies.put(fromVariant, dependencyResult);
    }
}
