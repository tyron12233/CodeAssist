/*
 * Copyright 2017 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts.repositories.metadata;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleDescriptorHashModuleSource;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.GradleModuleMetadataParser;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ResourcePattern;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.VersionLister;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.external.model.MutableComponentVariant;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.component.model.DefaultIvyArtifactName;
import com.tyron.builder.internal.component.model.IvyArtifactName;
import com.tyron.builder.internal.component.model.MutableModuleSources;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import com.tyron.builder.internal.resource.local.LocallyAvailableExternalResource;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import java.io.File;
import java.util.List;

import javax.inject.Inject;

/**
 * TODO: This class sources Gradle metadata files, but there's no corresponding
 * ModuleComponentResolveMetadata for this metadata yet.
 * Because of this, we will generate an empty instance (either a Ivy or Maven) based on the
 * repository type.
 */
public class DefaultGradleModuleMetadataSource extends AbstractMetadataSource<MutableModuleComponentResolveMetadata> {
    private final GradleModuleMetadataParser metadataParser;
    private final GradleModuleMetadataCompatibilityConverter metadataCompatibilityConverter;
    private final MutableModuleMetadataFactory<? extends MutableModuleComponentResolveMetadata>
            mutableModuleMetadataFactory;
    private final boolean listVersions;
    private final ChecksumService checksumService;

    @Inject
    public DefaultGradleModuleMetadataSource(GradleModuleMetadataParser metadataParser,
                                             MutableModuleMetadataFactory<?
                                                     extends MutableModuleComponentResolveMetadata> mutableModuleMetadataFactory,
                                             boolean listVersions,
                                             ChecksumService checksumService) {
        this.metadataParser = metadataParser;
        this.metadataCompatibilityConverter = new GradleModuleMetadataCompatibilityConverter(
                metadataParser.getAttributesFactory(), metadataParser.getInstantiator());
        this.mutableModuleMetadataFactory = mutableModuleMetadataFactory;
        this.listVersions = listVersions;
        this.checksumService = checksumService;
    }

    @Override
    public MutableModuleComponentResolveMetadata create(String repositoryName,
                                                        ComponentResolvers componentResolvers,
                                                        ModuleComponentIdentifier moduleComponentIdentifier,
                                                        ComponentOverrideMetadata prescribedMetaData,
                                                        ExternalResourceArtifactResolver artifactResolver,
                                                        BuildableModuleComponentMetaDataResolveResult result) {
        DefaultIvyArtifactName moduleMetadataArtifact =
                new DefaultIvyArtifactName(moduleComponentIdentifier.getModule(), "module",
                        "module");
        DefaultModuleComponentArtifactMetadata artifactId =
                new DefaultModuleComponentArtifactMetadata(moduleComponentIdentifier,
                        moduleMetadataArtifact);
        LocallyAvailableExternalResource gradleMetadataArtifact =
                artifactResolver.resolveArtifact(artifactId, result);
        if (gradleMetadataArtifact != null) {
            MutableModuleComponentResolveMetadata metaDataFromResource =
                    mutableModuleMetadataFactory
                            .createForGradleModuleMetadata(moduleComponentIdentifier);
            metadataParser.parse(gradleMetadataArtifact, metaDataFromResource);
            validateGradleMetadata(metaDataFromResource);
            createModuleSources(artifactId, gradleMetadataArtifact, metaDataFromResource);
            metadataCompatibilityConverter.process(metaDataFromResource);
            return metaDataFromResource;
        }
        return null;
    }

    private void createModuleSources(DefaultModuleComponentArtifactMetadata artifactId,
                                     LocallyAvailableExternalResource gradleMetadataArtifact,
                                     MutableModuleComponentResolveMetadata metaDataFromResource) {
        MutableModuleSources sources = metaDataFromResource.getSources();
        File file = gradleMetadataArtifact.getFile();
        sources.add(new ModuleDescriptorHashModuleSource(checksumService.md5(file),
                metaDataFromResource.isChanging()));
        sources.add(new DefaultMetadataFileSource(artifactId.getId(), file,
                findSha1(gradleMetadataArtifact.getMetaData(), file)));
    }

    private HashCode findSha1(ExternalResourceMetaData metaData, File artifact) {
        HashCode sha1 = metaData.getSha1();
        if (sha1 == null) {
            sha1 = checksumService.sha1(artifact);
        }
        return sha1;
    }

    private static void validateGradleMetadata(MutableModuleComponentResolveMetadata metaDataFromResource) {
        List<? extends MutableComponentVariant> mutableVariants =
                metaDataFromResource.getMutableVariants();
        if (mutableVariants == null || mutableVariants.isEmpty()) {
            throw new InvalidUserDataException("Gradle Module Metadata for module " +
                                               metaDataFromResource.getModuleVersionId() +
                                               " is invalid because it doesn't declare any variant");
        }
    }

    @Override
    public void listModuleVersions(ModuleDependencyMetadata dependency,
                                   ModuleIdentifier module,
                                   List<ResourcePattern> ivyPatterns,
                                   List<ResourcePattern> artifactPatterns,
                                   VersionLister versionLister,
                                   BuildableModuleVersionListingResolveResult result) {
        if (listVersions) {
            // List modules based on metadata files, but only if we won't check for maven-metadata (which is preferred)
            IvyArtifactName metaDataArtifact =
                    new DefaultIvyArtifactName(module.getName(), "module", "module");
            versionLister.listVersions(module, metaDataArtifact, ivyPatterns, result);
        }
    }
}
