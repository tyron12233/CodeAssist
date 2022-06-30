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
package com.tyron.builder.api.internal.artifacts.repositories;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.artifacts.ComponentMetadataListerDetails;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplierDetails;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;
import com.tyron.builder.api.artifacts.repositories.AuthenticationContainer;
import com.tyron.builder.api.artifacts.repositories.MavenArtifactRepository;
import com.tyron.builder.api.artifacts.repositories.MavenRepositoryContentDescriptor;
import com.tyron.builder.api.internal.artifacts.ModuleVersionPublisher;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ConfiguredModuleComponentRepository;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.GradleModuleMetadataParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser;
import com.tyron.builder.api.internal.artifacts.repositories.descriptor.MavenRepositoryDescriptor;
import com.tyron.builder.api.internal.artifacts.repositories.descriptor.RepositoryDescriptor;
import com.tyron.builder.api.internal.artifacts.repositories.maven.MavenMetadataLoader;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultArtifactMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultGradleModuleMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultImmutableMetadataSources;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultMavenPomMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.ImmutableMetadataSources;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMetadataArtifactProvider;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.RedirectingGradleMetadataModuleMetadataSource;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceArtifactResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.MavenResolver;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ResourcePattern;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.VersionLister;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.action.InstantiatingAction;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactIdentifier;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.external.model.MutableModuleComponentResolveMetadata;
import com.tyron.builder.internal.component.external.model.maven.MutableMavenModuleResolveMetadata;
import com.tyron.builder.internal.component.model.ComponentOverrideMetadata;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import com.tyron.builder.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.local.FileStore;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

public class DefaultMavenArtifactRepository extends AbstractAuthenticationSupportedRepository implements MavenArtifactRepository, ResolutionAwareRepository, PublicationAwareRepository {
    private static final DefaultMavenPomMetadataSource.MavenMetadataValidator
            NO_OP_VALIDATION_SERVICES = (repoName, metadata, artifactResolver) -> true;

    private final Transformer<String, MavenArtifactRepository> describer;
    private final FileResolver fileResolver;
    private final RepositoryTransportFactory transportFactory;
    private final DefaultUrlArtifactRepository urlArtifactRepository;
    private List<Object> additionalUrls = new ArrayList<>();
    private final LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata>
            locallyAvailableResourceFinder;
    private final FileStore<ModuleComponentArtifactIdentifier> artifactFileStore;
    private final MetaDataParser<MutableMavenModuleResolveMetadata> pomParser;
    private final GradleModuleMetadataParser metadataParser;
    private final FileStore<String> resourcesFileStore;
    private final FileResourceRepository fileResourceRepository;
    private final MavenMutableModuleMetadataFactory metadataFactory;
    private final IsolatableFactory isolatableFactory;
    private final ChecksumService checksumService;
    private final MavenMetadataSources metadataSources = new MavenMetadataSources();
    private final InstantiatorFactory instantiatorFactory;

    public DefaultMavenArtifactRepository(FileResolver fileResolver,
                                          RepositoryTransportFactory transportFactory,
                                          LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                                          InstantiatorFactory instantiatorFactory,
                                          FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                                          MetaDataParser<MutableMavenModuleResolveMetadata> pomParser,
                                          GradleModuleMetadataParser metadataParser,
                                          AuthenticationContainer authenticationContainer,
                                          FileStore<String> resourcesFileStore,
                                          FileResourceRepository fileResourceRepository,
                                          MavenMutableModuleMetadataFactory metadataFactory,
                                          IsolatableFactory isolatableFactory,
                                          ObjectFactory objectFactory,
                                          DefaultUrlArtifactRepository.Factory urlArtifactRepositoryFactory,
                                          ChecksumService checksumService,
                                          ProviderFactory providerFactory) {
        this(new DefaultDescriber(), fileResolver, transportFactory, locallyAvailableResourceFinder,
                instantiatorFactory, artifactFileStore, pomParser, metadataParser,
                authenticationContainer, resourcesFileStore, fileResourceRepository,
                metadataFactory, isolatableFactory, objectFactory, urlArtifactRepositoryFactory,
                checksumService, providerFactory);
    }

    public DefaultMavenArtifactRepository(Transformer<String, MavenArtifactRepository> describer,
                                          FileResolver fileResolver,
                                          RepositoryTransportFactory transportFactory,
                                          LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                                          InstantiatorFactory instantiatorFactory,
                                          FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                                          MetaDataParser<MutableMavenModuleResolveMetadata> pomParser,
                                          GradleModuleMetadataParser metadataParser,
                                          AuthenticationContainer authenticationContainer,
                                          FileStore<String> resourcesFileStore,
                                          FileResourceRepository fileResourceRepository,
                                          MavenMutableModuleMetadataFactory metadataFactory,
                                          IsolatableFactory isolatableFactory,
                                          ObjectFactory objectFactory,
                                          DefaultUrlArtifactRepository.Factory urlArtifactRepositoryFactory,
                                          ChecksumService checksumService,
                                          ProviderFactory providerFactory) {
        super(instantiatorFactory.decorateLenient(), authenticationContainer, objectFactory,
                providerFactory);
        this.describer = describer;
        this.fileResolver = fileResolver;
        this.urlArtifactRepository =
                urlArtifactRepositoryFactory.create("Maven", this::getDisplayName);
        this.transportFactory = transportFactory;
        this.locallyAvailableResourceFinder = locallyAvailableResourceFinder;
        this.artifactFileStore = artifactFileStore;
        this.pomParser = pomParser;
        this.metadataParser = metadataParser;
        this.resourcesFileStore = resourcesFileStore;
        this.fileResourceRepository = fileResourceRepository;
        this.metadataFactory = metadataFactory;
        this.isolatableFactory = isolatableFactory;
        this.checksumService = checksumService;
        this.metadataSources.setDefaults();
        this.instantiatorFactory = instantiatorFactory;
    }

    @Override
    public String getDisplayName() {
        return describer.transform(this);
    }

    @Override
    public URI getUrl() {
        return urlArtifactRepository.getUrl();
    }

    @Override
    public void setUrl(URI url) {
        invalidateDescriptor();
        urlArtifactRepository.setUrl(url);
    }

    @Override
    public void setUrl(Object url) {
        invalidateDescriptor();
        urlArtifactRepository.setUrl(url);
    }

    @Override
    public void setAllowInsecureProtocol(boolean allowInsecureProtocol) {
        invalidateDescriptor();
        urlArtifactRepository.setAllowInsecureProtocol(allowInsecureProtocol);
    }

    @Override
    public boolean isAllowInsecureProtocol() {
        return urlArtifactRepository.isAllowInsecureProtocol();
    }

    @Override
    public Set<URI> getArtifactUrls() {
        Set<URI> result = new LinkedHashSet<>();
        for (Object additionalUrl : additionalUrls) {
            result.add(fileResolver.resolveUri(additionalUrl));
        }
        return result;
    }

    @Override
    public void artifactUrls(Object... urls) {
        invalidateDescriptor();
        additionalUrls.addAll(Lists.newArrayList(urls));
    }

    @Override
    public void setArtifactUrls(Set<URI> urls) {
        invalidateDescriptor();
        setArtifactUrls((Iterable<?>) urls);
    }

    @Override
    public void setArtifactUrls(Iterable<?> urls) {
        invalidateDescriptor();
        additionalUrls = Lists.newArrayList(urls);
    }

    @Override
    public ModuleVersionPublisher createPublisher() {
        return createRealResolver();
    }

    @Override
    public ConfiguredModuleComponentRepository createResolver() {
        return createRealResolver();
    }

    @Override
    protected RepositoryDescriptor createDescriptor() {
        URI rootUri = validateUrl();
        return new MavenRepositoryDescriptor.Builder(getName(), rootUri)
                .setAuthenticated(usesCredentials())
                .setAuthenticationSchemes(getAuthenticationSchemes())
                .setMetadataSources(metadataSources.asList())
                .setArtifactUrls(Sets.newHashSet(getArtifactUrls())).create();
    }

    @Override
    protected Collection<URI> getRepositoryUrls() {
        // In a similar way to Ivy, Maven may use other hosts for additional artifacts, but not POMs
        ImmutableList.Builder<URI> builder = ImmutableList.builder();
        URI root = getUrl();
        if (root != null) {
            builder.add(root);
        }
        builder.addAll(getArtifactUrls());
        return builder.build();
    }

    @Nonnull
    protected URI validateUrl() {
        return urlArtifactRepository.validateUrl();
    }

    protected MavenResolver createRealResolver() {
        URI rootUrl = validateUrl();
        MavenResolver resolver = createResolver(rootUrl);

        for (URI repoUrl : getArtifactUrls()) {
            resolver.addArtifactLocation(repoUrl);
        }
        return resolver;
    }

    private MavenResolver createResolver(URI rootUri) {
        RepositoryTransport transport = getTransport(rootUri.getScheme());
        MavenMetadataLoader mavenMetadataLoader =
                new MavenMetadataLoader(transport.getResourceAccessor(), resourcesFileStore);
        ImmutableMetadataSources metadataSources = createMetadataSources(mavenMetadataLoader);
        Instantiator injector =
                createInjectorForMetadataSuppliers(transport, instantiatorFactory, getUrl(),
                        resourcesFileStore);
        InstantiatingAction<ComponentMetadataSupplierDetails> supplier =
                createComponentMetadataSupplierFactory(injector, isolatableFactory);
        InstantiatingAction<ComponentMetadataListerDetails> lister =
                createComponentMetadataVersionLister(injector, isolatableFactory);
        return new MavenResolver(getName(), rootUri, transport, locallyAvailableResourceFinder,
                artifactFileStore, metadataSources, MavenMetadataArtifactProvider.INSTANCE,
                mavenMetadataLoader, supplier, lister, injector, checksumService);
    }

    @Override
    public void metadataSources(Action<? super MetadataSources> configureAction) {
        invalidateDescriptor();
        metadataSources.reset();
        configureAction.execute(metadataSources);
    }

    @Override
    public MetadataSources getMetadataSources() {
        return metadataSources;
    }

    @Override
    public void mavenContent(Action<? super MavenRepositoryContentDescriptor> configureAction) {
        content(Cast.uncheckedCast(configureAction));
    }

    ImmutableMetadataSources createMetadataSources(MavenMetadataLoader mavenMetadataLoader) {
        ImmutableList.Builder<MetadataSource<?>> sources = ImmutableList.builder();
        // Don't list versions for gradleMetadata if maven-metadata.xml will be checked.
        boolean listVersionsForGradleMetadata = !metadataSources.mavenPom;
        MetadataSource<MutableModuleComponentResolveMetadata> gradleModuleMetadataSource =
                new MavenSnapshotDecoratingSource(
                        new DefaultGradleModuleMetadataSource(getMetadataParser(), metadataFactory,
                                listVersionsForGradleMetadata, checksumService));
        if (metadataSources.gradleMetadata) {
            sources.add(gradleModuleMetadataSource);
        }
        if (metadataSources.mavenPom) {
            DefaultMavenPomMetadataSource pomMetadataSource =
                    createPomMetadataSource(mavenMetadataLoader, fileResourceRepository);
            if (metadataSources.ignoreGradleMetadataRedirection) {
                sources.add(pomMetadataSource);
            } else {
                sources.add(new RedirectingGradleMetadataModuleMetadataSource(pomMetadataSource,
                        gradleModuleMetadataSource));
            }
        }
        if (metadataSources.artifact) {
            sources.add(new DefaultArtifactMetadataSource(metadataFactory));
        }
        return new DefaultImmutableMetadataSources(sources.build());
    }

    protected DefaultMavenPomMetadataSource createPomMetadataSource(MavenMetadataLoader mavenMetadataLoader,
                                                                    FileResourceRepository fileResourceRepository) {
        return new DefaultMavenPomMetadataSource(MavenMetadataArtifactProvider.INSTANCE,
                getPomParser(), fileResourceRepository, getMetadataValidationServices(),
                mavenMetadataLoader, checksumService);
    }

    protected DefaultMavenPomMetadataSource.MavenMetadataValidator getMetadataValidationServices() {
        return NO_OP_VALIDATION_SERVICES;
    }

    MetaDataParser<MutableMavenModuleResolveMetadata> getPomParser() {
        return pomParser;
    }

    private GradleModuleMetadataParser getMetadataParser() {
        return metadataParser;
    }

    FileStore<ModuleComponentArtifactIdentifier> getArtifactFileStore() {
        return artifactFileStore;
    }

    FileStore<String> getResourcesFileStore() {
        return resourcesFileStore;
    }

    public RepositoryTransport getTransport(String scheme) {
        return transportFactory.createTransport(scheme, getName(), getConfiguredAuthentication(),
                urlArtifactRepository.createRedirectVerifier());
    }

    protected LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> getLocallyAvailableResourceFinder() {
        return locallyAvailableResourceFinder;
    }

    protected InstantiatorFactory getInstantiatorFactory() {
        return instantiatorFactory;
    }

    @Override
    public RepositoryContentDescriptorInternal createRepositoryDescriptor() {
        return new DefaultMavenRepositoryContentDescriptor(this::getDisplayName);
    }

    @Override
    public RepositoryContentDescriptorInternal getRepositoryDescriptorCopy() {
        return getRepositoryDescriptor().asMutableCopy();
    }

    private static class DefaultDescriber implements Transformer<String, MavenArtifactRepository> {
        @Override
        public String transform(MavenArtifactRepository repository) {
            URI url = repository.getUrl();
            if (url == null) {
                return repository.getName();
            }
            return repository.getName() + '(' + url + ')';
        }
    }

    private static class MavenMetadataSources implements MetadataSources {
        boolean gradleMetadata;
        boolean mavenPom;
        boolean artifact;
        boolean ignoreGradleMetadataRedirection;

        void setDefaults() {
            mavenPom();
            ignoreGradleMetadataRedirection = false;
        }

        void reset() {
            gradleMetadata = false;
            mavenPom = false;
            artifact = false;
            ignoreGradleMetadataRedirection = false;
        }

        /**
         * This is used for reporting purposes on build scans.
         * Changing this means a change of repository for build scans.
         *
         * @return a list of implemented metadata sources, as strings.
         */
        List<String> asList() {
            List<String> list = new ArrayList<>();
            if (gradleMetadata) {
                list.add("gradleMetadata");
            }
            if (mavenPom) {
                list.add("mavenPom");
            }
            if (artifact) {
                list.add("artifact");
            }
            if (ignoreGradleMetadataRedirection) {
                list.add("ignoreGradleMetadataRedirection");
            }
            return list;
        }

        @Override
        public void gradleMetadata() {
            gradleMetadata = true;
        }

        @Override
        public void mavenPom() {
            mavenPom = true;
        }

        @Override
        public void artifact() {
            artifact = true;
        }

        @Override
        public void ignoreGradleMetadataRedirection() {
            ignoreGradleMetadataRedirection = true;
        }

        @Override
        public boolean isGradleMetadataEnabled() {
            return gradleMetadata;
        }

        @Override
        public boolean isMavenPomEnabled() {
            return mavenPom;
        }

        @Override
        public boolean isArtifactEnabled() {
            return artifact;
        }

        @Override
        public boolean isIgnoreGradleMetadataRedirectionEnabled() {
            return ignoreGradleMetadataRedirection;
        }
    }

    private static class MavenSnapshotDecoratingSource implements MetadataSource<MutableModuleComponentResolveMetadata> {
        private static final int HASH_ID = 30155977;

        private final MetadataSource<MutableModuleComponentResolveMetadata> delegate;

        private MavenSnapshotDecoratingSource(MetadataSource<MutableModuleComponentResolveMetadata> delegate) {
            this.delegate = delegate;
        }

        @Override
        public MutableModuleComponentResolveMetadata create(String repositoryName,
                                                            ComponentResolvers componentResolvers,
                                                            ModuleComponentIdentifier moduleComponentIdentifier,
                                                            ComponentOverrideMetadata prescribedMetaData,
                                                            ExternalResourceArtifactResolver artifactResolver,
                                                            BuildableModuleComponentMetaDataResolveResult result) {
            MutableModuleComponentResolveMetadata metadata =
                    delegate.create(repositoryName, componentResolvers, moduleComponentIdentifier,
                            prescribedMetaData, artifactResolver, result);
            if (metadata != null) {
                return MavenResolver.processMetaData((MutableMavenModuleResolveMetadata) metadata);
            }
            return null;
        }

        @Override
        public void listModuleVersions(ModuleDependencyMetadata dependency,
                                       ModuleIdentifier module,
                                       List<ResourcePattern> ivyPatterns,
                                       List<ResourcePattern> artifactPatterns,
                                       VersionLister versionLister,
                                       BuildableModuleVersionListingResolveResult result) {
            delegate.listModuleVersions(dependency, module, ivyPatterns, artifactPatterns,
                    versionLister, result);
        }

        @Override
        public void appendId(Hasher hasher) {
            hasher.putInt(HASH_ID);
            delegate.appendId(hasher);
        }
    }
}
