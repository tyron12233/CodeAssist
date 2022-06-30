/*
 * Copyright 2015 the original author or authors.
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
package com.tyron.builder.api.internal.resolve;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.component.LibraryBinaryIdentifier;
import com.tyron.builder.api.artifacts.component.LibraryComponentSelector;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.component.external.model.MetadataSourcedComponentArtifacts;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;
import com.tyron.builder.internal.component.local.model.PublishArtifactLocalArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.component.model.ComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.component.model.DependencyMetadata;
import com.tyron.builder.internal.component.model.ModuleSources;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.resolve.ArtifactResolveException;
import com.tyron.builder.internal.resolve.ModuleVersionResolveException;
import com.tyron.builder.internal.resolve.resolver.ArtifactResolver;
import com.tyron.builder.internal.resolve.resolver.ComponentMetaDataResolver;
import com.tyron.builder.internal.resolve.resolver.DependencyToComponentIdResolver;
import com.tyron.builder.internal.resolve.resolver.OriginArtifactSelector;
import com.tyron.builder.internal.resolve.result.BuildableArtifactResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentIdResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentResolveResult;
import com.tyron.builder.language.base.internal.resolve.LibraryResolveException;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.platform.base.Binary;
import com.tyron.builder.platform.base.VariantComponent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class LocalLibraryDependencyResolver implements DependencyToComponentIdResolver, ComponentMetaDataResolver, ArtifactResolver, OriginArtifactSelector, ComponentResolvers {
    private final VariantBinarySelector variantSelector;
    private final LibraryResolutionErrorMessageBuilder errorMessageBuilder;
    private final LocalLibraryMetaDataAdapter libraryMetaDataAdapter;
    private final LocalLibraryResolver libraryResolver;
    private final Class<? extends Binary> binaryType;
    private final Predicate<VariantComponent> binaryPredicate;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final ProjectModelResolver projectModelResolver;

    public LocalLibraryDependencyResolver(final Class<? extends Binary> binaryType,
                                          ProjectModelResolver projectModelResolver,
                                          LocalLibraryResolver libraryResolver,
                                          VariantBinarySelector variantSelector,
                                          LocalLibraryMetaDataAdapter libraryMetaDataAdapter,
                                          LibraryResolutionErrorMessageBuilder errorMessageBuilder,
                                          CalculatedValueContainerFactory calculatedValueContainerFactory) {
        this.libraryMetaDataAdapter = libraryMetaDataAdapter;
        this.variantSelector = variantSelector;
        this.errorMessageBuilder = errorMessageBuilder;
        this.projectModelResolver = projectModelResolver;
        this.libraryResolver = libraryResolver;
        this.binaryType = binaryType;
        this.binaryPredicate = new Predicate<VariantComponent>() {
            @Override
            public boolean apply(VariantComponent input) {
                return Iterables.any(input.getVariants(), new Predicate<Binary>() {
                    @Override
                    public boolean apply(Binary input) {
                        return binaryType.isAssignableFrom(input.getClass());
                    }
                });
            }
        };
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
    }

    @Override
    public ArtifactResolver getArtifactResolver() {
        return this;
    }

    @Override
    public DependencyToComponentIdResolver getComponentIdResolver() {
        return this;
    }

    @Override
    public ComponentMetaDataResolver getComponentResolver() {
        return this;
    }

    @Override
    public OriginArtifactSelector getArtifactSelector() {
        return this;
    }

    @Override
    public void resolve(DependencyMetadata dependency, VersionSelector acceptor, VersionSelector rejector, BuildableComponentIdResolveResult result) {
        if (dependency.getSelector() instanceof LibraryComponentSelector) {
            LibraryComponentSelector selector = (LibraryComponentSelector) dependency.getSelector();
            resolveLibraryAndChooseBinary(result, selector);
        }
    }

    private void resolveLibraryAndChooseBinary(BuildableComponentIdResolveResult result, LibraryComponentSelector selector) {
        final String selectorProjectPath = selector.getProjectPath();
        final String libraryName = selector.getLibraryName();
        final String variant = selector.getVariant();
        LibraryResolutionResult resolutionResult = doResolve(selectorProjectPath, libraryName);

        VariantComponent selectedLibrary = resolutionResult.getSelectedLibrary();
        if (selectedLibrary == null) {
            String message = resolutionResult.toResolutionErrorMessage(selector);
            ModuleVersionResolveException failure = new ModuleVersionResolveException(selector, new LibraryResolveException(message));
            result.failed(failure);
            return;
        }

        final Collection<? extends Binary> matchingVariants = chooseMatchingVariants(selectedLibrary, variant);
        if (matchingVariants.isEmpty()) {
            // no compatible variant found
            final Iterable<? extends Binary> values = selectedLibrary.getVariants();
            result.failed(new ModuleVersionResolveException(selector, new Factory<String>() {
                @Nullable
                @Override
                public String create() {
                    return errorMessageBuilder.noCompatibleVariantErrorMessage(libraryName, values);
                }
            }));
        } else if (matchingVariants.size() > 1) {
            result.failed(new ModuleVersionResolveException(selector, new Factory<String>() {
                @Nullable
                @Override
                public String create() {
                    return errorMessageBuilder.multipleCompatibleVariantsErrorMessage(libraryName, matchingVariants);
                }
            }));
        } else {
            Binary selectedBinary = matchingVariants.iterator().next();
            // TODO:Cedric This is not quite right. We assume that if we are asking for a specific binary, then we resolve to the assembly instead
            // of the jar, but it should be somehow parameterized
            LocalComponentMetadata metaData;
            if (variant == null) {
                metaData = libraryMetaDataAdapter.createLocalComponentMetaData(selectedBinary, selectorProjectPath, false);
            } else {
                metaData = libraryMetaDataAdapter.createLocalComponentMetaData(selectedBinary, selectorProjectPath, true);
            }
            result.resolved(metaData);
        }
    }

    private LibraryResolutionResult doResolve(String selectorProjectPath, String libraryName) {
        try {
            ModelRegistry projectModel = projectModelResolver.resolveProjectModel(selectorProjectPath);
            Collection<VariantComponent> candidates = libraryResolver.resolveCandidates(projectModel, libraryName);
            if (candidates.isEmpty()) {
                return LibraryResolutionResult.emptyResolutionResult(binaryType);
            }
            return LibraryResolutionResult.of(binaryType, candidates, libraryName, binaryPredicate);
        } catch (UnknownProjectException e) {
            return LibraryResolutionResult.projectNotFound(binaryType);
        }
    }

    private Collection<? extends Binary> chooseMatchingVariants(VariantComponent selectedLibrary, String variant) {
            return variantSelector.selectVariants(selectedLibrary, variant);
    }


    @Override
    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        if (isLibrary(identifier)) {
            throw new RuntimeException("Not yet implemented");
        }
    }

    @Override
    public boolean isFetchingMetadataCheap(ComponentIdentifier identifier) {
        return true;
    }

    private boolean isLibrary(ComponentIdentifier identifier) {
        return identifier instanceof LibraryBinaryIdentifier;
    }

    @Nullable
    @Override
    public ArtifactSet resolveArtifacts(ComponentResolveMetadata component, ConfigurationMetadata configuration, ArtifactTypeRegistry artifactTypeRegistry, ExcludeSpec exclusions, ImmutableAttributes overriddenAttributes) {
        ComponentIdentifier componentId = component.getId();
        if (isLibrary(componentId)) {
            return new MetadataSourcedComponentArtifacts().getArtifactsFor(component, configuration, this, new ConcurrentHashMap<>(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory);
        }
        return null;
    }

    @Override
    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        if (isLibrary(component.getId())) {
            result.resolved(Collections.<ComponentArtifactMetadata>emptySet());
        }
    }

    @Override
    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSources moduleSources, BuildableArtifactResolveResult result) {
        if (isLibrary(artifact.getComponentId())) {
            if (artifact instanceof PublishArtifactLocalArtifactMetadata) {
                result.resolved(((PublishArtifactLocalArtifactMetadata) artifact).getFile());
            } else {
                result.failed(new ArtifactResolveException("Unsupported artifact metadata type: " + artifact));
            }
        }
    }

}
