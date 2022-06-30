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
package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.artifacts.ComponentMetadataListerDetails;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplierDetails;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepositoryAccess;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.ImmutableMetadataSources;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MetadataArtifactProvider;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;
import com.tyron.builder.api.internal.component.ArtifactType;
import com.tyron.builder.internal.action.InstantiatingAction;
import com.tyron.builder.internal.component.external.model.MetadataSourcedComponentArtifacts;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.ivy.IvyModuleResolveMetadata;
import com.tyron.builder.internal.component.model.ConfigurationMetadata;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resolve.result.BuildableArtifactSetResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import com.tyron.builder.internal.resource.local.FileStore;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;

import java.net.URI;

import javax.annotation.Nullable;

public class IvyResolver extends ExternalResourceResolver<IvyModuleResolveMetadata> implements PatternBasedResolver {

    private final boolean dynamicResolve;
    private boolean m2Compatible;
    private final IvyLocalRepositoryAccess localRepositoryAccess;
    private final IvyRemoteRepositoryAccess remoteRepositoryAccess;

    public IvyResolver(String name,
                       RepositoryTransport transport,
                       LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                       boolean dynamicResolve,
                       FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                       @Nullable InstantiatingAction<ComponentMetadataSupplierDetails> componentMetadataSupplierFactory,
                       @Nullable InstantiatingAction<ComponentMetadataListerDetails> componentMetadataVersionListerFactory,
                       ImmutableMetadataSources repositoryContentFilter,
                       MetadataArtifactProvider metadataArtifactProvider,
                       Instantiator injector,
                       ChecksumService checksumService) {
        super(name, transport.isLocal(), transport.getRepository(), transport.getResourceAccessor(),
                locallyAvailableResourceFinder, artifactFileStore, repositoryContentFilter,
                metadataArtifactProvider, componentMetadataSupplierFactory,
                componentMetadataVersionListerFactory, injector, checksumService);
        this.dynamicResolve = dynamicResolve;
        this.localRepositoryAccess = new IvyLocalRepositoryAccess();
        this.remoteRepositoryAccess = new IvyRemoteRepositoryAccess();
    }

    @Override
    public String toString() {
        return "Ivy repository '" + getName() + "'";
    }

    @Override
    protected void appendId(Hasher hasher) {
        super.appendId(hasher);
        hasher.putBoolean(isM2compatible());
    }

    @Override
    protected Class<IvyModuleResolveMetadata> getSupportedMetadataType() {
        return IvyModuleResolveMetadata.class;
    }

    @Override
    public boolean isDynamicResolveMode() {
        return dynamicResolve;
    }

    @Override
    protected boolean isMetaDataArtifact(ArtifactType artifactType) {
        return artifactType == ArtifactType.IVY_DESCRIPTOR;
    }

    public boolean isM2compatible() {
        return m2Compatible;
    }

    @Override
    public void setM2compatible(boolean m2compatible) {
        this.m2Compatible = m2compatible;
    }

    @Override
    public void addArtifactLocation(URI baseUri, String pattern) {
        addArtifactPattern(toResourcePattern(baseUri, pattern));
    }

    @Override
    public void addDescriptorLocation(URI baseUri, String pattern) {
        addIvyPattern(toResourcePattern(baseUri, pattern));
    }

    private ResourcePattern toResourcePattern(URI baseUri, String pattern) {
        return isM2compatible() ? new M2ResourcePattern(baseUri, pattern) : new IvyResourcePattern(
                baseUri, pattern);
    }

    @Override
    public ModuleComponentRepositoryAccess getLocalAccess() {
        return localRepositoryAccess;
    }

    @Override
    public ModuleComponentRepositoryAccess getRemoteAccess() {
        return remoteRepositoryAccess;
    }

    private class IvyLocalRepositoryAccess extends LocalRepositoryAccess {
        @Override
        protected void resolveModuleArtifacts(IvyModuleResolveMetadata module,
                                              ConfigurationMetadata variant,
                                              BuildableComponentArtifactsResolveResult result) {
            result.resolved(new MetadataSourcedComponentArtifacts());
        }

        @Override
        protected void resolveJavadocArtifacts(IvyModuleResolveMetadata module,
                                               BuildableArtifactSetResolveResult result) {
            ConfigurationMetadata configuration = module.getConfiguration("javadoc");
            if (configuration != null) {
                result.resolved(configuration.getArtifacts());
            }
        }

        @Override
        protected void resolveSourceArtifacts(IvyModuleResolveMetadata module,
                                              BuildableArtifactSetResolveResult result) {
            ConfigurationMetadata configuration = module.getConfiguration("sources");
            if (configuration != null) {
                result.resolved(configuration.getArtifacts());
            }
        }
    }

    private class IvyRemoteRepositoryAccess extends RemoteRepositoryAccess {
        @Override
        protected void resolveModuleArtifacts(IvyModuleResolveMetadata module,
                                              ConfigurationMetadata variant,
                                              BuildableComponentArtifactsResolveResult result) {
            // Configuration artifacts are determined locally
        }

        @Override
        protected void resolveJavadocArtifacts(IvyModuleResolveMetadata module,
                                               BuildableArtifactSetResolveResult result) {
            // Probe for artifact with classifier
            result.resolved(findOptionalArtifacts(module, "javadoc", "javadoc"));
        }

        @Override
        protected void resolveSourceArtifacts(IvyModuleResolveMetadata module,
                                              BuildableArtifactSetResolveResult result) {
            // Probe for artifact with classifier
            result.resolved(findOptionalArtifacts(module, "source", "sources"));
        }
    }
}
