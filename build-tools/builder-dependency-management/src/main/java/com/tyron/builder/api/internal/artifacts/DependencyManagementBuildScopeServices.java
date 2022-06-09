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

package com.tyron.builder.api.internal.artifacts;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.tyron.builder.api.internal.artifacts.component.ComponentIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.component.DefaultComponentIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.dsl.CapabilityNotationParserFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheLockingManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCacheMetadata;
import com.tyron.builder.api.internal.artifacts.ivyservice.ArtifactCachesProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ChangingValueDependencyResolutionListener;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ConnectionFailureRepositoryDisabler;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleDescriptorHashCodec;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ModuleDescriptorHashModuleSource;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.RepositoryDisabler;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ResolverProviderFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.CachingVersionSelectorScheme;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionComparator;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.DependencyVerificationOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.AbstractModuleMetadataCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.InMemoryModuleMetadataCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ModuleComponentResolveMetadataSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ModuleMetadataSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ModuleRepositoryCacheProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ModuleRepositoryCaches;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ModuleSourcesSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.PersistentModuleMetadataCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.ReadOnlyModuleMetadataCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.SuppliedComponentMetadataSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.TwoStageModuleMetadataCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.AbstractArtifactsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultModuleArtifactCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultModuleArtifactsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.InMemoryModuleArtifactCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.InMemoryModuleArtifactsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.ModuleArtifactCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.ReadOnlyModuleArtifactCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.ReadOnlyModuleArtifactsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.TwoStageArtifactsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.artifacts.TwoStageModuleArtifactCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions.AbstractModuleVersionsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultModuleVersionsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions.InMemoryModuleVersionsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions.ReadOnlyModuleVersionsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.dynamicversions.TwoStageModuleVersionsCache;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.LocalComponentMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.DefaultProjectLocalComponentProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.DefaultProjectPublicationRegistry;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.ProjectArtifactResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.ProjectArtifactSetResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.DefaultArtifactDependencyResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.CachingComponentSelectionDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.DesugaredAttributeContainerSerializer;
import com.tyron.builder.api.internal.artifacts.mvnsettings.DefaultLocalMavenRepositoryLocator;
import com.tyron.builder.api.internal.artifacts.mvnsettings.DefaultMavenFileLocations;
import com.tyron.builder.api.internal.artifacts.mvnsettings.DefaultMavenSettingsProvider;
import com.tyron.builder.api.internal.artifacts.mvnsettings.LocalMavenRepositoryLocator;
import com.tyron.builder.api.internal.artifacts.mvnsettings.MavenSettingsProvider;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.DefaultMetadataFileSourceCodec;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MetadataFileSource;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.DefaultExternalResourceAccessor;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalResourceAccessor;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import com.tyron.builder.api.internal.artifacts.transform.TransformationNodeDependencyResolver;
import com.tyron.builder.api.internal.artifacts.verification.signatures.DefaultSignatureVerificationServiceFactory;
import com.tyron.builder.api.internal.artifacts.verification.signatures.SignatureVerificationServiceFactory;
import com.tyron.builder.api.internal.catalog.DefaultDependenciesAccessors;
import com.tyron.builder.api.internal.catalog.DependenciesAccessorsWorkspaceProvider;
import com.tyron.builder.api.internal.filestore.ArtifactIdentifierFileStore;
import com.tyron.builder.api.internal.filestore.DefaultArtifactIdentifierFileStore;
import com.tyron.builder.api.internal.filestore.TwoStageArtifactIdentifierFileStore;
import com.tyron.builder.api.internal.notations.ClientModuleNotationParserFactory;
import com.tyron.builder.api.internal.notations.DependencyConstraintNotationParser;
import com.tyron.builder.api.internal.notations.DependencyNotationParser;
import com.tyron.builder.api.internal.notations.ProjectDependencyFactory;
import com.tyron.builder.api.internal.runtimeshaded.RuntimeShadedJarFactory;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.external.model.PreferJavaRuntimeVariant;
import com.tyron.builder.internal.component.model.PersistentModuleSource;
import com.tyron.builder.internal.management.DefaultDependencyResolutionManagement;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataRuleExecutor;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor;
import com.tyron.builder.internal.resolve.caching.DesugaringAttributeContainerSerializer;
import com.tyron.builder.internal.resource.cached.ByUrlCachedExternalResourceIndex;
import com.tyron.builder.internal.resource.cached.CachedExternalResourceIndex;
import com.tyron.builder.internal.resource.cached.DefaultExternalResourceFileStore;
import com.tyron.builder.internal.resource.cached.ExternalResourceFileStore;
import com.tyron.builder.internal.resource.cached.TwoStageByUrlCachedExternalResourceIndex;
import com.tyron.builder.internal.resource.cached.TwoStageExternalResourceFileStore;
import com.tyron.builder.internal.resource.local.ivy.LocallyAvailableResourceFinderFactory;
import com.tyron.builder.internal.resource.transfer.CachingTextUriResourceLoader;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.FeaturePreviews;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyFactory;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.api.internal.resources.ApiTextResourceAdapter;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.cache.internal.CleaningInMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.GeneratedGradleJarCache;
import com.tyron.builder.cache.internal.InMemoryCacheDecoratorFactory;
import com.tyron.builder.cache.internal.ProducerGuard;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.initialization.DependenciesAccessors;
import com.tyron.builder.initialization.internal.InternalBuildFinishedListener;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.classpath.ClasspathBuilder;
import com.tyron.builder.internal.classpath.ClasspathWalker;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChangeDetector;
import com.tyron.builder.internal.execution.impl.DefaultExecutionEngine;
import com.tyron.builder.internal.execution.steps.AssignWorkspaceStep;
import com.tyron.builder.internal.execution.steps.BroadcastChangingOutputsStep;
import com.tyron.builder.internal.execution.steps.CachingContext;
import com.tyron.builder.internal.execution.steps.CachingResult;
import com.tyron.builder.internal.execution.steps.CaptureStateAfterExecutionStep;
import com.tyron.builder.internal.execution.steps.CaptureStateBeforeExecutionStep;
import com.tyron.builder.internal.execution.steps.CreateOutputsStep;
import com.tyron.builder.internal.execution.steps.ExecuteStep;
import com.tyron.builder.internal.execution.steps.IdentifyStep;
import com.tyron.builder.internal.execution.steps.IdentityCacheStep;
import com.tyron.builder.internal.execution.steps.LoadPreviousExecutionStateStep;
import com.tyron.builder.internal.execution.steps.RemovePreviousOutputsStep;
import com.tyron.builder.internal.execution.steps.RemoveUntrackedExecutionStateStep;
import com.tyron.builder.internal.execution.steps.ResolveChangesStep;
import com.tyron.builder.internal.execution.steps.ResolveInputChangesStep;
import com.tyron.builder.internal.execution.steps.SkipUpToDateStep;
import com.tyron.builder.internal.execution.steps.Step;
import com.tyron.builder.internal.execution.steps.StoreExecutionStateStep;
import com.tyron.builder.internal.execution.steps.TimeoutStep;
import com.tyron.builder.internal.execution.steps.UpToDateResult;
import com.tyron.builder.internal.execution.steps.ValidateStep;
import com.tyron.builder.internal.execution.steps.ValidationFinishedContext;
import com.tyron.builder.internal.execution.timeout.TimeoutHandler;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.RelativeFilePathResolver;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.id.UniqueId;
import com.tyron.builder.internal.installation.CurrentGradleInstallation;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.management.DependencyResolutionManagementInternal;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.internal.resource.connector.ResourceConnectorFactory;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.util.internal.BuildCommencedTimeProvider;
import com.tyron.builder.util.internal.SimpleMapInterner;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The set of dependency management services that are created per build.
 */
class DependencyManagementBuildScopeServices {
    void configure(ServiceRegistration registration) {
        registration.add(ProjectArtifactResolver.class);
        registration.add(ProjectArtifactSetResolver.class);
        registration.add(ProjectDependencyResolver.class);
        registration.add(DefaultExternalResourceFileStore.Factory.class);
        registration.add(DefaultArtifactIdentifierFileStore.Factory.class);
        registration.add(TransformationNodeDependencyResolver.class);
    }

    DependencyResolutionManagementInternal createSharedDependencyResolutionServices(Instantiator instantiator,
                                                                                    UserCodeApplicationContext context,
                                                                                    DependencyManagementServices dependencyManagementServices,
                                                                                    FileResolver fileResolver,
                                                                                    FileCollectionFactory fileCollectionFactory,
                                                                                    DependencyMetaDataProvider dependencyMetaDataProvider,
                                                                                    ObjectFactory objects,
                                                                                    ProviderFactory providers,
                                                                                    CollectionCallbackActionDecorator collectionCallbackActionDecorator,
                                                                                    FeaturePreviews featurePreviews) {
        return instantiator.newInstance(DefaultDependencyResolutionManagement.class,
            context,
            dependencyManagementServices,
            fileResolver,
            fileCollectionFactory,
            dependencyMetaDataProvider,
            objects,
            providers,
            collectionCallbackActionDecorator,
            featurePreviews
        );
    }

    DependencyManagementServices createDependencyManagementServices(ServiceRegistry parent) {
        return new DefaultDependencyManagementServices(parent);
    }

    ComponentIdentifierFactory createComponentIdentifierFactory(BuildState currentBuild, BuildStateRegistry buildRegistry) {
        return new DefaultComponentIdentifierFactory(buildRegistry.getBuild(currentBuild.getBuildIdentifier()));
    }

    VersionComparator createVersionComparator() {
        return new DefaultVersionComparator();
    }

    DefaultProjectDependencyFactory createProjectDependencyFactory(
        Instantiator instantiator,
        StartParameter startParameter,
        ImmutableAttributesFactory attributesFactory) {
        NotationParser<Object, Capability> capabilityNotationParser = new CapabilityNotationParserFactory(false).create();
        return new DefaultProjectDependencyFactory(instantiator, startParameter.isBuildProjectDependencies(), capabilityNotationParser, attributesFactory);
    }

    DependencyFactory createDependencyFactory(
        Instantiator instantiator,
        DefaultProjectDependencyFactory factory,
        ClassPathRegistry classPathRegistry,
        CurrentGradleInstallation currentGradleInstallation,
        FileCollectionFactory fileCollectionFactory,
        RuntimeShadedJarFactory runtimeShadedJarFactory,
        ImmutableAttributesFactory attributesFactory,
        SimpleMapInterner stringInterner) {
        NotationParser<Object, Capability> capabilityNotationParser = new CapabilityNotationParserFactory(false).create();
        ProjectDependencyFactory projectDependencyFactory = new ProjectDependencyFactory(factory);

        return new DefaultDependencyFactory(
            DependencyNotationParser
                    .parser(instantiator, factory, classPathRegistry, fileCollectionFactory, runtimeShadedJarFactory, currentGradleInstallation, stringInterner, attributesFactory, capabilityNotationParser),
            DependencyConstraintNotationParser
                    .parser(instantiator, factory, stringInterner, attributesFactory),
            new ClientModuleNotationParserFactory(instantiator, stringInterner).create(),
            capabilityNotationParser, projectDependencyFactory,
            attributesFactory);
    }

    RuntimeShadedJarFactory createRuntimeShadedJarFactory(GeneratedGradleJarCache jarCache, ProgressLoggerFactory progressLoggerFactory, ClasspathWalker classpathWalker, ClasspathBuilder classpathBuilder, BuildOperationExecutor executor) {
        return new RuntimeShadedJarFactory(jarCache, progressLoggerFactory, classpathWalker, classpathBuilder, executor);
    }

    ModuleExclusions createModuleExclusions() {
        return new ModuleExclusions();
    }

    MavenMutableModuleMetadataFactory createMutableMavenMetadataFactory(ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                                                        ImmutableAttributesFactory attributesFactory,
                                                                        NamedObjectInstantiator instantiator,
                                                                        PreferJavaRuntimeVariant schema) {
        return new MavenMutableModuleMetadataFactory(moduleIdentifierFactory, attributesFactory, instantiator, schema);
    }

    IvyMutableModuleMetadataFactory createMutableIvyMetadataFactory(ImmutableModuleIdentifierFactory moduleIdentifierFactory, ImmutableAttributesFactory attributesFactory, PreferJavaRuntimeVariant schema) {
        return new IvyMutableModuleMetadataFactory(moduleIdentifierFactory, attributesFactory, schema);
    }

    AttributeContainerSerializer createAttributeContainerSerializer(ImmutableAttributesFactory attributesFactory, NamedObjectInstantiator instantiator) {
        return new DesugaredAttributeContainerSerializer(attributesFactory, instantiator);
    }

    ModuleSourcesSerializer createModuleSourcesSerializer(ImmutableModuleIdentifierFactory moduleIdentifierFactory, FileStoreAndIndexProvider fileStoreAndIndexProvider) {
        Map<Integer, PersistentModuleSource.Codec<? extends PersistentModuleSource>> codecs = ImmutableMap.of(
            MetadataFileSource.CODEC_ID, new DefaultMetadataFileSourceCodec(moduleIdentifierFactory, fileStoreAndIndexProvider.getArtifactIdentifierFileStore()),
            ModuleDescriptorHashModuleSource.CODEC_ID, new ModuleDescriptorHashCodec()
        );
        return new ModuleSourcesSerializer(codecs);
    }

    ModuleRepositoryCacheProvider createModuleRepositoryCacheProvider(BuildCommencedTimeProvider timeProvider,
                                                                      ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                                                      ArtifactCachesProvider artifactCaches,
                                                                      AttributeContainerSerializer attributeContainerSerializer,
                                                                      MavenMutableModuleMetadataFactory mavenMetadataFactory,
                                                                      IvyMutableModuleMetadataFactory ivyMetadataFactory,
                                                                      SimpleMapInterner stringInterner,
                                                                      FileStoreAndIndexProvider fileStoreAndIndexProvider,
                                                                      ModuleSourcesSerializer moduleSourcesSerializer,
                                                                      ChecksumService checksumService) {
        ArtifactIdentifierFileStore artifactIdentifierFileStore = fileStoreAndIndexProvider.getArtifactIdentifierFileStore();
        ModuleRepositoryCaches writableCaches = artifactCaches.withWritableCache((md, manager) -> prepareModuleRepositoryCaches(md, manager, timeProvider, moduleIdentifierFactory, attributeContainerSerializer, mavenMetadataFactory, ivyMetadataFactory, stringInterner, artifactIdentifierFileStore, moduleSourcesSerializer, checksumService));
        AtomicReference<Path> roCachePath = new AtomicReference<>();
        Optional<ModuleRepositoryCaches> readOnlyCaches = artifactCaches.withReadOnlyCache((ro, manager) -> {
            roCachePath.set(ro.getCacheDir().toPath());
            return prepareReadOnlyModuleRepositoryCaches(ro, manager, timeProvider, moduleIdentifierFactory, attributeContainerSerializer, mavenMetadataFactory, ivyMetadataFactory, stringInterner, artifactIdentifierFileStore, moduleSourcesSerializer, checksumService);
        });
        AbstractModuleVersionsCache
                moduleVersionsCache = readOnlyCaches.map(mrc -> (AbstractModuleVersionsCache) new TwoStageModuleVersionsCache(timeProvider, mrc.moduleVersionsCache, writableCaches.moduleVersionsCache)).orElse(writableCaches.moduleVersionsCache);
        AbstractModuleMetadataCache
                persistentModuleMetadataCache = readOnlyCaches.map(mrc -> (AbstractModuleMetadataCache) new TwoStageModuleMetadataCache(timeProvider, mrc.moduleMetadataCache, writableCaches.moduleMetadataCache)).orElse(writableCaches.moduleMetadataCache);
        AbstractArtifactsCache
                moduleArtifactsCache = readOnlyCaches.map(mrc -> (AbstractArtifactsCache) new TwoStageArtifactsCache(timeProvider, mrc.moduleArtifactsCache, writableCaches.moduleArtifactsCache)).orElse(writableCaches.moduleArtifactsCache);
        ModuleArtifactCache
                moduleArtifactCache = readOnlyCaches.map(mrc -> (ModuleArtifactCache) new TwoStageModuleArtifactCache(roCachePath.get(), mrc.moduleArtifactCache, writableCaches.moduleArtifactCache)).orElse(writableCaches.moduleArtifactCache);
        ModuleRepositoryCaches persistentCaches = new ModuleRepositoryCaches(
            new InMemoryModuleVersionsCache(timeProvider, moduleVersionsCache),
            new InMemoryModuleMetadataCache(timeProvider, persistentModuleMetadataCache),
            new InMemoryModuleArtifactsCache(timeProvider, moduleArtifactsCache),
            new InMemoryModuleArtifactCache(timeProvider, moduleArtifactCache)
        );
        ModuleRepositoryCaches inMemoryOnlyCaches = new ModuleRepositoryCaches(
            new InMemoryModuleVersionsCache(timeProvider),
            new InMemoryModuleMetadataCache(timeProvider),
            new InMemoryModuleArtifactsCache(timeProvider),
            new InMemoryModuleArtifactCache(timeProvider)
        );
        return new ModuleRepositoryCacheProvider(persistentCaches, inMemoryOnlyCaches);
    }

    private ModuleRepositoryCaches prepareModuleRepositoryCaches(ArtifactCacheMetadata artifactCacheMetadata, ArtifactCacheLockingManager artifactCacheLockingManager, BuildCommencedTimeProvider timeProvider, ImmutableModuleIdentifierFactory moduleIdentifierFactory, AttributeContainerSerializer attributeContainerSerializer, MavenMutableModuleMetadataFactory mavenMetadataFactory, IvyMutableModuleMetadataFactory ivyMetadataFactory, SimpleMapInterner stringInterner, ArtifactIdentifierFileStore artifactIdentifierFileStore, ModuleSourcesSerializer moduleSourcesSerializer, ChecksumService checksumService) {
        DefaultModuleVersionsCache moduleVersionsCache = new DefaultModuleVersionsCache(
            timeProvider,
            artifactCacheLockingManager,
            moduleIdentifierFactory);
        PersistentModuleMetadataCache moduleMetadataCache = new PersistentModuleMetadataCache(
            timeProvider,
            artifactCacheLockingManager,
            artifactCacheMetadata,
            moduleIdentifierFactory,
            attributeContainerSerializer,
            mavenMetadataFactory,
            ivyMetadataFactory,
            stringInterner,
            moduleSourcesSerializer,
            checksumService);
        DefaultModuleArtifactsCache moduleArtifactsCache = new DefaultModuleArtifactsCache(
            timeProvider,
            artifactCacheLockingManager
        );
        DefaultModuleArtifactCache moduleArtifactCache = new DefaultModuleArtifactCache(
            "module-artifact",
            timeProvider,
            artifactCacheLockingManager,
            artifactIdentifierFileStore.getFileAccessTracker(),
            artifactCacheMetadata.getCacheDir().toPath()
        );
        return new ModuleRepositoryCaches(
            moduleVersionsCache,
            moduleMetadataCache,
            moduleArtifactsCache,
            moduleArtifactCache
        );
    }

    private ModuleRepositoryCaches prepareReadOnlyModuleRepositoryCaches(ArtifactCacheMetadata artifactCacheMetadata, ArtifactCacheLockingManager artifactCacheLockingManager, BuildCommencedTimeProvider timeProvider, ImmutableModuleIdentifierFactory moduleIdentifierFactory, AttributeContainerSerializer attributeContainerSerializer, MavenMutableModuleMetadataFactory mavenMetadataFactory, IvyMutableModuleMetadataFactory ivyMetadataFactory, SimpleMapInterner stringInterner, ArtifactIdentifierFileStore artifactIdentifierFileStore, ModuleSourcesSerializer moduleSourcesSerializer, ChecksumService checksumService) {
        ReadOnlyModuleVersionsCache moduleVersionsCache = new ReadOnlyModuleVersionsCache(
            timeProvider,
            artifactCacheLockingManager,
            moduleIdentifierFactory);
        ReadOnlyModuleMetadataCache moduleMetadataCache = new ReadOnlyModuleMetadataCache(
            timeProvider,
            artifactCacheLockingManager,
            artifactCacheMetadata,
            moduleIdentifierFactory,
            attributeContainerSerializer,
            mavenMetadataFactory,
            ivyMetadataFactory,
            stringInterner,
            moduleSourcesSerializer,
            checksumService);
        ReadOnlyModuleArtifactsCache moduleArtifactsCache = new ReadOnlyModuleArtifactsCache(
            timeProvider,
            artifactCacheLockingManager
        );
        ReadOnlyModuleArtifactCache moduleArtifactCache = new ReadOnlyModuleArtifactCache(
            "module-artifact",
            timeProvider,
            artifactCacheLockingManager,
            artifactIdentifierFileStore.getFileAccessTracker(),
            artifactCacheMetadata.getCacheDir().toPath()
        );
        return new ModuleRepositoryCaches(
            moduleVersionsCache,
            moduleMetadataCache,
            moduleArtifactsCache,
            moduleArtifactCache
        );
    }

    FileStoreAndIndexProvider createFileStoreAndIndexProvider(
        BuildCommencedTimeProvider timeProvider,
        ArtifactCachesProvider artifactCaches,
        DefaultExternalResourceFileStore.Factory defaultExternalResourceFileStoreFactory,
        DefaultArtifactIdentifierFileStore.Factory defaultArtifactIdentifierFileStoreFactory
    ) {
        ExternalResourceFileStore writableFileStore = defaultExternalResourceFileStoreFactory.create(artifactCaches.getWritableCacheMetadata());
        ExternalResourceFileStore externalResourceFileStore = artifactCaches.withReadOnlyCache((md, manager) ->
            (ExternalResourceFileStore) new TwoStageExternalResourceFileStore(defaultExternalResourceFileStoreFactory.create(md), writableFileStore)).orElse(writableFileStore);
        CachedExternalResourceIndex<String> writableByUrlCachedExternalResourceIndex = prepareArtifactUrlCachedResolutionIndex(timeProvider, artifactCaches.getWritableCacheLockingManager(), externalResourceFileStore, artifactCaches.getWritableCacheMetadata());
        ArtifactIdentifierFileStore writableArtifactIdentifierFileStore = artifactCaches.withWritableCache((md, manager) -> defaultArtifactIdentifierFileStoreFactory.create(md));
        ArtifactIdentifierFileStore artifactIdentifierFileStore = artifactCaches.withReadOnlyCache((md, manager) -> (ArtifactIdentifierFileStore) new TwoStageArtifactIdentifierFileStore(
            defaultArtifactIdentifierFileStoreFactory.create(md),
            writableArtifactIdentifierFileStore
        )).orElse(writableArtifactIdentifierFileStore);
        return new FileStoreAndIndexProvider(
            artifactCaches.withReadOnlyCache((md, manager) -> (CachedExternalResourceIndex<String>) new TwoStageByUrlCachedExternalResourceIndex(md.getCacheDir().toPath(), prepareArtifactUrlCachedResolutionIndex(timeProvider, manager, externalResourceFileStore, md), writableByUrlCachedExternalResourceIndex)).orElse(writableByUrlCachedExternalResourceIndex),
            externalResourceFileStore, artifactIdentifierFileStore);
    }

    private ByUrlCachedExternalResourceIndex prepareArtifactUrlCachedResolutionIndex(BuildCommencedTimeProvider timeProvider, ArtifactCacheLockingManager artifactCacheLockingManager, ExternalResourceFileStore externalResourceFileStore, ArtifactCacheMetadata artifactCacheMetadata) {
        return new ByUrlCachedExternalResourceIndex(
            "resource-at-url",
            timeProvider,
            artifactCacheLockingManager,
            externalResourceFileStore.getFileAccessTracker(),
            artifactCacheMetadata.getCacheDir().toPath()
        );
    }

    TextUriResourceLoader.Factory createTextUrlResourceLoaderFactory(FileStoreAndIndexProvider fileStoreAndIndexProvider, RepositoryTransportFactory repositoryTransportFactory, RelativeFilePathResolver resolver) {
        final HashSet<String> schemas = Sets.newHashSet("https", "http");
        return redirectVerifier -> {
            RepositoryTransport
                    transport = repositoryTransportFactory.createTransport(schemas, "resources http", Collections.emptyList(), redirectVerifier);
            ExternalResourceAccessor externalResourceAccessor = new DefaultExternalResourceAccessor(fileStoreAndIndexProvider.getExternalResourceFileStore(), transport.getResourceAccessor());
            return new CachingTextUriResourceLoader(externalResourceAccessor, schemas, resolver);
        };
    }

    protected ApiTextResourceAdapter.Factory createTextResourceAdapterFactory(TextUriResourceLoader.Factory textUriResourceLoaderFactory, TemporaryFileProvider tempFileProvider) {
        return new ApiTextResourceAdapter.Factory(textUriResourceLoaderFactory, tempFileProvider);
    }

    MavenSettingsProvider createMavenSettingsProvider() {
        return new DefaultMavenSettingsProvider(new DefaultMavenFileLocations());
    }

    LocalMavenRepositoryLocator createLocalMavenRepositoryLocator(MavenSettingsProvider mavenSettingsProvider) {
        return new DefaultLocalMavenRepositoryLocator(mavenSettingsProvider);
    }

    LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> createArtifactRevisionIdLocallyAvailableResourceFinder(ArtifactCachesProvider artifactCaches,
                                                                                                                           LocalMavenRepositoryLocator localMavenRepositoryLocator,
                                                                                                                           FileStoreAndIndexProvider fileStoreAndIndexProvider,
                                                                                                                           ChecksumService checksumService) {
        LocallyAvailableResourceFinderFactory finderFactory = new LocallyAvailableResourceFinderFactory(
            artifactCaches,
            localMavenRepositoryLocator,
            fileStoreAndIndexProvider.getArtifactIdentifierFileStore(), checksumService);
        return finderFactory.create();
    }

    RepositoryTransportFactory createRepositoryTransportFactory(TemporaryFileProvider temporaryFileProvider,
                                                                FileStoreAndIndexProvider fileStoreAndIndexProvider,
                                                                BuildCommencedTimeProvider buildCommencedTimeProvider,
                                                                ArtifactCachesProvider artifactCachesProvider,
                                                                List<ResourceConnectorFactory> resourceConnectorFactories,
                                                                BuildOperationExecutor buildOperationExecutor,
                                                                ProducerGuard<ExternalResourceName> producerGuard,
                                                                FileResourceRepository fileResourceRepository,
                                                                ChecksumService checksumService,
                                                                StartParameterResolutionOverride startParameterResolutionOverride,
                                                                ListenerManager listenerManager) {
        return artifactCachesProvider.withWritableCache((md, manager) -> new RepositoryTransportFactory(
            resourceConnectorFactories,
            temporaryFileProvider,
            fileStoreAndIndexProvider.getExternalResourceIndex(),
            buildCommencedTimeProvider,
            manager,
            buildOperationExecutor,
            startParameterResolutionOverride,
            producerGuard,
            fileResourceRepository,
            checksumService,
            listenerManager.getBroadcaster(FileResourceListener.class)));
    }

    RepositoryDisabler createRepositoryDisabler() {
        return new ConnectionFailureRepositoryDisabler();
    }

    DependencyVerificationOverride createDependencyVerificationOverride(StartParameterResolutionOverride startParameterResolutionOverride,
                                                                        BuildOperationExecutor buildOperationExecutor,
                                                                        ChecksumService checksumService,
                                                                        SignatureVerificationServiceFactory signatureVerificationServiceFactory,
                                                                        DocumentationRegistry documentationRegistry,
                                                                        ListenerManager listenerManager,
                                                                        BuildCommencedTimeProvider timeProvider,
                                                                        ServiceRegistry serviceRegistry) {
        DependencyVerificationOverride override = startParameterResolutionOverride.dependencyVerificationOverride(buildOperationExecutor, checksumService, signatureVerificationServiceFactory, documentationRegistry, timeProvider, () -> serviceRegistry.get(GradleProperties.class));
        registerBuildFinishedHooks(listenerManager, override);
        return override;
    }

    ResolveIvyFactory createResolveIvyFactory(StartParameterResolutionOverride startParameterResolutionOverride,
                                              ModuleRepositoryCacheProvider moduleRepositoryCacheProvider,
                                              DependencyVerificationOverride dependencyVerificationOverride,
                                              BuildCommencedTimeProvider buildCommencedTimeProvider,
                                              VersionComparator versionComparator,
                                              ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                              RepositoryDisabler repositoryBlacklister,
                                              VersionParser versionParser,
                                              ListenerManager listenerManager,
                                              CalculatedValueContainerFactory calculatedValueContainerFactory) {
        return new ResolveIvyFactory(
            moduleRepositoryCacheProvider,
            startParameterResolutionOverride,
            dependencyVerificationOverride,
            buildCommencedTimeProvider,
            versionComparator,
            moduleIdentifierFactory,
            repositoryBlacklister,
            versionParser,
            listenerManager.getBroadcaster(ChangingValueDependencyResolutionListener.class),
            calculatedValueContainerFactory);
    }

    ComponentSelectionDescriptorFactory createComponentSelectionDescriptorFactory() {
        return new CachingComponentSelectionDescriptorFactory();
    }

    ArtifactDependencyResolver createArtifactDependencyResolver(ResolveIvyFactory resolveIvyFactory,
                                                                DependencyDescriptorFactory dependencyDescriptorFactory,
                                                                VersionComparator versionComparator,
                                                                List<ResolverProviderFactory> resolverFactories,
                                                                ProjectDependencyResolver projectDependencyResolver,
                                                                ModuleExclusions moduleExclusions,
                                                                BuildOperationExecutor buildOperationExecutor,
                                                                ComponentSelectorConverter componentSelectorConverter,
                                                                ImmutableAttributesFactory attributesFactory,
                                                                VersionSelectorScheme versionSelectorScheme,
                                                                VersionParser versionParser,
                                                                ComponentMetadataSupplierRuleExecutor componentMetadataSupplierRuleExecutor,
                                                                InstantiatorFactory instantiatorFactory,
                                                                ComponentSelectionDescriptorFactory componentSelectionDescriptorFactory,
                                                                FeaturePreviews featurePreviews,
                                                                CalculatedValueContainerFactory calculatedValueContainerFactory) {
        return new DefaultArtifactDependencyResolver(
            buildOperationExecutor,
            resolverFactories,
            projectDependencyResolver,
            resolveIvyFactory,
            dependencyDescriptorFactory,
            versionComparator,
            moduleExclusions,
            componentSelectorConverter,
            attributesFactory,
            versionSelectorScheme,
            versionParser,
            componentMetadataSupplierRuleExecutor,
            instantiatorFactory,
            componentSelectionDescriptorFactory,
            featurePreviews,
            calculatedValueContainerFactory);
    }

    ProjectPublicationRegistry createProjectPublicationRegistry() {
        return new DefaultProjectPublicationRegistry();
    }

    LocalComponentProvider createProjectComponentProvider(
        LocalComponentMetadataBuilder metaDataBuilder,
        ImmutableModuleIdentifierFactory moduleIdentifierFactory
    ) {
        return new DefaultProjectLocalComponentProvider(metaDataBuilder, moduleIdentifierFactory);
    }

    ComponentSelectorConverter createModuleVersionSelectorFactory(ComponentIdentifierFactory componentIdentifierFactory, LocalComponentRegistry localComponentRegistry) {
        return new DefaultComponentSelectorConverter(componentIdentifierFactory, localComponentRegistry);
    }

    VersionParser createVersionParser() {
        return new VersionParser();
    }

    VersionSelectorScheme createVersionSelectorScheme(VersionComparator versionComparator, VersionParser versionParser) {
        DefaultVersionSelectorScheme
                delegate = new DefaultVersionSelectorScheme(versionComparator, versionParser);
        CachingVersionSelectorScheme selectorScheme = new CachingVersionSelectorScheme(delegate);
        return selectorScheme;
    }

    SimpleMapInterner createStringInterner() {
        return SimpleMapInterner.threadSafe();
    }

    ModuleComponentResolveMetadataSerializer createModuleComponentResolveMetadataSerializer(ImmutableAttributesFactory attributesFactory, MavenMutableModuleMetadataFactory mavenMetadataFactory, IvyMutableModuleMetadataFactory ivyMetadataFactory, ImmutableModuleIdentifierFactory moduleIdentifierFactory, NamedObjectInstantiator instantiator, ModuleSourcesSerializer moduleSourcesSerializer) {
        DesugaringAttributeContainerSerializer attributeContainerSerializer = new DesugaringAttributeContainerSerializer(attributesFactory, instantiator);
        return new ModuleComponentResolveMetadataSerializer(new ModuleMetadataSerializer(attributeContainerSerializer, mavenMetadataFactory, ivyMetadataFactory, moduleSourcesSerializer), attributeContainerSerializer, moduleIdentifierFactory);
    }

    SuppliedComponentMetadataSerializer createSuppliedComponentMetadataSerializer(ImmutableModuleIdentifierFactory moduleIdentifierFactory, AttributeContainerSerializer attributeContainerSerializer) {
        ModuleVersionIdentifierSerializer moduleVersionIdentifierSerializer = new ModuleVersionIdentifierSerializer(moduleIdentifierFactory);
        return new SuppliedComponentMetadataSerializer(moduleVersionIdentifierSerializer, attributeContainerSerializer);
    }

    ComponentMetadataRuleExecutor createComponentMetadataRuleExecutor(ValueSnapshotter valueSnapshotter,
                                                                      GlobalScopedCache globalScopedCache,
                                                                      InMemoryCacheDecoratorFactory cacheDecoratorFactory,
                                                                      BuildCommencedTimeProvider timeProvider,
                                                                      ModuleComponentResolveMetadataSerializer serializer) {
        return new ComponentMetadataRuleExecutor(globalScopedCache, cacheDecoratorFactory, valueSnapshotter, timeProvider, serializer);
    }

    ComponentMetadataSupplierRuleExecutor createComponentMetadataSupplierRuleExecutor(ValueSnapshotter snapshotter,
                                                                                      GlobalScopedCache globalScopedCache,
                                                                                      InMemoryCacheDecoratorFactory cacheDecoratorFactory,
                                                                                      final BuildCommencedTimeProvider timeProvider,
                                                                                      SuppliedComponentMetadataSerializer suppliedComponentMetadataSerializer,
                                                                                      ListenerManager listenerManager) {
        if (cacheDecoratorFactory instanceof CleaningInMemoryCacheDecoratorFactory) {
            listenerManager.addListener(new InternalBuildFinishedListener() {
                @Override
                public void buildFinished(GradleInternal build, boolean failed) {
                    ((CleaningInMemoryCacheDecoratorFactory) cacheDecoratorFactory).clearCaches(ComponentMetadataRuleExecutor::isMetadataRuleExecutorCache);
                }
            });
        }
        return new ComponentMetadataSupplierRuleExecutor(globalScopedCache, cacheDecoratorFactory, snapshotter, timeProvider, suppliedComponentMetadataSerializer);
    }

    SignatureVerificationServiceFactory createSignatureVerificationServiceFactory(GlobalScopedCache globalScopedCache,
                                                                                  InMemoryCacheDecoratorFactory decoratorFactory,
                                                                                  RepositoryTransportFactory transportFactory,
                                                                                  BuildOperationExecutor buildOperationExecutor,
                                                                                  BuildCommencedTimeProvider timeProvider,
                                                                                  BuildScopedCache buildScopedCache,
                                                                                  FileHasher fileHasher,
                                                                                  StartParameter startParameter) {
        return new DefaultSignatureVerificationServiceFactory(transportFactory, globalScopedCache, decoratorFactory, buildOperationExecutor, fileHasher, buildScopedCache, timeProvider, startParameter.isRefreshKeys());
    }

    private void registerBuildFinishedHooks(ListenerManager listenerManager, DependencyVerificationOverride dependencyVerificationOverride) {
        listenerManager.addListener(new InternalBuildFinishedListener() {
            @Override
            public void buildFinished(GradleInternal build, boolean failed) {
                dependencyVerificationOverride.buildFinished(build);
            }
        });
    }

    DependenciesAccessors createDependenciesAccessorGenerator(ClassPathRegistry registry,
                                                              DependenciesAccessorsWorkspaceProvider workspace,
                                                              DefaultProjectDependencyFactory factory,
                                                              ExecutionEngine executionEngine,
                                                              FeaturePreviews featurePreviews,
                                                              FileCollectionFactory fileCollectionFactory,
                                                              InputFingerprinter inputFingerprinter) {
        return new DefaultDependenciesAccessors(registry, workspace, factory, featurePreviews, executionEngine, fileCollectionFactory, inputFingerprinter);
    }


    /**
     * Execution engine for usage above Gradle scope
     *
     * Currently used for running artifact transformations in buildscript blocks.
     */
    ExecutionEngine createExecutionEngine(
        BuildOperationExecutor buildOperationExecutor,
        CurrentBuildOperationRef currentBuildOperationRef,
        ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
        Deleter deleter,
        ExecutionStateChangeDetector changeDetector,
        InputFingerprinter inputFingerprinter,
        ListenerManager listenerManager,
        OutputSnapshotter outputSnapshotter,
        OverlappingOutputDetector overlappingOutputDetector,
        TimeoutHandler timeoutHandler,
        ValidateStep.ValidationWarningRecorder validationWarningRecorder,
        VirtualFileSystem virtualFileSystem,
        DocumentationRegistry documentationRegistry
    ) {
        OutputChangeListener outputChangeListener = listenerManager.getBroadcaster(OutputChangeListener.class);
        // TODO: Figure out how to get rid of origin scope id in snapshot outputs step
        UniqueId fixedUniqueId = UniqueId.from("dhwwyv4tqrd43cbxmdsf24wquu");
        // @formatter:off
        return new DefaultExecutionEngine(documentationRegistry,
            new IdentifyStep<>(
            new IdentityCacheStep<>(
            new AssignWorkspaceStep<>(
            new LoadPreviousExecutionStateStep<>(
            new RemoveUntrackedExecutionStateStep<>(
            new CaptureStateBeforeExecutionStep<>(buildOperationExecutor, classLoaderHierarchyHasher, outputSnapshotter, overlappingOutputDetector,
            new ValidateStep<>(virtualFileSystem, validationWarningRecorder,
            new NoOpCachingStateStep<>(
            new ResolveChangesStep<>(changeDetector,
            new SkipUpToDateStep<>(
            new BroadcastChangingOutputsStep<>(outputChangeListener,
            new StoreExecutionStateStep<>(
            new CaptureStateAfterExecutionStep<>(buildOperationExecutor, fixedUniqueId, outputSnapshotter,
            new CreateOutputsStep<>(
            new TimeoutStep<>(timeoutHandler, currentBuildOperationRef,
            new ResolveInputChangesStep<>(
            new RemovePreviousOutputsStep<>(deleter, outputChangeListener,
            new ExecuteStep<>(buildOperationExecutor
        )))))))))))))))))));
        // @formatter:on
    }

    private static class NoOpCachingStateStep<C extends ValidationFinishedContext> implements Step<C, CachingResult> {
        private final Step<? super CachingContext, ? extends UpToDateResult> delegate;

        public NoOpCachingStateStep(Step<? super CachingContext, ? extends UpToDateResult> delegate) {
            this.delegate = delegate;
        }

        @Override
        public CachingResult execute(UnitOfWork work, ValidationFinishedContext context) {
            UpToDateResult result = delegate.execute(work, new CachingContext() {
                @Override
                public CachingState getCachingState() {
                    return CachingState.NOT_DETERMINED;
                }

                @Override
                public Optional<String> getNonIncrementalReason() {
                    return context.getNonIncrementalReason();
                }

                @Override
                public WorkValidationContext getValidationContext() {
                    return context.getValidationContext();
                }

                @Override
                public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
                    return context.getInputProperties();
                }

                @Override
                public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                    return context.getInputFileProperties();
                }

                @Override
                public UnitOfWork.Identity getIdentity() {
                    return context.getIdentity();
                }

                @Override
                public File getWorkspace() {
                    return context.getWorkspace();
                }

                @Override
                public Optional<ExecutionHistoryStore> getHistory() {
                    return context.getHistory();
                }

                @Override
                public Optional<PreviousExecutionState> getPreviousExecutionState() {
                    return context.getPreviousExecutionState();
                }

                @Override
                public Optional<ValidationResult> getValidationProblems() {
                    return context.getValidationProblems();
                }

                @Override
                public Optional<BeforeExecutionState> getBeforeExecutionState() {
                    return context.getBeforeExecutionState();
                }
            });
            return new CachingResult() {
                @Override
                public CachingState getCachingState() {
                    return CachingState.NOT_DETERMINED;
                }

                @Override
                public ImmutableList<String> getExecutionReasons() {
                    return result.getExecutionReasons();
                }

                @Override
                public Optional<AfterExecutionState> getAfterExecutionState() {
                    return result.getAfterExecutionState();
                }

                @Override
                public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                    return result.getReusedOutputOriginMetadata();
                }

                @Override
                public Try<ExecutionResult> getExecutionResult() {
                    return result.getExecutionResult();
                }

                @Override
                public Duration getDuration() {
                    return result.getDuration();
                }
            };
        }
    }
}
