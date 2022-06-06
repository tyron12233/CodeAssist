package com.tyron.builder.plugin.internal;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.artifacts.DependencyManagementServices;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.UnknownProjectFinder;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.initialization.RootScriptDomainObjectContext;
import com.tyron.builder.api.internal.plugins.PluginInspector;
import com.tyron.builder.initialization.ClassLoaderScopeRegistry;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.build.BuildIncluder;
import com.tyron.builder.internal.classpath.CachedClasspathTransformer;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.plugin.management.PluginManagementSpec;
import com.tyron.builder.plugin.management.internal.DefaultPluginManagementSpec;
import com.tyron.builder.plugin.management.internal.DefaultPluginResolutionStrategy;
import com.tyron.builder.plugin.management.internal.PluginResolutionStrategyInternal;
import com.tyron.builder.plugin.management.internal.autoapply.AutoAppliedPluginHandler;
import com.tyron.builder.plugin.management.internal.autoapply.AutoAppliedPluginRegistry;
import com.tyron.builder.plugin.management.internal.autoapply.DefaultAutoAppliedPluginHandler;
import com.tyron.builder.plugin.management.internal.autoapply.DefaultAutoAppliedPluginRegistry;
import com.tyron.builder.plugin.use.internal.DefaultPluginRequestApplicator;
import com.tyron.builder.plugin.use.internal.InjectedPluginClasspath;
import com.tyron.builder.plugin.use.internal.PluginDependencyResolutionServices;
import com.tyron.builder.plugin.use.resolve.internal.PluginRepositoriesProvider;
import com.tyron.builder.plugin.use.internal.PluginResolverFactory;
import com.tyron.builder.plugin.use.resolve.service.internal.ClientInjectedClasspathPluginResolver;
import com.tyron.builder.plugin.use.resolve.service.internal.DefaultInjectedClasspathPluginResolver;
import com.tyron.builder.plugin.use.resolve.service.internal.InjectedClasspathInstrumentationStrategy;

public class PluginUsePluginServiceRegistry extends AbstractPluginServiceRegistry {

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new BuildScopeServices());
    }

    @Override
    public void registerSettingsServices(ServiceRegistration registration) {
        registration.addProvider(new SettingsScopeServices());
    }

    private static class SettingsScopeServices {

        protected PluginManagementSpec createPluginManagementSpec(Instantiator instantiator, PluginDependencyResolutionServices dependencyResolutionServices,
                                                                  PluginResolutionStrategyInternal internalPluginResolutionStrategy, FileResolver fileResolver,
                                                                  BuildIncluder buildIncluder, GradleInternal gradle) {
            return instantiator.newInstance(DefaultPluginManagementSpec.class, dependencyResolutionServices.getPluginRepositoryHandlerProvider(), internalPluginResolutionStrategy, fileResolver, buildIncluder, gradle);
        }
    }

    private static class BuildScopeServices {
        void configure(ServiceRegistration registration) {
            registration.add(PluginResolverFactory.class);
            registration.add(DefaultPluginRequestApplicator.class);
        }

        PluginRepositoriesProvider createPluginResolverFactory(PluginDependencyResolutionServices dependencyResolutionServices) {
            return dependencyResolutionServices.getPluginRepositoriesProvider();
        }

        AutoAppliedPluginRegistry createAutoAppliedPluginRegistry(BuildDefinition buildDefinition) {
            return new DefaultAutoAppliedPluginRegistry(buildDefinition);
        }

        AutoAppliedPluginHandler createAutoAppliedPluginHandler(AutoAppliedPluginRegistry registry) {
            return new DefaultAutoAppliedPluginHandler(registry);
        }

        ClientInjectedClasspathPluginResolver createInjectedClassPathPluginResolver(ClassLoaderScopeRegistry classLoaderScopeRegistry, PluginInspector pluginInspector,
                                                                                    InjectedPluginClasspath injectedPluginClasspath, CachedClasspathTransformer classpathTransformer,
                                                                                    InjectedClasspathInstrumentationStrategy instrumentationStrategy) {
            if (injectedPluginClasspath.getClasspath().isEmpty()) {
                return ClientInjectedClasspathPluginResolver.EMPTY;
            }
            return new DefaultInjectedClasspathPluginResolver(classLoaderScopeRegistry.getCoreAndPluginsScope(), classpathTransformer, pluginInspector, injectedPluginClasspath.getClasspath(), instrumentationStrategy);
        }

        PluginResolutionStrategyInternal createPluginResolutionStrategy(Instantiator instantiator, ListenerManager listenerManager) {
            return instantiator.newInstance(DefaultPluginResolutionStrategy.class, listenerManager);
        }

        PluginDependencyResolutionServices createPluginDependencyResolutionServices(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory,
                                                                                    DependencyManagementServices dependencyManagementServices, DependencyMetaDataProvider dependencyMetaDataProvider) {
            return new PluginDependencyResolutionServices(
                makeDependencyResolutionServicesFactory(fileResolver, fileCollectionFactory, dependencyManagementServices, dependencyMetaDataProvider));
        }

        private Factory<DependencyResolutionServices> makeDependencyResolutionServicesFactory(final FileResolver fileResolver,
                                                                                              final FileCollectionFactory fileCollectionFactory,
                                                                                              final DependencyManagementServices dependencyManagementServices,
                                                                                              final DependencyMetaDataProvider dependencyMetaDataProvider) {
            return new Factory<DependencyResolutionServices>() {
                @Override
                public DependencyResolutionServices create() {
                    return dependencyManagementServices.create(fileResolver, fileCollectionFactory, dependencyMetaDataProvider, makeUnknownProjectFinder(), RootScriptDomainObjectContext.PLUGINS);
                }
            };
        }

        private ProjectFinder makeUnknownProjectFinder() {
            return new UnknownProjectFinder("Cannot use project dependencies in a plugin resolution definition.");
        }
    }
}
