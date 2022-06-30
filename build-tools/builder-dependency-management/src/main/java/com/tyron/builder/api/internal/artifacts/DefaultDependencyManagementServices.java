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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.component.ComponentIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import com.tyron.builder.api.internal.artifacts.configurations.DefaultConfigurationContainer;
import com.tyron.builder.api.internal.artifacts.configurations.DefaultConfigurationFactory;
import com.tyron.builder.api.internal.artifacts.dsl.ComponentMetadataHandlerInternal;
import com.tyron.builder.api.internal.artifacts.dsl.DefaultArtifactHandler;
import com.tyron.builder.api.internal.artifacts.dsl.DefaultComponentMetadataHandler;
import com.tyron.builder.api.internal.artifacts.dsl.DefaultComponentModuleMetadataHandler;
import com.tyron.builder.api.internal.artifacts.dsl.DefaultRepositoryHandler;
import com.tyron.builder.api.internal.artifacts.dsl.PublishArtifactNotationParserFactory;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DefaultDependencyConstraintHandler;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyLockingProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.GradlePluginVariantsSupport;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import com.tyron.builder.api.internal.artifacts.ivyservice.DefaultConfigurationResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.ErrorHandlingConfigurationResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.IvyContextManager;
import com.tyron.builder.api.internal.artifacts.ivyservice.IvyContextualArtifactPublisher;
import com.tyron.builder.api.internal.artifacts.ivyservice.ShortCircuitEmptyConfigurationResolver;
import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionRules;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.GradleModuleMetadataParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.parser.GradlePomModuleDescriptorParser;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.verification.DependencyVerificationOverride;
import com.tyron.builder.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.DefaultRootComponentMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.moduleconverter.dependencies.LocalConfigurationMetadataBuilder;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionDescriptorFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.store.ResolutionResultsStoreFactory;
import com.tyron.builder.api.internal.artifacts.mvnsettings.LocalMavenRepositoryLocator;
import com.tyron.builder.api.internal.artifacts.query.ArtifactResolutionQueryFactory;
import com.tyron.builder.api.internal.artifacts.query.DefaultArtifactResolutionQueryFactory;
import com.tyron.builder.api.internal.artifacts.repositories.DefaultBaseRepositoryFactory;
import com.tyron.builder.api.internal.artifacts.repositories.DefaultUrlArtifactRepository;
import com.tyron.builder.api.internal.artifacts.repositories.ResolutionAwareRepository;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransformActionScheme;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransformParameterScheme;
import com.tyron.builder.api.internal.artifacts.transform.ConsumerProvidedVariantFinder;
import com.tyron.builder.api.internal.artifacts.transform.DefaultArtifactTransforms;
import com.tyron.builder.api.internal.artifacts.transform.DefaultTransformationRegistrationFactory;
import com.tyron.builder.api.internal.artifacts.transform.DefaultTransformedVariantFactory;
import com.tyron.builder.api.internal.artifacts.transform.DefaultTransformerInvocationFactory;
import com.tyron.builder.api.internal.artifacts.transform.DefaultVariantTransformRegistry;
import com.tyron.builder.api.internal.artifacts.transform.ImmutableTransformationWorkspaceServices;
import com.tyron.builder.api.internal.artifacts.transform.MutableTransformationWorkspaceServices;
import com.tyron.builder.api.internal.artifacts.transform.TransformationRegistrationFactory;
import com.tyron.builder.api.internal.artifacts.transform.TransformedVariantFactory;
import com.tyron.builder.api.internal.artifacts.transform.TransformerInvocationFactory;
import com.tyron.builder.api.internal.artifacts.type.ArtifactTypeRegistry;
import com.tyron.builder.api.internal.artifacts.type.DefaultArtifactTypeRegistry;
import com.tyron.builder.api.internal.attributes.AttributeDesugaring;
import com.tyron.builder.api.internal.attributes.AttributesSchemaInternal;
import com.tyron.builder.api.internal.attributes.DefaultAttributesSchema;
import com.tyron.builder.internal.component.external.ivypublish.DefaultArtifactPublisher;
import com.tyron.builder.internal.component.external.ivypublish.DefaultIvyModuleDescriptorWriter;
import com.tyron.builder.internal.component.external.model.JavaEcosystemVariantDerivationStrategy;
import com.tyron.builder.internal.component.external.model.ModuleComponentArtifactMetadata;
import com.tyron.builder.internal.component.model.ComponentAttributeMatcher;
import com.tyron.builder.internal.locking.DefaultDependencyLockingHandler;
import com.tyron.builder.internal.locking.DefaultDependencyLockingProvider;
import com.tyron.builder.internal.locking.NoOpDependencyLockingProvider;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataRuleExecutor;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.dsl.ArtifactHandler;
import com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler;
import com.tyron.builder.api.artifacts.dsl.ComponentModuleMetadataHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyConstraintHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.attributes.AttributesSchema;
import com.tyron.builder.api.file.ProjectLayout;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.DependencyFactory;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.artifacts.transform.ArtifactTransformListener;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.component.ComponentTypeRegistry;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.tasks.TaskResolver;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.initialization.internal.InternalBuildFinishedListener;
import com.tyron.builder.internal.authentication.AuthenticationSchemeRegistry;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.hash.ChecksumService;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.management.DependencyResolutionManagementInternal;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.internal.resource.local.FileResourceRepository;
import com.tyron.builder.internal.resource.local.LocallyAvailableResourceFinder;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.util.internal.SimpleMapInterner;
import com.tyron.builder.vcs.internal.VcsMappingsStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DefaultDependencyManagementServices implements DependencyManagementServices {

    private final ServiceRegistry parent;

    public DefaultDependencyManagementServices(ServiceRegistry parent) {
        this.parent = parent;
    }

    @Override
    public DependencyResolutionServices create(FileResolver resolver, FileCollectionFactory fileCollectionFactory, DependencyMetaDataProvider dependencyMetaDataProvider, ProjectFinder projectFinder, DomainObjectContext domainObjectContext) {
        DefaultServiceRegistry services = new DefaultServiceRegistry(parent);
        services.add(FileResolver.class, resolver);
        services.add(FileCollectionFactory.class, fileCollectionFactory);
        services.add(DependencyMetaDataProvider.class, dependencyMetaDataProvider);
        services.add(ProjectFinder.class, projectFinder);
        services.add(DomainObjectContext.class, domainObjectContext);
        services.addProvider(new ArtifactTransformResolutionGradleUserHomeServices());
        services.addProvider(new DependencyResolutionScopeServices(domainObjectContext));
        return services.get(DependencyResolutionServices.class);
    }

    @Override
    public void addDslServices(ServiceRegistration registration, DomainObjectContext domainObjectContext) {
        registration.addProvider(new DependencyResolutionScopeServices(domainObjectContext));
    }

    private static class ArtifactTransformResolutionGradleUserHomeServices {

        ArtifactTransformListener createArtifactTransformListener() {
            return new ArtifactTransformListener() {
                @Override
                public void beforeTransformerInvocation(Describable transformer, Describable subject) {
                }

                @Override
                public void afterTransformerInvocation(Describable transformer, Describable subject) {
                }
            };
        }
    }

    private static class DependencyResolutionScopeServices {

        private final DomainObjectContext domainObjectContext;

        public DependencyResolutionScopeServices(DomainObjectContext domainObjectContext) {
            this.domainObjectContext = domainObjectContext;
        }

        void configure(ServiceRegistration registration) {
            registration.add(DefaultTransformedVariantFactory.class);
            registration.add(DefaultConfigurationFactory.class);
            registration.add(DefaultRootComponentMetadataBuilder.Factory.class);
        }

        AttributesSchemaInternal createConfigurationAttributesSchema(InstantiatorFactory instantiatorFactory, IsolatableFactory isolatableFactory, PlatformSupport platformSupport) {
            DefaultAttributesSchema attributesSchema = instantiatorFactory.decorateLenient().newInstance(DefaultAttributesSchema.class, new ComponentAttributeMatcher(), instantiatorFactory, isolatableFactory);
            platformSupport.configureSchema(attributesSchema);
            GradlePluginVariantsSupport.configureSchema(attributesSchema);
            return attributesSchema;
        }

        MutableTransformationWorkspaceServices createTransformerWorkspaceServices(ProjectLayout projectLayout, ExecutionHistoryStore executionHistoryStore) {
            return new MutableTransformationWorkspaceServices(projectLayout.getBuildDirectory().dir(".transforms"), executionHistoryStore);
        }

        TransformerInvocationFactory createTransformerInvocationFactory(
                ExecutionEngine executionEngine,
                FileSystemAccess fileSystemAccess,
                ImmutableTransformationWorkspaceServices transformationWorkspaceServices,
                ArtifactTransformListener artifactTransformListener,
                FileCollectionFactory fileCollectionFactory,
                ProjectStateRegistry projectStateRegistry,
                BuildOperationExecutor buildOperationExecutor
        ) {
            return new DefaultTransformerInvocationFactory(
                executionEngine,
                fileSystemAccess,
                artifactTransformListener,
                transformationWorkspaceServices,
                fileCollectionFactory,
                projectStateRegistry,
                buildOperationExecutor
            );
        }

        TransformationRegistrationFactory createTransformationRegistrationFactory(
                BuildOperationExecutor buildOperationExecutor,
                IsolatableFactory isolatableFactory,
                ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
                TransformerInvocationFactory transformerInvocationFactory,
                DomainObjectContext domainObjectContext,
                ArtifactTransformParameterScheme parameterScheme,
                ArtifactTransformActionScheme actionScheme,
                InputFingerprinter inputFingerprinter,
                CalculatedValueContainerFactory calculatedValueContainerFactory,
                FileCollectionFactory fileCollectionFactory,
                FileLookup fileLookup,
                ServiceRegistry internalServices,
                DocumentationRegistry documentationRegistry
        ) {
            return new DefaultTransformationRegistrationFactory(
                buildOperationExecutor,
                isolatableFactory,
                classLoaderHierarchyHasher,
                transformerInvocationFactory,
                fileCollectionFactory,
                fileLookup,
                inputFingerprinter,
                calculatedValueContainerFactory,
                domainObjectContext,
                parameterScheme,
                actionScheme,
                internalServices,
                documentationRegistry
            );
        }

        VariantTransformRegistry createArtifactTransformRegistry(InstantiatorFactory instantiatorFactory, ImmutableAttributesFactory attributesFactory, ServiceRegistry services, TransformationRegistrationFactory transformationRegistrationFactory, ArtifactTransformParameterScheme parameterScheme) {
            return new DefaultVariantTransformRegistry(instantiatorFactory, attributesFactory, services, transformationRegistrationFactory, parameterScheme.getInstantiationScheme());
        }

        DefaultUrlArtifactRepository.Factory createDefaultUrlArtifactRepositoryFactory(FileResolver fileResolver) {
            return new DefaultUrlArtifactRepository.Factory(fileResolver);
        }

        BaseRepositoryFactory createBaseRepositoryFactory(
                LocalMavenRepositoryLocator localMavenRepositoryLocator,
                FileResolver fileResolver,
                FileCollectionFactory fileCollectionFactory,
                RepositoryTransportFactory repositoryTransportFactory,
                LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                FileStoreAndIndexProvider fileStoreAndIndexProvider,
                VersionSelectorScheme versionSelectorScheme,
                AuthenticationSchemeRegistry authenticationSchemeRegistry,
                IvyContextManager ivyContextManager,
                ImmutableAttributesFactory attributesFactory,
                ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                InstantiatorFactory instantiatorFactory,
                FileResourceRepository fileResourceRepository,
                MavenMutableModuleMetadataFactory metadataFactory,
                IvyMutableModuleMetadataFactory ivyMetadataFactory,
                IsolatableFactory isolatableFactory,
                ObjectFactory objectFactory,
                CollectionCallbackActionDecorator callbackDecorator,
                NamedObjectInstantiator instantiator,
                DefaultUrlArtifactRepository.Factory urlArtifactRepositoryFactory,
                ChecksumService checksumService,
                ProviderFactory providerFactory
        ) {
            return new DefaultBaseRepositoryFactory(
                localMavenRepositoryLocator,
                fileResolver,
                fileCollectionFactory,
                repositoryTransportFactory,
                locallyAvailableResourceFinder,
                fileStoreAndIndexProvider.getArtifactIdentifierFileStore(),
                fileStoreAndIndexProvider.getExternalResourceFileStore(),
                new GradlePomModuleDescriptorParser(versionSelectorScheme, moduleIdentifierFactory, fileResourceRepository, metadataFactory),
                new GradleModuleMetadataParser(attributesFactory, moduleIdentifierFactory, instantiator),
                authenticationSchemeRegistry,
                ivyContextManager,
                moduleIdentifierFactory,
                instantiatorFactory,
                fileResourceRepository,
                metadataFactory,
                ivyMetadataFactory,
                isolatableFactory,
                objectFactory,
                callbackDecorator,
                urlArtifactRepositoryFactory,
                checksumService,
                providerFactory
            );
        }

        RepositoryHandler createRepositoryHandler(Instantiator instantiator, BaseRepositoryFactory baseRepositoryFactory, CollectionCallbackActionDecorator callbackDecorator) {
            return instantiator.newInstance(DefaultRepositoryHandler.class, baseRepositoryFactory, instantiator, callbackDecorator);
        }

        ConfigurationContainerInternal createConfigurationContainer(
            Instantiator instantiator,
            GlobalDependencyResolutionRules globalDependencyResolutionRules,
            VcsMappingsStore vcsMappingsStore,
            ComponentIdentifierFactory componentIdentifierFactory,
            ImmutableAttributesFactory attributesFactory,
            ImmutableModuleIdentifierFactory moduleIdentifierFactory,
            ComponentSelectorConverter componentSelectorConverter,
            DependencyLockingProvider dependencyLockingProvider,
            CollectionCallbackActionDecorator callbackDecorator,
            NotationParser<Object, ComponentSelector> moduleSelectorNotationParser,
            ObjectFactory objectFactory,
            DefaultRootComponentMetadataBuilder.Factory rootComponentMetadataBuilderFactory,
            DefaultConfigurationFactory defaultConfigurationFactory
        ) {
            return instantiator.newInstance(DefaultConfigurationContainer.class,
                instantiator,
                globalDependencyResolutionRules.getDependencySubstitutionRules(),
                vcsMappingsStore,
                componentIdentifierFactory,
                attributesFactory,
                moduleIdentifierFactory,
                componentSelectorConverter,
                dependencyLockingProvider,
                callbackDecorator,
                moduleSelectorNotationParser,
                objectFactory,
                rootComponentMetadataBuilderFactory,
                defaultConfigurationFactory
            );
        }

        PublishArtifactNotationParserFactory createPublishArtifactNotationParserFactory(
            Instantiator instantiator,
            DependencyMetaDataProvider metaDataProvider,
            DomainObjectContext domainObjectContext
        ) {
            return new PublishArtifactNotationParserFactory(
                instantiator,
                metaDataProvider,
                taskResolverFor(domainObjectContext)
            );
        }

        @Nullable
        private TaskResolver taskResolverFor(DomainObjectContext domainObjectContext) {
            if (domainObjectContext instanceof ProjectInternal) {
                return ((ProjectInternal) domainObjectContext).getTasks();
            }
            return null;
        }

        ArtifactTypeRegistry createArtifactTypeRegistry(Instantiator instantiator, ImmutableAttributesFactory immutableAttributesFactory, CollectionCallbackActionDecorator decorator, VariantTransformRegistry transformRegistry) {
            return new DefaultArtifactTypeRegistry(instantiator, immutableAttributesFactory, decorator, transformRegistry);
        }

        DependencyHandler createDependencyHandler(Instantiator instantiator,
                                                  ConfigurationContainerInternal configurationContainer,
                                                  DependencyFactory dependencyFactory,
                                                  ProjectFinder projectFinder,
                                                  DependencyConstraintHandler dependencyConstraintHandler,
                                                  ComponentMetadataHandler componentMetadataHandler,
                                                  ComponentModuleMetadataHandler componentModuleMetadataHandler,
                                                  ArtifactResolutionQueryFactory resolutionQueryFactory,
                                                  AttributesSchema attributesSchema,
                                                  VariantTransformRegistry artifactTransformRegistrations,
                                                  ArtifactTypeRegistry artifactTypeRegistry,
                                                  ObjectFactory objects,
                                                  PlatformSupport platformSupport) {
            return instantiator.newInstance(DefaultDependencyHandler.class,
                configurationContainer,
                dependencyFactory,
                projectFinder,
                dependencyConstraintHandler,
                componentMetadataHandler,
                componentModuleMetadataHandler,
                resolutionQueryFactory,
                attributesSchema,
                artifactTransformRegistrations,
                artifactTypeRegistry,
                objects,
                platformSupport);
        }

        DependencyLockingHandler createDependencyLockingHandler(Instantiator instantiator, ConfigurationContainerInternal configurationContainer, DependencyLockingProvider dependencyLockingProvider) {
            if (domainObjectContext.isPluginContext()) {
                throw new IllegalStateException("Cannot use locking handler in plugins context");
            }
            // The lambda factory is to avoid eager creation of the configuration container
            return instantiator.newInstance(DefaultDependencyLockingHandler.class, (Supplier<ConfigurationContainerInternal>) () -> configurationContainer, dependencyLockingProvider);
        }

        DependencyLockingProvider createDependencyLockingProvider(FileResolver fileResolver, StartParameter startParameter, DomainObjectContext context, GlobalDependencyResolutionRules globalDependencyResolutionRules, ListenerManager listenerManager, PropertyFactory propertyFactory, FilePropertyFactory filePropertyFactory) {
            if (domainObjectContext.isPluginContext()) {
                return NoOpDependencyLockingProvider.getInstance();
            }

            DefaultDependencyLockingProvider dependencyLockingProvider = new DefaultDependencyLockingProvider(fileResolver, startParameter, context, globalDependencyResolutionRules.getDependencySubstitutionRules(), propertyFactory, filePropertyFactory, listenerManager.getBroadcaster(FileResourceListener.class));
            if (startParameter.isWriteDependencyLocks()) {
                listenerManager.addListener(new InternalBuildFinishedListener() {
                    @Override
                    public void buildFinished(GradleInternal gradle, boolean failed) {
                        if (!failed) {
                            dependencyLockingProvider.buildFinished();
                        }
                    }
                });
            }
            return dependencyLockingProvider;
        }

        DependencyConstraintHandler createDependencyConstraintHandler(Instantiator instantiator, ConfigurationContainerInternal configurationContainer, DependencyFactory dependencyFactory, ObjectFactory objects, PlatformSupport platformSupport) {
            return instantiator.newInstance(DefaultDependencyConstraintHandler.class, configurationContainer, dependencyFactory, objects, platformSupport);
        }

        DefaultComponentMetadataHandler createComponentMetadataHandler(Instantiator instantiator,
                                                                       ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                                                       SimpleMapInterner interner,
                                                                       ImmutableAttributesFactory attributesFactory,
                                                                       IsolatableFactory isolatableFactory,
                                                                       ComponentMetadataRuleExecutor componentMetadataRuleExecutor,
                                                                       PlatformSupport platformSupport) {
            DefaultComponentMetadataHandler componentMetadataHandler = instantiator.newInstance(DefaultComponentMetadataHandler.class, instantiator, moduleIdentifierFactory, interner, attributesFactory, isolatableFactory, componentMetadataRuleExecutor, platformSupport);
            if (domainObjectContext.isScript()) {
                componentMetadataHandler.setVariantDerivationStrategy(
                        JavaEcosystemVariantDerivationStrategy.getInstance());
            }
            return componentMetadataHandler;
        }

        DefaultComponentModuleMetadataHandler createComponentModuleMetadataHandler(Instantiator instantiator, ImmutableModuleIdentifierFactory moduleIdentifierFactory) {
            return instantiator.newInstance(DefaultComponentModuleMetadataHandler.class, moduleIdentifierFactory);
        }

        ArtifactHandler createArtifactHandler(Instantiator instantiator, DependencyMetaDataProvider dependencyMetaDataProvider, ConfigurationContainerInternal configurationContainer, DomainObjectContext context) {
            NotationParser<Object, ConfigurablePublishArtifact> publishArtifactNotationParser = new PublishArtifactNotationParserFactory(instantiator, dependencyMetaDataProvider, taskResolverFor(context)).create();
            return instantiator.newInstance(DefaultArtifactHandler.class, configurationContainer, publishArtifactNotationParser);
        }

        ComponentMetadataProcessorFactory createComponentMetadataProcessorFactory(
                ComponentMetadataHandlerInternal componentMetadataHandler, DependencyResolutionManagementInternal dependencyResolutionManagement, DomainObjectContext context) {
            if (context.isScript()) {
                return componentMetadataHandler::createComponentMetadataProcessor;
            }
            return componentMetadataHandler.createFactory(dependencyResolutionManagement);
        }

        GlobalDependencyResolutionRules createModuleMetadataHandler(ComponentMetadataProcessorFactory componentMetadataProcessorFactory, ComponentModuleMetadataProcessor moduleMetadataProcessor, List<DependencySubstitutionRules> rules) {
            return new DefaultGlobalDependencyResolutionRules(componentMetadataProcessorFactory, moduleMetadataProcessor, rules);
        }

        ConfigurationResolver createDependencyResolver(ArtifactDependencyResolver artifactDependencyResolver,
                                                       RepositoriesSupplier repositoriesSupplier,
                                                       GlobalDependencyResolutionRules metadataHandler,
                                                       ComponentIdentifierFactory componentIdentifierFactory,
                                                       ResolutionResultsStoreFactory resolutionResultsStoreFactory,
                                                       StartParameter startParameter,
                                                       AttributesSchemaInternal attributesSchema,
                                                       VariantTransformRegistry variantTransforms,
                                                       ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                                       ImmutableAttributesFactory attributesFactory,
                                                       BuildOperationExecutor buildOperationExecutor,
                                                       ArtifactTypeRegistry artifactTypeRegistry,
                                                       ComponentSelectorConverter componentSelectorConverter,
                                                       AttributeContainerSerializer attributeContainerSerializer,
                                                       BuildState currentBuild,
                                                       TransformedVariantFactory transformedVariantFactory,
                                                       DependencyVerificationOverride dependencyVerificationOverride,
                                                       ComponentSelectionDescriptorFactory componentSelectionDescriptorFactory) {
            return new ErrorHandlingConfigurationResolver(
                new ShortCircuitEmptyConfigurationResolver(
                    new DefaultConfigurationResolver(
                        artifactDependencyResolver,
                        repositoriesSupplier,
                        metadataHandler,
                        resolutionResultsStoreFactory,
                        startParameter.isBuildProjectDependencies(),
                        attributesSchema,
                        new DefaultArtifactTransforms(
                            new ConsumerProvidedVariantFinder(
                                variantTransforms,
                                attributesSchema,
                                attributesFactory),
                            attributesSchema,
                            attributesFactory,
                            transformedVariantFactory
                        ),
                        moduleIdentifierFactory,
                        buildOperationExecutor,
                        artifactTypeRegistry,
                        componentSelectorConverter,
                        attributeContainerSerializer,
                        currentBuild.getBuildIdentifier(),
                        new AttributeDesugaring(attributesFactory),
                        dependencyVerificationOverride,
                        componentSelectionDescriptorFactory),
                    componentIdentifierFactory,
                    moduleIdentifierFactory,
                    currentBuild.getBuildIdentifier()));
        }

        ArtifactPublicationServices createArtifactPublicationServices(ServiceRegistry services) {
            return new DefaultArtifactPublicationServices(services);
        }

        DependencyResolutionServices createDependencyResolutionServices(ServiceRegistry services) {
            return new DefaultDependencyResolutionServices(services, domainObjectContext);
        }

        ArtifactResolutionQueryFactory createArtifactResolutionQueryFactory(ConfigurationContainerInternal configurationContainer,
                                                                            RepositoriesSupplier repositoriesSupplier,
                                                                            ResolveIvyFactory ivyFactory,
                                                                            GlobalDependencyResolutionRules metadataHandler,
                                                                            ComponentTypeRegistry componentTypeRegistry,
                                                                            ImmutableAttributesFactory attributesFactory,
                                                                            ComponentMetadataSupplierRuleExecutor executor) {
            return new DefaultArtifactResolutionQueryFactory(configurationContainer, repositoriesSupplier, ivyFactory, metadataHandler, componentTypeRegistry, attributesFactory, executor);

        }

        RepositoriesSupplier createRepositoriesSupplier(RepositoryHandler repositoryHandler, DependencyResolutionManagementInternal drm, DomainObjectContext context) {
            return () -> {
                List<ResolutionAwareRepository> repositories = collectRepositories(repositoryHandler);
                if (context.isScript() || context.isDetachedState()) {
                    return repositories;
                }
                DependencyResolutionManagementInternal.RepositoriesModeInternal mode = drm.getConfiguredRepositoriesMode();
                if (mode.useProjectRepositories()) {
                    if (repositories.isEmpty()) {
                        repositories = collectRepositories(drm.getRepositories());
                    }
                } else {
                    repositories = collectRepositories(drm.getRepositories());
                }
                return repositories;
            };
        }

        private static List<ResolutionAwareRepository> collectRepositories(RepositoryHandler repositoryHandler) {
            return repositoryHandler.stream()
                .map(ResolutionAwareRepository.class::cast)
                .collect(Collectors.toList());
        }
    }

    private static class DefaultDependencyResolutionServices implements DependencyResolutionServices {

        private final ServiceRegistry services;
        private final DomainObjectContext domainObjectContext;

        private DefaultDependencyResolutionServices(ServiceRegistry services, DomainObjectContext domainObjectContext) {
            this.services = services;
            this.domainObjectContext = domainObjectContext;
        }

        @Override
        public RepositoryHandler getResolveRepositoryHandler() {
            return services.get(RepositoryHandler.class);
        }

        @Override
        public ConfigurationContainerInternal getConfigurationContainer() {
            return services.get(ConfigurationContainerInternal.class);
        }

        @Override
        public DependencyHandler getDependencyHandler() {
            return services.get(DependencyHandler.class);
        }

        @Override
        public DependencyLockingHandler getDependencyLockingHandler() {
            return services.get(DependencyLockingHandler.class);
        }

        @Override
        public ImmutableAttributesFactory getAttributesFactory() {
            return services.get(ImmutableAttributesFactory.class);
        }

        @Override
        public AttributesSchema getAttributesSchema() {
            return services.get(AttributesSchema.class);
        }

        @Override
        public ObjectFactory getObjectFactory() {
            return services.get(ObjectFactory.class);
        }
    }

    private static class DefaultArtifactPublicationServices implements ArtifactPublicationServices {

        private final ServiceRegistry services;

        public DefaultArtifactPublicationServices(ServiceRegistry services) {
            this.services = services;
        }

        @Override
        public RepositoryHandler createRepositoryHandler() {
            Instantiator instantiator = services.get(Instantiator.class);
            BaseRepositoryFactory baseRepositoryFactory = services.get(BaseRepositoryFactory.class);
            CollectionCallbackActionDecorator callbackDecorator = services.get(CollectionCallbackActionDecorator.class);
            return instantiator.newInstance(DefaultRepositoryHandler.class, baseRepositoryFactory, instantiator, callbackDecorator);
        }

        @Override
        public ArtifactPublisher createArtifactPublisher() {
            DefaultArtifactPublisher publisher = new DefaultArtifactPublisher(
                    services.get(LocalConfigurationMetadataBuilder.class),
                    new DefaultIvyModuleDescriptorWriter(services.get(ComponentSelectorConverter.class))
            );
            return new IvyContextualArtifactPublisher(services.get(IvyContextManager.class), publisher);
        }

    }
}
